package com.david.gps3dar

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Booths are projected on the chosen route, not counted from nearby roads or toll sections. */
class TollRepository(private val client: OkHttpClient, catalogJson: String) {
    data class Booth(val id: Long, val name: String, val lat: Double, val lon: Double,
                     val alongMeters: Double, val carMxn: Double?, val effective: String? = null)
    data class Quote(val booths: List<Booth>, val hasTollSections: Boolean, val lookupSucceeded: Boolean) {
        // A known toll section with no booth records is unknown, never a free route.
        val coverageKnown get() = lookupSucceeded && (!hasTollSections || booths.isNotEmpty())
    }
    private data class Fare(val name: String, val cost: Double, val effective: String)
    private val fares = runCatching {
        val array = JSONObject(catalogJson).getJSONArray("fares")
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Fare(normalize(item.getString("name")), item.getDouble("carMxn"), item.getString("effective"))
        }.groupBy { it.name }
    }.getOrDefault(emptyMap())
    private val generation = AtomicInteger()
    private var call: Call? = null

    fun cancel() { generation.incrementAndGet(); call?.cancel(); call = null }

    fun load(route: List<RouteGeometry.Point>, hasTollSections: Boolean, completed: (Quote) -> Unit) {
        cancel()
        val id = generation.get()
        if (route.size < 2) { completed(Quote(emptyList(), hasTollSections, false)); return }
        if (!hasTollSections) { completed(Quote(emptyList(), false, true)); return }
        val corridor = RouteGeometry.simplify(route)
            .joinToString(",") { "${it.lat},${it.lon}" }
        val query = "[out:json][timeout:25];node[\"barrier\"=\"toll_booth\"](around:80,$corridor);out;"
        fetch(query, route, hasTollSections, id, 0, completed)
    }

    private fun fetch(query: String, route: List<RouteGeometry.Point>, hasTolls: Boolean, id: Int, retry: Int, completed: (Quote) -> Unit) {
        if (generation.get() != id) return
        val endpoints = listOf("https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter")
        val request = Request.Builder().url(endpoints[retry]).post(FormBody.Builder().add("data", query).build())
            .header("User-Agent", "GPS3D-AR-David/0.13").build()
        val requestCall = client.newCall(request)
        call = requestCall
        requestCall.enqueue(object : Callback {
            fun finish(body: String?) {
                if (requestCall.isCanceled() || generation.get() != id) return
                val parsed = runCatching { body?.let { parse(JSONObject(it), route) } }.getOrNull()
                if (parsed == null && retry == 0) fetch(query, route, hasTolls, id, 1, completed)
                else completed(Quote(parsed.orEmpty(), hasTolls, parsed != null))
            }
            override fun onFailure(call: Call, e: IOException) { finish(null) }
            override fun onResponse(call: Call, response: Response) {
                finish(response.use { if (it.isSuccessful) runCatching { it.body?.string() }.getOrNull() else null })
            }
        })
    }

    fun parse(root: JSONObject, route: List<RouteGeometry.Point>): List<Booth> {
        // Overpass can return HTTP 200 with a partial result and a timeout remark.
        if (root.optString("remark").isNotBlank()) error("Incomplete toll lookup")
        val array = root.getJSONArray("elements")
        val candidates = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val p = RouteGeometry.Point(item.optDouble("lat", Double.NaN), item.optDouble("lon", Double.NaN))
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return@mapNotNull null
            val projection = RouteGeometry.project(route, p) ?: return@mapNotNull null
            if (projection.distance > 38.0) return@mapNotNull null
            val tags = item.optJSONObject("tags") ?: JSONObject()
            val name = tags.optString("name").ifBlank { tags.optString("name:es") }.ifBlank { "Caseta sin nombre" }
            val match = matchFare(listOf(name, tags.optString("official_name"), tags.optString("alt_name")))
            Booth(item.optLong("id"), name, p.lat, p.lon, projection.along, match?.cost, match?.effective)
        }.sortedBy { it.alongMeters }
        val unique = mutableListOf<Booth>()
        for (booth in candidates) {
            // Separate lane barriers belonging to one plaza must count once.
            val existing = unique.indexOfFirst { abs(it.alongMeters - booth.alongMeters) < 120 &&
                RouteGeometry.distance(RouteGeometry.Point(it.lat, it.lon), RouteGeometry.Point(booth.lat, booth.lon)) < 120 }
            if (existing < 0) unique.add(booth)
            else if (unique[existing].carMxn == null && booth.carMxn != null) unique[existing] = booth
        }
        return unique.sortedBy { it.alongMeters }
    }

    private fun matchFare(names: List<String>): Fare? {
        for (name in names.flatMap { it.split(';') }) {
            val matches = fares[normalize(name)].orEmpty()
            // No guessed fares for different entry/exit pairs or duplicate plaza names.
            if (matches.isNotEmpty() && matches.map { it.cost }.distinct().size == 1) return matches.first()
        }
        return null
    }

    companion object {
        fun normalize(text: String): String = Normalizer.normalize(text.uppercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("^(?:PLAZA DE COBRO|CASETAS?(?: DE (?:PEAJE|COBRO))?|PEAJE)(?: DE)?\\s+"), "")
            .replace(Regex("\\s*\\((?:D|I[12]?)\\)\\s*"), " ")
            .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
