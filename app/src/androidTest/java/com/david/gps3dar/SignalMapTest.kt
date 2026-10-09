package com.david.gps3dar

import android.os.SystemClock
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
class SignalMapTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>, script: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { a -> a.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView responds", latch.await(8, TimeUnit.SECONDS))
        return result
    }
    @Test fun realMapRendersSignalLocationsWithUnknownColorRatherThanAColoredCycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { a -> ready = RealisticMapActivity::class.java.getDeclaredField("mapReady").apply { isAccessible = true }.getBoolean(a) }
                if (!ready) Thread.sleep(200)
            }
            assertTrue(ready)
            evaluate(scenario, "GPS3D.follow(-99.13,19.43,0,16,0,1); GPS3D.setSignals([[-99.1304,19.431],[-99.1296,19.4289]], 'Ubicaciones de prueba QA · Color: sin datos en vivo'); true")
            val renderedDeadline = SystemClock.elapsedRealtime() + 10_000
            var state = JSONObject(evaluate(scenario, "GPS3D.signalState()"))
            while (state.getInt("rendered") == 0 && SystemClock.elapsedRealtime() < renderedDeadline) {
                Thread.sleep(200); state = JSONObject(evaluate(scenario, "GPS3D.signalState()"))
            }
            assertTrue("Signal source, style and image are installed", state.getBoolean("source") && state.getBoolean("layer") && state.getBoolean("icon"))
            assertTrue("A real map symbol must be drawn at the supplied coordinates: $state", state.getInt("rendered") > 0)
            assertTrue(state.getJSONArray("coordinates").length() >= 2)
            assertTrue(state.getString("status").contains("sin datos en vivo"))
            evaluate(scenario, "GPS3D.setDarkTheme(true); true")
            Thread.sleep(1200)
            state = JSONObject(evaluate(scenario, "GPS3D.signalState()"))
            assertTrue("Changing theme preserves the signal overlay", state.getBoolean("layer") && state.getBoolean("icon"))
            instrumentation.waitForIdleSync()
        }
    }
}
