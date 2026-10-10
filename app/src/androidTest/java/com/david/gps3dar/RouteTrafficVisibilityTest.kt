package com.david.gps3dar

import android.Manifest
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RouteTrafficVisibilityTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>,script: String): String {
        val latch=CountDownLatch(1);var result="null"
        scenario.onActivity { it.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { value -> result=value;latch.countDown() } }
        assertTrue(latch.await(8,TimeUnit.SECONDS));return result
    }
    private fun waitFor(scenario: ActivityScenario<RealisticMapActivity>,condition: (JSONObject)->Boolean): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+45000
        do {
            val text=evaluate(scenario,"window.GPS3D ? window.GPS3D.trafficState() : null")
            if(text!="null") { val state=JSONObject(text);if(condition(state))return state }
            Thread.sleep(100)
        } while(SystemClock.elapsedRealtime()<deadline)
        throw AssertionError("Traffic state did not settle")
    }
    @Test fun routeUsesTheActualStreetFeedAndOnlyRouteColorsRemainUntilItIsCleared() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val deadline=SystemClock.elapsedRealtime()+30000
            while(evaluate(scenario,"!!window.GPS3D && window.GPS3D.cameraState().ready")!="true" &&
                SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
            scenario.onActivity { activity ->
                RealisticMapActivity::class.java.getDeclaredField("manualCameraUntilMs").apply { isAccessible=true }
                    .setLong(activity,SystemClock.elapsedRealtime()+120000)
            }
            evaluate(scenario,"GPS3D.setLocation(-99.219,19.709,0,0);GPS3D.follow(-99.219,19.709,0,15.8,0,1);true")
            waitFor(scenario) { it.optString("status")=="live" && it.optInt("rendered")>3 }
            val coordinates=evaluate(scenario,"""(() => {
                for(const f of GPS3D.trafficSourceFeatures()) {
                    if(f.properties.traffic_level==null)continue;
                    const lines=f.geometry.type==='LineString'?[f.geometry.coordinates]:f.geometry.coordinates;
                    for(const line of lines)if(line.length>=2 && NavigationMotion.distance(line[0],line[line.length-1])>40)return line;
                }return null;
            })()""")
            assertNotEquals("Real traffic must contain routeable geometry","null",coordinates)
            assertTrue(JSONArray(coordinates).length()>=2)
            evaluate(scenario,"GPS3D.setRoutes($coordinates,[],[],false);true")
            val route=waitFor(scenario) { it.optInt("routeRendered")>0 }
            assertTrue(route.getBoolean("routeActive"));assertFalse(route.getBoolean("areaVisible"))
            assertEquals(0,route.getInt("rendered"))
            val routeFeatures=route.getJSONArray("routeFeatures")
            for(i in 0 until routeFeatures.length()) {
                val properties=routeFeatures.getJSONObject(i).getJSONObject("properties")
                assertNotEquals("unknown",properties.getString("severity"))
                assertTrue(properties.getString("color").startsWith("#"))
            }
            // A style/theme switch must not accidentally bring all street colors back.
            evaluate(scenario,"GPS3D.setDarkTheme(true);true")
            assertFalse(waitFor(scenario) { it.optInt("routeRendered")>0 }.getBoolean("areaVisible"))
            // Capture the transition atomically. The emulator is actually online, so its
            // native connectivity observer may restore online state between JS evaluations.
            val offline=JSONObject(evaluate(scenario,"GPS3D.setNetworkAvailable(false);GPS3D.trafficState()"))
            assertEquals(0,offline.getInt("routeRendered"));assertEquals("offline",offline.getString("status"))
            evaluate(scenario,"GPS3D.setNetworkAvailable(true);GPS3D.setRoutes([],[],[]);true")
            val restored=waitFor(scenario) { it.optString("status")=="live" && it.optInt("rendered")>3 }
            assertFalse(restored.getBoolean("routeActive"));assertTrue(restored.getBoolean("areaVisible"))
        }
    }
}
