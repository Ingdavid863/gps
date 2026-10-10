package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ArContinuityTest {
    private val origin = RouteGeometry.Point(19.728, -99.204)
    private fun move(e: Double, s: Double) = ArRouteGeometry.fromOffset(origin, e, s)

    @Test fun visibleStreetIsRenewedDuringALongWalkWithoutTruncatingTheFarEnd() {
        val route = ArRouteGeometry(listOf(origin, move(0.0, -2200.0)))
        for (along in 0..1800 step 3) {
            val window = route.window(along.toDouble(), 160.0)
            assertTrue("Street window must extend 160 m ahead at " + along,
                window.last().along >= along + 160.0 - .001)
            assertTrue(window.first().along <= along.toDouble())
            assertTrue(window.zipWithNext().all { (a,b) -> b.along - a.along <= 4.001 })
            assertTrue(window.zipWithNext().all { (a,b) ->
                RouteGeometry.distance(route.block(a).point, b.point) <= 8.01 })
        }
        val initial = route.window(0.0, 160.0)
        val advanced = route.window(6.1, 160.0)
        assertTrue(initial.map { it.id }.intersect(advanced.map { it.id }.toSet()).size > 35)
    }

    @Test fun densePolylineDoesNotStopAfter22SamplesAndPreservesTheIntersection() {
        val points = (0..90).map { move(0.0, -it.toDouble()) } +
            (1..130).map { move(it.toDouble(), -90.0) }
        val route = ArRouteGeometry(points)
        val window = route.window(0.0, 160.0)
        assertTrue(window.size > 22)
        assertEquals(160.0, window.last().along, .05)
        assertTrue(window.any { RouteGeometry.distance(it.point, move(0.0, -90.0)) < .01 })
        assertTrue(window.all { RouteGeometry.project(points, it.point)!!.distance < .01 })
    }

    private val projection = floatArrayOf(
        1.5f,0f,0f,0f, 0f,2f,0f,0f, 0f,0f,-1f,-1f, 0f,0f,-.1f,0f)

    @Test fun destinationBearingFollowsCameraAndRemainsFindableWhenBehindOrOutsideTheView() {
        val ahead = ArSkyGeometry.project(floatArrayOf(0f,5f,-35f), projection)!!
        assertEquals(.5f, ahead.x, .001f)
        assertTrue(ahead.y in .28f.. .55f)
        val right = ArSkyGeometry.project(floatArrayOf(35f,5f,-3f), projection)!!
        assertEquals(.88f, right.x, .001f); assertTrue(right.arrow.contains("→"))
        val left = ArSkyGeometry.project(floatArrayOf(-35f,5f,-3f), projection)!!
        assertEquals(.12f, left.x, .001f); assertTrue(left.arrow.contains("←"))
        val behind = ArSkyGeometry.project(floatArrayOf(1f,5f,35f), projection)!!
        assertTrue(behind.arrow.contains("teléfono"))
        assertNull(ArSkyGeometry.project(floatArrayOf(Float.NaN,0f,-1f), projection))
    }

    @Test fun leftRightAndArrivalSymbolsAgreeWithTheActualManeuver() {
        assertEquals("↰", ArSkyGeometry.maneuverSymbol("TURN_LEFT"))
        assertEquals("↱", ArSkyGeometry.maneuverSymbol("SHARP_RIGHT"))
        assertEquals("⚑", ArSkyGeometry.maneuverSymbol("ARRIVE"))
        assertEquals("↑", ArSkyGeometry.maneuverSymbol("STRAIGHT"))
    }
}
