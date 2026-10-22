package com.david.gps3dar

import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OfflineRouteRecoveryTest {
    private fun field(name: String)=RealisticMapActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun network(activity: RealisticMapActivity, online: Boolean) {
        RealisticMapActivity::class.java.getDeclaredMethod("setMapNetworkAvailable",Boolean::class.javaPrimitiveType)
            .apply{isAccessible=true}.invoke(activity,online)
    }
    private fun await(scenario: ActivityScenario<RealisticMapActivity>, check: (RealisticMapActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000;var done=false
        while(!done&&SystemClock.elapsedRealtime()<deadline){scenario.onActivity{done=check(it)};if(!done)Thread.sleep(40)}
        assertTrue("navigation transition completed",done)
    }
    private fun activate(activity: RealisticMapActivity, points: List<RealisticMapActivity.GeoPoint>, avoid: Boolean=false) {
        field("voiceEnabled").setBoolean(activity,false)
        field("lastAcceptedLocation").set(activity,Location("block-device-fixture").apply {
            elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L
        })
        activity.findViewById<EditText>(R.id.searchInput).setText("Destino de prueba")
        field("routeDestination").set(activity,points.last())
        field("routeAlternatives").set(activity,listOf(RealisticMapActivity.RouteOption(points,
            listOf(RealisticMapActivity.NavStep(points.last().lat,points.last().lon,"Llegaste al destino","●",points.lastIndex,"ARRIVE")),
            600.0,20000.0,avoidsTolls=avoid)))
        RealisticMapActivity::class.java.getDeclaredMethod("activateRoute",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .apply{isAccessible=true}.invoke(activity,0,false)
    }
    @Before fun cleanSavedNavigation() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteFile("saved-navigation.json")
    }
    @After fun clearSavedNavigation() = cleanSavedNavigation()
    private fun deviceInternet(online: Boolean) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(transport in listOf("wifi","data"))
            instrumentation.uiAutomation.executeShellCommand("svc $transport ${if(online) "enable" else "disable"}")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val connectivity=instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+15000
        fun connected()=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        while(SystemClock.elapsedRealtime()<deadline&&(if(online)!connected() else connectivity.activeNetwork!=null))Thread.sleep(100)
        if(online)assertTrue("device internet restored for subsequent cases",connected())
        else assertNull("restore case has no internet transport",connectivity.activeNetwork)
    }

    @Test fun selectedWholeRouteAndTurnsRestoreAfterActivityRecreationWhileOffline() {
        val points=(0..200).map{RealisticMapActivity.GeoPoint(23.1+it*.001,-110.0)}
        try {
            deviceInternet(false)
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> network(activity,false);activate(activity,points) }
            await(scenario){File(it.filesDir,"saved-navigation.json").isFile}
            scenario.recreate()
            scenario.onActivity { network(it,false) }
            await(scenario){field("routeActive").getBoolean(it)}
            scenario.onActivity { activity ->
                assertEquals(points,field("routePoints").get(activity))
                assertEquals(points.last(),field("routeDestination").get(activity))
                val steps=field("navSteps").get(activity) as List<*>
                assertEquals("Llegaste al destino",(steps.single() as RealisticMapActivity.NavStep).instruction)
                assertEquals("Destino de prueba",activity.findViewById<EditText>(R.id.searchInput).text.toString())
            }
            await(scenario){activity ->
                val text=activity.findViewById<TextView>(R.id.mapCacheStatus).text
                !field("networkAvailable").getBoolean(activity)&&
                    (text.contains("Sin internet")||text.contains("disponibles sin conexión"))
            }
        } } finally { deviceInternet(true) }
    }
    @Test fun wrongTurnRequestsAFastRouteAndResumesAsSoonAsInternetReturns() {
        val initial=listOf(RealisticMapActivity.GeoPoint(23.1,-110.0),RealisticMapActivity.GeoPoint(23.103,-110.0))
        val current=RealisticMapActivity.GeoPoint(23.101,-109.9998)
        val detour=listOf(current,RealisticMapActivity.GeoPoint(23.101,-109.999),RealisticMapActivity.GeoPoint(23.103,-109.999),initial.last())
        val requests=LinkedBlockingQueue<Request>();val release=CountDownLatch(1);val sequence=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor{chain ->
            requests.put(chain.request())
            if(sequence.incrementAndGet()==1)release.await(8,TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("routes",JSONArray().put(JSONObject()
                    .put("summary",JSONObject().put("lengthInMeters",500).put("travelTimeInSeconds",80))
                    .put("legs",JSONArray().put(JSONObject().put("points",JSONArray(detour.map{p ->
                        JSONObject().put("latitude",p.lat).put("longitude",p.lon)})))))).toString()
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    field("http").set(activity,client)
                    activate(activity,initial,true);network(activity,true)
                }
                fun fix() = Location("fixture").apply {
                    latitude=current.lat;longitude=current.lon;speed=12f;bearing=90f;accuracy=3f;bearingAccuracyDegrees=5f
                    elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos();time=System.currentTimeMillis()
                }
                fun deviation() { scenario.onActivity { activity ->
                    val location=fix();field("rawLocation").set(activity,location)
                    RealisticMapActivity::class.java.getDeclaredMethod("maybeReroute",Location::class.java,RealisticMapActivity.RouteMatch::class.java)
                        .apply{isAccessible=true}.invoke(activity,location,null)
                } }
                val at=SystemClock.elapsedRealtime();deviation();Thread.sleep(500);deviation()
                val first=requests.poll(1500,TimeUnit.MILLISECONDS) ?: error("wrong-turn request was delayed")
                assertTrue("request starts within one second of a confirmed wrong turn",SystemClock.elapsedRealtime()-at<1800)
                assertEquals("0",first.url.queryParameter("maxAlternatives"));assertEquals("tollRoads",first.url.queryParameter("avoid"))
                assertEquals("90",first.url.queryParameter("vehicleHeading"))
                scenario.onActivity { activity ->
                    assertTrue(field("automaticRerouteInFlight").getBoolean(activity))
                    assertEquals(TimeUnit.SECONDS.toNanos(6),(field("routeCall").get(activity) as okhttp3.Call).timeout().timeoutNanos())
                    network(activity,false)
                }
                await(scenario){field("pendingAutoReroute").getBoolean(it)&&!field("rerouting").getBoolean(it)}
                release.countDown()
                scenario.onActivity { activity ->
                    assertEquals(initial,field("routePoints").get(activity))
                    field("rawLocation").set(activity,fix());network(activity,true)
                }
                assertNotNull("reconnection immediately resumes pending recalc",requests.poll(1500,TimeUnit.MILLISECONDS))
                await(scenario){field("routePoints").get(it)==detour&&!field("rerouting").getBoolean(it)}
                scenario.onActivity { activity ->
                    assertTrue(field("avoidTolls").getBoolean(activity))
                    assertFalse(field("pendingAutoReroute").getBoolean(activity))
                }
            } finally { release.countDown() }
        }
    }
}
