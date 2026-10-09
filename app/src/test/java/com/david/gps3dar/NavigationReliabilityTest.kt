package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NavigationReliabilityTest {
    @Test fun highwayWarningsAdaptToSpeedAndNeverSkipTheNowPhase() {
        val a = NavigationAnnouncements()
        assertEquals(0, a.pending("turn", 1300.0, 30.0))
        assertEquals(1, a.pending("turn", 1000.0, 30.0))
        a.accepted("turn", 1)
        assertEquals(0, a.pending("turn", 850.0, 30.0))
        assertEquals(2, a.pending("turn", 300.0, 30.0))
        a.accepted("turn", 2)
        assertEquals(3, a.pending("turn", 70.0, 30.0))
        // A missing GPS update can leap past the advance threshold.
        assertEquals(3, a.pending("next", 12.0, 8.0))
    }
    @Test fun repeatedStreetNamesAndFailedVoiceDoNotSuppressLaterInstructions() {
        val a = NavigationAnnouncements()
        a.accepted("route:1", 3)
        assertEquals(1, a.pending("route:2", 350.0, 4.0))
        a.accepted("route:2", 1)
        a.failed("route:2", 1)
        assertEquals(1, a.pending("route:2", 350.0, 4.0))
        a.reset()
        assertEquals(3, a.pending("route:1", 15.0, 0.0))
    }
    @Test fun darkModeNeedsSustainedLowLightAndIgnoresBriefHeadlights() {
        val a = AutoDarkMode()
        assertFalse(a.update(0, false, 10.0))
        assertFalse(a.update(11000, false, 10.0))
        assertTrue(a.update(12000, false, 10.0))
        assertTrue(a.update(14000, false, 150.0))
        assertTrue(a.update(18000, false, 10.0))
        assertTrue(a.update(20000, true, 1500.0))
        assertTrue(a.update(30000, false, 150.0))
        assertFalse(a.update(40000, false, 150.0))
    }
    @Test fun solarNightWorksOfflineUsingGpsCoordinates() {
        val noon = Instant.parse("2026-10-09T18:30:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-10-09T06:30:00Z").toEpochMilli()
        assertTrue(AutoDarkMode.solarElevation(noon, 19.43, -99.13) > 50)
        assertTrue(AutoDarkMode.solarElevation(midnight, 19.43, -99.13) < -50)
    }
    @Test fun bufferingIsBoundedAndMovesAheadWithRouteProgress() {
        val route = (0..300).map { RouteGeometry.Point(19.0 + it * .001, -99.0) }
        val before = MapTilePlanner.plan(route, 0)
        val after = MapTilePlanner.plan(route, 200)
        assertTrue(before.size <= 320)
        assertEquals(before.size, before.toSet().size)
        assertNotEquals(before.first(), after.first())
        assertTrue(before.any { it.z == 18 })
        assertTrue(MapTilePlanner.plan(emptyList(), 0).isEmpty())
    }
}
