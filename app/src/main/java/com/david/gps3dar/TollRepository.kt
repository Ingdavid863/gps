package com.david.gps3dar

import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.math.abs

/** Booths are projected on the chosen route, not counted from nearby roads or toll sections. */
class TollRepository(private val client: OkHttpClient, catalogJson: String) {
    data class Booth(val id: Long, val name: String, val lat: Double, val lon: Double,
                     val alongMeters: Double, val carMxn: Double?, val effective: String? = null,
                     val source: String? = null, val role: String? = null, val maxCarMxn: Double? = carMxn)
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
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var national = runCatching { TollCatalog(JSONObject(catalogJson)) }.getOrNull()
    private var call: Call? = null

    fun cancel() { generation.incrementAndGet(); call?.cancel(); call = null }
    fun close() { cancel(); worker.shutdownNow() }
    fun replaceCatalog(json: String) { national = TollCatalog(JSONObject(json)) }

    fun load(route: List<RouteGeometry.Point>, hasTollSections: Boolean, sections: List<IntRange> = emptyList(), entered: List<Long> = emptyList(), completed: (Quote) -> Unit) {
        cancel()
        val id = generation.get()
        if (route.size < 2) { completed(Quote(emptyList(), hasTollSections, false)); return }
        if (!hasTollSections) { completed(Quote(emptyList(), false, true)); return }
        worker.execute {
            val quote = national?.quote(route, hasTollSections, sections, entered) ?: Quote(emptyList(), hasTollSections, false)
            if (generation.get() == id) completed(quote)
        }
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
