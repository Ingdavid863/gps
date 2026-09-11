package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RealisticMapActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webMapView: WebView
    private lateinit var fusedLocation: FusedLocationProviderClient

    private lateinit var searchInput: EditText
    private lateinit var searchSuggestions: LinearLayout
    private lateinit var routeChoices: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var instruction: TextView
    private lateinit var turnIcon: TextView
    private lateinit var turnDistance: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var speedText: TextView
    private lateinit var etaText: TextView
    private lateinit var routeDistance: TextView
    private lateinit var arrivalText: TextView
    private lateinit var stopButton: TextView
    private lateinit var signalDistance: TextView
    private lateinit var signalPhase: TextView
    private lateinit var signalTime: TextView
    private lateinit var modeButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var settings3d: TextView
    private lateinit var settingsTerrain: TextView
    private lateinit var settingsBuildings: TextView
    private lateinit var settingsSatellite: TextView
    private lateinit var settingsVoice: TextView
    private lateinit var settingsAutoZoom: TextView
    private lateinit var settingsFollowDelay: TextView

    private val http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())

    private var mapReady = false
    private var rawLocation: Location? = null
    private var filteredLocation: Location? = null
    private var displayLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var lastCameraBearing = 0.0
    private var lastSpokenInstruction = ""

    private var routePoints: List<GeoPoint> = emptyList()
    private var navSteps: List<NavStep> = emptyList()
    private var currentStepIndex = 0
    private var routeActive = false
    private var routeDurationSeconds = 0.0
    private var routeDistanceMeters = 0.0
    private var routeAlternatives: List<RouteOption> = emptyList()
    private var activeRouteIndex = 0

    private var searchResults: List<SearchResult> = emptyList()
    private var searchCall: Call? = null
    private var searchDebounce: Runnable? = null
    private var suppressSearchWatcher = false

    private var trafficSignals: List<SignalPoint> = emptyList()
    private var lastSignalQuery: Location? = null

    private var is3D = true
    private var terrainEnabled = true
    private var buildingsEnabled = true
    private var satelliteEnabled = false
    private var voiceEnabled = true
    private var autoZoomEnabled = true
    private var followResumeDelayMs = DEFAULT_FOLLOW_RESUME_MS
    private var manualCameraUntilMs = 0L

    private lateinit var tts: TextToSpeech

    private val ticker = object : Runnable {
        override fun run() {
            updateSignalPanel()
            ui.postDelayed(this, 1000L)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { processLocation(it) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startHighAccuracyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realistic_map)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)

        bindViews()
        setupWebMap()
        setupSearchUi()
        setupSettingsUi()
        setupButtons()
        refreshSettingsLabels()
        requestLocationPermission()
        ui.post(ticker)
    }

    private fun bindViews() {
        webMapView = findViewById(R.id.webMapView)
        searchInput = findViewById(R.id.searchInput)
        searchSuggestions = findViewById(R.id.searchSuggestions)
        routeChoices = findViewById(R.id.routeChoices)
        settingsPanel = findViewById(R.id.settingsPanel)
        instruction = findViewById(R.id.instruction)
        turnIcon = findViewById(R.id.turnIcon)
        turnDistance = findViewById(R.id.turnDistance)
        gpsStatus = findViewById(R.id.gpsStatus)
        speedText = findViewById(R.id.speedText)
        etaText = findViewById(R.id.etaText)
        routeDistance = findViewById(R.id.routeDistance)
        arrivalText = findViewById(R.id.arrivalText)
        stopButton = findViewById(R.id.stopButton)
        signalDistance = findViewById(R.id.signalDistance)
        signalPhase = findViewById(R.id.signalPhase)
        signalTime = findViewById(R.id.signalTime)
        modeButton = findViewById(R.id.modeButton)
        voiceButton = findViewById(R.id.voiceButton)
        settings3d = findViewById(R.id.settings3d)
        settingsTerrain = findViewById(R.id.settingsTerrain)
        settingsBuildings = findViewById(R.id.settingsBuildings)
        settingsSatellite = findViewById(R.id.settingsSatellite)
        settingsVoice = findViewById(R.id.settingsVoice)
        settingsAutoZoom = findViewById(R.id.settingsAutoZoom)
        settingsFollowDelay = findViewById(R.id.settingsFollowDelay)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebMap() {
        webMapView.setBackgroundColor(Color.TRANSPARENT)
        webMapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webMapView.settings.javaScriptEnabled = true
        webMapView.settings.domStorageEnabled = true
        webMapView.settings.loadsImagesAutomatically = true
        webMapView.settings.builtInZoomControls = false
        webMapView.settings.displayZoomControls = false
        webMapView.webChromeClient = WebChromeClient()
        webMapView.webViewClient = WebViewClient()
        webMapView.addJavascriptInterface(WebMapBridge(), "AndroidBridge")
        webMapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                pauseCameraFollow()
            }
            false
        }
        webMapView.loadUrl("file:///android_asset/map3d.html")
    }

    private inner class WebMapBridge {
        @JavascriptInterface
        fun onMapReady() {
            ui.post {
                mapReady = true
                syncMapAll()
            }
        }

        @JavascriptInterface
        fun routeTo(lat: Double, lon: Double) {
            ui.post {
                val here = displayLocation ?: rawLocation
                if (here == null) {
                    Toast.makeText(this@RealisticMapActivity, "Esperando una ubicación GPS precisa…", Toast.LENGTH_SHORT).show()
                } else {
                    hideSearchSuggestions()
                    requestRoute(here.latitude, here.longitude, lat, lon)
                }
            }
        }
    }

    private fun setupSearchUi() {
        findViewById<TextView>(R.id.searchButton).setOnClickListener { searchDestination() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestination()
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val query = s?.toString()?.trim().orEmpty()
                searchDebounce?.let { ui.removeCallbacks(it) }
                searchCall?.cancel()

                if (query.length < 3) {
                    searchResults = emptyList()
                    hideSearchSuggestions()
                    return
                }

                searchDebounce = Runnable { fetchSearchSuggestions(query, navigateFirst = false) }
                ui.postDelayed(searchDebounce!!, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setupSettingsUi() {
        findViewById<TextView>(R.id.menuButton).setOnClickListener {
            hideSearchSuggestions()
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        settings3d.setOnClickListener {
            is3D = !is3D
            refreshSettingsLabels()
            syncVisualSettings()
            recenter(true)
        }
        settingsTerrain.setOnClickListener {
            terrainEnabled = !terrainEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsBuildings.setOnClickListener {
            buildingsEnabled = !buildingsEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsSatellite.setOnClickListener {
            satelliteEnabled = !satelliteEnabled
            refreshSettingsLabels()
            syncVisualSettings()
        }
        settingsVoice.setOnClickListener {
            voiceEnabled = !voiceEnabled
            refreshSettingsLabels()
        }
        settingsAutoZoom.setOnClickListener {
            autoZoomEnabled = !autoZoomEnabled
            refreshSettingsLabels()
            recenter(true)
        }
        settingsFollowDelay.setOnClickListener {
            followResumeDelayMs = when (followResumeDelayMs) {
                5000L -> 8000L
                8000L -> 12000L
                12000L -> 20000L
                else -> 5000L
            }
            refreshSettingsLabels()
        }
        findViewById<TextView>(R.id.settingsClose).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.recenterButton).setOnClickListener { recenter(true) }
        modeButton.setOnClickListener {
            is3D = !is3D
            refreshSettingsLabels()
            syncVisualSettings()
            recenter(true)
        }
        voiceButton.setOnClickListener {
            voiceEnabled = !voiceEnabled
            refreshSettingsLabels()
            Toast.makeText(
                this,
                if (voiceEnabled) "Indicaciones por voz activadas" else "Indicaciones por voz silenciadas",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<TextView>(R.id.reportButton).setOnClickListener {
            Toast.makeText(
                this,
                "Reportes de tráfico, accidente, peligro y obra: siguiente módulo",
                Toast.LENGTH_LONG
            ).show()
        }
        stopButton.setOnClickListener { stopNavigation() }
    }

    private fun refreshSettingsLabels() {
        modeButton.text = if (is3D) "3D" else "2D"
        voiceButton.text = if (voiceEnabled) "🔊" else "🔇"
        settings3d.text = "Vista 3D avanzada: ${if (is3D) "activada" else "desactivada"}"
        settingsTerrain.text = "Terreno DEM real: ${if (terrainEnabled) "activado" else "desactivado"}"
        settingsBuildings.text = "Edificios 3D por tiles: ${if (buildingsEnabled) "activados" else "desactivados"}"
        settingsSatellite.text = "Satélite híbrido: ${if (satelliteEnabled) "activado" else "desactivado"}"
        settingsVoice.text = "Voz: ${if (voiceEnabled) "activada" else "desactivada"}"
        settingsAutoZoom.text = "Zoom automático según velocidad: ${if (autoZoomEnabled) "activado" else "desactivado"}"
        settingsFollowDelay.text = "Retomar seguimiento después de zoom: ${followResumeDelayMs / 1000L} s"
    }

    private fun syncMapAll() {
        syncVisualSettings()
        syncRoutes()
        syncSignals()
        displayLocation?.let { updateLocationMarker(it) }
        recenter(false)
    }

    private fun syncVisualSettings() {
        jsCall("setVisuals($is3D,$terrainEnabled,$buildingsEnabled,$satelliteEnabled)")
    }

    private fun jsCall(call: String) {
        if (!mapReady) return
        webMapView.evaluateJavascript("window.GPS3D && window.GPS3D.$call;", null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "MX")
        }
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startHighAccuracyLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHighAccuracyLocation() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 750L)
            .setMinUpdateIntervalMillis(400L)
            .setMaxUpdateDelayMillis(1200L)
            .setWaitForAccurateLocation(true)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun processLocation(location: Location) {
        rawLocation = location

        if (location.hasAccuracy() && location.accuracy > 65f) {
            gpsStatus.text = "GPS débil · ±${location.accuracy.toInt()} m"
            return
        }

        val previous = lastAcceptedLocation
        if (previous != null) {
            val dt = max(0.25, (location.time - previous.time) / 1000.0)
            val jumpSpeed = previous.distanceTo(location) / dt
            if (jumpSpeed > 85.0 && location.accuracy > 12f) return
        }
        lastAcceptedLocation = Location(location)

        val smooth = smoothLocation(location)
        filteredLocation = smooth
        val shown = if (routeActive) snapToRoute(smooth) else smooth
        displayLocation = shown

        updateLocationMarker(shown)
        updateCamera(shown)
        updateDrivingUi(location, shown)
        updateNavigationStep(shown)
        maybeQuerySignals(location)
    }

    private fun smoothLocation(raw: Location): Location {
        val old = filteredLocation ?: return Location(raw)
        val alpha = when {
            raw.speed > 15f -> 0.72
            raw.accuracy <= 6f -> 0.62
            raw.accuracy <= 15f -> 0.46
            else -> 0.28
        }
        return Location(raw).apply {
            latitude = old.latitude + (raw.latitude - old.latitude) * alpha
            longitude = old.longitude + (raw.longitude - old.longitude) * alpha
        }
    }

    private fun updateDrivingUi(raw: Location, shown: Location) {
        val accuracy = if (raw.hasAccuracy()) raw.accuracy.toInt() else 0
        gpsStatus.text = when {
            accuracy in 1..7 -> "GPS excelente · ±$accuracy m"
            accuracy in 8..15 -> "GPS preciso · ±$accuracy m"
            accuracy in 16..30 -> "GPS medio · ±$accuracy m"
            else -> "GPS débil · ±$accuracy m"
        }
        speedText.text = (raw.speed * 3.6f).toInt().coerceAtLeast(0).toString()

        if (routeActive && routeDistanceMeters > 0) {
            val remaining = estimateRemainingDistance(shown)
            val ratio = (remaining / routeDistanceMeters).coerceIn(0.0, 1.0)
            val seconds = routeDurationSeconds * ratio
            etaText.text = formatDuration(seconds)
            routeDistance.text = formatDistance(remaining)
            arrivalText.text = "Llegada aprox. ${formatArrival(seconds)}"
        }
    }

    private fun updateLocationMarker(location: Location) {
        val raw = rawLocation
        val bearing = if (raw?.hasBearing() == true && raw.speed > 0.8f) {
            raw.bearing.toDouble()
        } else {
            lastCameraBearing
        }
        jsCall("setLocation(${num(location.longitude)},${num(location.latitude)},${num(bearing)})")
    }

    private fun pauseCameraFollow() {
        manualCameraUntilMs = SystemClock.elapsedRealtime() + followResumeDelayMs
    }

    private fun updateCamera(location: Location) {
        val moving = rawLocation?.speed ?: 0f
        val raw = rawLocation
        if (raw?.hasBearing() == true && moving > 1.0f) {
            lastCameraBearing = smoothBearing(lastCameraBearing, raw.bearing.toDouble(), if (moving > 8f) 0.38 else 0.24)
        }

        if (SystemClock.elapsedRealtime() < manualCameraUntilMs) return

        val target = lookAheadTarget(location, lastCameraBearing, moving)
        val zoom = desiredZoom(moving)
        val pitch = if (is3D) 68.0 else 0.0
        jsCall(
            "follow(${num(target.lon)},${num(target.lat)},${num(lastCameraBearing)},${num(zoom)},${num(pitch)},650)"
        )
    }

    private fun desiredZoom(speedMps: Float): Double {
        if (!autoZoomEnabled) return 17.0
        if (!routeActive) return 16.9
        return when {
            speedMps >= 30f -> 16.20
            speedMps >= 22f -> 16.50
            speedMps >= 14f -> 16.85
            speedMps >= 7f -> 17.20
            else -> 17.55
        }
    }

    private fun lookAheadTarget(location: Location, bearing: Double, speedMps: Float): GeoPoint {
        if (!routeActive || speedMps < 1.8f) return GeoPoint(location.latitude, location.longitude)
        val meters = (42.0 + speedMps * 2.6).coerceIn(42.0, 112.0)
        val r = Math.toRadians(bearing)
        val lat = location.latitude + (cos(r) * meters / 110540.0)
        val lonScale = 111320.0 * cos(Math.toRadians(location.latitude)).coerceAtLeast(0.2)
        val lon = location.longitude + (sin(r) * meters / lonScale)
        return GeoPoint(lat, lon)
    }

    private fun recenter(animated: Boolean) {
        val here = displayLocation ?: rawLocation ?: return
        manualCameraUntilMs = 0L
        val moving = rawLocation?.speed ?: 0f
        val target = lookAheadTarget(here, lastCameraBearing, moving)
        jsCall(
            "follow(${num(target.lon)},${num(target.lat)},${num(lastCameraBearing)},${num(desiredZoom(moving))},${num(if (is3D) 68.0 else 0.0)},${if (animated) 800 else 1})"
        )
    }

    private fun searchDestination() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        fetchSearchSuggestions(query, navigateFirst = true)
    }

    private fun fetchSearchSuggestions(query: String, navigateFirst: Boolean) {
        if (navigateFirst) instruction.text = "Buscando $query…"
        searchCall?.cancel()

        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("nominatim.openstreetmap.org")
            .addPathSegment("search")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "5")
            .addQueryParameter("countrycodes", "mx")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("dedupe", "1")
            .addQueryParameter("accept-language", "es")
            .addQueryParameter("q", query)

        rawLocation?.let { here ->
            val left = here.longitude - 0.75
            val right = here.longitude + 0.75
            val top = here.latitude + 0.65
            val bottom = here.latitude - 0.65
            builder.addQueryParameter("viewbox", "$left,$top,$right,$bottom")
        }

        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "GPS3D-AR-David/0.5")
            .build()

        searchCall = http.newCall(request)
        searchCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                ui.post {
                    if (navigateFirst) instruction.text = "No se pudo buscar el destino"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val array = JSONArray(it.body?.string().orEmpty())
                    val parsed = ArrayList<SearchResult>()
                    for (i in 0 until min(5, array.length())) {
                        val item = array.getJSONObject(i)
                        parsed.add(
                            SearchResult(
                                label = item.optString("display_name", "Destino"),
                                lat = item.getString("lat").toDouble(),
                                lon = item.getString("lon").toDouble()
                            )
                        )
                    }

                    ui.post {
                        if (!navigateFirst && searchInput.text.toString().trim() != query) return@post
                        searchResults = parsed
                        if (navigateFirst) {
                            val first = parsed.firstOrNull()
                            val current = displayLocation ?: rawLocation
                            when {
                                first == null -> instruction.text = "Destino no encontrado"
                                current == null -> instruction.text = "Esperando ubicación GPS…"
                                else -> {
                                    hideSearchSuggestions()
                                    requestRoute(current.latitude, current.longitude, first.lat, first.lon)
                                }
                            }
                        } else {
                            showSearchSuggestions(parsed)
                        }
                    }
                }
            }
        })
    }

    private fun showSearchSuggestions(results: List<SearchResult>) {
        searchSuggestions.removeAllViews()
        if (results.isEmpty()) {
            hideSearchSuggestions()
            return
        }

        settingsPanel.visibility = View.GONE
        results.forEachIndexed { index, result ->
            val label = result.label.split(",").take(3).joinToString(",")
            val row = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(12), 0)
                text = "${index + 1}. $label"
                textSize = 14f
                setTextColor(Color.rgb(39, 49, 58))
                maxLines = 2
                setOnClickListener {
                    suppressSearchWatcher = true
                    searchInput.setText(label)
                    searchInput.setSelection(searchInput.text.length)
                    suppressSearchWatcher = false
                    hideSearchSuggestions()
                    val current = displayLocation ?: rawLocation
                    if (current == null) {
                        Toast.makeText(this@RealisticMapActivity, "Esperando ubicación GPS…", Toast.LENGTH_SHORT).show()
                    } else {
                        requestRoute(current.latitude, current.longitude, result.lat, result.lon)
                    }
                }
            }
            searchSuggestions.addView(row)
            if (index < results.lastIndex) {
                searchSuggestions.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(Color.argb(25, 0, 0, 0))
                })
            }
        }
        searchSuggestions.visibility = View.VISIBLE
    }

    private fun hideSearchSuggestions() {
        searchSuggestions.visibility = View.GONE
    }

    private fun requestRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        instruction.text = "Calculando rutas…"
        turnIcon.text = "…"
        hideSearchSuggestions()

        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat" +
            "?overview=full&geometries=geojson&steps=true&alternatives=3"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GPS3D-AR-David/0.5")
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post {
                    instruction.text = "No se pudo calcular la ruta"
                    turnIcon.text = "!"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val routes = root.optJSONArray("routes") ?: return
                    if (routes.length() == 0) return

                    val parsedRoutes = ArrayList<RouteOption>()
                    for (r in 0 until min(3, routes.length())) {
                        val route = routes.getJSONObject(r)
                        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
                        val points = ArrayList<GeoPoint>(coordinates.length())
                        for (i in 0 until coordinates.length()) {
                            val c = coordinates.getJSONArray(i)
                            points.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                        parsedRoutes.add(
                            RouteOption(
                                points = points,
                                steps = parseSteps(route),
                                durationSeconds = route.optDouble("duration", 0.0),
                                distanceMeters = route.optDouble("distance", 0.0)
                            )
                        )
                    }

                    ui.post {
                        routeAlternatives = parsedRoutes
                        activeRouteIndex = 0
                        activateRoute(0, recenterMap = true)
                    }
                }
            }
        })
    }

    private fun activateRoute(index: Int, recenterMap: Boolean) {
        val option = routeAlternatives.getOrNull(index) ?: return
        activeRouteIndex = index
        routePoints = option.points
        navSteps = option.steps
        currentStepIndex = if (navSteps.size > 1) 1 else 0
        routeDurationSeconds = option.durationSeconds
        routeDistanceMeters = option.distanceMeters
        routeActive = routePoints.isNotEmpty()
        lastSpokenInstruction = ""

        syncRoutes()
        stopButton.visibility = if (routeActive) View.VISIBLE else View.GONE
        etaText.text = formatDuration(routeDurationSeconds)
        routeDistance.text = formatDistance(routeDistanceMeters)
        arrivalText.text = "Llegada aprox. ${formatArrival(routeDurationSeconds)}"
        updateNavigationStep(displayLocation)
        showRouteChoices()
        if (recenterMap) recenter(true)
    }

    private fun syncRoutes() {
        val others = routeAlternatives.indices.filter { it != activeRouteIndex }.take(2)
        val alt1 = others.getOrNull(0)?.let { routeAlternatives[it].points } ?: emptyList()
        val alt2 = others.getOrNull(1)?.let { routeAlternatives[it].points } ?: emptyList()
        jsCall("setRoutes(${pointsJson(routePoints)},${pointsJson(alt1)},${pointsJson(alt2)})")
    }

    private fun showRouteChoices() {
        routeChoices.removeAllViews()
        if (routeAlternatives.size < 2) {
            routeChoices.visibility = View.GONE
            return
        }

        routeAlternatives.take(3).forEachIndexed { index, option ->
            val card = TextView(this).apply {
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(if (index == 0) 0 else dp(3), 0, if (index == routeAlternatives.lastIndex) 0 else dp(3), 0)
                layoutParams = params
                background = ContextCompat.getDrawable(this@RealisticMapActivity, R.drawable.search_panel)
                elevation = dp(4).toFloat()
                gravity = android.view.Gravity.CENTER
                setPadding(dp(5), dp(8), dp(5), dp(8))
                text = if (index == activeRouteIndex) {
                    "✓ ${formatDuration(option.durationSeconds)}\n${formatDistance(option.distanceMeters)}"
                } else {
                    "Ruta ${index + 1} · ${formatDuration(option.durationSeconds)}\n${formatDistance(option.distanceMeters)}"
                }
                textSize = 12f
                setTextColor(if (index == activeRouteIndex) Color.rgb(22, 105, 216) else Color.rgb(56, 64, 72))
                setOnClickListener { activateRoute(index, recenterMap = false) }
            }
            routeChoices.addView(card)
        }
        routeChoices.visibility = View.VISIBLE
    }

    private fun parseSteps(route: JSONObject): List<NavStep> {
        val out = ArrayList<NavStep>()
        val legs = route.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val loc = maneuver.optJSONArray("location") ?: continue
                val type = maneuver.optString("type", "turn")
                val modifier = maneuver.optString("modifier", "straight")
                val name = step.optString("name", "").ifBlank { "la vía" }
                out.add(
                    NavStep(
                        lat = loc.getDouble(1),
                        lon = loc.getDouble(0),
                        instruction = buildInstruction(type, modifier, name),
                        icon = iconFor(type, modifier)
                    )
                )
            }
        }
        return out
    }

    private fun buildInstruction(type: String, modifier: String, name: String): String = when (type) {
        "arrive" -> "Llegaste a tu destino"
        "depart" -> "Continúa por $name"
        "roundabout", "rotary" -> "En la glorieta, continúa hacia $name"
        else -> when {
            modifier.contains("left") -> "Gira a la izquierda en $name"
            modifier.contains("right") -> "Gira a la derecha en $name"
            modifier == "uturn" -> "Da vuelta en U hacia $name"
            else -> "Continúa por $name"
        }
    }

    private fun iconFor(type: String, modifier: String): String = when {
        type == "arrive" -> "●"
        type == "roundabout" || type == "rotary" -> "↻"
        modifier.contains("left") -> "↰"
        modifier.contains("right") -> "↱"
        modifier == "uturn" -> "↶"
        else -> "↑"
    }

    private fun updateNavigationStep(location: Location?) {
        if (!routeActive || navSteps.isEmpty() || location == null) {
            if (!routeActive) {
                instruction.text = "Busca un destino o mantén pulsado el mapa"
                turnIcon.text = "↑"
                turnDistance.text = "GPS3D · terreno DEM + edificios 3D"
            }
            return
        }

        var step = navSteps[currentStepIndex.coerceIn(0, navSteps.lastIndex)]
        var distance = distanceMeters(location.latitude, location.longitude, step.lat, step.lon)
        if (distance < 28 && currentStepIndex < navSteps.lastIndex) {
            currentStepIndex++
            step = navSteps[currentStepIndex]
            distance = distanceMeters(location.latitude, location.longitude, step.lat, step.lon)
        }

        instruction.text = step.instruction
        turnIcon.text = step.icon
        turnDistance.text = "En ${formatDistance(distance)}"

        if (voiceEnabled && distance < 180 && step.instruction != lastSpokenInstruction) {
            lastSpokenInstruction = step.instruction
            tts.speak(
                "En ${formatDistance(distance)}, ${step.instruction}",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "nav"
            )
        }
    }

    private fun stopNavigation() {
        routeActive = false
        routePoints = emptyList()
        navSteps = emptyList()
        routeAlternatives = emptyList()
        activeRouteIndex = 0
        currentStepIndex = 0
        routeDistanceMeters = 0.0
        routeDurationSeconds = 0.0
        lastSpokenInstruction = ""

        syncRoutes()
        routeChoices.visibility = View.GONE
        stopButton.visibility = View.GONE
        etaText.text = "Sin ruta activa"
        routeDistance.text = "Selecciona un destino para comenzar"
        arrivalText.text = ""
        updateNavigationStep(displayLocation)
        recenter(true)
    }

    private fun snapToRoute(location: Location): Location {
        if (routePoints.size < 2) return location
        var bestLat = location.latitude
        var bestLon = location.longitude
        var bestDistance = Double.MAX_VALUE

        val maxSnap = max(18.0, min(42.0, (if (location.hasAccuracy()) location.accuracy else 12f) * 1.7))
        for (i in 0 until routePoints.lastIndex) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val projected = projectToSegment(location.latitude, location.longitude, a.lat, a.lon, b.lat, b.lon)
            val d = distanceMeters(location.latitude, location.longitude, projected.first, projected.second)
            if (d < bestDistance) {
                bestDistance = d
                bestLat = projected.first
                bestLon = projected.second
            }
        }

        return if (bestDistance <= maxSnap) {
            Location(location).apply {
                latitude = bestLat
                longitude = bestLon
            }
        } else {
            location
        }
    }

    private fun projectToSegment(
        lat: Double,
        lon: Double,
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Pair<Double, Double> {
        val refLat = Math.toRadians((lat1 + lat2 + lat) / 3.0)
        val metersLon = 111320.0 * cos(refLat)
        val metersLat = 110540.0
        val px = (lon - lon1) * metersLon
        val py = (lat - lat1) * metersLat
        val bx = (lon2 - lon1) * metersLon
        val by = (lat2 - lat1) * metersLat
        val denom = bx * bx + by * by
        val t = if (denom < 0.001) 0.0 else ((px * bx + py * by) / denom).coerceIn(0.0, 1.0)
        return Pair(lat1 + (lat2 - lat1) * t, lon1 + (lon2 - lon1) * t)
    }

    private fun estimateRemainingDistance(location: Location): Double {
        if (routePoints.size < 2) return routeDistanceMeters
        var nearestIndex = 0
        var nearestDistance = Double.MAX_VALUE
        val stride = max(1, routePoints.size / 700)

        for (i in routePoints.indices step stride) {
            val p = routePoints[i]
            val d = distanceMeters(location.latitude, location.longitude, p.lat, p.lon)
            if (d < nearestDistance) {
                nearestDistance = d
                nearestIndex = i
            }
        }

        var remaining = 0.0
        for (i in nearestIndex until routePoints.lastIndex) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            remaining += distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return remaining.coerceAtMost(routeDistanceMeters * 1.05)
    }

    private fun maybeQuerySignals(location: Location) {
        val previous = lastSignalQuery
        if (previous != null && location.distanceTo(previous) < 450f) return
        lastSignalQuery = Location(location)

        val query = "[out:json][timeout:12];node[\"highway\"=\"traffic_signals\"]" +
            "(around:1800,${location.latitude},${location.longitude});out;"
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(body)
            .header("User-Agent", "GPS3D-AR-David/0.5")
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val array = root.optJSONArray("elements") ?: return
                    val found = ArrayList<SignalPoint>()
                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        found.add(
                            SignalPoint(
                                id = o.optLong("id"),
                                lat = o.optDouble("lat"),
                                lon = o.optDouble("lon")
                            )
                        )
                    }
                    ui.post {
                        trafficSignals = found
                        syncSignals()
                        updateSignalPanel()
                    }
                }
            }
        })
    }

    private fun syncSignals() {
        val json = trafficSignals.take(300).joinToString(prefix = "[", postfix = "]") {
            "[${num(it.lon)},${num(it.lat)}]"
        }
        jsCall("setSignals($json)")
    }

    private fun updateSignalPanel() {
        val here = displayLocation ?: return
        val nearest = trafficSignals.minByOrNull {
            distanceMeters(here.latitude, here.longitude, it.lat, it.lon)
        } ?: return

        val meters = distanceMeters(here.latitude, here.longitude, nearest.lat, nearest.lon).toInt()
        val cycle = 70
        val offset = (nearest.id % cycle).toInt()
        val t = (((System.currentTimeMillis() / 1000L).toInt() + offset) % cycle + cycle) % cycle
        val phase: String
        val remaining: Int

        when {
            t < 34 -> {
                phase = "🟢 DEMO"
                remaining = 34 - t
            }
            t < 38 -> {
                phase = "🟡 DEMO"
                remaining = 38 - t
            }
            else -> {
                phase = "🔴 DEMO"
                remaining = 70 - t
            }
        }

        signalDistance.text = "🚦 Próximo: ${formatDistance(meters.toDouble())}"
        signalPhase.text = phase
        signalTime.text = "$remaining s"
    }

    private fun pointsJson(points: List<GeoPoint>): String = points.joinToString(prefix = "[", postfix = "]") {
        "[${num(it.lon)},${num(it.lat)}]"
    }

    private fun smoothBearing(old: Double, target: Double, alpha: Double): Double {
        if (old == 0.0) return target
        val delta = (target - old + 540.0) % 360.0 - 180.0
        return (old + delta * alpha + 360.0) % 360.0
    }

    private fun formatDistance(meters: Double): String = when {
        meters < 950 -> "${meters.toInt().coerceAtLeast(0)} m"
        else -> String.format(Locale("es", "MX"), "%.1f km", meters / 1000.0)
    }

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun formatArrival(seconds: Double): String {
        val time = System.currentTimeMillis() + (seconds * 1000.0).toLong()
        return SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date(time))
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        webMapView.onResume()
    }

    override fun onPause() {
        webMapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchCall?.cancel()
        fusedLocation.removeLocationUpdates(locationCallback)
        tts.stop()
        tts.shutdown()
        webMapView.removeJavascriptInterface("AndroidBridge")
        webMapView.loadUrl("about:blank")
        webMapView.destroy()
        super.onDestroy()
    }

    data class GeoPoint(val lat: Double, val lon: Double)
    data class SignalPoint(val id: Long, val lat: Double, val lon: Double)
    data class NavStep(val lat: Double, val lon: Double, val instruction: String, val icon: String)
    data class SearchResult(val label: String, val lat: Double, val lon: Double)
    data class RouteOption(
        val points: List<GeoPoint>,
        val steps: List<NavStep>,
        val durationSeconds: Double,
        val distanceMeters: Double
    )

    companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val DEFAULT_FOLLOW_RESUME_MS = 8000L
    }
}
