package com.david.gps3dar

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Call
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedDestinationIntentTest {
    @Test fun sharedAndNavigationIntentsReplaceDestinationsWithoutSpecialAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val forbidden = setOf("android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS", "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SYSTEM_ALERT_WINDOW")
        assertTrue("Merged APK must not request screen access, SMS or package installation",
            info.requestedPermissions.orEmpty().none { it in forbidden })
        assertTrue("Merged APK must not contain a screen-reading service",
            info.services.orEmpty().none { it.permission in forbidden })
        val first = Intent(context, RealisticMapActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://www.google.com/maps/dir/?api=1&destination=19.7356,-99.2060&travelmode=walking")
        ActivityScenario.launch<RealisticMapActivity>(first).use { scenario ->
            fun assertDestination(lat: Double, lon: Double, walking: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                var matched = false
                do {
                    scenario.onActivity { activity ->
                        val selected = RealisticMapActivity::class.java.getDeclaredField("selectedMapPoint")
                            .apply { isAccessible = true }.get(activity) as? RealisticMapActivity.SearchResult
                        val pending = RealisticMapActivity::class.java.getDeclaredField("pendingExternalDestination")
                            .apply { isAccessible = true }.get(activity) as? RealisticMapActivity.GeoPoint
                        val call = RealisticMapActivity::class.java.getDeclaredField("routeCall")
                            .apply { isAccessible = true }.get(activity) as? Call
                        // Starting a route clears its preview pin. Verify the pending
                        // destination or the actual request sent to the routing provider
                        // as well, without waiting on the live service's response.
                        val endpoint = call?.request()?.url?.pathSegments?.joinToString("/")
                            ?.let { Regex("([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)").findAll(it).lastOrNull() }
                        val requested = endpoint?.let { SharedDestination.coordinates(it.value) }
                        val received = SharedDestination.parse(activity.intent.data?.toString()
                            ?: activity.intent.getStringExtra(Intent.EXTRA_TEXT))?.point
                        matched = received?.lat == lat && received.lon == lon && (
                            (selected?.lat == lat && selected.lon == lon) ||
                            (pending?.lat == lat && pending.lon == lon) ||
                            (requested?.lat == lat && requested.lon == lon))
                        if (matched) {
                            assertEquals(walking, RealisticMapActivity::class.java.getDeclaredField("pedestrianRoute")
                                .apply { isAccessible = true }.getBoolean(activity))
                        }
                    }
                    if (!matched) Thread.sleep(100)
                } while (!matched && SystemClock.elapsedRealtime() < deadline)
                assertTrue("The actual Android intent must replace the selected destination", matched)
            }
            assertDestination(19.7356, -99.2060, true)
            context.startActivity(Intent(context, RealisticMapActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("google.navigation:q=19.7301,-99.2103&mode=d"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            assertDestination(19.7301, -99.2103, false)
            context.startActivity(Intent(context, RealisticMapActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("geo:0,0?q=19.7402,-99.2151"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            assertDestination(19.7402, -99.2151, false)
        }
    }
}
