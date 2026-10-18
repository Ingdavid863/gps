package com.david.gps3dar

import android.Manifest
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationPrecisionTest {
    @Test fun staleInitialFixAndImpossibleAccurateJumpCannotReplaceTheAcceptedPosition() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun field(name:String)=RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
                val process=RealisticMapActivity::class.java.getDeclaredMethod("processLocation",Location::class.java).apply { isAccessible=true }
                val now=SystemClock.elapsedRealtimeNanos()
                fun fix(age:Long,lon:Double=-110.0,precision:Float=6f)=Location("precision-fixture").apply {
                    latitude=23.1;longitude=lon;accuracy=precision;speed=26f;bearing=0f
                    elapsedRealtimeNanos=now-age
                }
                field("lastAcceptedLocation").set(activity,null)
                field("rawLocation").set(activity,null)
                process.invoke(activity,fix(10_000_000_000L))
                assertNull("A cached location from ten seconds ago cannot initialize driving",field("rawLocation").get(activity))
                val good=fix(1_000_000_000L)
                process.invoke(activity,good)
                assertEquals(good.elapsedRealtimeNanos,(field("lastAcceptedLocation").get(activity) as Location).elapsedRealtimeNanos)
                process.invoke(activity,fix(500_000_000L,-109.998,3f))
                process.invoke(activity,fix(100_000_000L,-109.999,100f))
                process.invoke(activity,fix(1_500_000_000L))
                val raw=field("rawLocation").get(activity) as Location
                assertEquals("Rejected fixes cannot change raw position or its heading source",good.elapsedRealtimeNanos,raw.elapsedRealtimeNanos)
                assertEquals(good.longitude,raw.longitude,0.0)
            }
        }
    }
}
