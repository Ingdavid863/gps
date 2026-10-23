package com.david.gps3dar

import kotlin.math.*

object MapTilePlanner {
    // Vector geometry at this level is overscaled for close offline driving views.
    const val OFFLINE_MAX_ZOOM = 16
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
    /** Every segment, every overview level and a surrounding corridor. Never truncate a trip. */
    @Suppress("UNUSED_PARAMETER")
    fun plan(route: List<RouteGeometry.Point>, from: Int = 0): List<Tile> {
        if (route.size < 2 || route.any { !it.lat.isFinite() || !it.lon.isFinite() ||
                it.lat !in -85.051128..85.051128 || it.lon !in -180.0..180.0 }) return emptyList()
        val result = linkedSetOf<Tile>()
        for (z in 3..OFFLINE_MAX_ZOOM) {
            val n = 1 shl z
            fun x(p: RouteGeometry.Point) = (p.lon + 180) / 360 * n
            fun y(p: RouteGeometry.Point): Double {
                val r = Math.toRadians(p.lat)
                return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
            }
            var lastX = Int.MIN_VALUE; var lastY = Int.MIN_VALUE
            fun add(cx: Int, cy: Int) {
                if (cx == lastX && cy == lastY) return
                lastX = cx; lastY = cy
                // The close pitched viewport exposes one extra row behind the car
                // while zooming out. Keep that transition inside the saved corridor.
                val radius = if (z == OFFLINE_MAX_ZOOM) 3 else 2
                for (dx in -radius..radius) for (dy in -radius..radius) {
                    val yy = cy + dy
                    if (yy in 0 until n) result.add(Tile(z, ((cx + dx) % n + n) % n, yy))
                }
            }
            for (i in 0 until route.lastIndex) {
                val ax = x(route[i]); val ay = y(route[i])
                var dx = x(route[i + 1]) - ax
                if (dx > n / 2.0) dx -= n
                if (dx < -n / 2.0) dx += n
                val dy = y(route[i + 1]) - ay
                val pieces = max(1, ceil(max(abs(dx), abs(dy)) * 2).toInt())
                for (j in 0..pieces) {
                    val t = j.toDouble() / pieces
                    add(floor(ax + dx * t).toInt(), floor(ay + dy * t).toInt())
                }
            }
            // Also fill the low-zoom overview rectangle, including its corners away from the line.
            val xs = route.map { tile(it.lon, it.lat, z).x }; val ys = route.map { tile(it.lon, it.lat, z).y }
            val west = xs.min(); val east = xs.max(); val north = ys.min(); val south = ys.max()
            if ((east - west + 1).toLong() * (south - north + 1) <= 64)
                for (xx in west..east) for (yy in north..south) add(xx, yy)
        }
        return result.toList()
    }
}
