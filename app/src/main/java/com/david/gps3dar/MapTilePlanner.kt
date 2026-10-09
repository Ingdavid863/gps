package com.david.gps3dar

import kotlin.math.*

object MapTilePlanner {
    data class Tile(val z: Int, val x: Int, val y: Int)
    /** Prepare an overview and a 10 km driving corridor, with a fixed data budget. */
    fun plan(route: List<RouteGeometry.Point>, from: Int, budget: Int = 320): List<Tile> {
        if (route.size < 2) return emptyList()
        val tiles = linkedSetOf<Tile>()
        fun add(p: RouteGeometry.Point, z: Int, margin: Int) {
            val n = 1 shl z
            val x = floor((p.lon + 180) / 360 * n).toInt()
            val lat = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
            val y = floor((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n).toInt()
            for (dx in -margin..margin) for (dy in -margin..margin)
                if (tiles.size < budget) tiles.add(Tile(z, ((x + dx) % n + n) % n, (y + dy).coerceIn(0, n - 1)))
        }
        val ahead = mutableListOf<RouteGeometry.Point>()
        var covered = 0.0
        val start = from.coerceIn(0, route.lastIndex - 1)
        ahead.add(route[start])
        for (i in start until route.lastIndex) {
            val a = route[i]; val b = route[i + 1]; val d = RouteGeometry.distance(a, b)
            val pieces = max(1, ceil(d / 100).toInt())
            for (j in 1..pieces) {
                if (covered >= 10000) break
                val t = j.toDouble() / pieces
                ahead.add(RouteGeometry.Point(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                covered += d / pieces
            }
            if (covered >= 10000) break
        }
        // Nearby high-detail tiles first, so the first minutes are ready before far sections.
        for (p in ahead) for (z in listOf(16, 17, 18)) add(p, z, if (z == 16) 1 else 0)
        for (z in listOf(8, 10, 12, 14)) for (p in route) add(p, z, 0)
        return tiles.toList()
    }
}
