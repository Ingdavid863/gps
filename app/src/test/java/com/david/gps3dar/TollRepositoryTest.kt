package com.david.gps3dar

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TollRepositoryTest {
    private val catalog = """{"fares":[{"name":"TEPOTZOTLAN","carMxn":113,"effective":"Abr 13/2026"},{"name":"ACCESO (I1)","carMxn":20,"effective":"Abr 13/2026"},{"name":"ACCESO (I2)","carMxn":40,"effective":"Abr 13/2026"}]}"""
    private val route = listOf(RouteGeometry.Point(19.4, -99.2), RouteGeometry.Point(19.42, -99.2))
    @Test fun onePlazaPerLaneAndNoNearbyRoad() {
        val root = JSONObject("""{"elements":[
            {"id":1,"lat":19.41,"lon":-99.2,"tags":{"name":"Caseta de Tepotzotlán"}},
            {"id":2,"lat":19.41002,"lon":-99.20001,"tags":{"name":"Tepotzotlán"}},
            {"id":3,"lat":19.41,"lon":-99.21,"tags":{"name":"Otra autopista"}}
        ]}""")
        val booths = TollRepository(OkHttpClient(), catalog).parse(root, route)
        assertEquals(1, booths.size)
        assertEquals(113.0, booths.single().carMxn!!, .001)
    }
    @Test fun ambiguousEntryExitCostIsUnknown() {
        val root = JSONObject("""{"elements":[{"id":1,"lat":19.41,"lon":-99.2,"tags":{"name":"Acceso"}}]}""")
        assertNull(TollRepository(OkHttpClient(), catalog).parse(root, route).single().carMxn)
    }
    @Test fun noBoothsOnTolledRoadDoesNotMeanFree() {
        assertFalse(TollRepository.Quote(emptyList(), true, true).coverageKnown)
        assertTrue(TollRepository.Quote(emptyList(), false, true).coverageKnown)
        assertFalse(TollRepository.Quote(emptyList(), false, false).coverageKnown)
    }
    @Test(expected = IllegalStateException::class) fun overpassPartialResultIsNotComplete() {
        TollRepository(OkHttpClient(), catalog).parse(JSONObject("""{"remark":"runtime error: timeout","elements":[]}"""), route)
    }
    @Test fun projectionUsesSegmentsNotJustVertices() {
        val p = RouteGeometry.project(route, RouteGeometry.Point(19.41, -99.2))!!
        assertEquals(0.0, p.distance, .01)
        assertTrue(p.along > 1000)
    }
}
