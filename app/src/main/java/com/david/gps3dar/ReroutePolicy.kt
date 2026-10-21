package com.david.gps3dar

import kotlin.math.max
import kotlin.math.min

/** Short confirmation on independent, accurate GNSS fixes; never reroute from animation predictions. */
class ReroutePolicy {
    private var firstOffAt = -1L
    private var previousFix = -1L
    private var samples = 0
    private var retryAfter = 0L
    fun reset() { firstOffAt = -1L; previousFix = -1L; samples = 0; retryAfter = 0L }
    fun failed(now: Long) { retryAfter = now + 2_000 }
    fun offRoute(distance: Double, accuracy: Double, speed: Double, headingDelta: Double?): Boolean {
        if (!accuracy.isFinite() || accuracy !in 0.1..35.0 || !distance.isFinite()) return false
        val threshold = max(16.0, min(35.0, accuracy * 1.6))
        return distance > threshold || (speed > 3.0 && headingDelta != null &&
            (distance > max(10.0, accuracy * 1.2) && headingDelta > 55.0 || headingDelta > 135.0))
    }
    fun shouldRequest(now: Long, fixAt: Long, distance: Double, accuracy: Double, speed: Double,
                      headingDelta: Double?, resumePending: Boolean = false): Boolean {
        if (!offRoute(distance, accuracy, speed, headingDelta)) {
            firstOffAt = -1; samples = 0; previousFix = fixAt; return false
        }
        if (resumePending && now >= retryAfter) return true
        if (fixAt == previousFix) return false
        previousFix = fixAt
        if (firstOffAt < 0 || now - firstOffAt > 10_000) { firstOffAt = now; samples = 0 }
        samples++
        val confirmation = when {
            distance > max(35.0, accuracy * 2.0) -> 300L
            speed > 3.0 && headingDelta != null && headingDelta > 55.0 -> 450L
            speed >= 2.0 -> 650L
            else -> 1_000L
        }
        return samples >= 2 && now - firstOffAt >= confirmation && now >= retryAfter
    }
}
