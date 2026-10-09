package com.david.gps3dar

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class DestinationSearchTest {
    private fun response(name: String) = """{"results":[{"position":{"lat":19.43,"lon":-99.13},"poi":{"name":"$name"},"address":{"freeformAddress":"$name, Ciudad de México"}}]}"""
    private fun searchClient(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        chain.proceed(chain.request().newBuilder().url(server.url(url.encodedPath + "?" + url.encodedQuery)).build())
    }.build()

    @Test fun firstAutocompleteResultDoesNotWaitForTheSlowerGeocoderAndRepeatUsesCache() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(response("Lago de Guadalupe"))
                .apply { if (request.path!!.contains("geocode")) setBodyDelay(1500, TimeUnit.MILLISECONDS) }
        }
        server.start()
        val service = DestinationSearch(searchClient(server))
        val first = CountDownLatch(1); val finished = CountDownLatch(2)
        service.search("Lago de Guadalupe", "test", null, false) { results, _ ->
            if (results.isNotEmpty()) first.countDown()
            finished.countDown()
        }
        assertTrue("fast provider appears before slower geocoder", first.await(1, TimeUnit.SECONDS))
        assertTrue(finished.await(4, TimeUnit.SECONDS))
        val requests = server.requestCount
        val repeat = CountDownLatch(1)
        service.search("Lago de Guadalupe", "test", null, false) { results, _ -> assertFalse(results.isEmpty()); repeat.countDown() }
        assertTrue("repeated query returns immediately", repeat.await(100, TimeUnit.MILLISECONDS))
        assertEquals(requests, server.requestCount)
        service.cancel(); server.shutdown()
    }

    @Test fun newQueryCancelsOldResultsAndExplicitQueryWaitsForCorrectCombinedChoices() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(response(if (request.path!!.contains("old")) "Old" else "New"))
                .setBodyDelay(if (request.path!!.contains("old")) 1300 else 0, TimeUnit.MILLISECONDS)
        }
        server.start()
        val service = DestinationSearch(searchClient(server)); val names = CopyOnWriteArrayList<String>(); val done = CountDownLatch(1)
        service.search("old", "test", null, false) { results, _ -> names.addAll(results.map { it.title }) }
        Thread.sleep(100)
        service.search("new", "test", null, true) { results, _ -> names.addAll(results.map { it.title }); done.countDown() }
        assertTrue(done.await(4, TimeUnit.SECONDS))
        Thread.sleep(1400)
        assertEquals(listOf("New"), names.toList())
        service.cancel(); server.shutdown()
    }

    @Test fun selectedDestinationsPersistDeduplicateReorderAndSearchWithoutAccents() {
        var stored = "[]"
        fun history() = RecentDestinations({ stored }, { stored = it })
        val a = DestinationSearch.Result(19.43, -99.13, "Lago de Guadalupe", "Cuautitlán Izcalli")
        val b = DestinationSearch.Result(19.44, -99.14, "Museo", "Ciudad de México")
        history().select(a); history().select(b); history().select(a)
        assertEquals(listOf(a, b), history().list())
        assertEquals(listOf(a), history().list("cuautitlan lago"))
        repeat(30) { history().select(b.copy(lat = 19.0 + it * .01)) }
        assertEquals(24, history().list().size)
        stored = "broken JSON"
        assertTrue(history().list().isEmpty())
        history().select(a.copy(lat = Double.NaN))
        assertEquals("broken JSON", stored)
    }
}
