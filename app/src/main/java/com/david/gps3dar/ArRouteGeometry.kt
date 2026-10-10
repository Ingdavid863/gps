package com.david.gps3dar

import kotlin.math.*

/** Small, stable pieces preserve every bend; no straight shortcut through a building. */
class ArRouteGeometry(val points: List<RouteGeometry.Point>) {
    data class Sample(val point: RouteGeometry.Point, val along: Double) {
        val id: String get() = java.lang.Double.toHexString(along)
    }
    data class Offset(val east: Double, val south: Double)
    val cumulative = DoubleArray(points.size)
    val length: Double get() = cumulative.lastOrNull() ?: 0.0
    init {
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + RouteGeometry.distance(points[i - 1], points[i])
    }
    fun at(along: Double): RouteGeometry.Point {
        require(points.isNotEmpty())
        val d = along.coerceIn(0.0, length)
        val i = (cumulative.indexOfFirst { it >= d }.takeIf { it >= 0 } ?: points.lastIndex).coerceAtLeast(1)
        if (points.size == 1) return points[0]
        val f = ((d - cumulative[i - 1]) / (cumulative[i] - cumulative[i - 1]).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        return RouteGeometry.Point(points[i - 1].lat + f * (points[i].lat - points[i - 1].lat),
            points[i - 1].lon + f * (points[i].lon - points[i - 1].lon))
    }
    fun window(along: Double, ahead: Double = 32.0): List<Sample> {
        if (points.size < 2) return emptyList()
        val start = (floor((along - 4.0) / 4.0) * 4.0).coerceAtLeast(0.0)
        val end = (ceil(along / 4.0) * 4.0 + ahead).coerceAtMost(length)
        val distances = sortedSetOf(start, end)
        var d = start + 4.0
        while (d < end) { distances += d; d += 4.0 }
        cumulative.filter { it > start && it < end }.forEach { distances += it }
        return distances.map { Sample(at(it), it) }.filterIndexed { i, s ->
            i == 0 || s.along - distances.elementAt(i - 1) > .05
        }
    }
    /** One terrain lookup per 8 m block; unresolved neighbours cannot remove a segment. */
    fun block(sample: Sample): Sample {
        val along = (floor(sample.along / 8.0) * 8.0 + 4.0).coerceAtMost(length)
        return Sample(at(along), along)
    }
    companion object {
        fun offset(origin: RouteGeometry.Point, target: RouteGeometry.Point): Offset = Offset(
            (target.lon - origin.lon) * 111320.0 * cos(Math.toRadians(origin.lat)),
            (origin.lat - target.lat) * 110540.0)
        fun fromOffset(origin: RouteGeometry.Point, east: Double, south: Double) = RouteGeometry.Point(
            origin.lat - south / 110540.0,
            origin.lon + east / (111320.0 * cos(Math.toRadians(origin.lat))))
        fun automaticAccuracy(horizontal: Double, yaw: Double): Boolean =
            horizontal.isFinite() && yaw.isFinite() && horizontal in 0.0..5.0 && yaw in 0.0..10.0
        fun heading(a: RouteGeometry.Point, b: RouteGeometry.Point): Double {
            val o = offset(a, b)
            return Math.toDegrees(atan2(o.east, -o.south))
        }
        fun alignmentYaw(routeHeading: Double, cameraEast: Double, cameraSouth: Double): Double =
            Math.toRadians(routeHeading - Math.toDegrees(atan2(cameraEast, -cameraSouth)))
    }
}
