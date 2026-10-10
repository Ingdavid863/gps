package com.david.gps3dar

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Uses Android's normal destination sharing; no access to another app's screen. */
class MapsConnectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.WHITE)
        }
        fun paragraph(value: String, size: Float = 18f) {
            body.addView(TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.rgb(23, 33, 43))
                setPadding(0, 12, 0, 20)
            })
        }
        paragraph("Destinos desde Google Maps", 25f)
        paragraph("1. En Google Maps, abre el lugar o las indicaciones de tu destino.")
        paragraph("2. Toca Compartir o Compartir indicaciones.")
        paragraph("3. Selecciona GPS3D AR David en el menú de compartir.")
        paragraph("Elige enviar cada destino desde Maps o búscalo directamente en GPS3D. El envío desde Maps no es automático.", 17f)
        paragraph("Maps comparte el destino; GPS3D calcula el recorrido. VR usa una ruta a pie; al volver se conserva tu ruta de conducción. Si elegiste caminar, el mapa y la cámara usan la misma ruta a pie.", 17f)
        body.addView(Button(this).apply {
            text = "Abrir Google Maps"
            setOnClickListener {
                val maps = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))
                    .setPackage("com.google.android.apps.maps")
                runCatching { startActivity(maps) }.onFailure {
                    Toast.makeText(this@MapsConnectionActivity, "Abre Google Maps en tu teléfono para compartir el destino", Toast.LENGTH_LONG).show()
                }
            }
        })
        body.addView(Button(this).apply {
            text = "Volver al mapa"
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
