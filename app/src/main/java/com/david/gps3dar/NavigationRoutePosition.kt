package com.david.gps3dar

import kotlin.math.*

/** Metric progress on the provider polyline. Point indices are segment hints, not turn locations. */
class NavigationRoutePosition(val points: List<RouteGeometry.Point>) {
    data class Match(val segment: Int, val point: RouteGeometry.Point, val distance: Double,
                     val along: Double, val bearing: Double)
    val cumulative = DoubleArray(points.size)
    val length: Double get() = cumulative.lastOrNull() ?: 0.0
    init {
        for (i in 1 until points.size) cumulative[i] = cumulative[i-1] + RouteGeometry.distance(points[i-1], points[i])
    }
    private fun segment(p: RouteGeometry.Point, i: Int): Match? {
        val a=points[i]; val b=points[i+1]
        val sx=111320*cos(Math.toRadians(p.lat)); val sy=110540.0
        val dx=(b.lon-a.lon)*sx; val dy=(b.lat-a.lat)*sy
        if (hypot(dx,dy)<.01) return null
        val px=(p.lon-a.lon)*sx; val py=(p.lat-a.lat)*sy
        val fraction=((px*dx+py*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
        val point=RouteGeometry.Point(a.lat+(b.lat-a.lat)*fraction, a.lon+(b.lon-a.lon)*fraction)
        return Match(i, point, hypot(px-dx*fraction,py-dy*fraction),
            cumulative[i]+fraction*(cumulative[i+1]-cumulative[i]),
            (Math.toDegrees(atan2(dx,dy))+360)%360)
    }
    fun match(p: RouteGeometry.Point, heading: Double?=null, previousAlong: Double?=null,
              speed: Double=0.0, elapsedSeconds: Double=1.0, accuracy: Double=10.0): Match? {
        val ahead=max(100.0,speed*(elapsedSeconds.coerceIn(0.0,6.0)+4)).coerceAtMost(400.0)
        val behind=max(25.0,accuracy*2).coerceAtMost(50.0)
        val expected=speed*elapsedSeconds.coerceIn(0.0,6.0)+max(20.0,accuracy*2)
        var best: Match?=null; var bestScore=Double.POSITIVE_INFINITY
        for (i in 0 until points.lastIndex) {
            if(previousAlong!=null && (cumulative[i+1]<previousAlong-behind || cumulative[i]>previousAlong+ahead))continue
            val candidate=segment(p,i) ?: continue
            val delta=heading?.let { abs((candidate.bearing-it+540)%360-180) } ?: 0.0
            // A stale course immediately after a turn must not hold the marker on the approach.
            // Opposing carriageways, however, cannot be selected simply because they are closer.
            val directionPenalty=if(delta>105) (delta-105)*.65 else 0.0
            val jumpPenalty=previousAlong?.let { max(0.0,candidate.along-it-expected)*.35 } ?: 0.0
            val score=candidate.distance+directionPenalty+jumpPenalty
            if(score<bestScore){best=candidate;bestScore=score}
        }
        return best
    }
    fun instructionAlong(point: RouteGeometry.Point, pointIndex: Int=-1, reportedOffset: Double=Double.NaN): Double {
        if(points.size<2)return 0.0
        if(pointIndex in points.indices) {
            val candidates=(max(0,pointIndex-1)..min(points.lastIndex-1,pointIndex+1))
                .mapNotNull { segment(point,it) }
            val closest=candidates.minByOrNull { it.distance }
            if(closest!=null && closest.distance<20)return closest.along
        }
        if(reportedOffset.isFinite() && reportedOffset in 0.0..length+5)return reportedOffset.coerceIn(0.0,length)
        return match(point)?.along ?: 0.0
    }
    fun distanceToTurn(turnAlong: Double, currentAlong: Double) = (turnAlong-currentAlong).coerceAtLeast(0.0)
}
