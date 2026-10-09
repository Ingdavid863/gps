package com.david.gps3dar

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Observed OSM signal locations only: neither congestion nor clock time supplies a signal phase. */
class SignalRepository(private val client: OkHttpClient, private val cache: File? = null,
    private val endpoints: List<String> = listOf("https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter")) {
    data class Signal(val id: Long, val point: RouteGeometry.Point, val pedestrian: Boolean = false)
    data class Snapshot(val center: RouteGeometry.Point, val signals: List<Signal>, val osmTimestamp: String,
        val loadedAt: Long, val cached: Boolean = false)
    @Volatile var snapshot: Snapshot? = runCatching {
        cache?.takeIf { it.isFile }?.readText()?.let { decode(it) }?.copy(cached = true)
    }.getOrNull()
        private set
    @Volatile var lastError: String? = null
        private set
    private var call: Call? = null
    private var epoch = 0
    private var lastAttempt = 0L
    @Synchronized fun load(center: RouteGeometry.Point, now: Long = System.currentTimeMillis(), done: (Snapshot?, String?) -> Unit) {
        if (!valid(center) || call != null || now - lastAttempt < 60_000) return
        val previous = snapshot
        if (previous != null && now - previous.loadedAt in 0..3_600_000 && RouteGeometry.distance(center, previous.center) < 450) return
        lastAttempt = now
        lastError = null
        val token = ++epoch
        request(center, now, token, 0, done)
    }
    private fun request(center: RouteGeometry.Point, now: Long, token: Int, index: Int, done: (Snapshot?, String?) -> Unit) {
        val query = "[out:json][timeout:12];(node[\"highway\"=\"traffic_signals\"](around:2500,${center.lat},${center.lon});node[\"highway\"=\"crossing\"][\"crossing\"=\"traffic_signals\"](around:2500,${center.lat},${center.lon});node[\"highway\"=\"crossing\"][\"crossing:signals\"=\"yes\"](around:2500,${center.lat},${center.lon}););out body;"
        val request = Request.Builder().url(endpoints[index]).post(FormBody.Builder().add("data", query).build())
            .header("User-Agent", "GPS3D-AR-David/0.18 (OSM signal locations)").build()
        val current = client.newCall(request); call = current
        fun failed() {
            synchronized(this) {
                if (token != epoch) return
                call = null
                if (index + 1 < endpoints.size) request(center, now, token, index + 1, done)
                else { lastError = "Servicio de ubicaciones OSM no disponible"; done(snapshot, lastError) }
            }
        }
        current.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled()) failed() }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (!it.isSuccessful) throw IOException("Signal service unavailable")
                    parse(it.body?.string().orEmpty(), center, now)
                } }.getOrNull()
                if (result == null) { failed(); return }
                synchronized(this@SignalRepository) {
                    if (token != epoch) return
                    this@SignalRepository.call = null; snapshot = result
                    runCatching { cache?.writeText(encode(result)) }
                    done(result, null)
                }
            }
        })
    }
    @Synchronized fun close() { epoch++; call?.cancel(); call = null }
    companion object {
        fun valid(point: RouteGeometry.Point) = point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0
        fun parse(json: String, center: RouteGeometry.Point, now: Long): Snapshot {
            val root = JSONObject(json)
            require(!root.has("remark")) { "Partial Overpass replies cannot replace known signal locations" }
            val rows = root.getJSONArray("elements")
            val points = ArrayList<Signal>()
            val ids = HashSet<Long>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val tags = row.optJSONObject("tags") ?: continue
                val pedestrian = tags.optString("highway") == "crossing" &&
                    (tags.optString("crossing") == "traffic_signals" || tags.optString("crossing:signals") == "yes")
                if (row.optString("type") != "node" || (!pedestrian && tags.optString("highway") != "traffic_signals")) continue
                val point = RouteGeometry.Point(row.optDouble("lat", Double.NaN), row.optDouble("lon", Double.NaN))
                val id = row.optLong("id", 0)
                if (id <= 0 || !ids.add(id) || !valid(point) || RouteGeometry.distance(center, point) > 2700) continue
                points.add(Signal(id, point, pedestrian))
            }
            return Snapshot(center, points.sortedBy { RouteGeometry.distance(center, it.point) }.take(500),
                root.optJSONObject("osm3s")?.optString("timestamp_osm_base").orEmpty(), now)
        }
        fun next(signals: List<Signal>, here: RouteGeometry.Point, route: List<RouteGeometry.Point>, walking: Boolean): Pair<Signal, Double>? {
            val applicable = signals.filter { walking || !it.pedestrian }
            if (route.size < 2) return applicable.map { it to RouteGeometry.distance(here, it.point) }.filter { it.second < 1800 }.minByOrNull { it.second }
            val progress = RouteGeometry.project(route, here)?.along ?: return null
            return applicable.mapNotNull { signal ->
                val p = RouteGeometry.project(route, signal.point) ?: return@mapNotNull null
                val ahead = p.along - progress
                if (p.distance > 28 || ahead < 0 || ahead > 2500) null else signal to ahead
            }.minByOrNull { it.second }
        }
        fun encode(s: Snapshot): String = JSONObject().put("lat", s.center.lat).put("lon", s.center.lon)
            .put("loadedAt", s.loadedAt).put("osmTimestamp", s.osmTimestamp)
            .put("signals", JSONArray(s.signals.map { JSONObject().put("id", it.id).put("lat", it.point.lat).put("lon", it.point.lon).put("pedestrian", it.pedestrian) })).toString()
        fun decode(json: String): Snapshot {
            val root = JSONObject(json); val center = RouteGeometry.Point(root.getDouble("lat"), root.getDouble("lon")); require(valid(center))
            val points = root.getJSONArray("signals"); val signals = (0 until points.length()).map {
                val row = points.getJSONObject(it)
                Signal(row.getLong("id"), RouteGeometry.Point(row.getDouble("lat"), row.getDouble("lon")), row.optBoolean("pedestrian"))
            }.filter { valid(it.point) && it.id > 0 }.take(500)
            return Snapshot(center, signals, root.optString("osmTimestamp"), root.getLong("loadedAt"), cached = true)
        }
    }
}
