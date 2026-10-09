package com.david.gps3dar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TollCatalogTest {
    private val route = listOf(RouteGeometry.Point(19.40,-99.20), RouteGeometry.Point(19.41,-99.20), RouteGeometry.Point(19.42,-99.20))
    private fun catalog() = TollCatalog(JSONObject("""{"schema":2,"revision":1,"plazas":[
        {"id":1,"name":"Entrada norte","lat":19.405,"lon":-99.2,"role":"Entrada","bearings":[0],"fares":[]},
        {"id":2,"name":"Salida sur","lat":19.415,"lon":-99.2,"role":"Salida","bearings":[0],"fares":[
            {"entryId":1,"carMxn":90.58,"effective":"Publicado","source":"Concesión"},
            {"entryId":3,"carMxn":66.63,"effective":"Publicado","source":"Concesión"}]},
        {"id":3,"name":"Otra entrada","lat":19.405,"lon":-99.21,"role":"Entrada","bearings":[0],"fares":[]},
        {"id":4,"name":"Carril contrario","lat":19.407,"lon":-99.20001,"role":"N/A","bearings":[180],"fares":[
            {"entryId":4,"carMxn":100,"effective":"Publicado","source":"Concesión"}]}
    ]}"""))
    @Test fun closedSystemUsesActualEntryAndChargesOnce() {
        val booths=catalog().quote(route,true).booths
        assertEquals(listOf(1L,2L),booths.map { it.id })
        assertEquals(0.0,booths[0].carMxn!!,.001)
        assertEquals(90.58,booths.sumOf { it.carMxn!! },.001)
    }
    @Test fun rerouteRetainsPassedEntryButNeverGuessesAnUnknownEntry() {
        val remaining=listOf(RouteGeometry.Point(19.41,-99.20),route.last())
        assertNull(catalog().quote(remaining,true).booths.single().carMxn)
        assertEquals(90.58,catalog().quote(remaining,true,entered=listOf(1)).booths.single().carMxn!!,.001)
        assertEquals(66.63,catalog().quote(remaining,true,entered=listOf(3)).booths.single().carMxn!!,.001)
    }
    @Test fun tollSectionsExcludeNearbyGateOnAnotherRoad() {
        val booths=catalog().quote(route,true,listOf(5..6)).booths
        assertTrue(booths.isEmpty())
        assertFalse(catalog().quote(route,true,listOf(5..6)).coverageKnown)
        assertTrue(catalog().quote(route,false).coverageKnown)
    }
    @Test fun rejectsInvalidCoordinatesAndRevisions() {
        val bad=JSONObject("""{"schema":2,"revision":1,"plazas":[{"id":1,"name":"Fuera","lat":90,"lon":-99,"fares":[]}]}""")
        assertTrue(runCatching { TollCatalog(bad) }.isFailure)
        bad.getJSONArray("plazas").getJSONObject(0).put("lat",19.4)
        bad.put("revision",0)
        assertTrue(runCatching { TollCatalog(bad) }.isFailure)
    }
    @Test fun bundledNationalDataRetainsDifferentRoadsAndAccessRamps() {
        val root=JSONObject(File("src/main/assets/mx-tolls.json").readText())
        val catalog=TollCatalog(root)
        assertTrue(catalog.plazas.size>=1200)
        fun rate(id:Long)=catalog.plazas.single { it.id==id }.rates.single { it.entry==id }
        assertEquals("CAPUFE",rate(702).source)
        assertTrue(rate(1602).source.contains("Circuito Exterior Mexiquense"))
        assertFalse(rate(671).source.contains("Circuito Exterior Mexiquense"))
        assertTrue(rate(704).source.contains("Circuito Exterior Mexiquense"))
        assertNotEquals(rate(704).cost,rate(700).cost,.001)
    }
}
