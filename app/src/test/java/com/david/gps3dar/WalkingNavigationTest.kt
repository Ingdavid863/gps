package com.david.gps3dar

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class WalkingNavigationTest {
    private val origin = RouteGeometry.Point(19.72, -99.22)
    private fun move(e: Double, s: Double) = ArRouteGeometry.fromOffset(origin, e, s)
    private val response = """{"routes":[{"summary":{"travelTimeInSeconds":40},"legs":[{"points":[
        {"latitude":19.72,"longitude":-99.22},{"latitude":19.7201,"longitude":-99.22},
        {"latitude":19.7201,"longitude":-99.2199}]}],"sections":[{"sectionType":"TRAVEL_MODE","travelMode":"pedestrian"}],
        "guidance":{"instructions":[{"point":{"latitude":19.7201,"longitude":-99.22},"message":"Gira a la derecha","maneuver":"TURN_RIGHT"}]}}]}"""

    @Test fun cameraRequestUsesWalkingModeAndParsesActualPolylineAndInstructions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val destination = move(10.0, -10.0)
            val url = WalkingRoute.requestUrl("test-key", origin, destination, server.url("/"))
            val route = OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use {
                WalkingRoute.parseResponse(it.body!!.string(), destination)
            }
            val request = server.takeRequest().requestUrl!!
            assertEquals("pedestrian", request.queryParameter("travelMode"))
            assertEquals("false", request.queryParameter("traffic"))
            assertNull(request.queryParameter("vehicleHeading"))
            assertEquals(3, route.points.size)
            assertEquals("Gira a la derecha", route.steps.single().message)
            assertEquals(route, WalkingRoute.decode(route.encode()))
            assertTrue(route.reusableFor(destination, origin, route.createdAt + 60000))
            assertFalse(route.reusableFor(move(500.0, 0.0), origin, route.createdAt + 60000))
            assertFalse(route.reusableFor(destination, move(100.0, 0.0), route.createdAt + 60000))
        }
    }
    @Test fun doesNotRenderAVehicleOnlySectionOrInvalidCoordinatesAsAWalkingPath() {
        assertThrows(IllegalArgumentException::class.java) {
            WalkingRoute.parseResponse(response.replace("\"travelMode\":\"pedestrian\"", "\"travelMode\":\"other\""), origin)
        }
        assertThrows(IllegalArgumentException::class.java) { WalkingRoute.checkedPoint(Double.NaN, -99.0) }
        assertThrows(IllegalArgumentException::class.java) { WalkingRoute.checkedPoint(19.0, 999.0) }
    }
    @Test fun floorPolylineKeepsACornerAndBoundsAnchorCountAndSegmentLength() {
        val corner = move(0.0, -18.0)
        val route = listOf(origin, corner, move(25.0, -18.0), move(2500.0, -18.0))
        val geometry = ArRouteGeometry(route)
        val samples = geometry.window(1.2)
        assertTrue(samples.any { RouteGeometry.distance(it.point, corner) < .01 })
        assertTrue(samples.size <= 22)
        assertTrue(samples.zipWithNext().all { (a, b) -> RouteGeometry.distance(a.point, b.point) <= 4.01 })
        assertTrue(samples.all { RouteGeometry.project(route, it.point)!!.distance < .01 })
        assertEquals(geometry.window(1.1).map { it.id }, geometry.window(1.2).map { it.id })
        assertTrue(geometry.window(300.0).first().along >= 296.0)
        assertFalse(geometry.window(300.0).map { it.id }.intersect(samples.map { it.id }.toSet()).isNotEmpty())
    }
    @Test fun eastUpSouthCoordinatesAndManualAlignmentDoNotMirrorTheStreet() {
        val east = ArRouteGeometry.offset(origin, move(10.0, 0.0))
        val north = ArRouteGeometry.offset(origin, move(0.0, -10.0))
        assertEquals(10.0, east.east, .001); assertEquals(-10.0, north.south, .001)
        for (heading in listOf(0.0, 90.0, 180.0, -90.0)) {
            val yaw = ArRouteGeometry.alignmentYaw(heading, 1.0, 0.0)
            val h = Math.toRadians(heading)
            val x = sin(h); val z = -cos(h)
            assertEquals(1.0, x * cos(yaw) + z * sin(yaw), .0001)
            assertEquals(0.0, z * cos(yaw) - x * sin(yaw), .0001)
        }
        assertTrue(ArRouteGeometry.automaticAccuracy(3.8, 6.0))
        assertFalse(ArRouteGeometry.automaticAccuracy(3.8, 50.0))
        assertFalse(ArRouteGeometry.automaticAccuracy(20.0, 3.0))
        assertFalse(ArRouteGeometry.automaticAccuracy(Double.NaN, 0.0))
    }
    @Test fun realProviderOffersPedestrianPathsInMexico() {
        val key = System.getenv("TOMTOM_API_KEY")?.takeIf { it.isNotBlank() } ?: return
        val to = RouteGeometry.Point(19.4354, -99.1323)
        val from = RouteGeometry.Point(19.4326, -99.1332)
        val route = OkHttpClient().newCall(Request.Builder().url(WalkingRoute.requestUrl(key, from, to)).build())
            .execute().use { r ->
                assertTrue("Live pedestrian service HTTP ${r.code}", r.isSuccessful)
                WalkingRoute.parseResponse(r.body!!.string(), to)
            }
        assertTrue(route.points.size > 2)
        assertTrue(ArRouteGeometry(route.points).length in 50.0..5000.0)
        assertTrue(route.steps.isNotEmpty())
        File("build/ar-qa").mkdirs()
        File("build/ar-qa/walking-route-live.json").writeText(route.encode())
        println("LIVE_WALKING_ROUTE points=${route.points.size} instructions=${route.steps.size} seconds=${route.durationSeconds}")
    }
}
