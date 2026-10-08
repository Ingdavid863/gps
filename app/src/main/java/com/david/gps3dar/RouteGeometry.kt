package com.david.gps3dar

import kotlin.math.*

object RouteGeometry {
    data class Point(val lat: Double, val lon: Double)
    data class Projection(val segment: Int, val fraction: Double, val distance: Double, val along: Double)

    fun distance(a: Point, b: Point): Double {
        val lat = Math.toRadians((a.lat + b.lat) / 2)
        return hypot((a.lon - b.lon) * 111320 * cos(lat), (a.lat - b.lat) * 110540)
    }

    fun project(route: List<Point>, p: Point): Projection? {
        var best: Projection? = null
        var along = 0.0
        for (i in 0 until route.lastIndex) {
            val a = route[i]; val b = route[i + 1]
            val sx = 111320 * cos(Math.toRadians(p.lat)); val sy = 110540.0
            val dx = (b.lon - a.lon) * sx; val dy = (b.lat - a.lat) * sy
            val px = (p.lon - a.lon) * sx; val py = (p.lat - a.lat) * sy
            val f = ((px * dx + py * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
            val d = hypot(px - f * dx, py - f * dy)
            val length = distance(a, b)
            if (best == null || d < best.distance) best = Projection(i, f, d, along + f * length)
            along += length
        }
        return best
    }

    /** Keeps every bend within 8 m of the original, for a bounded Overpass corridor. */
    fun simplify(route: List<Point>, tolerance: Double = 8.0): List<Point> {
        if (route.size < 3) return route
        val keep = BooleanArray(route.size); keep[0] = true; keep[route.lastIndex] = true
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.add(0 to route.lastIndex)
        while (ranges.isNotEmpty()) {
            val (start, end) = ranges.removeLast()
            var maxDistance = tolerance; var selected = -1
            val segment = listOf(route[start], route[end])
            for (i in start + 1 until end) {
                val d = project(segment, route[i])?.distance ?: 0.0
                if (d > maxDistance) { maxDistance = d; selected = i }
            }
            if (selected >= 0) { keep[selected] = true; ranges.add(start to selected); ranges.add(selected to end) }
        }
        return route.filterIndexed { i, _ -> keep[i] }
    }
}
