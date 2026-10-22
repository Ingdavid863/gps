package com.david.gps3dar

import android.Manifest
import android.graphics.Color
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MapControlsTest {
    @Before fun startWithoutARestoredNavigationSession() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteFile("saved-navigation.json")
    }
    private fun waitCamera(scenario: ActivityScenario<RealisticMapActivity>, mode: Int, pitch: Double): JSONObject {
        val deadline=System.currentTimeMillis()+5000
        var state=JSONObject()
        do {
            val value=evaluate(scenario,"window.GPS3D.cameraState()")
            if(value!="null")state=JSONObject(value)
            if(state.optInt("mode",-1)==mode&&kotlin.math.abs(state.optDouble("pitch",-100.0)-pitch)<=1.0&&!state.optBoolean("moving"))return state
            Thread.sleep(50)
        }while(System.currentTimeMillis()<deadline)
        fail("Camera transition must settle at mode=$mode, pitch=$pitch: $state");return state
    }
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity -> activity.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView responds", latch.await(8, TimeUnit.SECONDS))
        return result
    }
    private fun assertEtaColor(scenario: ActivityScenario<RealisticMapActivity>, expected: Int, detail: String = "") {
        // JavaScriptInterface posts to the UI thread independently of evaluateJavascript.
        // An idle UI queue alone does not mean that bridge callback has arrived yet.
        val deadline = System.currentTimeMillis() + 5000
        var actual = 0
        do {
            scenario.onActivity { actual = it.findViewById<TextView>(R.id.etaText).currentTextColor }
            if (actual == expected) return
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        assertEquals("Traffic color callback must reach the native HUD: $detail", expected, actual)
    }
    @Test fun nativeEyeCyclesAllThreeViewsAndEtaFollowsCurrentTrafficSegment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 30000
            val ready="!!window.GPS3D && window.GPS3D.cameraState().ready"
            while (evaluate(scenario, ready) != "true" && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertEquals("true", evaluate(scenario, ready))
            instrumentation.waitForIdleSync()
            val initial = waitCamera(scenario,1,65.0)
            assertEquals("Starts in driving perspective", 1, initial.getInt("mode"))
            assertEquals(65.0, initial.getDouble("pitch"), 1.0)
            // Move through overview and 2D, then verify the full cycle.
            repeat(2) { scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() } }
            Thread.sleep(700)
            evaluate(scenario, "window.GPS3D.setRoutes([[-99.13,19.43],[-99.135,19.44],[-99.15,19.46]],[],[]);true;")
            scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() }
            var state = waitCamera(scenario,1,65.0)
            assertEquals(1, state.getInt("mode"));assertEquals(65.0, state.getDouble("pitch"), 1.0)
            scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() }
            state = waitCamera(scenario,2,0.0)
            assertEquals(2, state.getInt("mode"));assertEquals(0.0, state.getDouble("pitch"), 1.0)
            assertEquals("complete route supplied",3,state.getJSONArray("route").length())
            assertFalse("overview applied immediately: $state",state.getBoolean("moving"))
            val padding = state.getJSONObject("padding")
            scenario.onActivity { activity ->
                val map = activity.findViewById<WebView>(R.id.webMapView)
                val density = activity.resources.displayMetrics.density
                val width = map.width / density;val height = map.height / density
                for (i in 0 until state.getJSONArray("route").length()) {
                    val point = state.getJSONArray("route").getJSONArray(i)
                    assertTrue("origin/destination visible; native=${width}x$height; state=$state", point.getDouble(0) in (padding.getDouble("left")-2)..(width-padding.getDouble("right")+2))
                    assertTrue("route fits above footer; native=${width}x$height; state=$state", point.getDouble(1) in (padding.getDouble("top")-2)..(height-padding.getDouble("bottom")+2))
                }
            }
            scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() }
            state = waitCamera(scenario,0,0.0)
            assertEquals(0, state.getInt("mode"));assertEquals(0.0, state.getDouble("pitch"), 1.0)
            evaluate(scenario, "window.GPS3D.setTrafficIntervals([{start:0,end:1,severity:'heavy'},{start:1,end:2,severity:'moderate'}]);true;")
            assertEtaColor(scenario, Color.parseColor("#D93025"))
            val afterProgress = evaluate(scenario, "window.GPS3D.setRouteProgress(-99.135,19.44,1);window.GPS3D.navigationState();")
            assertEtaColor(scenario, Color.parseColor("#F9AB00"), afterProgress)
        }
    }
}
