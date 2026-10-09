package com.david.gps3dar

import android.content.Context
import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

/** A separate persistent HTTP cache for public map assets. Routing/search/traffic bypass it. */
class MapResourceCache(directory: File, transport: OkHttpClient = OkHttpClient()) {
    constructor(context: Context) : this(File(context.filesDir, "map-buffer"))
    private val cache = Cache(directory, 250L * 1024 * 1024)
    private val client = transport.newBuilder().cache(cache)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val viewportWorker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val viewportGeneration = AtomicInteger()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Pair<ByteArray, String>?>>()
    private val visibleRequests = AtomicInteger()
    @Volatile private var online = true
    private val stale = CacheControl.Builder().onlyIfCached().maxStale(3650, TimeUnit.DAYS).build()
    private var key = ""
    fun configure(apiKey: String) { key = apiKey }
    fun setNetworkAvailable(available: Boolean) { online = available }
    private fun canonical(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        return if (u.port == 443 && u.host.endsWith("api.tomtom.com") && u.encodedPath.startsWith("/map/1/tile/"))
            u.newBuilder().host("a.api.tomtom.com").build().toString() else url
    }
    private fun allowed(url: String): Boolean = runCatching {
        val u = url.toHttpUrlOrNull() ?: return false
        val host = u.host
        u.scheme == "https" && ((host == "api.tomtom.com" || host in listOf("a.api.tomtom.com", "b.api.tomtom.com", "c.api.tomtom.com", "d.api.tomtom.com")) &&
            (u.encodedPath.startsWith("/map/1/tile/basic/") || u.encodedPath.startsWith("/style/1/")) ||
            host == "tiles.openfreemap.org" && listOf("/planet", "/fonts/", "/sprites/", "/natural_earth/").any { u.encodedPath.startsWith(it) })
    }.getOrDefault(false)
    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "GPS3D-AR-David/0.15").build()
    internal fun bytes(url: String, refresh: Boolean = false): Pair<ByteArray, String>? {
        if (!allowed(url)) return null
        val address = canonical(url)
        val request = request(address)
        // Reuse prepared resources immediately, including after their freshness expires.
        // A background route-buffer pass refreshes expired responses when online.
        if (!refresh || !online) client.newCall(request.newBuilder().cacheControl(stale).build()).execute().use { r ->
            if (r.isSuccessful) return r.body?.bytes()?.let { it to r.header("Content-Type", "application/octet-stream")!! }
        }
        if (!online) return null
        val promise = CompletableFuture<Pair<ByteArray, String>?>()
        val active = inFlight.putIfAbsent(address, promise)
        if (active != null) return runCatching { active.get(8, TimeUnit.SECONDS) }.getOrNull()
        val resource = runCatching {
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes()?.let { it to r.header("Content-Type", "application/octet-stream")!! } else null
            }
        }.getOrNull()
        promise.complete(resource)
        inFlight.remove(address, promise)
        return resource
    }
    fun intercept(url: String): WebResourceResponse? {
        if (!allowed(url)) return null
        visibleRequests.incrementAndGet()
        try {
            val resource = runCatching { bytes(url) }.getOrNull()
                ?: return WebResourceResponse("text/plain", "UTF-8", 503, "Map resource unavailable",
                    mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
            return WebResourceResponse(resource.second.substringBefore(';'), null, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(resource.first))
        } finally { visibleRequests.decrementAndGet() }
    }
    private fun tileUrl(tile: MapTilePlanner.Tile) =
        "https://a.api.tomtom.com/map/1/tile/basic/main/${tile.z}/${tile.x}/${tile.y}.pbf?key=$key"

    fun prepareViewport(west: Double, south: Double, east: Double, north: Double, zoom: Double) {
        if (key.isBlank() || !online) return
        val id = viewportGeneration.incrementAndGet()
        val tiles = MapTilePlanner.viewport(west, south, east, north, zoom)
        viewportWorker.execute {
            runCatching {
            for (tile in tiles) {
                if (viewportGeneration.get() != id || !online) return@execute
                // Visible resources always take priority over speculative downloads.
                while (visibleRequests.get() >= 4) {
                    Thread.sleep(40)
                    if (viewportGeneration.get() != id) return@execute
                }
                if (bytes(tileUrl(tile), refresh = true) == null) return@execute
            }
            }
        }
    }
    fun prepare(route: List<RouteGeometry.Point>, from: Int, done: (Int, Int) -> Unit) {
        if (key.isBlank() || route.size < 2) return
        val id = generation.incrementAndGet()
        val tiles = MapTilePlanner.plan(route, from)
        worker.execute {
            runCatching {
            var completed = 0
            for (tile in tiles) {
                if (generation.get() != id) return@execute
                if (!online) return@execute
                while (visibleRequests.get() >= 4) {
                    Thread.sleep(40)
                    if (generation.get() != id) return@execute
                }
                val ok = runCatching { bytes(tileUrl(tile), refresh = true)?.first?.isNotEmpty() == true }.getOrDefault(false)
                if (ok) completed++
                done(completed, tiles.size)
                // Bounded single-worker look-ahead, shared cache with the visible map.
                if (generation.get() != id) return@execute
                if (!ok) break
                Thread.sleep(80)
            }
            }
        }
    }
    fun cancel() { generation.incrementAndGet(); viewportGeneration.incrementAndGet() }
    fun close() { cancel(); worker.shutdownNow(); viewportWorker.shutdownNow(); client.dispatcher.cancelAll(); cache.close() }
}
