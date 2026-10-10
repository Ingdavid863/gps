package com.david.gps3dar

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.location.Location
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

@RunWith(AndroidJUnit4::class)
class NavigationStateTest {
    private val origin=RouteGeometry.Point(19.72,-99.22)
    private fun p(e: Double,n: Double)=ArRouteGeometry.fromOffset(origin,e,-n)
    private fun field(name: String)=RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun activate(activity: RealisticMapActivity, points: List<RouteGeometry.Point>) {
        val target=points.last()
        val option=RealisticMapActivity.RouteOption(points.map { RealisticMapActivity.GeoPoint(it.lat,it.lon) },
            listOf(RealisticMapActivity.NavStep(points[1].lat,points[1].lon,"Gira a la derecha","↱",1,"TURN_RIGHT"),
                RealisticMapActivity.NavStep(target.lat,target.lon,"Llegaste","🏁",points.lastIndex,"ARRIVE")),100.0,180.0)
        field("routeDestination").set(activity,RealisticMapActivity.GeoPoint(target.lat,target.lon))
        field("routeAlternatives").set(activity,listOf(option))
        RealisticMapActivity::class.java.getDeclaredMethod("activateRoute",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .apply { isAccessible=true }.invoke(activity,0,false)
    }
    @Test fun returningFromWalkingArRestoresDrivingGeometryLabelsAndTollControls() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val points=listOf(origin,p(0.0,80.0),p(100.0,80.0)); val target=points.last()
        val walking=WalkingRoute(target,listOf(origin,p(20.0,40.0),target),
            listOf(WalkingRoute.Step(target,"Llegaste","ARRIVE")),140.0)
        val cache=File(instrumentation.targetContext.filesDir,ArNavigationActivity.ROUTE_FILE)
        cache.writeText(walking.encode())
        val launched=CountDownLatch(1)
        val monitor=object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.component?.className!=ArNavigationActivity::class.java.name)return null
                launched.countDown()
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent()
                    .putExtra(ArNavigationActivity.EXTRA_WALKING_ROUTE,true)
                    .putExtra(WalkingRoute.EXTRA_LAT,target.lat).putExtra(WalkingRoute.EXTRA_LON,target.lon))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activate(activity,points);activity.findViewById<TextView>(R.id.modeButton).performClick() }
                assertTrue(launched.await(5,TimeUnit.SECONDS));instrumentation.waitForIdleSync()
                val deadline=SystemClock.elapsedRealtime()+5000
                var pending=true
                do {
                    scenario.onActivity { pending=field("arDestination").get(it)!=null }
                    if(pending)Thread.sleep(100)
                } while(pending && SystemClock.elapsedRealtime()<deadline)
                scenario.onActivity { activity ->
                    assertFalse("AR must not overwrite car routing",field("pedestrianRoute").getBoolean(activity))
                    assertNull("AR result has been consumed",field("arDestination").get(activity))
                    assertFalse(activity.findViewById<TextView>(R.id.etaText).text.toString().startsWith("A pie"))
                    assertTrue(activity.findViewById<TextView>(R.id.avoidTollsButton).isEnabled)
                    assertEquals(points.map { RealisticMapActivity.GeoPoint(it.lat,it.lon) },field("routePoints").get(activity))
                }
            }
        } finally { instrumentation.removeMonitor(monitor);cache.delete() }
    }
    @Test fun freshLocationAtTheCornerHasZeroTurnDistanceAndDoesNotTrailAfterTheTurn() {
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val points=listOf(origin,p(0.0,80.0),p(100.0,80.0));activate(activity,points)
                val method=RealisticMapActivity::class.java.getDeclaredMethod("processLocation",Location::class.java).apply { isAccessible=true }
                fun fix(point: RouteGeometry.Point,bearing: Float) {
                    method.invoke(activity,Location("gps").apply {
                        latitude=point.lat;longitude=point.lon;accuracy=3f;speed=4f;this.bearing=bearing
                        time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    })
                }
                fix(p(0.0,75.0),0f)
                val distance=activity.findViewById<TextView>(R.id.turnDistance).text.toString()
                assertTrue("At 5 m before the turn: $distance",distance in listOf("En 4 m","En 5 m","En 6 m"))
                fix(p(0.0,80.0),0f)
                assertEquals("En 0 m",activity.findViewById<TextView>(R.id.turnDistance).text.toString())
                fix(p(5.0,80.0),90f)
                assertTrue(activity.findViewById<TextView>(R.id.instruction).text.toString().contains("destino"))
                val shown=field("displayLocation").get(activity) as Location
                assertTrue(RouteGeometry.distance(RouteGeometry.Point(shown.latitude,shown.longitude),p(5.0,80.0))<2.0)
            }
        }
    }
}
