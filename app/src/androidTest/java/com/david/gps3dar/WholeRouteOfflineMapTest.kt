package com.david.gps3dar

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.location.Location
import android.provider.MediaStore
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual provider -> protected native files -> new WebView with device internet disabled. */
@RunWith(AndroidJUnit4::class)
class WholeRouteOfflineMapTest {
    private fun field(name: String)=RealisticMapActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun js(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val finished=CountDownLatch(1);var value="null"
        scenario.onActivity { activity -> activity.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script){value=it;finished.countDown()} }
        assertTrue(finished.await(10,TimeUnit.SECONDS));return value
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @Test fun actualChosenRouteRendersBeyondTenKilometresAfterRestartWithInternetDisabled() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        context.deleteFile("saved-navigation.json")
        var selected: List<RealisticMapActivity.GeoPoint> = emptyList()
        var prepared=0
        try {
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field("voiceEnabled").setBoolean(activity,false)
                    field("lastAcceptedLocation").set(activity,Location("fixture").apply {
                        elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+300_000_000_000L
                    })
                    RealisticMapActivity::class.java.getDeclaredMethod("requestRoute",Double::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType,Double::class.javaPrimitiveType,Double::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,Boolean::class.javaObjectType)
                        .apply{isAccessible=true}.invoke(activity,19.432608,-99.133209,19.632608,-99.133209,false,null)
                }
                val deadline=SystemClock.elapsedRealtime()+150000;var ready=false;var saved=false
                while(SystemClock.elapsedRealtime()<deadline&&!ready){
                    scenario.onActivity { activity ->
                        val state=(field("mapCache").get(activity) as MapResourceCache).preparationState()
                        ready=field("routeActive").getBoolean(activity)&&state.ready&&field("routeSessionSaved").getBoolean(activity)
                        prepared=state.saved;saved=field("routeActive").getBoolean(activity)
                        @Suppress("UNCHECKED_CAST")
                        selected=field("routePoints").get(activity) as List<RealisticMapActivity.GeoPoint>
                    }
                    if(!ready)Thread.sleep(200)
                }
                assertTrue("actual routing service returned a route",saved)
                assertTrue("the entire real trip is prepared, resources=$prepared",ready)
                assertTrue(prepared>320)
            }
            // Browsing cache and renderer memory are deliberately discarded. Only the route pack remains.
            File(context.filesDir,"map-buffer").deleteRecursively()
            shell("svc wifi disable");shell("svc data disable")
            val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
            val disconnected=SystemClock.elapsedRealtime()+10000
            while(connectivity.activeNetwork!=null&&SystemClock.elapsedRealtime()<disconnected)Thread.sleep(100)
            assertNull("test device has no active internet transport",connectivity.activeNetwork)
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    field("voiceEnabled").setBoolean(activity,false)
                    field("lastAcceptedLocation").set(activity,Location("fixture").apply {
                        elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L
                    })
                    field("manualCameraUntilMs").setLong(activity,SystemClock.elapsedRealtime()+120000)
                }
                val deadline=SystemClock.elapsedRealtime()+20000;var restored=false
                while(!restored&&SystemClock.elapsedRealtime()<deadline){
                    scenario.onActivity { restored=field("routeActive").getBoolean(it)&&field("mapReady").getBoolean(it) }
                    if(!restored)Thread.sleep(100)
                }
                assertTrue("saved route and local map engine restore offline",restored)
                scenario.onActivity { assertEquals(selected,field("routePoints").get(it));assertFalse(field("networkAvailable").getBoolean(it)) }
                val geometry=NavigationRoutePosition(selected.map{RouteGeometry.Point(it.lat,it.lon)})
                val index=geometry.cumulative.indexOfFirst{it>geometry.length*.8}.coerceAtLeast(1)
                assertTrue("test viewport is past the old look-ahead",geometry.cumulative[index]>10000)
                val point=selected[index]
                js(scenario,"GPS3D.setDarkTheme(true);GPS3D.setLocation(${point.lon},${point.lat},0,0);GPS3D.follow(${point.lon},${point.lat},0,18.65,65,1);true")
                val renderEnd=SystemClock.elapsedRealtime()+15000;var state=JSONObject()
                while(SystemClock.elapsedRealtime()<renderEnd){
                    val data=js(scenario,"GPS3D.mapState(true)");if(data!="null")state=JSONObject(data)
                    if(state.optBoolean("tilesLoaded")&&state.optInt("sourceMaxZoom")==16&&
                        state.optInt("roadData")>0&&kotlin.math.abs(state.optDouble("zoom")-18.65)<.02)break
                    Thread.sleep(100)
                }
                assertTrue("saved vector map has loaded: $state",state.optBoolean("tilesLoaded"))
                assertEquals(16,state.optInt("sourceMaxZoom"))
                val diagnostic=JSONObject().put("rendered",state.optInt("rendered")).put("roadData",state.optInt("roadData"))
                    .put("failures",state.optJSONObject("failures")).put("cacheRequests",state.optInt("nativeCacheRequests"))
                assertTrue("prepared native road data must be available: $diagnostic",state.optInt("roadData")>0)
                assertTrue("worker tiles use the native cache",state.optInt("nativeCacheRequests")>0)
                assertTrue("driving buildings reuse the prepared primary vector map",state.optInt("sceneBuildings")>=5)
                assertEquals("true",js(scenario,"GPS3D.navigationState().groundRoute.features.length>1 && !!document.querySelector('.vehicle-car polygon[fill=\"#eff6fc\"]')"))
                Thread.sleep(250)
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot());val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val streets=pixels.count{c->kotlin.math.abs(Color.red(c)-66)<5&&kotlin.math.abs(Color.green(c)-78)<5&&kotlin.math.abs(Color.blue(c)-90)<5}
                val values=ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,"android-whole-route-offline.png");put(MediaStore.Downloads.MIME_TYPE,"image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DARQA")
                }
                val uri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                assertTrue("actual asphalt is visible beyond 10 km without internet, pixels=$streets",streets>100)
                println("Native whole-route offline QA passed: $prepared protected resources; ${geometry.cumulative[index].toInt()} metres; $streets asphalt pixels")
            }
        } finally {
            shell("svc wifi enable");shell("svc data enable");context.deleteFile("saved-navigation.json")
        }
    }
}
