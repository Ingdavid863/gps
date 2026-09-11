package com.david.gps3dar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.util.Locale

/**
 * Native AR entry point for GPS3D.
 *
 * SceneView owns the ARCore camera/session and Filament renderer. Geospatial
 * pose data is read from Earth.cameraGeospatialPose and surfaced both on-screen
 * and in Logcat under the tag GPS3D_AR.
 */
class ArNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GPS3D_AR"
        private const val POSE_LOG_INTERVAL_MS = 500L
    }

    private lateinit var sceneHost: ComposeView
    private lateinit var statusText: TextView
    private var sceneStarted = false
    private var lastPoseLogAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cameraGranted = granted[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        val fineGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted) {
            statusText.text = "Se necesita permiso de cámara para iniciar AR"
            return@registerForActivityResult
        }

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
            setBackgroundColor(0xB30B2235.toInt())
            textSize = 12f
            gravity = Gravity.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(12)
                topMargin = dp(16)
                rightMargin = dp(92)
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
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && (fineGranted || !hasArCoreApiKey())) {
            startArScene(enableGeospatial = fineGranted && hasArCoreApiKey())
            return
        }

        val permissions = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (hasArCoreApiKey() && !fineGranted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        statusText.text = if (hasArCoreApiKey()) {
            "Solicitando cámara y ubicación precisa para AR Geospatial…"
        } else {
            "Solicitando cámara para AR…"
        }
        permissionLauncher.launch(permissions.toTypedArray())
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
                                statusText.text = "ARCore + Geospatial · buscando VPS/GPS…\nMira edificios y la calle alrededor."
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
                                "AR activo · falta ARCORE_API_KEY en esta compilación"
                            }
                        }
                    }
                },
                onSessionUpdated = { session, _ ->
                    if (!enableGeospatial) return@ARSceneView

                    val earth = session.earth
                    if (earth == null) {
                        statusText.post {
                            statusText.text = "Geospatial · esperando Earth/VPS…"
                        }
                        return@ARSceneView
                    }

                    if (earth.trackingState != TrackingState.TRACKING) {
                        statusText.post {
                            statusText.text = "Geospatial · localizando…\nMueve el teléfono y apunta hacia edificios/calles."
                        }
                        return@ARSceneView
                    }

                    val pose = earth.cameraGeospatialPose
                    val text = String.format(
                        Locale.US,
                        "GEOSPATIAL ✓  ±%.1f m\nLat %.6f\nLon %.6f\nAlt %.1f m · Heading %.1f°",
                        pose.horizontalAccuracy,
                        pose.latitude,
                        pose.longitude,
                        pose.altitude,
                        pose.heading
                    )
                    statusText.post { statusText.text = text }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPoseLogAt >= POSE_LOG_INTERVAL_MS) {
                        lastPoseLogAt = now
                        Log.d(
                            TAG,
                            String.format(
                                Locale.US,
                                "Latitud: %.7f, Longitud: %.7f, Altitud: %.2f m, Heading: %.2f°, Precisión H: %.2f m, Precisión V: %.2f m",
                                pose.latitude,
                                pose.longitude,
                                pose.altitude,
                                pose.heading,
                                pose.horizontalAccuracy,
                                pose.verticalAccuracy
                            )
                        )
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
