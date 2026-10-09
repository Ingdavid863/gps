package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.content.Intent
import kotlin.math.*

/** Walking directions and real, world-tracked ground geometry above the ARCore camera. */
class ArNavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val ROUTE_FILE = "walking-route.json"
        const val EXTRA_WALKING_ROUTE = "walking_route_available"
        private const val PREFS = "gps3d_navigation"
    }
    private lateinit var sceneHost: ComposeView
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var lastSpokenStep = -1
    private var sceneStarted = false
    private var updatesStarted = false
    private var routeCall: Call? = null
    private val http = OkHttpClient()
    private val main = android.os.Handler(Looper.getMainLooper())
    private val ground = GroundRouteController()
    private var target: RouteGeometry.Point? = null
    private var here: RouteGeometry.Point? = null
    private var hereAt = 0L
    private var route: WalkingRoute? = null
    private var routeGeometry: ArRouteGeometry? = null
    private var stepAlong = DoubleArray(0)
    private var requestEpoch = 0
    private var lastRequestAt = 0L
    private var offRouteSince = 0L
    private var lastFrameAt = 0L
    private var speedKmh by mutableIntStateOf(0)
    private var arStatus by mutableStateOf("Preparando cámara y suelo…")
    private var groundState by mutableStateOf(GroundRouteController.State())
    private var directionDistance by mutableStateOf("Ruta para caminar")
    private var directionInstruction by mutableStateOf("Esperando ubicación precisa…")
    private var directionRoad by mutableStateOf("")
    private var voiceEnabled by mutableStateOf(true)
    private var routeActive by mutableStateOf(false)
    private var width = 0
    private var height = 0
    private var visualLocationAllowed = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 15_000_000_000L) return
            here = RouteGeometry.Point(location.latitude, location.longitude)
            hereAt = SystemClock.elapsedRealtime()
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).toInt().coerceAtLeast(0) else 0
            if (speedKmh > 20) {
                Toast.makeText(this@ArNavigationActivity, "VR es para caminar. Volviendo al mapa.", Toast.LENGTH_LONG).show()
                finish(); return
            }
            if (location.accuracy <= 25f && route == null && routeCall == null) requestWalkingRoute()
            if (route != null && !groundState.manual) updateDirections(
                RouteGeometry.project(route!!.points, here!!)?.along ?: 0.0, here!!)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            arStatus = "Permite la cámara en Ajustes para ver la línea sobre el piso"
            renderScene(false)
        } else {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
            else arStatus = "Activa ubicación precisa para calcular el recorrido a pie"
            startScene()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lat = intent.getDoubleExtra(WalkingRoute.EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(WalkingRoute.EXTRA_LON, Double.NaN)
        target = runCatching { WalkingRoute.checkedPoint(lat, lon) }.getOrNull()
        if (target == null) {
            Toast.makeText(this, "Elige un destino en el mapa antes de abrir VR", Toast.LENGTH_LONG).show()
            finish(); return
        }
        directionRoad = intent.getStringExtra(WalkingRoute.EXTRA_LABEL).orEmpty().take(100)
        voiceEnabled = intent.getBooleanExtra("walking_voice", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("voice_enabled", true))
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        sceneHost = ComposeView(this)
        setContentView(sceneHost)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderScene(false)
        if (hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLocationUpdates(); startScene()
        } else permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startScene() {
        if (sceneStarted) return
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!preferences.contains("geospatial_notice_seen")) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Ubicar la ruta sobre el suelo")
                .setMessage("Esta función usa Google Play Services para RA (ARCore), sujeto a la Política de Privacidad de Google. Para localizar la ruta, Google procesará datos de sensores, como cámara y ubicación.\n\nTambién puedes colocar la línea manualmente sobre el piso.")
                .setPositiveButton("Usar ubicación visual") { _, _ ->
                    preferences.edit().putBoolean("geospatial_notice_seen", true).putBoolean("geospatial_allowed", true).apply()
                    startScene()
                }
                .setNegativeButton("Anclaje manual") { _, _ ->
                    preferences.edit().putBoolean("geospatial_notice_seen", true).putBoolean("geospatial_allowed", false).apply()
                    startScene()
                }
                .setNeutralButton("Más información") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data")))
                    startScene()
                }.setCancelable(false).show()
            return
        }
        visualLocationAllowed = preferences.getBoolean("geospatial_allowed", false)
        sceneStarted = true
        renderScene(true)
    }

    private fun renderScene(withCamera: Boolean) {
        sceneHost.setContent {
            val engine = rememberEngine()
            val materials = rememberMaterialLoader(engine)
            val blue = remember(materials) { materials.createUnlitColorInstance(Color(0xFF087FFF)) }
            val white = remember(materials) { materials.createUnlitColorInstance(Color.White) }
            val orange = remember(materials) { materials.createUnlitColorInstance(Color(0xFFFFA20A)) }
            DisposableEffect(Unit) { onDispose { ground.clear() } }
            ARNavigationScreen(
                speedKmh = speedKmh, speedLimitKmh = null, distanceText = directionDistance,
                instructionText = directionInstruction, roadText = directionRoad, arStatus = arStatus,
                voiceEnabled = voiceEnabled, trafficEnabled = false,
                walkingMode = true, routeActive = routeActive,
                onAnchorFloor = { ground.requestManualPlacement = true },
                onAutomaticGround = {
                    if (!visualLocationAllowed) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("geospatial_notice_seen").apply()
                        recreate()
                    } else { ground.useAutomatic(); groundState = GroundRouteController.State() }
                },
                onOpenMap = { finish() }, onSearch = { finish() },
                onToggleVoice = ::toggleVoice, onToggleTraffic = {},
                onReportHazard = {}, onReportPolice = {},
                arContent = {
                    if (withCamera) ARSceneView(
                        modifier = Modifier.fillMaxSize().onSizeChanged { width = it.width; height = it.height },
                        engine = engine, materialLoader = materials, planeRenderer = false,
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
                        sessionConfiguration = { session, config ->
                            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                                Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                            ground.geospatialConfigured = visualLocationAllowed && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                                apiKey("com.google.android.ar.API_KEY").isNotBlank() &&
                                session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                            if (ground.geospatialConfigured) {
                                config.geospatialMode = Config.GeospatialMode.ENABLED
                                config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
                            }
                        },
                        onSessionFailed = {
                            arStatus = "La cámara AR no pudo iniciar. Revisa Google Play Services para RA y los permisos."
                        },
                        onSessionPaused = { ground.clear(); groundState = GroundRouteController.State() },
                        onSessionUpdated = { session, frame ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFrameAt >= 100L) {
                                lastFrameAt = now
                                val location = here?.takeIf { now - hereAt < 15000L }
                                val placing = ground.requestManualPlacement
                                groundState = ground.update(session, frame, location, now, width, height)
                                if (placing && !groundState.manual) Toast.makeText(this@ArNavigationActivity,
                                    groundState.status, Toast.LENGTH_LONG).show()
                                arStatus = groundState.status
                                if (route != null && frame.camera.trackingState == TrackingState.TRACKING) {
                                    updateDirections(groundState.along, location)
                                    if (!groundState.manual && groundState.distanceFromRoute > 15.0) {
                                        if (offRouteSince == 0L) offRouteSince = now
                                        if (now - offRouteSince > 8000 && now - lastRequestAt > 20000 && routeCall == null) requestWalkingRoute()
                                    } else offRouteSince = 0L
                                }
                            }
                        }
                    ) {
                        groundState.ribbons.forEach { ribbon ->
                            key(ribbon.anchor) {
                                AnchorNode(anchor = ribbon.anchor,
                                    visibleTrackingStates = if (groundState.visible && ribbon.visible) setOf(TrackingState.TRACKING) else emptySet()) {
                                    // White border and colored core are real meshes, 2.5 cm above the floor.
                                    CubeNode(size = Size(.26f, .014f, ribbon.length + .015f),
                                        position = Position(ribbon.x, ribbon.y, ribbon.z),
                                        rotation = Rotation(ribbon.pitch, ribbon.yaw, 0f), materialInstance = white)
                                    CubeNode(size = Size(.17f, .018f, ribbon.length + .02f),
                                        position = Position(ribbon.x, ribbon.y + .007f, ribbon.z),
                                        rotation = Rotation(ribbon.pitch, ribbon.yaw, 0f),
                                        materialInstance = if (groundState.manual) orange else blue)
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun requestWalkingRoute() {
        val from = here ?: return
        val destination = target ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - hereAt > 15000L) return
        if (lastRequestAt != 0L && now - lastRequestAt < 8000L) return
        lastRequestAt = now
        val epoch = ++requestEpoch
        directionInstruction = if (route == null) "Calculando ruta para caminar…" else "Recalculando ruta para caminar…"
        if (route == null) {
            val cached = runCatching { WalkingRoute.decode(File(filesDir, ROUTE_FILE).readText()) }.getOrNull()
            if (cached?.reusableFor(destination, from, System.currentTimeMillis()) == true) installRoute(cached)
        }
        val key = apiKey("com.david.gps3dar.TOMTOM_API_KEY")
        if (key.isBlank()) { directionInstruction = "Servicio de rutas no configurado"; return }
        val call = http.newCall(Request.Builder().url(WalkingRoute.requestUrl(key, from, destination)).build())
        routeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) main.post {
                    if (epoch != requestEpoch || isDestroyed) return@post
                    routeCall = null
                    directionInstruction = if (route != null) "Ruta guardada · sin conexión" else "Sin conexión · vuelve al mapa y reintenta"
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { r -> runCatching {
                    require(r.isSuccessful) { "No se pudo consultar la ruta para caminar (HTTP ${r.code})" }
                    WalkingRoute.parseResponse(r.body?.string().orEmpty(), destination)
                } }
                main.post {
                    if (epoch != requestEpoch || isDestroyed) return@post
                    routeCall = null
                    parsed.onSuccess { walking ->
                        runCatching { File(filesDir, ROUTE_FILE).writeText(walking.encode()) }
                        installRoute(walking)
                    }.onFailure { error ->
                        if (route == null) directionInstruction = error.message ?: "No hay ruta para caminar disponible"
                    }
                }
            }
        })
    }

    private fun installRoute(walking: WalkingRoute) {
        route = walking; routeGeometry = ArRouteGeometry(walking.points)
        stepAlong = walking.steps.map { RouteGeometry.project(walking.points, it.point)?.along ?: 0.0 }.toDoubleArray()
        routeActive = true; lastSpokenStep = -1; offRouteSince = 0L
        ground.setRoute(walking); groundState = GroundRouteController.State()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_WALKING_ROUTE, true)
            .putExtra(WalkingRoute.EXTRA_LAT, walking.destination.lat)
            .putExtra(WalkingRoute.EXTRA_LON, walking.destination.lon))
        here?.let { updateDirections(RouteGeometry.project(walking.points, it)?.along ?: 0.0, it) }
    }

    private fun updateDirections(along: Double, location: RouteGeometry.Point?) {
        val walking = route ?: return
        val geometry = routeGeometry ?: return
        val remaining = (geometry.length - along).coerceAtLeast(0.0)
        // Distance alone is insufficient: a parallel street could be close to the destination.
        val arrived = remaining < 5.0 && (groundState.manual ||
            (location != null && RouteGeometry.distance(location, walking.destination) < 10.0))
        val next = walking.steps.withIndex().firstOrNull { (index, step) ->
            !step.maneuver.startsWith("DEPART") &&
                stepAlong[index] > along + 3.0
        }
        directionInstruction = if (arrived) "Llegaste a tu destino" else next?.value?.message ?: "Sigue la línea hacia el destino"
        val stepDistance = next?.index?.let { stepAlong[it] - along } ?: remaining
        directionDistance = if (arrived) "Destino a pie" else "A pie · ${distanceText(stepDistance)} · quedan ${distanceText(remaining)}"
        val estimatedMinutes = ceil(walking.durationSeconds * remaining / geometry.length.coerceAtLeast(1.0) / 60).toInt()
        directionRoad = intent.getStringExtra(WalkingRoute.EXTRA_LABEL).orEmpty().take(70) +
            if (!arrived) " · ${estimatedMinutes.coerceAtLeast(1)} min" else ""
        val index = next?.index ?: walking.steps.size
        if (voiceEnabled && ttsReady && index != lastSpokenStep && (stepDistance < 25 || arrived)) {
            tts.speak(directionInstruction, TextToSpeech.QUEUE_FLUSH, null, "walking:$index")
            lastSpokenStep = index
        }
        if (arrived && groundState.visible) groundState = groundState.copy(visible = false)
    }

    private fun distanceText(meters: Double): String = if (meters < 1000) "${meters.toInt()} m"
        else String.format(Locale("es", "MX"), "%.1f km", meters / 1000)

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (updatesStarted || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        updatesStarted = true
        fusedLocation.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L).build(), locationCallback, Looper.getMainLooper())
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        if (!voiceEnabled && ::tts.isInitialized) tts.stop()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("voice_enabled", voiceEnabled).apply()
    }
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale("es", "MX")
    }
    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun apiKey(name: String): String = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData?.getString(name)?.trim().orEmpty()
    }.getOrDefault("")

    override fun onResume() {
        super.onResume()
        if (::fusedLocation.isInitialized && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
    }
    override fun onPause() {
        if (::fusedLocation.isInitialized && updatesStarted) { fusedLocation.removeLocationUpdates(locationCallback); updatesStarted = false }
        if (::tts.isInitialized) tts.stop()
        super.onPause()
    }
    override fun onDestroy() {
        requestEpoch++; routeCall?.cancel(); main.removeCallbacksAndMessages(null); ground.clear()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}

