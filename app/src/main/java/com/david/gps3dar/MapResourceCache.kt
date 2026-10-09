package com.david.gps3dar

import android.content.Context
import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A separate persistent HTTP cache for public map assets. Routing/search/traffic bypass it. */
class MapResourceCache(context: Context) {
    private val cache = Cache(File(context.filesDir, "map-buffer"), 250L * 1024 * 1024)
    private val client = OkHttpClient.Builder().cache(cache)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val stale = CacheControl.Builder().onlyIfCached().maxStale(3650, TimeUnit.DAYS).build()
    private var key = ""
    fun configure(apiKey: String) { key = apiKey }
    private fun allowed(url: String): Boolean = runCatching {
        val u = android.net.Uri.parse(url)
        val host = u.host.orEmpty()
        u.scheme == "https" && (host == "api.tomtom.com" || host in listOf("a.api.tomtom.com", "b.api.tomtom.com", "c.api.tomtom.com", "d.api.tomtom.com")) &&
            (u.path.orEmpty().startsWith("/map/1/tile/basic/") || u.path.orEmpty().startsWith("/style/1/"))
    }.getOrDefault(false)
    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "GPS3D-AR-David/0.14").build()
    private fun bytes(url: String): Pair<ByteArray, String>? {
        if (!allowed(url)) return null
        val request = request(url)
        // Reuse prepared resources immediately, including after their freshness expires.
        // A background route-buffer pass refreshes expired responses when online.
        client.newCall(request.newBuilder().cacheControl(stale).build()).execute().use { r ->
            if (r.isSuccessful) return r.body?.bytes()?.let { it to r.header("Content-Type", "application/octet-stream")!! }
        }
        return runCatching {
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes()?.let { it to r.header("Content-Type", "application/octet-stream")!! } else null
            }
        }.getOrNull()
    }
    fun intercept(url: String): WebResourceResponse? {
        if (!allowed(url)) return null
        val resource = runCatching { bytes(url) }.getOrNull() ?: return null
        return WebResourceResponse(resource.second.substringBefore(';'), null, 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(resource.first))
    }
    fun prepare(route: List<RouteGeometry.Point>, from: Int, done: (Int, Int) -> Unit) {
        if (key.isBlank() || route.size < 2) return
        val id = generation.incrementAndGet()
        val tiles = MapTilePlanner.plan(route, from)
        worker.execute {
            var completed = 0
            for (tile in tiles) {
                if (generation.get() != id) return@execute
                val url = "https://a.api.tomtom.com/map/1/tile/basic/main/${tile.z}/${tile.x}/${tile.y}.pbf?key=$key"
                val ok = runCatching {
                    client.newCall(request(url)).execute().use { r -> r.isSuccessful && (r.body?.bytes()?.isNotEmpty() == true) }
                }.getOrDefault(false)
                if (ok) completed++
                done(completed, tiles.size)
                // Bounded single-worker look-ahead, shared cache with the visible map.
                if (generation.get() != id) return@execute
                if (!ok) break
                Thread.sleep(80)
            }
        }
    }
    fun cancel() { generation.incrementAndGet() }
    fun close() { cancel(); worker.shutdownNow(); client.dispatcher.cancelAll(); cache.close() }
}
