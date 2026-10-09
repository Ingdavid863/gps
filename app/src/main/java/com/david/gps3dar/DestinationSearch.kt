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
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Combines address geocoding and POI search; a generation owns every response. */
class DestinationSearch(private val client: OkHttpClient) {
    data class Result(val lat: Double, val lon: Double, val title: String, val address: String) {
        val label: String get() = listOf(title, address).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }
    private val generation = AtomicInteger()
    private val calls = mutableListOf<Call>()
    private val fastClient = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private data class Cached(val at: Long, val results: List<Result>)
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 24
    }
    private fun cacheKey(query: String, here: SharedDestination.Point?): String =
        RecentDestinations.normalize(query) + (here?.let { String.format(Locale.ROOT, ":%.2f,%.2f", it.lat, it.lon) } ?: "")

    @Synchronized fun cached(query: String, here: SharedDestination.Point?): List<Result> =
        cache[cacheKey(query, here)]?.takeIf { System.currentTimeMillis() - it.at < 300000 }?.results.orEmpty()

    @Synchronized fun cancel() {
        generation.incrementAndGet()
        calls.forEach { it.cancel() }
        calls.clear()
    }

    fun search(query: String, key: String, here: SharedDestination.Point?, explicit: Boolean,
               completed: (List<Result>, String?) -> Unit) {
        cancel()
        val id = generation.get()
        val existing = cached(query, here)
        if (existing.isNotEmpty()) { completed(existing, null); return }
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
                if (parsed == null) failures.incrementAndGet()
                synchronized(results) {
                    results[index] = parsed ?: emptyList()
                    val finished = remaining.decrementAndGet() == 0
                    if (generation.get() != id) return@synchronized
                    // Address results go first when the user supplies a street number.
                    val addressQuery = Regex("\\d").containsMatchIn(query)
                    val order = if (addressQuery) listOf(1, 0) else listOf(0, 1)
                    val combined = order.flatMap { results[it].orEmpty() }.distinctBy {
                        "${(it.lat * 100000).toLong()},${(it.lon * 100000).toLong()}:${it.title.lowercase()}"
                    }.take(10)
                    if (finished && combined.isNotEmpty()) synchronized(this) {
                        cache[cacheKey(query, here)] = Cached(System.currentTimeMillis(), combined)
                    }
                    if (finished && combined.isEmpty() && explicit) searchFallback(query, id, completed)
                    else if ((!explicit && combined.isNotEmpty()) || finished) {
                        completed(combined, if (failures.get() == 2) "Sin conexión con el buscador. Puedes elegir un destino reciente." else null)
                    }
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
        val call = fastClient.newCall(request)
        synchronized(this) { if (generation.get() != id) return; calls.add(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@DestinationSearch) { calls.remove(call) }
                if (!call.isCanceled() && generation.get() == id) completed(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) runCatching { it.body?.string() }.getOrNull() else null }
                synchronized(this@DestinationSearch) { calls.remove(call) }
                if (!call.isCanceled() && generation.get() == id) completed(body)
            }
        })
    }
}
