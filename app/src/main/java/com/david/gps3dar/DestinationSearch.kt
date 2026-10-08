package com.david.gps3dar

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Combines address geocoding and POI search; a generation owns every response. */
class DestinationSearch(private val client: OkHttpClient) {
    data class Result(val lat: Double, val lon: Double, val title: String, val address: String) {
        val label: String get() = listOf(title, address).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }
    private val generation = AtomicInteger()
    private val calls = mutableListOf<Call>()

    @Synchronized fun cancel() {
        generation.incrementAndGet()
        calls.forEach { it.cancel() }
        calls.clear()
    }

    fun search(query: String, key: String, here: SharedDestination.Point?, explicit: Boolean,
               completed: (List<Result>, String?) -> Unit) {
        cancel()
        val id = generation.get()
        if (key.isBlank()) {
            if (!explicit) { completed(emptyList(), "Escribe la dirección y toca Ir"); return }
            searchFallback(query, id, completed)
            return
        }
        val results = arrayOfNulls<List<Result>>(2)
        val remaining = AtomicInteger(2)
        val failures = AtomicInteger()
        listOf("search", "geocode").forEachIndexed { index, endpoint ->
            val url = HttpUrl.Builder().scheme("https").host("api.tomtom.com")
                .addPathSegment("search").addPathSegment("2").addPathSegment(endpoint)
                .addPathSegment(query + ".json").addQueryParameter("key", key)
                .addQueryParameter("limit", "8").addQueryParameter("countrySet", "MX")
                .addQueryParameter("language", "es-ES")
            if (endpoint == "search") {
                url.addQueryParameter("typeahead", (!explicit).toString()).addQueryParameter("maxFuzzyLevel", "4")
                here?.let { url.addQueryParameter("lat", it.lat.toString()).addQueryParameter("lon", it.lon.toString()) }
            }
            enqueue(Request.Builder().url(url.build()).header("User-Agent", "GPS3D-AR-David/0.13").build(), id) { body ->
                val parsed = runCatching { body?.let { parseTomTom(JSONObject(it)) } }.getOrNull()
                synchronized(results) { results[index] = parsed ?: emptyList() }
                if (parsed == null) failures.incrementAndGet()
                if (remaining.decrementAndGet() == 0 && generation.get() == id) {
                    // Address results go first when the user supplies a street number.
                    val addressQuery = Regex("\\d").containsMatchIn(query)
                    val order = if (addressQuery) listOf(1, 0) else listOf(0, 1)
                    val combined = order.flatMap { results[it].orEmpty() }.distinctBy {
                        "${(it.lat * 100000).toLong()},${(it.lon * 100000).toLong()}:${it.title.lowercase()}"
                    }.take(10)
                    if (combined.isEmpty() && explicit) searchFallback(query, id, completed)
                    else completed(combined, if (failures.get() == 2) "No se pudo consultar el buscador" else null)
                }
            }
        }
    }

    private fun parseTomTom(root: JSONObject): List<Result> {
        val array = root.optJSONArray("results") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val p = item.optJSONObject("position") ?: return@mapNotNull null
            val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val a = item.optJSONObject("address")
            val address = a?.optString("freeformAddress").orEmpty()
            val title = item.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: address.substringBefore(',').ifBlank { "Dirección" }
            Result(lat, lon, title, address)
        }
    }

    // Nominatim is used only for an explicit submitted query, never for autocomplete.
    private var lastFallbackAt = 0L
    private fun searchFallback(query: String, id: Int, completed: (List<Result>, String?) -> Unit) {
        synchronized(this) {
            if (generation.get() != id) return
            val now = System.currentTimeMillis()
            if (now - lastFallbackAt < 1100L) { completed(emptyList(), "Espera un segundo y vuelve a buscar"); return }
            lastFallbackAt = now
        }
        val url = HttpUrl.Builder().scheme("https").host("nominatim.openstreetmap.org")
            .addPathSegment("search").addQueryParameter("format", "jsonv2").addQueryParameter("q", query)
            .addQueryParameter("countrycodes", "mx").addQueryParameter("limit", "8")
            .addQueryParameter("accept-language", "es").build()
        enqueue(Request.Builder().url(url).header("User-Agent", "GPS3D-AR-David/0.13 (Android navigation)").build(), id) { body ->
            val parsed = runCatching {
                val array = JSONArray(body ?: error("network"))
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val p = SharedDestination.coordinates(item.optString("lat") + "," + item.optString("lon")) ?: return@mapNotNull null
                    val name = item.optString("display_name")
                    Result(p.lat, p.lon, name.substringBefore(','), name.substringAfter(',', "").trim())
                }
            }.getOrNull()
            if (generation.get() == id) completed(parsed.orEmpty(), if (parsed == null) "No se pudo consultar el buscador" else null)
        }
    }

    private fun enqueue(request: Request, id: Int, completed: (String?) -> Unit) {
        val call = client.newCall(request)
        synchronized(this) { if (generation.get() != id) return; calls.add(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled() && generation.get() == id) completed(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) runCatching { it.body?.string() }.getOrNull() else null }
                if (!call.isCanceled() && generation.get() == id) completed(body)
            }
        })
    }
}
