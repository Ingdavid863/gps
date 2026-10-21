package com.david.gps3dar

import com.david.gps3dar.RealisticMapActivity.GeoPoint
import com.david.gps3dar.RealisticMapActivity.NavStep
import com.david.gps3dar.RealisticMapActivity.RouteOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Full selected polyline and guidance, saved atomically without a network dependency. */
class NavigationSessionStore(private val file: File) {
    data class Session(val route: RouteOption, val destination: GeoPoint, val label: String, val view: Int)
    private val io = Executors.newSingleThreadExecutor()
    fun save(session: Session) { io.execute {
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.new")
            temporary.writeText(encode(session))
            check(temporary.renameTo(file))
        }
    } }
    fun load(done: (Session?) -> Unit) { io.execute { done(runCatching { decode(file.readText()) }.getOrNull()) } }
    fun clear() { io.execute { file.delete(); File(file.parentFile, "${file.name}.new").delete() } }
    fun close() { io.shutdown() }
    companion object {
        internal fun encode(session: Session): String {
            val route = session.route
            return JSONObject().put("version", 1).put("label", session.label).put("view", session.view)
                .put("destination", JSONArray(listOf(session.destination.lat, session.destination.lon)))
                .put("points", JSONArray(route.points.map { JSONArray(listOf(it.lat, it.lon)) }))
                .put("steps", JSONArray(route.steps.map { step -> JSONObject().put("lat", step.lat).put("lon", step.lon)
                    .put("instruction", step.instruction).put("icon", step.icon).put("index", step.routeIndex)
                    .put("maneuver", step.maneuver).apply { if (step.alongMeters.isFinite()) put("along", step.alongMeters) } }))
                .put("duration", route.durationSeconds).put("distance", route.distanceMeters)
                .put("trafficDelay", route.trafficDelaySeconds).put("noTrafficDuration", route.noTrafficDurationSeconds)
                .put("hasTolls", route.hasTolls).put("avoidsTolls", route.avoidsTolls).put("pedestrian", route.pedestrian)
                .put("tollSections", JSONArray(route.tollSections.map { JSONArray(listOf(it.first, it.last)) })).toString()
        }
        internal fun decode(body: String): Session {
            val json = JSONObject(body); require(json.getInt("version") == 1)
            fun point(lat: Double, lon: Double): GeoPoint {
                WalkingRoute.checkedPoint(lat, lon); return GeoPoint(lat, lon)
            }
            val coordinates = json.getJSONArray("points")
            val points = (0 until coordinates.length()).map { coordinates.getJSONArray(it).let { p -> point(p.getDouble(0), p.getDouble(1)) } }
            require(points.size >= 2)
            val instructions = json.getJSONArray("steps")
            val steps = (0 until instructions.length()).map { instructions.getJSONObject(it).let { step ->
                val p = point(step.getDouble("lat"), step.getDouble("lon"))
                NavStep(p.lat, p.lon, step.getString("instruction"), step.getString("icon"),
                    step.getInt("index").coerceIn(0, points.lastIndex), step.getString("maneuver"), step.optDouble("along", Double.NaN))
            } }
            val sections = json.getJSONArray("tollSections")
            val destination = json.getJSONArray("destination")
            val duration = json.getDouble("duration"); val distance = json.getDouble("distance")
            require(duration.isFinite() && duration >= 0 && distance.isFinite() && distance >= 0)
            return Session(RouteOption(points, steps, duration, distance, json.getDouble("trafficDelay"),
                json.getDouble("noTrafficDuration"), json.getBoolean("hasTolls"), json.getBoolean("avoidsTolls"),
                (0 until sections.length()).map { sections.getJSONArray(it).let { section ->
                    section.getInt(0).coerceIn(0, points.lastIndex)..section.getInt(1).coerceIn(0, points.lastIndex)
                } }, json.getBoolean("pedestrian")), point(destination.getDouble(0), destination.getDouble(1)),
                json.getString("label"), json.optInt("view", 1).coerceIn(0, 2))
        }
    }
}
