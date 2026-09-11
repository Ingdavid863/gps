package com.david.gps3dar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView

/**
 * Native AR entry point for GPS3D.
 *
 * SceneView owns the ARCore camera/session and Filament renderer. The existing
 * MapLibre navigation screen is kept as a fallback while the geospatial route
 * overlay is implemented in the next phase.
 */
class ArNavigationActivity : AppCompatActivity() {

    private lateinit var sceneHost: ComposeView
    private lateinit var statusText: TextView
    private var sceneStarted = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val fineGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        startArScene(enableGeospatial = fineGranted && hasArCoreApiKey())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        prepareArSession()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        sceneHost = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(sceneHost)

        statusText = TextView(this).apply {
            text = "Preparando ARCore…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x990B2235.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(12)
                topMargin = dp(16)
            }
        }
        root.addView(statusText)

        val mapButton = TextView(this).apply {
            text = "MAPA 3D"
            setTextColor(Color.rgb(25, 72, 126))
            setBackgroundColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                rightMargin = dp(12)
                topMargin = dp(16)
            }
            setOnClickListener {
                startActivity(Intent(this@ArNavigationActivity, RealisticMapActivity::class.java))
            }
        }
        root.addView(mapButton)

        setContentView(root)
    }

    private fun prepareArSession() {
        if (!hasArCoreApiKey()) {
            // Camera AR works without a cloud credential. Geospatial, Terrain /
            // Rooftop anchors and Streetscape require the ARCore Cloud API key.
            startArScene(enableGeospatial = false)
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startArScene(enableGeospatial = true)
        } else {
            statusText.text = "AR listo · solicitando ubicación precisa para Geospatial…"
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startArScene(enableGeospatial: Boolean) {
        if (sceneStarted) return
        sceneStarted = true

        sceneHost.setContent {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                planeRenderer = false,
                sessionConfiguration = { session, config ->
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

                    config.depthMode = if (
                        session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    ) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }

                    if (enableGeospatial) {
                        if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                            config.geospatialMode = Config.GeospatialMode.ENABLED
                            config.streetscapeGeometryMode =
                                Config.StreetscapeGeometryMode.ENABLED
                            statusText.post {
                                statusText.text = "ARCore + Geospatial · inicializando VPS/GPS…"
                            }
                        } else {
                            statusText.post {
                                statusText.text = "AR activo · este dispositivo no soporta Geospatial"
                            }
                        }
                    } else {
                        statusText.post {
                            statusText.text = if (hasArCoreApiKey()) {
                                "AR activo · ubicación precisa no autorizada; Geospatial desactivado"
                            } else {
                                "AR activo · agrega ARCORE_API_KEY para activar Geospatial"
                            }
                        }
                    }
                }
            )
        }
    }

    private fun hasArCoreApiKey(): Boolean {
        return runCatching {
            val info = packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
            info.metaData
                ?.getString("com.google.android.ar.API_KEY")
                ?.trim()
                .orEmpty()
                .isNotBlank()
        }.getOrDefault(false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
