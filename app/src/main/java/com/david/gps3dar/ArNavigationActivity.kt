package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.util.Locale

/**
 * Native AR entry point for GPS3D.
 *
 * SceneView owns the ARCore camera/session and Filament renderer while the Compose
 * HUD stays above it. GPS speed, Geospatial/VPS status and the driving controls are
 * updated independently so UI work never blocks the AR rendering loop.
 */
class ArNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GPS3D_AR"
        private const val POSE_LOG_INTERVAL_MS = 750L
        private const val PREFS = "gps3d_navigation"
        private const val PREF_VOICE = "voice_enabled"
        private const val PREF_TRAFFIC = "traffic_enabled"
    }

    private lateinit var sceneHost: ComposeView
    private lateinit var fusedLocation: FusedLocationProviderClient

    private var sceneStarted = false
    private var lastPoseLogAt = 0L
    private var locationUpdatesStarted = false

    private var speedKmh by mutableIntStateOf(0)
    private var arStatus by mutableStateOf("Preparando ARCore…")
    private var directionDistance by mutableStateOf("Sin ruta activa")
    private var directionInstruction by mutableStateOf("Abre el mapa 3D para elegir un destino")
    private var directionRoad by mutableStateOf("")
    private var voiceEnabled by mutableStateOf(true)
    private var trafficEnabled by mutableStateOf(true)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            speedKmh = if (location.hasSpeed()) {
                (location.speed * 3.6f).toInt().coerceIn(0, 260)
            } else {
                0
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cameraGranted = granted[Manifest.permission.CAMERA] == true ||
            hasPermission(Manifest.permission.CAMERA)
        val fineGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        if (!cameraGranted) {
            arStatus = "Se necesita permiso de cámara para iniciar AR"
            renderScenePlaceholder()
            return@registerForActivityResult
        }

        if (fineGranted) startLocationUpdates()
        startArScene(enableGeospatial = fineGranted && hasArCoreApiKey())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        restoreDrivingPreferences()
        buildUi()
        prepareArSession()
    }

    private fun buildUi() {
        sceneHost = ComposeView(this)
        setContentView(sceneHost)
    }

    private fun renderScenePlaceholder() {
        sceneHost.setContent {
            ARNavigationScreen(
                speedKmh = speedKmh,
                speedLimitKmh = null,
                distanceText = directionDistance,
                instructionText = directionInstruction,
                roadText = directionRoad,
                arStatus = arStatus,
                voiceEnabled = voiceEnabled,
                trafficEnabled = trafficEnabled,
                onOpenMap = ::openMap,
                onSearch = ::openMap,
                onToggleVoice = ::toggleVoice,
                onToggleTraffic = ::toggleTraffic,
                onReportHazard = { showReportMessage("Peligro") },
                onReportPolice = { showReportMessage("Policía") },
                arContent = {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }
    }

    private fun prepareArSession() {
        val cameraGranted = hasPermission(Manifest.permission.CAMERA)
        val fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        if (fineGranted) startLocationUpdates()

        if (cameraGranted) {
            startArScene(enableGeospatial = fineGranted && hasArCoreApiKey())
            return
        }

        arStatus = if (hasArCoreApiKey()) {
            "Solicitando cámara y ubicación precisa para AR Geospatial…"
        } else {
            "Solicitando cámara para AR…"
        }
        renderScenePlaceholder()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startArScene(enableGeospatial: Boolean) {
        if (sceneStarted) return
        sceneStarted = true

        sceneHost.setContent {
            ARNavigationScreen(
                speedKmh = speedKmh,
                speedLimitKmh = null,
                distanceText = directionDistance,
                instructionText = directionInstruction,
                roadText = directionRoad,
                arStatus = arStatus,
                voiceEnabled = voiceEnabled,
                trafficEnabled = trafficEnabled,
                onOpenMap = ::openMap,
                onSearch = ::openMap,
                onToggleVoice = ::toggleVoice,
                onToggleTraffic = ::toggleTraffic,
                onReportHazard = { showReportMessage("Peligro") },
                onReportPolice = { showReportMessage("Policía") },
                arContent = {
                    ARSceneView(
                        modifier = Modifier.fillMaxSize(),
                        planeRenderer = false,
                        sessionConfiguration = { session, config ->
                            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                            config.depthMode = if (
                                session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                            ) {
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Config.DepthMode.DISABLED
                            }

                            if (enableGeospatial &&
                                session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                            ) {
                                config.geospatialMode = Config.GeospatialMode.ENABLED
                                config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
                                arStatus = "AR Geospatial · localizando con VPS/GPS…"
                            } else {
                                arStatus = when {
                                    !enableGeospatial && !hasArCoreApiKey() ->
                                        "AR activo · falta ARCORE_API_KEY para Geospatial"
                                    !enableGeospatial ->
                                        "AR activo · ubicación precisa no disponible"
                                    else ->
                                        "AR activo · Geospatial no compatible con este dispositivo"
                                }
                            }
                        },
                        onSessionUpdated = { session, _ ->
                            if (!enableGeospatial) return@ARSceneView

                            val earth = session.earth
                            if (earth == null || earth.trackingState != TrackingState.TRACKING) {
                                arStatus = "Geospatial · buscando referencia visual/GPS…"
                                return@ARSceneView
                            }

                            val pose = earth.cameraGeospatialPose
                            arStatus = String.format(
                                Locale("es", "MX"),
                                "VPS/GPS ✓  precisión ±%.1f m · rumbo %.0f°",
                                pose.horizontalAccuracy,
                                pose.heading
                            )

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPoseLogAt >= POSE_LOG_INTERVAL_MS) {
                                lastPoseLogAt = now
                                Log.d(
                                    TAG,
                                    String.format(
                                        Locale.US,
                                        "Lat %.7f Lon %.7f Alt %.2f Heading %.2f HAcc %.2f VAcc %.2f",
                                        pose.latitude,
                                        pose.longitude,
                                        pose.altitude,
                                        pose.heading,
                                        pose.horizontalAccuracy,
                                        pose.verticalAccuracy
                                    )
                                )
                            }
                        }
                    )
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesStarted || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        locationUpdatesStarted = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(1500L)
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesStarted) return
        fusedLocation.removeLocationUpdates(locationCallback)
        locationUpdatesStarted = false
    }

    private fun openMap() {
        startActivity(
            Intent(this, RealisticMapActivity::class.java)
                .putExtra("voice_enabled", voiceEnabled)
                .putExtra("traffic_enabled", trafficEnabled)
        )
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        saveDrivingPreferences()
        Toast.makeText(
            this,
            if (voiceEnabled) "Indicaciones por voz activadas" else "Indicaciones por voz silenciadas",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleTraffic() {
        trafficEnabled = !trafficEnabled
        saveDrivingPreferences()
        Toast.makeText(
            this,
            if (trafficEnabled) "Tráfico en tiempo real activado" else "Tráfico oculto",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showReportMessage(kind: String) {
        Toast.makeText(
            this,
            "$kind: módulo de reportes preparado; se activará con el servicio colaborativo.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restoreDrivingPreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        voiceEnabled = prefs.getBoolean(PREF_VOICE, true)
        trafficEnabled = prefs.getBoolean(PREF_TRAFFIC, true)
    }

    private fun saveDrivingPreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_VOICE, voiceEnabled)
            .putBoolean(PREF_TRAFFIC, trafficEnabled)
            .apply()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasArCoreApiKey(): Boolean {
        return runCatching {
            val info = packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
            info.metaData
                ?.getString("com.google.android.ar.API_KEY")
                ?.trim()
                .orEmpty()
                .isNotBlank()
        }.getOrDefault(false)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) startLocationUpdates()
    }

    override fun onPause() {
        stopLocationUpdates()
        super.onPause()
    }
}
