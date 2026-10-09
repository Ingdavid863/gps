package com.david.gps3dar

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real OS service activation, accessible external fixture, Android Sharesheet and Activity receipt. */
@RunWith(AndroidJUnit4::class)
class AutomaticRouteTest {
    @Test fun enablingOnceTransfersTwoDifferentDestinationsAndHandlesActiveNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }
        val oldServices = shell("settings get secure enabled_accessibility_services").trim()
        val oldEnabled = shell("settings get secure accessibility_enabled").trim()
        val prefs = AutoRouteShareService.prefs(context)
        prefs.edit().clear().putBoolean("enabled", true).commit()
        try {
            assertTrue("CI must install the observed-controls fixture", shell("pm path com.google.android.apps.maps").contains("package:"))
            shell("settings put secure enabled_accessibility_services com.david.gps3dar/com.david.gps3dar.AutoRouteShareService")
            shell("settings put secure accessibility_enabled 1")
            Thread.sleep(700)
            val source = "https://www.google.com/maps/dir/?api=1&destination=19.7356,-99.2060&travelmode=driving"
            val second = "https://www.google.com/maps/dir/?api=1&destination=19.7301,-99.2103&travelmode=driving"
            fun launchAndWait(title: String, url: String, navigation: Boolean): RealisticMapActivity {
                context.startActivity(Intent().setClassName(AutoShareFlow.MAPS, "com.david.gps3dar.mapsfixture.MapsFixtureActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("destination", title).putExtra("url", url).putExtra("navigation", navigation))
                val deadline = SystemClock.elapsedRealtime() + 25_000
                var received: RealisticMapActivity? = null
                while (SystemClock.elapsedRealtime() < deadline && received == null) {
                    instrumentation.runOnMainSync {
                        received = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<RealisticMapActivity>().firstOrNull {
                                it.intent.action == Intent.ACTION_SEND && it.intent.getStringExtra(Intent.EXTRA_TEXT) == url
                            }
                    }
                    if (received == null) Thread.sleep(200)
                }
                assertNotNull("Accessibility must send the actual URL through the real chooser: " + prefs.getString("status", ""), received)
                return received!!
            }
            val first = launchAndWait("Parque, Privadas del Valle Manzana 002", source, false)
            val firstSignature = prefs.getString("last_signature", "")!!
            assertEquals(64, firstSignature.length)
            instrumentation.runOnMainSync {
                RealisticMapActivity::class.java.getDeclaredField("pedestrianRoute").apply { isAccessible = true }.setBoolean(first, true)
            }
            Thread.sleep(6500)
            val received = launchAndWait("Otro destino, Huehuetoca calle 12", second, true)
            assertNotEquals(firstSignature, prefs.getString("last_signature", ""))
            instrumentation.runOnMainSync {
                assertFalse("Incoming driving destination must replace a previous walking mode",
                    RealisticMapActivity::class.java.getDeclaredField("pedestrianRoute").apply { isAccessible = true }.getBoolean(received))
            }
            val output = File("/sdcard/Download/GPS3DQA").apply { mkdirs() }
            File(output, "automatic-route-result.json").writeText("""{"enabled_once":true,"real_android_share_receipts":2,"active_navigation":true,"walking_reset":true,"maps_source":"observed-controls QA fixture, not a live Google Maps build"}""")
        } finally {
            prefs.edit().putBoolean("enabled", false).commit()
            if (oldServices == "null" || oldServices.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            shell("settings put secure accessibility_enabled ${if (oldEnabled == "1") "1" else "0"}")
        }
    }
}
