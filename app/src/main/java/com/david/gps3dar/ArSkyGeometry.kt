package com.david.gps3dar

import kotlin.math.*

/** A destination bearing, not a claim that the destination is at the billboard's drawing distance. */
data class ArDestinationIndicator(val x: Float, val y: Float, val arrow: String = "")

object ArSkyGeometry {
    fun project(cameraVector: FloatArray, projection: FloatArray): ArDestinationIndicator? {
        if (cameraVector.size < 3 || projection.size < 16 ||
            cameraVector.any { !it.isFinite() } || projection.any { !it.isFinite() }) return null
        val x = cameraVector[0]; val y = cameraVector[1]; val z = cameraVector[2]
        if (hypot(x.toDouble(), z.toDouble()) < .01) return ArDestinationIndicator(.5f, .34f)
        val w = projection[3] * x + projection[7] * y + projection[11] * z
        if (w <= .001f) {
            return ArDestinationIndicator(if (x < 0) .12f else .88f, .37f,
                if (x < 0) "← Gira el teléfono" else "Gira el teléfono →")
        }
        val sx = ((projection[0] * x + projection[4] * y + projection[8] * z) / w + 1) / 2
        val sy = (1 - (projection[1] * x + projection[5] * y + projection[9] * z) / w) / 2
        val arrow = when {
            sx < .12f -> "← Destino"
            sx > .88f -> "Destino →"
            sy < .28f -> "↑ Destino"
            sy > .55f -> "↓ Destino"
            else -> ""
        }
        return ArDestinationIndicator(sx.coerceIn(.12f, .88f), sy.coerceIn(.28f, .55f), arrow)
    }

    fun maneuverSymbol(maneuver: String): String = when {
        maneuver.contains("LEFT") -> "↰"
        maneuver.contains("RIGHT") -> "↱"
        maneuver.startsWith("ARRIVE") -> "⚑"
        maneuver.contains("UTURN") || maneuver.contains("U_TURN") -> "↶"
        else -> "↑"
    }
}
