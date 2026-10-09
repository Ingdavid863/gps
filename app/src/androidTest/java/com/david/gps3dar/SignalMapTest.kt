package com.david.gps3dar

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SignalMapTest {
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
            val latch = CountDownLatch(1); var result = ""
            scenario.onActivity { a -> a.findViewById<WebView>(R.id.webMapView).evaluateJavascript("""
                (() => { GPS3D.setSignals([[-99.13,19.43]], 'Semáforo cercano: 100 m · OSM\nColor: sin datos en vivo');
                return document.getElementById('signal-status').textContent; })()
            """.trimIndent()) { result = it; latch.countDown() } }
            assertTrue(latch.await(5, TimeUnit.SECONDS)); assertTrue(result.contains("sin datos en vivo")); assertFalse(result.contains("DEMO"))
            instrumentation.waitForIdleSync()
        }
    }
}
