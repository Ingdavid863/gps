package com.david.gps3dar

import org.junit.Assert.*
import org.junit.Test

class NavigationRoutePositionTest {
    private val origin=RouteGeometry.Point(19.7,-99.2)
    private fun p(e: Double,n: Double)=ArRouteGeometry.fromOffset(origin,e,-n)
    @Test fun turnInsideASparseSegmentUsesItsExactLocationRatherThanAVertex() {
        val geometry=NavigationRoutePosition(listOf(p(0.0,0.0),p(0.0,100.0)))
        val turn=geometry.instructionAlong(p(0.0,80.0),0,80.0)
        assertEquals(80.0,turn,.01)
        assertEquals(5.0,geometry.distanceToTurn(turn,geometry.match(p(0.0,75.0))!!.along),.01)
        assertEquals(0.0,geometry.distanceToTurn(turn,geometry.match(p(0.0,80.0))!!.along),.01)
    }
    @Test fun aFreshFixAfterTurningDoesNotStayBehindOnTheApproach() {
        val geometry=NavigationRoutePosition(listOf(p(0.0,0.0),p(0.0,80.0),p(100.0,80.0)))
        val after=geometry.match(p(5.0,80.0),0.0,78.0,4.0,1.0,3.0)!!
        assertEquals(1,after.segment);assertEquals(85.0,after.along,.02)
        assertEquals(0.0,after.distance,.01)
    }
    @Test fun anOpposingCarriagewayDoesNotWinWithASlightlyCloserNoisyFix() {
        val geometry=NavigationRoutePosition(listOf(p(0.0,0.0),p(0.0,200.0),p(10.0,200.0),p(10.0,0.0)))
        val match=geometry.match(p(6.0,100.0),0.0,99.0,4.0,1.0,8.0)!!
        assertEquals(0,match.segment);assertEquals(100.0,match.along,.01)
    }
    @Test fun returningNearTheSameStreetCannotJumpAheadAroundALongLoop() {
        val geometry=NavigationRoutePosition(listOf(p(0.0,0.0),p(0.0,250.0),p(5.0,250.0),p(5.0,0.0)))
        val match=geometry.match(p(4.0,20.0),null,20.0,1.0,1.0,5.0)!!
        assertEquals(0,match.segment);assertEquals(20.0,match.along,.01)
    }
    @Test fun duplicatedLegEndpointsKeepProviderIndicesAndDistancesIntact() {
        val geometry=NavigationRoutePosition(listOf(p(0.0,0.0),p(0.0,40.0),p(0.0,40.0),p(0.0,100.0)))
        assertEquals(80.0,geometry.instructionAlong(p(0.0,80.0),2,80.0),.01)
        assertEquals(100.0,geometry.length,.01)
    }
}
