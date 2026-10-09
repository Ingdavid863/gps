package com.david.gps3dar

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/** Runs the real camera HUD on Android. No physical-world positioning is claimed by this test. */
class ArHudTest {
    @get:Rule val compose = createComposeRule()

    @Test fun destinationFlagAndLeftRightInstructionsRemainVisible() {
        val maneuver = mutableStateOf("TURN_LEFT")
        val indicator = mutableStateOf(ArDestinationIndicator(.5f, .36f))
        compose.setContent {
            ARNavigationScreen(speedKmh = 4, speedLimitKmh = null,
                distanceText = "A pie · 106 m · quedan 3.8 km",
                instructionText = "Gira a la izquierda en Avenida Ortiz",
                roadText = "Teoloyucan · 46 min", arStatus = "Ruta continua · alineación manual",
                voiceEnabled = true, trafficEnabled = false, walkingMode = true, routeActive = true,
                maneuver = maneuver.value, destinationIndicator = indicator.value,
                destinationDistance = "3.8 km", onOpenMap = {}, onSearch = {},
                onToggleVoice = {}, onToggleTraffic = {}, onReportHazard = {}, onReportPolice = {},
                arContent = { Box(Modifier.fillMaxSize().background(Color(0xFF50655C))) })
        }
        compose.onNodeWithText("Destino · 3.8 km").assertIsDisplayed()
        compose.onNodeWithText("↰").assertIsDisplayed()
        compose.onNodeWithText("A pie · 106 m · quedan 3.8 km").assertIsDisplayed()
        saveScreenshot("ar-hud-destination.png")
        compose.runOnIdle {
            maneuver.value = "TURN_RIGHT"
            indicator.value = ArDestinationIndicator(.88f, .37f, "Gira el teléfono →")
        }
        compose.onNodeWithText("↱").assertIsDisplayed()
        compose.onNodeWithText("Gira el teléfono →").assertIsDisplayed()
        saveScreenshot("ar-hud-offscreen.png")
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/GPS3DARQA")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)!!.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
