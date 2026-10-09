package com.david.gps3dar

import android.graphics.Bitmap
import android.graphics.Color
import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import android.os.SystemClock
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

/** Real WebView + native HTTP interception: a browser-only test cannot verify this cache. */
@RunWith(AndroidJUnit4::class)
class MapNetworkTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val latch=CountDownLatch(1); var result="null"
        scenario.onActivity { it.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { value->result=value;latch.countDown() } }
        assertTrue(latch.await(8,TimeUnit.SECONDS));return result
    }
    private fun waitMap(scenario: ActivityScenario<RealisticMapActivity>, zoom: Double, seconds: Int): JSONObject {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        var state=JSONObject()
        do {
            val text=evaluate(scenario,"window.GPS3D ? window.GPS3D.mapState() : null")
            if(text!="null")state=JSONObject(text)
            if(kotlin.math.abs(state.optDouble("zoom",0.0)-zoom)<.02&&state.optBoolean("tilesLoaded")&&state.optInt("rendered")>0)return state
            Thread.sleep(100)
        } while(SystemClock.elapsedRealtime()<end)
        fail("complete native viewport failed: $state");return state
    }
    @Test fun actualMapRendersOnZoomOutAndPreparedViewportSurvivesOfflineNightMode() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val packageName=instrumentation.targetContext.packageName
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val deadline=SystemClock.elapsedRealtime()+30000
            while(evaluate(scenario,"!!window.GPS3D && window.GPS3D.cameraState().ready")!="true"&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
            assertEquals("true",evaluate(scenario,"!!window.GPS3D && window.GPS3D.cameraState().ready"))
            scenario.onActivity { activity ->
                RealisticMapActivity::class.java.getDeclaredField("manualCameraUntilMs").apply {isAccessible=true}
                    .setLong(activity,SystemClock.elapsedRealtime()+120000)
            }
            var preparedCount=0
            evaluate(scenario,"window.GPS3D.setTrafficEnabled(false);window.GPS3D.setDarkTheme(false);true")
            for(zoom in listOf(17.4,14.0,12.0,10.0,6.0)) {
                evaluate(scenario,"window.GPS3D.follow(-99.133209,19.432608,0,$zoom,0,1);true")
                val state=waitMap(scenario,zoom,30)
                assertEquals("tomtom",state.getString("provider"))
                assertEquals(0,state.getJSONObject("failures").optInt("vectorTiles"))
                if(zoom==12.0)preparedCount=state.getInt("rendered")
            }
            scenario.onActivity { activity ->
                RealisticMapActivity::class.java.getDeclaredMethod("setMapNetworkAvailable",Boolean::class.javaPrimitiveType!!)
                    .apply{isAccessible=true}.invoke(activity,false)
            }
            evaluate(scenario,"window.GPS3D.setDarkTheme(true);window.GPS3D.follow(-99.133209,19.432608,0,12,0,1);true")
            val at=SystemClock.elapsedRealtime()
            val state=waitMap(scenario,12.0,8)
            assertTrue("prepared native zoom is responsive",SystemClock.elapsedRealtime()-at<4000)
            assertTrue("full coverage, not a surviving corner tile",state.getInt("rendered")>=preparedCount*.70)
            assertTrue("street geometry actually renders",state.getInt("roads")>30)
            Thread.sleep(500)
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val roadPixels=pixels.count { color ->
                    val r=Color.red(color);val g=Color.green(color);val b=Color.blue(color)
                    r in 106..189&&g in 81..154&&b in 56..109&&r>g+10&&g>b+10
                }
                val scale=instrumentation.targetContext.resources.displayMetrics.density
                assertTrue("streets visible in Android pixels, not labels on a blank map: $roadPixels",roadPixels>300*scale*scale)
                val values=ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,"android-offline-map.png")
                    put(MediaStore.Downloads.MIME_TYPE,"image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DQA")
                }
                val resolver=instrumentation.targetContext.contentResolver
                val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                requireNotNull(resolver.openOutputStream(uri)).use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        }
    }
}

