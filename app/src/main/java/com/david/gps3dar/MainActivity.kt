package com.david.gps3dar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var instruction: TextView
    private lateinit var signalDistance: TextView
    private lateinit var signalPhase: TextView
    private lateinit var signalTime: TextView

    private val http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())
    private var currentLocation: Location? = null
    private var trafficSignals: List<SignalPoint> = emptyList()
    private var lastSignalQuery: Location? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateSignalPanel()
            ui.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) enableLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        instruction = findViewById(R.id.instruction)
        signalDistance = findViewById(R.id.signalDistance)
        signalPhase = findViewById(R.id.signalPhase)
        signalTime = findViewById(R.id.signalTime)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(19.432608, -99.133209))
                .zoom(15.5)
                .tilt(58.0)
                .build()

            map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                ensureRouteLayers(style)
                ensureSignalLayers(style)
                requestLocationPermission()
            }

            map.addOnMapLongClickListener { destination ->
                val here = currentLocation
                if (here == null) {
                    Toast.makeText(this, "Esperando ubicación GPS…", Toast.LENGTH_SHORT).show()
                } else {
                    requestRoute(here.latitude, here.longitude, destination.latitude, destination.longitude)
                }
                true
            }
        }

        ui.post(ticker)
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            enableLocation()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val style = map.style ?: return
        val component = map.locationComponent
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(true)
                .build()
        )
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.TRACKING_COMPASS

        component.locationEngine?.requestLocationUpdates(
            org.maplibre.android.location.engine.LocationEngineRequest.Builder(1000L)
                .setPriority(org.maplibre.android.location.engine.LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setFastestInterval(750L)
                .build(),
            object : org.maplibre.android.location.engine.LocationEngineCallback<org.maplibre.android.location.engine.LocationEngineResult> {
                override fun onSuccess(result: org.maplibre.android.location.engine.LocationEngineResult?) {
                    val loc = result?.lastLocation ?: return
                    currentLocation = loc
                    maybeQuerySignals(loc)
                }
                override fun onFailure(exception: Exception) = Unit
            },
            Looper.getMainLooper()
        )
    }

    private fun ensureRouteLayers(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(
                LineLayer(ROUTE_OUTLINE, ROUTE_SOURCE).withProperties(
                    lineColor(Color.argb(180, 0, 0, 0)), lineWidth(13f)
                )
            )
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor(Color.rgb(0, 180, 255)), lineWidth(8f)
                )
            )
        }
    }

    private fun ensureSignalLayers(style: Style) {
        if (style.getSource(SIGNAL_SOURCE) == null) {
            style.addSource(GeoJsonSource(SIGNAL_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(
                CircleLayer(SIGNAL_LAYER, SIGNAL_SOURCE).withProperties(
                    circleRadius(8f), circleColor(Color.rgb(245, 180, 0))
                )
            )
        }
    }

    private fun requestRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        instruction.text = "Calculando ruta…"
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        val req = Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.2").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post {
                    instruction.text = "No se pudo calcular la ruta"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val root = JSONObject(it.body?.string().orEmpty())
                    val routes = root.optJSONArray("routes") ?: return
                    if (routes.length() == 0) return
                    val coordinates = routes.getJSONObject(0)
                        .getJSONObject("geometry").getJSONArray("coordinates")
                    val points = ArrayList<Point>()
                    for (i in 0 until coordinates.length()) {
                        val c = coordinates.getJSONArray(i)
                        points.add(Point.fromLngLat(c.getDouble(0), c.getDouble(1)))
                    }
                    ui.post {
                        val style = map.style ?: return@post
                        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(LineString.fromLngLats(points))
                        instruction.text = "Ruta activa · mantén pulsado para cambiar destino"
                        if (points.isNotEmpty()) {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fromLat, fromLon), 17.0))
                            map.cameraPosition = CameraPosition.Builder(map.cameraPosition).tilt(60.0).build()
                        }
                    }
                }
            }
        })
    }

    private fun maybeQuerySignals(location: Location) {
        val prev = lastSignalQuery
        if (prev != null && location.distanceTo(prev) < 450f) return
        lastSignalQuery = Location(location)

        val query = "[out:json][timeout:12];node[\"highway\"=\"traffic_signals\"](around:1800,${location.latitude},${location.longitude});out;"
        val body = FormBody.Builder().add("data", query).build()
        val req = Request.Builder().url("https://overpass-api.de/api/interpreter")
            .post(body).header("User-Agent", "GPS3D-AR-David/0.2").build()

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
                        map.style?.getSourceAs<GeoJsonSource>(SIGNAL_SOURCE)
                            ?.setGeoJson(FeatureCollection.fromFeatures(features))
                        updateSignalPanel()
                    }
                }
            }
        })
    }

    private fun updateSignalPanel() {
        val here = currentLocation ?: return
        val nearest = trafficSignals.minByOrNull { distanceMeters(here.latitude, here.longitude, it.lat, it.lon) } ?: return
        val meters = distanceMeters(here.latitude, here.longitude, nearest.lat, nearest.lon).toInt()

        val cycle = 70
        val offset = (nearest.id % cycle).toInt()
        val t = (((System.currentTimeMillis() / 1000L).toInt() + offset) % cycle + cycle) % cycle
        val phase: String
        val remaining: Int
        when {
            t < 34 -> { phase = "🟢 VERDE [SIMULADO]"; remaining = 34 - t }
            t < 38 -> { phase = "🟡 AMARILLO [SIMULADO]"; remaining = 38 - t }
            else -> { phase = "🔴 ROJO [SIMULADO]"; remaining = 70 - t }
        }
        signalDistance.text = "🚦 Semáforo próximo: $meters m"
        signalPhase.text = "Estado: $phase"
        signalTime.text = "Cambio en: $remaining s"
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = Location("a").apply { latitude = lat1; longitude = lon1 }
        val b = Location("b").apply { latitude = lat2; longitude = lon2 }
        return a.distanceTo(b).toDouble()
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { ui.removeCallbacks(ticker); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    data class SignalPoint(val id: Long, val lat: Double, val lon: Double)

    companion object {
        const val ROUTE_SOURCE = "route-source"
        const val ROUTE_OUTLINE = "route-outline"
        const val ROUTE_LAYER = "route-layer"
        const val SIGNAL_SOURCE = "signal-source"
        const val SIGNAL_LAYER = "signal-layer"
    }
}
