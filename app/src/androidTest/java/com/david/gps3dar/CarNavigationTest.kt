package com.david.gps3dar

import android.Manifest
import android.graphics.SurfaceTexture
import android.location.Location
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.testing.TestCarContext
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gps3dar.car.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos

@RunWith(AndroidJUnit4::class)
class CarNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(block: ()->Unit) = instrumentation.runOnMainSync(block)
    private val points=listOf(RealisticMapActivity.GeoPoint(19.72,-99.22),
        RealisticMapActivity.GeoPoint(19.72+100/110540.0,-99.22),
        RealisticMapActivity.GeoPoint(19.72+100/110540.0,-99.22+100/(111320*cos(Math.toRadians(19.72)))))
    private fun fix(north: Double)=Location("fixture").apply {
        latitude=19.72+north/110540;longitude=-99.22;speed=0f;bearing=0f;accuracy=3f
        elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L+north.toLong()*1_000_000
    }
    private fun route(avoid: Boolean=false)=RealisticMapActivity.RouteOption(points,listOf(
        RealisticMapActivity.NavStep(points[1].lat,points[1].lon,"Gira a la derecha","→",1,"TURN_RIGHT"),
        RealisticMapActivity.NavStep(points[2].lat,points[2].lon,"Llegaste","🏁",2,"ARRIVE")),120.0,200.0,avoidsTolls=avoid)
    private fun field(name: String)=RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun await(condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(!condition()) { assertTrue("Car map must settle",SystemClock.elapsedRealtime()<end);Thread.sleep(100) }
    }
    private fun js(map: CarMapSurface,script: String): String {
        val done=CountDownLatch(1);var value="null"
        main { map.webView?.evaluateJavascript(script) { value=it;done.countDown() } }
        assertTrue(done.await(8,TimeUnit.SECONDS));return value
    }
    @Test fun phoneCarAndClusterUseOneRemainingPolylineAndReturnCarChangesToThePhone() {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            var map: CarMapSurface?=null;var surface: Surface?=null;var texture: SurfaceTexture?=null
            try {
                scenario.onActivity { activity ->
                    CarNavigation.stop("phone");CarNavigation.fix(fix(0.0),true)
                    field("lastAcceptedLocation").set(activity,fix(0.0));field("rawLocation").set(activity,fix(0.0))
                    field("displayLocation").set(activity,fix(0.0));field("voiceEnabled").setBoolean(activity,false)
                    field("routeDestination").set(activity,points.last());field("routeAlternatives").set(activity,listOf(route()))
                    RealisticMapActivity::class.java.getDeclaredMethod("activateRoute",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                        .apply { isAccessible=true }.invoke(activity,0,false)
                    assertEquals(points,CarNavigation.state.route!!.points)
                    texture=SurfaceTexture(0).apply { setDefaultBufferSize(800,480) };surface=Surface(texture)
                    map=CarMapSurface(activity).apply { connect(surface!!,800,480,160) }
                    CarNavigation.fix(fix(55.0))
                    val context=TestCarContext.createCarContext(activity)
                    val template=GpsCarScreen(context,map!!).onGetTemplate() as NavigationTemplate
                    assertNotNull(template.navigationInfo);assertNotNull(template.destinationTravelEstimate)
                    assertNotNull(GpsCarScreen(context,map!!,true).onGetTemplate())
                }
                await { js(map!!,"!!window.GPS3D && GPS3D.cameraState().ready")=="true" }
                await { js(map!!,"GPS3D.navigationState().progress>50")=="true" }
                val rendered=JSONObject(js(map!!,"GPS3D.navigationState()"))
                assertTrue(rendered.getJSONObject("remaining").getJSONObject("geometry").getJSONArray("coordinates")
                    .getJSONArray(0).getDouble(1)>19.72+50/110540.0)
                assertEquals(45.0,CarNavigation.state.turnMeters,1.0)
                main { CarNavigation.setRoute(route(true),points.last(),"Ruta del auto","car") }
                await {
                    var shared=false;scenario.onActivity { shared=field("avoidTolls").getBoolean(it) && field("routePoints").get(it)==points };shared
                }
                main { CarNavigation.stop() }
                await { var stopped=false;scenario.onActivity { stopped=(field("routePoints").get(it) as List<*>).isEmpty() };stopped }
                await { js(map!!,"GPS3D.navigationState().remaining.features.length===0")=="true" }
            } finally { main { map?.close();surface?.release();texture?.release();CarNavigation.stop("phone") } }
        }
    }
    @Test fun carAvoidTollsIsAtomicAndAnOlderResponseCannotReplaceANewerPhoneRoute() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val old=CarNavigation.routeClient
        CarNavigation.routeClient=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("tollRoads",chain.request().url.queryParameter("avoid"));entered.countDown()
            assertTrue(release.await(15,TimeUnit.SECONDS))
            val route=JSONObject().put("summary",JSONObject().put("lengthInMeters",200).put("travelTimeInSeconds",120))
                .put("legs",JSONArray().put(JSONObject().put("points",JSONArray(points.map {
                    JSONObject().put("latitude",it.lat).put("longitude",it.lon) }))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("routes",JSONArray().put(route)).toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            main {
                CarNavigation.stop("phone");CarNavigation.fix(fix(0.0));CarNavigation.setRoute(route(),points.last(),"Actual","phone")
                CarNavigation.routeTo(instrumentation.targetContext,points.last(),"Sin casetas",true)
                assertFalse(CarNavigation.state.route!!.avoidsTolls);assertTrue(CarNavigation.state.pending)
            }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            main { CarNavigation.setRoute(route(),points.last(),"Nuevo destino del teléfono","phone") }
            release.countDown()
            // The cancelled callback must not resurrect its previous destination or toll preference.
            instrumentation.waitForIdleSync()
            main { assertEquals("Nuevo destino del teléfono",CarNavigation.state.label);assertFalse(CarNavigation.state.pending) }
        } finally { release.countDown();main { CarNavigation.stop("phone") };CarNavigation.routeClient=old }
    }
}
