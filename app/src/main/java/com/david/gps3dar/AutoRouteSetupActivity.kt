package com.david.gps3dar

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AutoRouteSetupActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 36); setBackgroundColor(Color.WHITE) }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(23, 33, 43)); setPadding(0, 12, 0, 20); body.addView(this)
        }
        text("Destinos automáticos desde Maps", 25f)
        text("Configúralo una vez. Cuando Google Maps abra una ruta nueva, el asistente pulsará Compartir indicaciones y elegirá GPS3D por ti.", 18f)
        text("En DiDi selecciona Google Maps como navegador. GPS3D recibirá el destino y calculará su propia ruta. La lista de navegadores de DiDi se conserva.", 17f)
        status = text("", 17f)
        toggle = Button(this).apply { body.addView(this); setOnClickListener { toggleSharing() } }
        Button(this).apply {
            text = "Abrir permiso de Accesibilidad"; body.addView(this)
            setOnClickListener { openAccessibility() }
        }
        Button(this).apply {
            text = "Reenviar la próxima ruta"; body.addView(this)
            setOnClickListener { AutoRouteShareService.routeStopped(this@AutoRouteSetupActivity); refresh() }
        }
        text("El asistente procesa los controles visibles de Google Maps y el menú de compartir de Android. No lee DiDi ni otras aplicaciones, no almacena pantallas y no envía sus textos a un servidor. Solo actúa sobre los botones de navegación y envío a GPS3D.", 16f)
        text("Si tu versión de Maps cambia esos botones, el envío puede detenerse. Puedes usar Compartir indicaciones manualmente y consultar aquí el estado.", 16f)
        text("Si Android bloquea el permiso por instalar un APK: abre Información de la app → ⋮ → Permitir ajustes restringidos; después regresa a Accesibilidad.", 16f)
        Button(this).apply { text = "Volver al mapa"; body.addView(this); setOnClickListener { finish() } }
        setContentView(ScrollView(this).apply { addView(body) })
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        val enabled = AutoRouteShareService.enabled(this)
        val authorized = AutoRouteShareService.authorized(this)
        toggle.text = if (enabled) "Pausar envío automático" else "Activar envío automático"
        status.text = when {
            !enabled -> "Envío automático: pausado"
            !authorized -> "Falta habilitar GPS3D: envío automático de rutas en Accesibilidad"
            else -> "Envío automático: activo\n" + AutoRouteShareService.prefs(this).getString("status", "Esperando una ruta nueva en Google Maps")
        }
    }
    private fun toggleSharing() {
        val prefs = AutoRouteShareService.prefs(this)
        if (AutoRouteShareService.enabled(this)) { prefs.edit().putBoolean("enabled", false).apply(); refresh(); return }
        AlertDialog.Builder(this).setTitle("Activar envío automático")
            .setMessage("Autorizas a GPS3D a usar Accesibilidad para pulsar Compartir indicaciones en Google Maps y elegir GPS3D en el menú de compartir cada vez que detecte una ruta nueva. Solo procesa esas dos pantallas. Puedes pausarlo en cualquier momento.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Activar") { _, _ ->
                prefs.edit().putBoolean("enabled", true).apply(); refresh()
                if (!AutoRouteShareService.authorized(this)) openAccessibility()
            }.show()
    }
    private fun openAccessibility() {
        val detail = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, AutoRouteShareService::class.java))
        runCatching { startActivity(detail) }.onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}
