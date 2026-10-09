package com.david.gps3dar

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MapTrafficTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { it.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { value -> result=value; latch.countDown() } }
        assertTrue(latch.await(8,TimeUnit.SECONDS)); return result
    }
    private fun waitTraffic(scenario: ActivityScenario<RealisticMapActivity>): JSONObject {
        val deadline = SystemClock.elapsedRealtime()+45000
        do {
            val result=evaluate(scenario,"window.GPS3D ? window.GPS3D.trafficState() : null")
            if(result!="null") {
                val state=JSONObject(result)
                if(state.optString("status")=="live"&&state.optInt("rendered")>3) return state
            }
            Thread.sleep(100)
        } while(SystemClock.elapsedRealtime()<deadline)
        fail("Live traffic did not render without a route"); return JSONObject()
    }
    @Test fun streetTrafficRendersWithoutDestinationInActualAndroidWebView() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assertTrue("A real pixel comparison needs the Android display awake",
            instrumentation.targetContext.getSystemService(PowerManager::class.java).isInteractive)
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $it").close()
        }
        fun save(name: String, bitmap: Bitmap) {
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name)
                put(MediaStore.Downloads.MIME_TYPE,"image/png")
                put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DQA")
            }
            val resolver=instrumentation.targetContext.contentResolver
            val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            requireNotNull(resolver.openOutputStream(uri)).use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val deadline=SystemClock.elapsedRealtime()+30000
            while(evaluate(scenario,"!!window.GPS3D && window.GPS3D.cameraState().ready")!="true"&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
            scenario.onActivity { activity ->
                RealisticMapActivity::class.java.getDeclaredField("manualCameraUntilMs").apply {isAccessible=true}
                    .setLong(activity,SystemClock.elapsedRealtime()+120000)
            }
            evaluate(scenario,"window.GPS3D.setLocation(-99.219,19.709,0,0);window.GPS3D.follow(-99.219,19.709,0,15.8,0,1);true")
            val day=waitTraffic(scenario)
            assertFalse(day.getBoolean("routeActive"))
            evaluate(scenario,"window.GPS3D.setDarkTheme(true);true")
            val night=waitTraffic(scenario)
            assertTrue("Real traffic records determine the colors: $night",night.optInt("rendered")>3)
            Thread.sleep(500)
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            save("android-street-traffic.png", bitmap)
            val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            fun trafficColors(pixels: IntArray) = pixels.count { color ->
                val r=android.graphics.Color.red(color);val g=android.graphics.Color.green(color);val b=android.graphics.Color.blue(color)
                (g>100&&g>r*1.45&&g>b*1.15&&r<90)||
                    (r>100&&r>b*1.8&&g<220&&(r>g*1.35||(r>210&&g>120)))
            }
            val colored=trafficColors(colors)
            evaluate(scenario,"window.GPS3D.setTrafficEnabled(false);true")
            Thread.sleep(500)
            assertTrue("The display must stay awake until the baseline is captured",
                instrumentation.targetContext.getSystemService(PowerManager::class.java).isInteractive)
            val baseline=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            save("android-traffic-disabled.png", baseline)
            val baseColors=IntArray(baseline.width*baseline.height)
            baseline.getPixels(baseColors,0,baseline.width,0,0,baseline.width,baseline.height)
            val additional=colored-trafficColors(baseColors)
            baseline.recycle()
            assertTrue("Android traffic pixels must exceed the marker/background alone: $additional",additional>300)
            evaluate(scenario,"window.GPS3D.setTrafficEnabled(true);true")
            waitTraffic(scenario)
            bitmap.recycle()
            evaluate(scenario,"window.GPS3D.setNetworkAvailable(false);true")
            val offline=JSONObject(evaluate(scenario,"window.GPS3D.trafficState()"))
            assertEquals("offline",offline.getString("status"));assertEquals(0,offline.getInt("rendered"))
        }
    }
}
