package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test

class NavigationFixQualityTest {
    private fun fix(x: Double, accuracy: Double = 6.0, speed: Double = 1.4, nanos: Long = 1_000_000_000) =
        NavigationFixQuality.Fix(RouteGeometry.Point(23.1, -110.0 + x / (111320 * kotlin.math.cos(Math.toRadians(23.1)))), accuracy, speed, nanos)
    @Test fun isolatedCoarseFixCannotMoveASlowVehicleToTheSideOfItsRoad() {
        assertFalse(NavigationFixQuality.accept(fix(0.0), fix(28.0,35.0,nanos=2_000_000_000),2_000_000_000))
        assertTrue(NavigationFixQuality.accept(fix(0.0), fix(8.0,35.0,nanos=2_000_000_000),2_000_000_000))
        assertTrue("A precise real departure must not be hidden",NavigationFixQuality.accept(fix(0.0),fix(28.0,6.0,nanos=2_000_000_000),2_000_000_000))
        assertTrue("Poor reception must eventually update the vehicle",NavigationFixQuality.accept(fix(0.0),fix(28.0,35.0,nanos=4_000_000_000),4_000_000_000))
    }
    @Test fun drivingProgressAndChronologicalFreshFixesRemainValid() {
        assertTrue(NavigationFixQuality.accept(fix(0.0,speed=26.0),fix(26.0,30.0,26.0,2_000_000_000),2_000_000_000))
        assertFalse(NavigationFixQuality.accept(null,fix(0.0),5_000_000_000))
        assertFalse(NavigationFixQuality.accept(fix(0.0),fix(1.0),1_000_000_000))
        assertFalse(NavigationFixQuality.accept(fix(0.0),fix(180.0,nanos=2_000_000_000),2_000_000_000))
        assertFalse(NavigationFixQuality.accept(null,fix(0.0,0.0),1_000_000_000))
    }
    @Test fun stationaryAndUncertainCourseCannotRotateTheDrivingCamera() {
        assertFalse(NavigationFixQuality.reliableCourse(true,1.4,5.0))
        assertFalse(NavigationFixQuality.reliableCourse(true,26.0,80.0))
        assertFalse(NavigationFixQuality.reliableCourse(false,26.0,null))
        assertTrue(NavigationFixQuality.reliableCourse(true,26.0,10.0))
        assertTrue(NavigationFixQuality.reliableCourse(true,26.0,null))
    }
}
