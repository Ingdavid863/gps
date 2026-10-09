package com.david.gps3dar

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual Android ActivityResult handoff. The emulator has no physical VPS camera. */
@RunWith(AndroidJUnit4::class)
class WalkingHandoffTest {
    @Test fun vrReceivesTheDestinationAndReturningToMapKeepsThePedestrianRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = RouteGeometry.Point(19.7202, -99.2198)
        val walking = WalkingRoute(target, listOf(RouteGeometry.Point(19.72, -99.22),
            RouteGeometry.Point(19.7202, -99.22), target),
            listOf(WalkingRoute.Step(target, "Llegaste a tu destino", "ARRIVE")), 35.0)
        val cache = File(instrumentation.targetContext.filesDir, ArNavigationActivity.ROUTE_FILE)
        cache.writeText(walking.encode())
        val intercepted = CountDownLatch(1)
        var received: Intent? = null
        var launches = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != ArNavigationActivity::class.java.name) return null
                launches++
                received = intent; intercepted.countDown()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()
                    .putExtra(ArNavigationActivity.EXTRA_WALKING_ROUTE, true)
                    .putExtra(WalkingRoute.EXTRA_LAT, target.lat).putExtra(WalkingRoute.EXTRA_LON, target.lon))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    RealisticMapActivity::class.java.getDeclaredField("routeDestination").apply { isAccessible = true }
                        .set(activity, RealisticMapActivity.GeoPoint(target.lat, target.lon))
                    activity.findViewById<TextView>(R.id.modeButton).performClick()
                }
                assertTrue("VR must receive an actual destination", intercepted.await(5, TimeUnit.SECONDS))
                assertEquals(target.lat, received!!.getDoubleExtra(WalkingRoute.EXTRA_LAT, 0.0), .0000001)
                assertEquals(target.lon, received!!.getDoubleExtra(WalkingRoute.EXTRA_LON, 0.0), .0000001)
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + 5000
                var pedestrian = false
                do {
                    scenario.onActivity { activity ->
                        pedestrian = RealisticMapActivity::class.java.getDeclaredField("pedestrianRoute")
                            .apply { isAccessible = true }.getBoolean(activity)
                    }
                    if (!pedestrian) Thread.sleep(100)
                } while (!pedestrian && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Map must keep the returned walking route", pedestrian)
                scenario.onActivity { activity ->
                    assertEquals("Caminar", activity.findViewById<TextView>(R.id.avoidTollsButton).text.toString())
                    assertTrue(activity.findViewById<TextView>(R.id.etaText).text.toString().startsWith("A pie"))
                    assertEquals(3, (RealisticMapActivity::class.java.getDeclaredField("routePoints")
                        .apply { isAccessible = true }.get(activity) as List<*>).size)
                    activity.findViewById<TextView>(R.id.modeButton).performClick()
                    assertEquals("Reopening AR must preserve the exact shared walking route",
                        walking, WalkingRoute.decode(cache.readText()))
                }
                assertEquals("Map/camera switching must work twice", 2, launches)
            }
        } finally { instrumentation.removeMonitor(monitor); cache.delete() }
    }
}
