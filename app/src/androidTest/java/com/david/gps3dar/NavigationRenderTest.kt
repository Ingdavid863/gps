package com.david.gps3dar

import android.Manifest
import android.location.Location
import android.os.SystemClock
import android.view.View
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
class NavigationRenderTest {
    private fun evaluate(scenario: ActivityScenario<RealisticMapActivity>,script: String): String {
        val latch=CountDownLatch(1);var result="null"
        scenario.onActivity { it.findViewById<WebView>(R.id.webMapView).evaluateJavascript(script) { value -> result=value;latch.countDown() } }
        assertTrue(latch.await(8,TimeUnit.SECONDS));return result
    }
    private fun waitFor(scenario: ActivityScenario<RealisticMapActivity>,script: String) {
        val deadline=SystemClock.elapsedRealtime()+30000
        while(evaluate(scenario,script)!="true") {
            assertTrue("WebView condition must settle: $script",SystemClock.elapsedRealtime()<deadline)
            Thread.sleep(100)
        }
    }
    @Test fun arrowAndCameraAnimateAndEveryRouteLayerKeepsOnlyTheRemainingJourney() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} $it").close()
        }
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            waitFor(scenario,"!!window.GPS3D && GPS3D.cameraState().ready")
            scenario.onActivity { activity ->
                // Keep emulator GNSS from replacing the deterministic renderer fixtures.
                RealisticMapActivity::class.java.getDeclaredField("lastAcceptedLocation").apply { isAccessible=true }
                    .set(activity,Location("fixture").apply { elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()+120_000_000_000L })
                RealisticMapActivity::class.java.getDeclaredField("manualCameraUntilMs").apply { isAccessible=true }
                    .setLong(activity,SystemClock.elapsedRealtime()+120000)
                assertEquals(View.GONE,activity.findViewById<View>(R.id.gpsStatus).visibility)
                assertEquals(View.GONE,activity.findViewById<View>(R.id.mapCacheStatus).visibility)
            }
            evaluate(scenario,"""(() => {
                GPS3D.setTrafficPaused(true);
                window.fixtureRoute=[[-99.22,19.72],[-99.22,19.72+80/110540],[-99.22+100/(111320*Math.cos(19.72*Math.PI/180)),19.72+80/110540]];
                GPS3D.setRoutes(fixtureRoute,[],[],false);
                GPS3D.setRouteProgress(...fixtureRoute[0],0);
                GPS3D.setLocation(...fixtureRoute[0],0,8,true);
                GPS3D.follow(...fixtureRoute[0],0,17.4,0,1);
                return true;
            })()""")
            waitFor(scenario,"GPS3D.navigationState().progress>3")
            val advancing=JSONObject(evaluate(scenario,"GPS3D.navigationState()"))
            val coordinates=advancing.getJSONObject("remaining").getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            assertTrue("Completed geometry is trimmed between GNSS fixes",coordinates.getJSONArray(0).getDouble(1)>19.72)
            assertEquals("true",evaluate(scenario,"""(() => {
                const s=GPS3D.navigationState(),start=s.remaining.features[0].geometry.coordinates[0];
                return NavigationMotion.distance(s.vehicle,start)<2.5 &&
                    getComputedStyle(document.getElementById('traffic-status')).display==='none' &&
                    getComputedStyle(document.getElementById('signal-status')).display==='none' &&
                    getComputedStyle(document.querySelector('.maplibregl-ctrl-attrib-button')).display==='none';
            })()"""))
            evaluate(scenario,"""(() => {
                const p=GPS3D.navigationState().vehicle;
                GPS3D.setLocation(...p,15,8,true);GPS3D.follow(...p,15,17.4,0,1);
                window.motionSamples=[];window.motionDone=false;
                const start=performance.now();
                function sample(now) {
                    const s=GPS3D.navigationState();motionSamples.push([s.bearing,s.cameraBearing]);
                    if(now-start<1400)requestAnimationFrame(sample);else motionDone=true;
                }requestAnimationFrame(sample);return true;
            })()""")
            waitFor(scenario,"window.motionDone===true")
            assertEquals("true",evaluate(scenario,"""(() => {
                const distinct=i=>new Set(motionSamples.map(p=>p[i].toFixed(2))).size;
                return distinct(0)>5 && distinct(1)>5 && motionSamples.some(p=>p[0]>1&&p[0]<14);
            })()"""))
            // After the turn, neither traffic nor the white/blue route may restore passed streets.
            evaluate(scenario,"""(() => {
                const p=[fixtureRoute[1][0]+10/(111320*Math.cos(19.72*Math.PI/180)),fixtureRoute[1][1]];
                GPS3D.setRouteProgress(...p,1);GPS3D.setLocation(...p,90,0,true);
                GPS3D.setTrafficIntervals([{start:0,end:2,severity:'slow'}]);
                const s=GPS3D.navigationState();
                window.trafficTrimmed=s.traffic.features.length>0 &&
                    s.traffic.features.every(f=>f.geometry.coordinates.every(p=>p[0]>fixtureRoute[1][0]));
                return true;
            })()""")
            assertEquals("true",evaluate(scenario,"window.trafficTrimmed"))
            evaluate(scenario,"GPS3D.setRoutes(fixtureRoute.map(p=>p.slice()),[],[],false);GPS3D.setViewMode(1);GPS3D.setDarkTheme(true);GPS3D.setViewMode(2);GPS3D.setViewMode(0);true")
            val restored=JSONObject(evaluate(scenario,"GPS3D.navigationState()"))
            val remaining=restored.getJSONObject("remaining").getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            assertEquals(2,remaining.length())
            assertTrue("Resync, overview and theme keep past streets removed",remaining.getJSONArray(0).getDouble(0)>-99.22)
            assertTrue(restored.getDouble("progress")>=89.9)
            val target=remaining.getJSONArray(remaining.length()-1)
            evaluate(scenario,"GPS3D.setRouteProgress(${target.getDouble(0)},${target.getDouble(1)},1);true")
            val arrived=JSONObject(evaluate(scenario,"GPS3D.navigationState()"))
            assertEquals("No route stroke after arrival",0,arrived.getJSONObject("remaining").getJSONArray("features").length())
            assertEquals(0,arrived.getJSONObject("traffic").getJSONArray("features").length())
        }
    }
}
