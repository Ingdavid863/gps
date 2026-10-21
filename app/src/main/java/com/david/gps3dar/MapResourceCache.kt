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
import java.security.MessageDigest

/** A separate persistent HTTP cache for public map assets. Routing/search/traffic bypass it. */
class MapResourceCache(directory: File, transport: OkHttpClient = OkHttpClient()) {
    constructor(context: Context) : this(File(context.filesDir, "map-buffer"))
    private val cache = Cache(directory, 250L * 1024 * 1024)
    private val client = transport.newBuilder().cache(cache)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).build()
    private val viewportWorker = Executors.newSingleThreadExecutor()
    private val viewportGeneration = AtomicInteger()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Pair<ByteArray, String>?>>()
    private val visibleRequests = AtomicInteger()
    @Volatile private var online = true
    private val routePack = OfflineRoutePack(File(directory.parentFile, "saved-route-map")) { url ->
        while (visibleRequests.get() >= 4 && online && !Thread.currentThread().isInterrupted) Thread.sleep(40)
        bytes(url)
    }
    private val stale = CacheControl.Builder().onlyIfCached().maxStale(3650, TimeUnit.DAYS).build()
    private var key = ""
    fun configure(apiKey: String) { key = apiKey }
    fun setNetworkAvailable(available: Boolean) { online = available; routePack.setOnline(available) }
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
        routePack.read(address)?.let { return it }
        val request = request(address)
        // Reuse prepared resources immediately, including after their freshness expires.
        // A background route-buffer pass refreshes expired responses when online.
        if (!refresh || !online) client.newCall(request.newBuilder().cacheControl(stale).build()).execute().use { r ->
            if (r.isSuccessful) return r.body?.bytes()?.let {
                (it to r.header("Content-Type", "application/octet-stream")!!).also { resource -> routePack.protectStyle(address, resource) }
            }
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
        resource?.let { routePack.protectStyle(address, it) }
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
    internal fun styleResources(): List<String> {
        val style = "https://api.tomtom.com/style/1"
        val sprite = "$style/sprite/22.2.1-9/sprite"
        val sprites = listOf(".json", ".png", "@2x.json", "@2x.png").map { "$sprite$it?map=basic_main&key=$key" }
        val glyphs = listOf("NotoSans-MediumItalic", "Noto-Regular", "Noto-Bold", "Noto-Medium").flatMap { font ->
            listOf("0-255", "256-511", "8192-8447").map { "$style/glyph/22.2.1-9/$font/$it.pbf?key=$key" }
        }
        return sprites + glyphs
    }
    fun prepare(route: List<RouteGeometry.Point>, done: (OfflineRoutePack.Progress) -> Unit) {
        if (key.isBlank() || route.size < 2) { done(OfflineRoutePack.Progress(0, 0, "error")); return }
        val id = MessageDigest.getInstance("SHA-256").digest(route.joinToString(";") { "${it.lat},${it.lon}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        routePack.prepare(id, { styleResources() + MapTilePlanner.plan(route).map(::tileUrl) }, done)
    }
    fun retryPreparation() = routePack.retry()
    fun preparationState() = routePack.current()
    fun cancel() { routePack.cancel(); viewportGeneration.incrementAndGet() }
    fun close() { cancel(); routePack.close(); viewportWorker.shutdownNow(); client.dispatcher.cancelAll(); cache.close() }
}
