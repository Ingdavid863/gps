package com.david.gps3dar

import kotlin.math.*

object MapTilePlanner {
    data class Tile(val z: Int, val x: Int, val y: Int)
    fun tile(lon: Double, lat: Double, z: Int): Tile {
        val n = 1 shl z
        val x = floor((lon + 180) / 360 * n).toInt()
        val r = Math.toRadians(lat.coerceIn(-85.051128, 85.051128))
        val y = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
        return Tile(z, ((x % n) + n) % n, y.coerceIn(0, n - 1))
    }

    /** Visible tiles and coarser parents first; the next detailed zoom also stays prepared. */
    fun viewport(west: Double, south: Double, east: Double, north: Double, zoom: Double, budget: Int = 96): List<Tile> {
        if (listOf(west, south, east, north, zoom).any { !it.isFinite() } || south > north || budget <= 0) return emptyList()
        val z = floor(zoom).toInt().coerceIn(3, 18)
        val levels = (listOf((z - 1).coerceAtLeast(3), z) + (z - 2 downTo 3) + listOf((z + 1).coerceAtMost(18))).distinct()
        val result = linkedSetOf<Tile>()
        for (level in levels) {
            val a = tile(west, north, level); val b = tile(east, south, level)
            val n = 1 shl level
            val span = if (east < west) (b.x - a.x + n) % n else (b.x - a.x).coerceAtLeast(0)
            // Reject excessively large/synthetic viewports instead of allocating unbounded grids.
            if ((span + 1).toLong() * (b.y - a.y + 1) > 64) continue
            val tiles = (0..span).flatMap { dx -> (a.y..b.y).map { y -> Tile(level, (a.x + dx) % n, y) } }
            val center = tile(if (east < west) (west + east + 360) / 2 else (west + east) / 2, (north + south) / 2, level)
            tiles.sortedBy { abs(it.x - center.x) + abs(it.y - center.y) }.forEach {
                if (result.size < budget) result.add(it)
            }
        }
        return result.toList()
    }
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
        // Reserve every overview zoom before the detailed corridor exhausts the budget.
        // Previously only 8/10/12/14 were prepared, leaving gaps at intermediate zooms.
        for (z in 3..15) {
            val step = max(1, route.size / 16)
            for (i in route.indices step step) add(route[i], z, 0)
            add(route.last(), z, 0)
            if (tiles.size >= min(budget / 3, 90)) break
        }
        for (p in ahead) for (z in listOf(16, 17, 18)) add(p, z, if (z == 16) 1 else 0)
        return tiles.toList()
    }
}
