package com.david.gps3dar

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignalRepositoryTest {
    private val center = RouteGeometry.Point(19.43, -99.13)
    private val real = """{"osm3s":{"timestamp_osm_base":"2026-10-09T20:00:00Z"},"elements":[
        {"type":"node","id":10,"lat":19.431,"lon":-99.13,"tags":{"highway":"traffic_signals"}},
        {"type":"node","id":10,"lat":19.431,"lon":-99.13,"tags":{"highway":"traffic_signals"}},
        {"type":"node","id":11,"lat":19.432,"lon":-99.13,"tags":{"highway":"crossing","crossing":"traffic_signals"}},
        {"type":"node","id":12,"lat":19.433,"lon":-99.13,"tags":{"highway":"crossing","crossing:signals":"yes"}},
        {"type":"node","id":13,"lat":19.434,"lon":-99.13,"tags":{"highway":"crossing"}},
        {"type":"node","id":14,"lat":90.1,"lon":-99.13,"tags":{"highway":"traffic_signals"}},
        {"type":"node","id":15,"lat":20.5,"lon":-99.13,"tags":{"highway":"traffic_signals"}}]}"""
    @Test fun onlyMappedSignalLocationsAreAcceptedAndNoPhaseOrCountdownIsInvented() {
        val result = SignalRepository.parse(real, center, 1_000_000)
        assertEquals(listOf(10L,11L,12L), result.signals.map { it.id })
        assertEquals(2, result.signals.count { it.pedestrian })
        assertEquals("2026-10-09T20:00:00Z", result.osmTimestamp)
        val saved = SignalRepository.encode(result)
        assertFalse(saved.contains("phase")); assertFalse(saved.contains("remainingSeconds"))
        val restored = SignalRepository.decode(saved)
        assertEquals(result.signals, restored.signals); assertTrue(restored.cached)
    }
    @Test fun partialRepliesAreRejectedInsteadOfClaimingThereAreNoSignals() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalRepository.parse("""{"remark":"runtime error: Query timed out","elements":[]}""", center, 1000)
        }
        assertTrue(SignalRepository.parse("""{"elements":[]}""", center, 1000).signals.isEmpty())
    }
    @Test fun nearestAheadFollowsRouteAndIgnoresPassedOrParallelRoadSignalsAndPedestrianOnlySignalsForCars() {
        val route = listOf(center, RouteGeometry.Point(19.435, -99.13))
        val here = RouteGeometry.Point(19.432, -99.13)
        val signals = listOf(
            SignalRepository.Signal(1, RouteGeometry.Point(19.431, -99.13)),
            SignalRepository.Signal(2, RouteGeometry.Point(19.4324, -99.13), true),
            SignalRepository.Signal(3, RouteGeometry.Point(19.4323, -99.129)),
            SignalRepository.Signal(4, RouteGeometry.Point(19.433, -99.13)))
        assertEquals(4L, SignalRepository.next(signals, here, route, false)!!.first.id)
        assertEquals(2L, SignalRepository.next(signals, here, route, true)!!.first.id)
    }
    @Test fun realHttpQueryRetriesUnavailableEndpointAndUsesBoundedSignalTags() {
        MockWebServer().use { first -> MockWebServer().use { backup ->
            first.start(); backup.start(); first.enqueue(MockResponse().setResponseCode(503))
            backup.enqueue(MockResponse().setBody(real).setHeader("Content-Type", "application/json"))
            val service = SignalRepository(OkHttpClient(), endpoints = listOf(first.url("/api/interpreter").toString(), backup.url("/api/interpreter").toString()))
            val finished = CountDownLatch(1)
            service.load(center, 1_000_000) { result, error -> assertNull(error); assertEquals(3, result!!.signals.size); finished.countDown() }
            assertTrue(finished.await(8, TimeUnit.SECONDS))
            val request = backup.takeRequest(2, TimeUnit.SECONDS)!!
            val query = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertEquals("POST", request.method); assertTrue(query.contains("around:2500")); assertTrue(query.contains("highway\"=\"traffic_signals"))
            service.load(center, 1_001_000) { _, _ -> fail("A fresh stationary cache should avoid repeated requests") }
            assertEquals(1, backup.requestCount); service.close()
        } }
    }
    @Test fun liveMexicoSignalLocationsHaveDocumentedOsmNodesWhenLiveQaIsEnabled() {
        if (System.getenv("GPS3D_SIGNAL_LIVE_QA") != "1") return
        val regions = linkedMapOf("real-mexico-signals" to center,
            "route-area-north-mexico-valley" to RouteGeometry.Point(19.7356, -99.2060),
            "cuautitlan-izcalli-area" to RouteGeometry.Point(19.65, -99.21))
        val directory = java.io.File("build/signal-qa").apply { mkdirs() }
        var previousAttempt = 0L
        for ((name, point) in regions) {
            if (previousAttempt > 0) Thread.sleep((61_000 - (System.currentTimeMillis() - previousAttempt)).coerceAtLeast(0))
            previousAttempt = System.currentTimeMillis()
            val service = SignalRepository(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build())
            try {
                val finished = CountDownLatch(1)
                var snapshot: SignalRepository.Snapshot? = null
                var failure: String? = null
                val start = System.currentTimeMillis()
                service.load(point) { s, e -> snapshot = s; failure = e; finished.countDown() }
                assertTrue("Live lookup finishes for $name", finished.await(65, TimeUnit.SECONDS))
                println("Live signal lookup $name: nodes=${snapshot?.signals?.size}, error=$failure, reason=${service.failureReason}")
                assertNull("Live OSM endpoint must be reachable for $name: ${service.failureReason}", failure)
                assertNotNull(snapshot); assertTrue(snapshot!!.loadedAt >= start)
                if (name == "real-mexico-signals") assertTrue(snapshot!!.signals.isNotEmpty())
                // An empty local response documents missing map coverage; it does not invent traffic lights.
                java.io.File(directory, "$name.json").writeText(SignalRepository.encode(snapshot!!))
            } finally { service.close() }
        }
    }
}
