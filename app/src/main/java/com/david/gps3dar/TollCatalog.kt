package com.david.gps3dar

import org.json.JSONObject
import kotlin.math.*

/** National plaza IDs and entry/exit fares are kept locally; no route upload is needed. */
class TollCatalog(root: JSONObject) {
    data class Rate(val entry: Long, val cost: Double, val effective: String, val source: String, val maxCost: Double)
    data class Plaza(val id: Long, val name: String, val point: RouteGeometry.Point, val role: String,
                     val bearings: List<Double>, val rates: List<Rate>)
    private data class Hit(val plaza: Plaza, val projection: RouteGeometry.Projection)
    val revision = root.getLong("revision")
    val plazas: List<Plaza> = root.getJSONArray("plazas").let { items -> (0 until items.length()).map { i ->
        val p = items.getJSONObject(i)
        val array = p.getJSONArray("fares")
        val rates = (0 until array.length()).map { j ->
            val f = array.getJSONObject(j)
            Rate(f.getLong("entryId"), f.getDouble("carMxn"), f.getString("effective"), f.getString("source"),
                f.optDouble("carMxnMax", f.getDouble("carMxn")))
        }
        val b = p.optJSONArray("bearings")
        Plaza(p.getLong("id"), p.getString("name"), RouteGeometry.Point(p.getDouble("lat"), p.getDouble("lon")),
            p.optString("role"), (0 until (b?.length() ?: 0)).map { b!!.getDouble(it) }, rates)
    }}
    init {
        require(root.getInt("schema") == 2 && revision > 0)
        require(plazas.isNotEmpty() && plazas.map { it.id }.distinct().size == plazas.size)
        require(plazas.all { it.point.lat in 14.0..33.0 && it.point.lon in -118.0..-86.0 &&
            it.bearings.all { b -> b.isFinite() && b >= 0 && b < 360 } &&
            it.rates.all { r -> r.cost.isFinite() && r.cost in 0.0..10000.0 && r.maxCost.isFinite() && r.maxCost in r.cost..10000.0 } })
    }
    /** Detect a route actually crossing a plaza even if a provider omits toll sections. */
    fun crossedPlazas(route: List<RouteGeometry.Point>): List<Plaza> {
        if (route.size < 2) return emptyList()
        val minLat = route.minOf { it.lat } - .0002
        val maxLat = route.maxOf { it.lat } + .0002
        val minLon = route.minOf { it.lon } - .0002
        val maxLon = route.maxOf { it.lon } + .0002
        return plazas.filter { plaza ->
            if (plaza.point.lat !in minLat..maxLat || plaza.point.lon !in minLon..maxLon) return@filter false
            val projection = RouteGeometry.project(route, plaza.point) ?: return@filter false
            if (projection.distance > 12.0) return@filter false
            val a = route[projection.segment]; val b = route[projection.segment + 1]
            val bearing = (Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat)) + 360) % 360
            plaza.bearings.isEmpty() || plaza.bearings.any { abs((it - bearing + 540) % 360 - 180) < 70 }
        }
    }
    fun quote(route: List<RouteGeometry.Point>, hasTolls: Boolean, sections: List<IntRange> = emptyList(), entered: List<Long> = emptyList()): TollRepository.Quote {
        if (route.size < 2) return TollRepository.Quote(emptyList(), hasTolls, false)
        if (!hasTolls) return TollRepository.Quote(emptyList(), false, true)
        val minLat=route.minOf { it.lat }-.001;val maxLat=route.maxOf { it.lat }+.001
        val minLon=route.minOf { it.lon }-.001;val maxLon=route.maxOf { it.lon }+.001
        val hits = plazas.mapNotNull { p ->
            if (p.point.lat !in minLat..maxLat || p.point.lon !in minLon..maxLon) return@mapNotNull null
            val projection=RouteGeometry.project(route,p.point) ?: return@mapNotNull null
            if (projection.distance>38) return@mapNotNull null
            if (sections.isNotEmpty() && sections.none { projection.segment in (it.first-1)..(it.last+1) }) return@mapNotNull null
            val a=route[projection.segment];val b=route[projection.segment+1]
            val bearing=(Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),b.lat-a.lat))+360)%360
            if (p.bearings.isNotEmpty() && p.bearings.none { abs((it-bearing+540)%360-180)<70 }) return@mapNotNull null
            Hit(p,projection)
        }.sortedBy { it.projection.along }
        // Choose the lane nearest the route, with travel direction resolved from RNC roads.
        val unique=mutableListOf<Hit>()
        for (hit in hits) {
            val index=unique.indexOfFirst { abs(it.projection.along-hit.projection.along)<100 &&
                RouteGeometry.distance(it.plaza.point,hit.plaza.point)<100 &&
                TollRepository.normalize(it.plaza.name)==TollRepository.normalize(hit.plaza.name) }
            if (index<0) unique.add(hit) else if(hit.projection.distance<unique[index].projection.distance) unique[index]=hit
        }
        val ordered=unique.sortedBy { it.projection.along }
        val booths=ordered.mapIndexed { i, hit ->
            val p=hit.plaza
            val prior=ordered.take(i).asReversed().firstOrNull { entry -> p.rates.any { it.entry==entry.plaza.id } }
            val entryId=prior?.plaza?.id ?: entered.asReversed().firstOrNull { entry -> p.rates.any { it.entry==entry } }
            val matches=when {
                entryId!=null -> p.rates.filter { it.entry==entryId }
                else -> p.rates.filter { it.entry==p.id }
            }
            val fare=matches.takeIf { it.isNotEmpty() && it.map { r -> r.cost to r.maxCost }.distinct().size==1 }?.first()
            val deferred=p.role=="Entrada" && ordered.drop(i+1).any { exit -> exit.plaza.rates.any { it.entry==p.id && it.entry!=exit.plaza.id } }
            TollRepository.Booth(p.id,p.name,p.point.lat,p.point.lon,hit.projection.along,
                if(deferred) 0.0 else fare?.cost,if(deferred) "Pago en salida" else fare?.effective,
                fare?.source ?: "INEGI / IMT RNC",p.role,if(deferred) 0.0 else fare?.maxCost)
        }
        return TollRepository.Quote(booths,true,true)
    }
}

