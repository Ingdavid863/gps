package com.david.gps3dar

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Camera navigation always uses a pedestrian route, never the driving polyline. */
data class WalkingRoute(
    val destination: RouteGeometry.Point,
    val points: List<RouteGeometry.Point>,
    val steps: List<Step>,
    val durationSeconds: Double,
    val createdAt: Long = System.currentTimeMillis()
) {
    data class Step(val point: RouteGeometry.Point, val message: String, val maneuver: String)

    fun encode(): String = JSONObject()
        .put("destination", JSONArray(listOf(destination.lat, destination.lon)))
        .put("points", JSONArray(points.map { JSONArray(listOf(it.lat, it.lon)) }))
        .put("steps", JSONArray(steps.map { JSONObject().put("lat", it.point.lat)
            .put("lon", it.point.lon).put("message", it.message).put("maneuver", it.maneuver) }))
        .put("duration", durationSeconds).put("createdAt", createdAt).toString()

    fun reusableFor(target: RouteGeometry.Point, here: RouteGeometry.Point, now: Long): Boolean =
        now >= createdAt && now - createdAt < 24 * 60 * 60 * 1000L &&
            RouteGeometry.distance(target, destination) < 3.0 &&
            (RouteGeometry.project(points, here)?.distance ?: Double.MAX_VALUE) < 25.0

    companion object {
        const val EXTRA_LAT = "walking_destination_lat"
        const val EXTRA_LON = "walking_destination_lon"
        const val EXTRA_LABEL = "walking_destination_label"

        fun requestUrl(key: String, from: RouteGeometry.Point, to: RouteGeometry.Point,
                       host: HttpUrl? = null): HttpUrl {
            val b = host?.newBuilder() ?: HttpUrl.Builder().scheme("https").host("api.tomtom.com")
            return b.addPathSegment("routing").addPathSegment("1").addPathSegment("calculateRoute")
                .addPathSegment("${from.lat},${from.lon}:${to.lat},${to.lon}").addPathSegment("json")
                .addQueryParameter("key", key).addQueryParameter("travelMode", "pedestrian")
                .addQueryParameter("traffic", "false").addQueryParameter("routeType", "shortest")
                .addQueryParameter("routeRepresentation", "polyline").addQueryParameter("maxAlternatives", "0")
                .addQueryParameter("instructionsType", "text").addQueryParameter("language", "es-ES")
                .addQueryParameter("sectionType", "travelMode").build()
        }

        fun parseResponse(body: String, target: RouteGeometry.Point): WalkingRoute {
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val sections = route.optJSONArray("sections") ?: JSONArray()
            require((0 until sections.length()).none {
                val section = sections.getJSONObject(it)
                section.optString("sectionType") == "TRAVEL_MODE" &&
                    section.optString("travelMode", "pedestrian") != "pedestrian"
            }) { "Hay tramos sin ruta peatonal disponible" }
            val points = mutableListOf<RouteGeometry.Point>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val list = legs.getJSONObject(i).getJSONArray("points")
                for (j in 0 until list.length()) {
                    val p = list.getJSONObject(j)
                    val point = checkedPoint(p.getDouble("latitude"), p.getDouble("longitude"))
                    if (points.lastOrNull()?.let { RouteGeometry.distance(it, point) < .05 } != true) points += point
                }
            }
            require(points.size >= 2) { "La ruta para caminar está vacía" }
            val instructions = route.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
            val steps = (0 until instructions.length()).mapNotNull { i ->
                val item = instructions.getJSONObject(i)
                val p = item.optJSONObject("point") ?: return@mapNotNull null
                Step(checkedPoint(p.getDouble("latitude"), p.getDouble("longitude")),
                    item.optString("message").ifBlank { "Sigue la ruta a pie" }, item.optString("maneuver"))
            }
            return WalkingRoute(target, points, steps, route.getJSONObject("summary").getDouble("travelTimeInSeconds"))
        }

        fun decode(body: String): WalkingRoute {
            val r = JSONObject(body); val d = r.getJSONArray("destination")
            val p = r.getJSONArray("points"); val s = r.getJSONArray("steps")
            val points = (0 until p.length()).map { i -> p.getJSONArray(i).let { checkedPoint(it.getDouble(0), it.getDouble(1)) } }
            require(points.size >= 2)
            return WalkingRoute(checkedPoint(d.getDouble(0), d.getDouble(1)), points,
                (0 until s.length()).map { i -> s.getJSONObject(i).let {
                    Step(checkedPoint(it.getDouble("lat"), it.getDouble("lon")), it.getString("message"), it.getString("maneuver"))
                } }, r.getDouble("duration"), r.getLong("createdAt"))
        }

        fun checkedPoint(lat: Double, lon: Double): RouteGeometry.Point {
            require(lat.isFinite() && lon.isFinite() && lat in -89.9..89.9 && lon in -180.0..180.0)
            return RouteGeometry.Point(lat, lon)
        }
    }
}
