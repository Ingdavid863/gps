package com.david.gps3dar

import android.location.Location
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos

@RunWith(AndroidJUnit4::class)
class TollRoutingTest {
    private fun p(east: Double, north: Double) = RealisticMapActivity.GeoPoint(
        19.72 + north / 110540.0, -99.22 + east / (111320 * cos(Math.toRadians(19.72))))
    private val paid = listOf(p(0.0,0.0),p(0.0,80.0),p(0.0,180.0))
    private val free = listOf(p(0.0,5.0),p(120.0,5.0),p(120.0,180.0),paid.last())
    private fun field(name: String) = RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun fixture(points: List<RealisticMapActivity.GeoPoint>, toll: Boolean = false): JSONObject {
        val end = points.last()
        return JSONObject().put("summary",JSONObject().put("lengthInMeters",if (toll) 180 else 420)
            .put("travelTimeInSeconds",if (toll) 60 else 120))
            .put("legs",JSONArray().put(JSONObject().put("points",JSONArray(points.map {
                JSONObject().put("latitude",it.lat).put("longitude",it.lon)
            }))))
            .put("guidance",JSONObject().put("instructions",JSONArray().put(JSONObject()
                .put("point",JSONObject().put("latitude",end.lat).put("longitude",end.lon))
                .put("pointIndex",points.lastIndex).put("maneuver","ARRIVE").put("message","Llegaste"))))
            .put("sections",JSONArray().apply { if (toll) put(JSONObject()
                .put("sectionType","TOLL").put("startPointIndex",0).put("endPointIndex",points.lastIndex)) })
    }
    private fun response(request: Request, vararg routes: JSONObject) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(JSONObject().put("routes",JSONArray(routes.toList())).toString()
            .toResponseBody("application/json".toMediaType())).build()
    private fun setup(scenario: ActivityScenario<RealisticMapActivity>, client: OkHttpClient) {
        scenario.onActivity { activity ->
            field("http").set(activity,client)
            field("voiceEnabled").setBoolean(activity,false)
            val location = Location("fixture").apply {
                latitude=free.first().lat;longitude=free.first().lon;accuracy=3f;speed=2f;bearing=0f
                time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L
            }
            field("lastAcceptedLocation").set(activity,location)
            field("rawLocation").set(activity,location);field("displayLocation").set(activity,location)
            val plaza=paid[1]
            (field("tollRepository").get(activity) as TollRepository).replaceCatalog(JSONObject()
                .put("schema",2).put("revision",1).put("plazas",JSONArray().put(JSONObject()
                    .put("id",1).put("name","Caseta de prueba").put("lat",plaza.lat).put("lon",plaza.lon)
                    .put("bearings",JSONArray().put(0)).put("fares",JSONArray().put(JSONObject()
                        .put("entryId",1).put("carMxn",100).put("effective","Prueba").put("source","Fixture")))))
                .toString())
            field("routeDestination").set(activity,paid.last())
            field("routeAlternatives").set(activity,listOf(RealisticMapActivity.RouteOption(
                paid,listOf(RealisticMapActivity.NavStep(paid.last().lat,paid.last().lon,"Llegaste","🏁",2,"ARRIVE")),
                60.0,180.0,hasTolls=true,tollSections=listOf(0..2))))
            RealisticMapActivity::class.java.getDeclaredMethod("activateRoute",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                .apply { isAccessible=true }.invoke(activity,0,false)
        }
        await(scenario) { (field("tollQuote").get(it) as? TollRepository.Quote)?.booths?.size == 1 }
    }
    private fun await(scenario: ActivityScenario<RealisticMapActivity>, condition: (RealisticMapActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+20000
        var done=false
        while (!done && SystemClock.elapsedRealtime()<deadline) {
            scenario.onActivity { done=condition(it) }
            if (!done) Thread.sleep(50)
        }
        assertTrue("Navigation change must finish",done)
    }
    private fun assertPaidRoute(activity: RealisticMapActivity) {
        assertEquals(paid,field("routePoints").get(activity))
        assertFalse(field("avoidTolls").getBoolean(activity))
        assertEquals(1,(field("tollQuote").get(activity) as TollRepository.Quote).booths.size)
        assertTrue(activity.findViewById<TextView>(R.id.tollCount).text.contains("1"))
    }
    @Test fun avoidTollsUsesTheCurrentFixAndAutomaticallyActivatesTheFreeAlternative() {
        val requests=LinkedBlockingQueue<Request>(); val release=CountDownLatch(1)
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests.put(chain.request());assertTrue(release.await(15,TimeUnit.SECONDS))
            response(chain.request(),fixture(paid,true),fixture(free))
        }.build()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            try {
                setup(scenario,client)
                scenario.onActivity { it.findViewById<View>(R.id.avoidTollsButton).performClick() }
                val request=requests.poll(8,TimeUnit.SECONDS) ?: error("Routing request was not sent")
                assertEquals("tollRoads",request.url.queryParameter("avoid"))
                assertEquals("car",request.url.queryParameter("travelMode"))
                assertTrue(request.url.pathSegments[3].startsWith("${free.first().lat},${free.first().lon}:"))
                scenario.onActivity { activity ->
                    assertPaidRoute(activity)
                    assertFalse(activity.findViewById<View>(R.id.avoidTollsButton).isEnabled)
                    assertEquals(true,field("pendingAvoidTolls").get(activity))
                }
                release.countDown()
                await(scenario) { !field("rerouting").getBoolean(it) }
                scenario.onActivity { activity ->
                    assertEquals(free,field("routePoints").get(activity))
                    assertEquals(paid.last(),field("routeDestination").get(activity))
                    assertTrue(field("avoidTolls").getBoolean(activity))
                    assertEquals("Casetas: 0",activity.findViewById<TextView>(R.id.tollCount).text.toString())
                    assertEquals(120.0,field("routeDurationSeconds").getDouble(activity),.01)
                    assertEquals(0,field("activeRouteIndex").getInt(activity))
                    assertTrue(activity.getPreferences(0).getBoolean("avoidTolls",false))
                }
                await(scenario) { field("mapReady").getBoolean(it) }
                val rendered=CountDownLatch(1);var data="null"
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webMapView).evaluateJavascript("GPS3D.navigationState()") {
                        data=it;rendered.countDown()
                    }
                }
                assertTrue(rendered.await(8,TimeUnit.SECONDS))
                val geometry=JSONObject(data).getJSONObject("remaining").getJSONObject("geometry").getJSONArray("coordinates")
                assertTrue("The WebView renders the detour, not the old toll road",(0 until geometry.length()).any {
                    kotlin.math.abs(geometry.getJSONArray(it).getDouble(0)-free[1].lon)<.00001
                })
            } finally { release.countDown() }
        }
    }
    @Test fun missingTollMetadataTriggersASecondRequestAroundTheKnownBooth() {
        val requests=LinkedBlockingQueue<Request>();val sequence=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests.put(chain.request())
            response(chain.request(),fixture(if (sequence.incrementAndGet()==1) paid else free))
        }.build()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            setup(scenario,client)
            scenario.onActivity { it.findViewById<View>(R.id.avoidTollsButton).performClick() }
            val first=requests.poll(8,TimeUnit.SECONDS) ?: error("First route request missing")
            val second=requests.poll(8,TimeUnit.SECONDS) ?: error("Booth avoidance request missing")
            assertEquals("GET",first.method);assertEquals("POST",second.method)
            val body=okio.Buffer();second.body!!.writeTo(body)
            val rectangle=JSONObject(body.readUtf8()).getJSONObject("avoidAreas").getJSONArray("rectangles").getJSONObject(0)
            assertTrue(rectangle.getJSONObject("southWestCorner").getDouble("latitude") < paid[1].lat)
            assertTrue(rectangle.getJSONObject("northEastCorner").getDouble("latitude") > paid[1].lat)
            await(scenario) { !field("rerouting").getBoolean(it) }
            scenario.onActivity { activity ->
                assertEquals(free,field("routePoints").get(activity))
                assertTrue(field("avoidTolls").getBoolean(activity))
                assertEquals("Casetas: 0",activity.findViewById<TextView>(R.id.tollCount).text.toString())
            }
            assertEquals(2,sequence.get())
        }
    }
    @Test fun unavailableFreeRoutePreservesThePaidRouteAndItsBooth() {
        val sequence=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            sequence.incrementAndGet();response(chain.request(),fixture(paid))
        }.build()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            setup(scenario,client)
            scenario.onActivity { it.findViewById<View>(R.id.avoidTollsButton).performClick() }
            await(scenario) { !field("rerouting").getBoolean(it) }
            scenario.onActivity { activity ->
                assertPaidRoute(activity)
                assertTrue(activity.findViewById<View>(R.id.avoidTollsButton).isEnabled)
                assertNull(field("pendingAvoidTolls").get(activity))
                assertFalse(activity.getPreferences(0).getBoolean("avoidTolls",false))
            }
            assertEquals(2,sequence.get())
        }
    }
}
