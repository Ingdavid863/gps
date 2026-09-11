package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var fusedLocation: FusedLocationProviderClient

    private lateinit var searchInput: EditText
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

    private var trafficSignals: List<SignalPoint> = emptyList()
    private var lastSignalQuery: Location? = null

    private var is3D = true
    private var voiceEnabled = true
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

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            map.uiSettings.isCompassEnabled = true
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(19.432608, -99.133209))
                .zoom(16.3)
                .tilt(60.0)
                .build()

            map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                ensureMapLayers(style)
                requestLocationPermission()
            }

            map.addOnMapLongClickListener { destination ->
                val here = displayLocation ?: rawLocation
                if (here == null) {
                    Toast.makeText(this, "Esperando una ubicación GPS precisa…", Toast.LENGTH_SHORT).show()
                } else {
                    requestRoute(here.latitude, here.longitude, destination.latitude, destination.longitude)
                }
                true
            }
        }

        findViewById<TextView>(R.id.searchButton).setOnClickListener { searchDestination() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestination()
                true
            } else false
        }

        findViewById<TextView>(R.id.recenterButton).setOnClickListener { recenter(true) }
        findViewById<TextView>(R.id.modeButton).setOnClickListener {
            is3D = !is3D
            it as TextView
            it.text = if (is3D) "3D" else "2D"
            recenter(true)
        }
        findViewById<TextView>(R.id.voiceButton).setOnClickListener {
            voiceEnabled = !voiceEnabled
            (it as TextView).text = if (voiceEnabled) "🔊" else "🔇"
            Toast.makeText(this, if (voiceEnabled) "Indicaciones por voz activadas" else "Indicaciones por voz silenciadas", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.reportButton).setOnClickListener {
            Toast.makeText(this, "Panel de reportes: tráfico, accidente, peligro y obra · siguiente módulo", Toast.LENGTH_LONG).show()
        }
        stopButton.setOnClickListener { stopNavigation() }

        ui.post(ticker)
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

    private fun ensureMapLayers(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(LineLayer(ROUTE_OUTLINE, ROUTE_SOURCE).withProperties(lineColor(Color.WHITE), lineWidth(14f)))
            style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(lineColor(Color.rgb(25, 104, 238)), lineWidth(9f)))
        }
        if (style.getSource(SIGNAL_SOURCE) == null) {
            style.addSource(GeoJsonSource(SIGNAL_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(CircleLayer(SIGNAL_LAYER, SIGNAL_SOURCE).withProperties(circleRadius(7f), circleColor(Color.rgb(245, 180, 0))))
        }
        if (style.getSource(LOCATION_SOURCE) == null) {
            style.addSource(GeoJsonSource(LOCATION_SOURCE, Feature.fromGeometry(Point.fromLngLat(-99.133209, 19.432608))))
            style.addLayer(
                CircleLayer(LOCATION_LAYER, LOCATION_SOURCE).withProperties(
                    circleRadius(9f),
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

    private fun updateCamera(location: Location) {
        val moving = rawLocation?.speed ?: 0f
        if (moving > 1.8f && location.hasBearing()) {
            lastCameraBearing = smoothBearing(lastCameraBearing, location.bearing.toDouble(), 0.32)
        } else if (rawLocation?.hasBearing() == true && moving > 1.0f) {
            lastCameraBearing = smoothBearing(lastCameraBearing, rawLocation!!.bearing.toDouble(), 0.22)
        }

        val target = LatLng(location.latitude, location.longitude)
        val position = CameraPosition.Builder()
            .target(target)
            .zoom(if (routeActive) 17.4 else 16.7)
            .tilt(if (is3D) 62.0 else 0.0)
            .bearing(if (moving > 1.0f) lastCameraBearing else map.cameraPosition.bearing)
            .build()
        map.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position), 550)
    }

    private fun recenter(animated: Boolean) {
        val here = displayLocation ?: return
        val position = CameraPosition.Builder()
            .target(LatLng(here.latitude, here.longitude))
            .zoom(if (routeActive) 17.4 else 16.7)
            .tilt(if (is3D) 62.0 else 0.0)
            .bearing(lastCameraBearing)
            .build()
        if (animated) map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(position), 500)
        else map.cameraPosition = position
    }

    private fun searchDestination() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        instruction.text = "Buscando $query…"

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("nominatim.openstreetmap.org")
            .addPathSegment("search")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .addQueryParameter("countrycodes", "mx")
            .addQueryParameter("q", query)
            .build()
        val req = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.3").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post {
                    instruction.text = "No se pudo buscar el destino"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val arr = JSONArray(it.body?.string().orEmpty())
                    if (arr.length() == 0) {
                        ui.post { instruction.text = "Destino no encontrado" }
                        return
                    }
                    val item = arr.getJSONObject(0)
                    val lat = item.getString("lat").toDouble()
                    val lon = item.getString("lon").toDouble()
                    val here = displayLocation ?: rawLocation ?: return
                    ui.post { requestRoute(here.latitude, here.longitude, lat, lon) }
                }
            }
        })
    }

    private fun requestRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        instruction.text = "Calculando la mejor ruta…"
        turnIcon.text = "…"
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true&alternatives=true"
        val req = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.3").build()
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
                    val best = routes.getJSONObject(0)
                    val coordinates = best.getJSONObject("geometry").getJSONArray("coordinates")
                    val points = ArrayList<Point>(coordinates.length())
                    for (i in 0 until coordinates.length()) {
                        val c = coordinates.getJSONArray(i)
                        points.add(Point.fromLngLat(c.getDouble(0), c.getDouble(1)))
                    }
                    val parsedSteps = parseSteps(best)
                    val duration = best.optDouble("duration", 0.0)
                    val distance = best.optDouble("distance", 0.0)

                    ui.post {
                        routePoints = points
                        navSteps = parsedSteps
                        currentStepIndex = if (parsedSteps.size > 1) 1 else 0
                        routeDurationSeconds = duration
                        routeDistanceMeters = distance
                        routeActive = points.isNotEmpty()
                        map.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(LineString.fromLngLats(points))
                        stopButton.visibility = if (routeActive) View.VISIBLE else View.GONE
                        updateNavigationStep(displayLocation)
                        recenter(true)
                    }
                }
            }
        })
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
        currentStepIndex = 0
        routeDistanceMeters = 0.0
        routeDurationSeconds = 0.0
        lastSpokenInstruction = ""
        map.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        stopButton.visibility = View.GONE
        etaText.text = "Sin ruta activa"
        routeDistance.text = "Selecciona un destino para comenzar"
        arrivalText.text = ""
        updateNavigationStep(displayLocation)
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
            .header("User-Agent", "GPS3D-AR-David/0.3").build()

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

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        fusedLocation.removeLocationUpdates(locationCallback)
        tts.stop()
        tts.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    data class SignalPoint(val id: Long, val lat: Double, val lon: Double)
    data class NavStep(val lat: Double, val lon: Double, val instruction: String, val icon: String)

    companion object {
        const val ROUTE_SOURCE = "route-source"
        const val ROUTE_OUTLINE = "route-outline"
        const val ROUTE_LAYER = "route-layer"
        const val SIGNAL_SOURCE = "signal-source"
        const val SIGNAL_LAYER = "signal-layer"
        const val LOCATION_SOURCE = "location-source"
        const val LOCATION_LAYER = "location-layer"
    }
}
