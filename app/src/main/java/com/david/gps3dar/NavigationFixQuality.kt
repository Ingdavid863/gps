package com.david.gps3dar

import kotlin.math.max

/** Reject isolated degradation without widening the road-snapping radius or hiding departures. */
object NavigationFixQuality {
    data class Fix(val point: RouteGeometry.Point, val accuracy: Double, val speed: Double, val nanos: Long)
    fun accept(previous: Fix?, next: Fix, nowNanos: Long): Boolean {
        if (!next.point.lat.isFinite() || !next.point.lon.isFinite() ||
            next.point.lat !in -90.0..90.0 || next.point.lon !in -180.0..180.0 ||
            !next.accuracy.isFinite() || next.accuracy !in 0.1..65.0 ||
            nowNanos - next.nanos !in 0L..3_000_000_000L) return false
        if (previous == null) return true
        if (next.nanos <= previous.nanos) return false
        val seconds = (next.nanos - previous.nanos) / 1_000_000_000.0
        val distance = RouteGeometry.distance(previous.point, next.point)
        if (distance / max(.25, seconds) > 85.0) return false
        // Do not let a transient coarse/network solution displace a recent precise fix.
        // After 2.5 seconds it may replace it, so degraded reception cannot freeze the car.
        val degraded = previous.accuracy <= 15 && next.accuracy > max(25.0, previous.accuracy * 2)
        val travel = max(previous.speed, next.speed).coerceAtLeast(0.0) * seconds
        if (seconds < 2.5 && degraded && distance > max(12.0, travel + previous.accuracy * 2 + 4)) return false
        return true
    }
    fun reliableCourse(hasBearing: Boolean, speed: Double, accuracyDegrees: Double?) =
        hasBearing && speed >= 2.5 && (accuracyDegrees == null || accuracyDegrees.isFinite() && accuracyDegrees <= 35)
}
