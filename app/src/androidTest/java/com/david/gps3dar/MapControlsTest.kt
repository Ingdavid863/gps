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
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MapControlsTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity -> activity.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView responds", latch.await(8, TimeUnit.SECONDS))
        return result
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
            evaluate(scenario, "window.GPS3D.setRoutes([[-99.13,19.43],[-99.135,19.44],[-99.15,19.46]],[],[]);true;")
            scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() }
            Thread.sleep(700)
            var state = JSONObject(evaluate(scenario, "window.GPS3D.cameraState()"))
            assertEquals(1, state.getInt("mode"));assertEquals(45.0, state.getDouble("pitch"), 1.0)
            scenario.onActivity { it.findViewById<ImageButton>(R.id.viewModeButton).performClick() }
            Thread.sleep(700)
            state = JSONObject(evaluate(scenario, "window.GPS3D.cameraState()"))
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
            Thread.sleep(700)
            state = JSONObject(evaluate(scenario, "window.GPS3D.cameraState()"))
            assertEquals(0, state.getInt("mode"));assertEquals(0.0, state.getDouble("pitch"), 1.0)
            evaluate(scenario, "window.GPS3D.setTrafficIntervals([{start:0,end:1,severity:'heavy'},{start:1,end:2,severity:'moderate'}]);true;")
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(Color.parseColor("#D93025"), it.findViewById<TextView>(R.id.etaText).currentTextColor) }
            evaluate(scenario, "window.GPS3D.setRouteProgress(-99.135,19.44,1);true;")
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(Color.parseColor("#F9AB00"), it.findViewById<TextView>(R.id.etaText).currentTextColor) }
        }
    }
}
