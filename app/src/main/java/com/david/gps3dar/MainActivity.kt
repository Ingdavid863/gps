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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
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
    private lateinit var settingsVoice: TextView
    private lateinit var settingsAutoZoom: TextView
    private lateinit var settingsFollowDelay: TextView

    private val http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())

    private var rawLocation: Location? = null
    private var filteredLocation: Location? = null
    private var displayLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var lastCameraBearing = 0.0
    private var lastSpokenInstruction = ""

    private var routePoints: List<Point> = emptyList()
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
    private var voiceEnabled = true
    private var autoZoomEnabled = true
    private var followResumeDelayMs = DEFAULT_FOLLOW_RESUME_MS
    private var manualCameraUntilMs = 0L
    private lateinit var tts: TextToSpeech

    private val ticker = object : Runnable {
        override fun run() {
            updateSignalPanel()
            ui.postDelayed(this, 1000)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val newest = result.lastLocation ?: return
            processLocation(newest)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) startHighAccuracyLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)

        mapView = findViewById(R.id.mapView)
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
        settingsVoice = findViewById(R.id.settingsVoice)
        settingsAutoZoom = findViewById(R.id.settingsAutoZoom)
        settingsFollowDelay = findViewById(R.id.settingsFollowDelay)

        setupSearchUi()
        setupSettingsUi()
        setupButtons()
        refreshSettingsLabels()

        mapView.onCreate(savedInstanceState)
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                pauseCameraFollow()
            }
            false
        }

        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            map.uiSettings.isCompassEnabled = true
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(19.432608, -99.133209))
                .zoom(16.8)
                .tilt(60.0)
                .build()

            map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                enhance3DBuildings(style)
                ensureMapLayers(style)
                requestLocationPermission()
            }

            map.addOnMapLongClickListener { destination ->
                val here = displayLocation ?: rawLocation
                if (here == null) {
                    Toast.makeText(this, "Esperando una ubicación GPS precisa…", Toast.LENGTH_SHORT).show()
                } else {
                    hideSearchSuggestions()
                    requestRoute(here.latitude, here.longitude, destination.latitude, destination.longitude)
                }
                true
            }
        }

        ui.post(ticker)
    }

    private fun setupSearchUi() {
        findViewById<TextView>(R.id.searchButton).setOnClickListener { searchDestination() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestination()
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val q = s?.toString()?.trim().orEmpty()
                searchDebounce?.let { ui.removeCallbacks(it) }
                searchCall?.cancel()
                if (q.length < 3) {
                    searchResults = emptyList()
                    hideSearchSuggestions()
                    return
                }
                searchDebounce = Runnable { fetchSearchSuggestions(q, navigateFirst = false) }
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
            recenter(true)
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
        findViewById<TextView>(R.id.settingsClose).setOnClickListener { settingsPanel.visibility = View.GONE }
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.recenterButton).setOnClickListener { recenter(true) }
        modeButton.setOnClickListener {
            is3D = !is3D
            refreshSettingsLabels()
            recenter(true)
        }
        voiceButton.setOnClickListener {
            voiceEnabled = !voiceEnabled
            refreshSettingsLabels()
            Toast.makeText(this, if (voiceEnabled) "Indicaciones por voz activadas" else "Indicaciones por voz silenciadas", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.reportButton).setOnClickListener {
            Toast.makeText(this, "Reportes de tráfico, accidente, peligro y obra: siguiente módulo", Toast.LENGTH_LONG).show()
        }
        stopButton.setOnClickListener { stopNavigation() }
    }

    private fun refreshSettingsLabels() {
        if (::modeButton.isInitialized) modeButton.text = if (is3D) "3D" else "2D"
        if (::voiceButton.isInitialized) voiceButton.text = if (voiceEnabled) "🔊" else "🔇"
        if (::settings3d.isInitialized) settings3d.text = "Vista 3D: ${if (is3D) "activada" else "desactivada"}"
        if (::settingsVoice.isInitialized) settingsVoice.text = "Voz: ${if (voiceEnabled) "activada" else "desactivada"}"
        if (::settingsAutoZoom.isInitialized) settingsAutoZoom.text = "Zoom automático según velocidad: ${if (autoZoomEnabled) "activado" else "desactivado"}"
        if (::settingsFollowDelay.isInitialized) settingsFollowDelay.text = "Retomar seguimiento después de zoom: ${followResumeDelayMs / 1000L} s"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale("es", "MX")
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startHighAccuracyLocation()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
        val snapped = if (routeActive) snapToRoute(smooth) else smooth
        displayLocation = snapped

        updateLocationMarker(snapped)
        updateCamera(snapped)
        updateDrivingUi(location, snapped)
        updateNavigationStep(snapped)
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

    private fun enhance3DBuildings(style: Style) {
        val height = Expression.coalesce(
            Expression.get("render_height"),
            Expression.literal(8.0)
        )
        val base = Expression.coalesce(
            Expression.get("render_min_height"),
            Expression.literal(0.0)
        )

        val existing = style.getLayer("building-3d")
        if (existing is FillExtrusionLayer) {
            existing.minZoom = 14f
            existing.setProperties(
                fillExtrusionColor(Color.rgb(205, 211, 216)),
                fillExtrusionHeight(height),
                fillExtrusionBase(base),
                fillExtrusionOpacity(0.92f)
            )
        } else if (style.getSource("openmaptiles") != null) {
            val buildings = FillExtrusionLayer(BUILDING_3D_LAYER, "openmaptiles")
            buildings.sourceLayer = "building"
            buildings.minZoom = 14f
            buildings.setProperties(
                fillExtrusionColor(Color.rgb(205, 211, 216)),
                fillExtrusionHeight(height),
                fillExtrusionBase(base),
                fillExtrusionOpacity(0.92f)
            )
            style.addLayer(buildings)
        }
    }

    private fun ensureMapLayers(style: Style) {
        if (style.getSource(ALT_ROUTE_SOURCE_1) == null) {
            style.addSource(GeoJsonSource(ALT_ROUTE_SOURCE_1, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(LineLayer(ALT_ROUTE_LAYER_1, ALT_ROUTE_SOURCE_1).withProperties(
                lineColor(Color.rgb(120, 129, 139)), lineWidth(7f), lineOpacity(0.82f)
            ))
        }
        if (style.getSource(ALT_ROUTE_SOURCE_2) == null) {
            style.addSource(GeoJsonSource(ALT_ROUTE_SOURCE_2, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(LineLayer(ALT_ROUTE_LAYER_2, ALT_ROUTE_SOURCE_2).withProperties(
                lineColor(Color.rgb(92, 119, 146)), lineWidth(7f), lineOpacity(0.76f)
            ))
        }
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(LineLayer(ROUTE_OUTLINE, ROUTE_SOURCE).withProperties(lineColor(Color.WHITE), lineWidth(15f)))
            style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(lineColor(Color.rgb(22, 105, 238)), lineWidth(10f)))
        }
        if (style.getSource(SIGNAL_SOURCE) == null) {
            style.addSource(GeoJsonSource(SIGNAL_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(CircleLayer(SIGNAL_LAYER, SIGNAL_SOURCE).withProperties(circleRadius(7f), circleColor(Color.rgb(245, 180, 0))))
        }
        if (style.getSource(LOCATION_SOURCE) == null) {
            style.addSource(GeoJsonSource(LOCATION_SOURCE, Feature.fromGeometry(Point.fromLngLat(-99.133209, 19.432608))))
            style.addLayer(
                CircleLayer(LOCATION_LAYER, LOCATION_SOURCE).withProperties(
                    circleRadius(10f),
                    circleColor(Color.rgb(28, 113, 245)),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(4f)
                )
            )
        }
    }

    private fun updateLocationMarker(location: Location) {
        val point = Point.fromLngLat(location.longitude, location.latitude)
        map.style?.getSourceAs<GeoJsonSource>(LOCATION_SOURCE)?.setGeoJson(point)
    }

    private fun pauseCameraFollow() {
        manualCameraUntilMs = SystemClock.elapsedRealtime() + followResumeDelayMs
    }

    private fun updateCamera(location: Location) {
        val moving = rawLocation?.speed ?: 0f
        if (moving > 1.8f && location.hasBearing()) {
            lastCameraBearing = smoothBearing(lastCameraBearing, location.bearing.toDouble(), 0.32)
        } else if (rawLocation?.hasBearing() == true && moving > 1.0f) {
            lastCameraBearing = smoothBearing(lastCameraBearing, rawLocation!!.bearing.toDouble(), 0.22)
        }

        if (SystemClock.elapsedRealtime() < manualCameraUntilMs) return

        val bearing = if (moving > 1.0f) lastCameraBearing else map.cameraPosition.bearing
        val target = lookAheadTarget(location, bearing, moving)
        val position = CameraPosition.Builder()
            .target(target)
            .zoom(desiredZoom(moving))
            .tilt(if (is3D) 60.0 else 0.0)
            .bearing(bearing)
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(position), 650)
    }

    private fun desiredZoom(speedMps: Float): Double {
        if (!autoZoomEnabled) return map.cameraPosition.zoom
        if (!routeActive) return 16.9
        return when {
            speedMps >= 30f -> 16.35
            speedMps >= 22f -> 16.65
            speedMps >= 14f -> 16.95
            speedMps >= 7f -> 17.30
            else -> 17.65
        }
    }

    private fun lookAheadTarget(location: Location, bearing: Double, speedMps: Float): LatLng {
        if (!routeActive || speedMps < 1.8f) return LatLng(location.latitude, location.longitude)
        val meters = (38.0 + speedMps * 2.4).coerceIn(38.0, 105.0)
        val r = Math.toRadians(bearing)
        val lat = location.latitude + (cos(r) * meters / 110540.0)
        val lonScale = 111320.0 * cos(Math.toRadians(location.latitude)).coerceAtLeast(0.2)
        val lon = location.longitude + (sin(r) * meters / lonScale)
        return LatLng(lat, lon)
    }

    private fun recenter(animated: Boolean) {
        val here = displayLocation ?: return
        manualCameraUntilMs = 0L
        val moving = rawLocation?.speed ?: 0f
        val bearing = if (moving > 1.0f) lastCameraBearing else map.cameraPosition.bearing
        val position = CameraPosition.Builder()
            .target(lookAheadTarget(here, bearing, moving))
            .zoom(desiredZoom(moving))
            .tilt(if (is3D) 60.0 else 0.0)
            .bearing(bearing)
            .build()
        if (animated) map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 700)
        else map.cameraPosition = position
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

        val here = rawLocation
        if (here != null) {
            val left = here.longitude - 0.75
            val right = here.longitude + 0.75
            val top = here.latitude + 0.65
            val bottom = here.latitude - 0.65
            builder.addQueryParameter("viewbox", "$left,$top,$right,$bottom")
        }

        val req = Request.Builder().url(builder.build()).header("User-Agent", "GPS3D-AR-David/0.4").build()
        searchCall = http.newCall(req)
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
                    val arr = JSONArray(it.body?.string().orEmpty())
                    val parsed = ArrayList<SearchResult>()
                    for (i in 0 until min(5, arr.length())) {
                        val item = arr.getJSONObject(i)
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
                            if (first == null) {
                                instruction.text = "Destino no encontrado"
                            } else if (current == null) {
                                instruction.text = "Esperando ubicación GPS…"
                            } else {
                                hideSearchSuggestions()
                                requestRoute(current.latitude, current.longitude, first.lat, first.lon)
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
                        Toast.makeText(this@MainActivity, "Esperando ubicación GPS…", Toast.LENGTH_SHORT).show()
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
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true&alternatives=3"
        val req = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.4").build()
        http.newCall(req).enqueue(object : Callback {
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
                        val points = ArrayList<Point>(coordinates.length())
                        for (i in 0 until coordinates.length()) {
                            val c = coordinates.getJSONArray(i)
                            points.add(Point.fromLngLat(c.getDouble(0), c.getDouble(1)))
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
                        showRouteChoices()
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
        drawRouteOptions()
        stopButton.visibility = if (routeActive) View.VISIBLE else View.GONE
        etaText.text = formatDuration(routeDurationSeconds)
        routeDistance.text = formatDistance(routeDistanceMeters)
        arrivalText.text = "Llegada aprox. ${formatArrival(routeDurationSeconds)}"
        updateNavigationStep(displayLocation)
        showRouteChoices()
        if (recenterMap) recenter(true)
    }

    private fun drawRouteOptions() {
        setRouteSource(ROUTE_SOURCE, routePoints)
        val others = routeAlternatives.indices.filter { it != activeRouteIndex }.take(2)
        setRouteSource(ALT_ROUTE_SOURCE_1, others.getOrNull(0)?.let { routeAlternatives[it].points } ?: emptyList())
        setRouteSource(ALT_ROUTE_SOURCE_2, others.getOrNull(1)?.let { routeAlternatives[it].points } ?: emptyList())
    }

    private fun setRouteSource(sourceId: String, points: List<Point>) {
        val source = map.style?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        if (points.size >= 2) source.setGeoJson(LineString.fromLngLats(points))
        else source.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
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
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.search_panel)
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
                turnDistance.text = "GPS3D · navegación de alta precisión"
            }
            return
        }

        var step = navSteps[currentStepIndex.coerceIn(0, navSteps.lastIndex)]
        var d = distanceMeters(location.latitude, location.longitude, step.lat, step.lon)
        if (d < 28 && currentStepIndex < navSteps.lastIndex) {
            currentStepIndex++
            step = navSteps[currentStepIndex]
            d = distanceMeters(location.latitude, location.longitude, step.lat, step.lon)
        }

        instruction.text = step.instruction
        turnIcon.text = step.icon
        turnDistance.text = "En ${formatDistance(d)}"

        if (voiceEnabled && d < 180 && step.instruction != lastSpokenInstruction) {
            lastSpokenInstruction = step.instruction
            tts.speak("En ${formatDistance(d)}, ${step.instruction}", TextToSpeech.QUEUE_FLUSH, null, "nav")
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
        setRouteSource(ROUTE_SOURCE, emptyList())
        setRouteSource(ALT_ROUTE_SOURCE_1, emptyList())
        setRouteSource(ALT_ROUTE_SOURCE_2, emptyList())
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
            val projected = projectToSegment(location.latitude, location.longitude, a.latitude(), a.longitude(), b.latitude(), b.longitude())
            val d = distanceMeters(location.latitude, location.longitude, projected.first, projected.second)
            if (d < bestDistance) {
                bestDistance = d
                bestLat = projected.first
                bestLon = projected.second
            }
        }

        return if (bestDistance <= maxSnap) Location(location).apply {
            latitude = bestLat
            longitude = bestLon
        } else location
    }

    private fun projectToSegment(lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
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
        for (i in routePoints.indices step max(1, routePoints.size / 700)) {
            val p = routePoints[i]
            val d = distanceMeters(location.latitude, location.longitude, p.latitude(), p.longitude())
            if (d < nearestDistance) {
                nearestDistance = d
                nearestIndex = i
            }
        }
        var remaining = 0.0
        for (i in nearestIndex until routePoints.lastIndex) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            remaining += distanceMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude())
        }
        return remaining.coerceAtMost(routeDistanceMeters * 1.05)
    }

    private fun maybeQuerySignals(location: Location) {
        val prev = lastSignalQuery
        if (prev != null && location.distanceTo(prev) < 450f) return
        lastSignalQuery = Location(location)
        val query = "[out:json][timeout:12];node[\"highway\"=\"traffic_signals\"](around:1800,${location.latitude},${location.longitude});out;"
        val body = FormBody.Builder().add("data", query).build()
        val req = Request.Builder().url("https://overpass-api.de/api/interpreter").post(body)
            .header("User-Agent", "GPS3D-AR-David/0.4").build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val arr = root.optJSONArray("elements") ?: return
                    val found = ArrayList<SignalPoint>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        found.add(SignalPoint(o.optLong("id"), o.optDouble("lat"), o.optDouble("lon")))
                    }
                    ui.post {
                        trafficSignals = found
                        val features = found.take(300).map { s -> Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)) }
                        map.style?.getSourceAs<GeoJsonSource>(SIGNAL_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
                        updateSignalPanel()
                    }
                }
            }
        })
    }

    private fun updateSignalPanel() {
        val here = displayLocation ?: return
        val nearest = trafficSignals.minByOrNull { distanceMeters(here.latitude, here.longitude, it.lat, it.lon) } ?: return
        val meters = distanceMeters(here.latitude, here.longitude, nearest.lat, nearest.lon).toInt()
        val cycle = 70
        val offset = (nearest.id % cycle).toInt()
        val t = (((System.currentTimeMillis() / 1000L).toInt() + offset) % cycle + cycle) % cycle
        val phase: String
        val remaining: Int
        when {
            t < 34 -> { phase = "🟢 DEMO"; remaining = 34 - t }
            t < 38 -> { phase = "🟡 DEMO"; remaining = 38 - t }
            else -> { phase = "🔴 DEMO"; remaining = 70 - t }
        }
        signalDistance.text = "🚦 Próximo: ${formatDistance(meters.toDouble())}"
        signalPhase.text = phase
        signalTime.text = "$remaining s"
    }

    private fun smoothBearing(old: Double, target: Double, alpha: Double): Double {
        var delta = (target - old + 540.0) % 360.0 - 180.0
        if (old == 0.0) delta = 0.0
        return if (old == 0.0) target else (old + delta * alpha + 360.0) % 360.0
    }

    private fun formatDistance(meters: Double): String = when {
        meters < 950 -> "${meters.toInt().coerceAtLeast(0)} m"
        else -> String.format(Locale("es", "MX"), "%.1f km", meters / 1000.0)
    }

    private fun formatDuration(seconds: Double): String {
        val mins = (seconds / 60.0).toInt().coerceAtLeast(1)
        return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        searchDebounce?.let { ui.removeCallbacks(it) }
        searchCall?.cancel()
        fusedLocation.removeLocationUpdates(locationCallback)
        tts.stop()
        tts.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    data class SignalPoint(val id: Long, val lat: Double, val lon: Double)
    data class NavStep(val lat: Double, val lon: Double, val instruction: String, val icon: String)
    data class SearchResult(val label: String, val lat: Double, val lon: Double)
    data class RouteOption(
        val points: List<Point>,
        val steps: List<NavStep>,
        val durationSeconds: Double,
        val distanceMeters: Double
    )

    companion object {
        const val ROUTE_SOURCE = "route-source"
        const val ROUTE_OUTLINE = "route-outline"
        const val ROUTE_LAYER = "route-layer"
        const val ALT_ROUTE_SOURCE_1 = "alt-route-source-1"
        const val ALT_ROUTE_LAYER_1 = "alt-route-layer-1"
        const val ALT_ROUTE_SOURCE_2 = "alt-route-source-2"
        const val ALT_ROUTE_LAYER_2 = "alt-route-layer-2"
        const val SIGNAL_SOURCE = "signal-source"
        const val SIGNAL_LAYER = "signal-layer"
        const val LOCATION_SOURCE = "location-source"
        const val LOCATION_LAYER = "location-layer"
        const val BUILDING_3D_LAYER = "gps3d-buildings"
        const val SEARCH_DEBOUNCE_MS = 350L
        const val DEFAULT_FOLLOW_RESUME_MS = 8000L
    }
}
