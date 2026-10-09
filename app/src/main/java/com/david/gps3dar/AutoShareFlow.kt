package com.david.gps3dar

import java.text.Normalizer
import java.util.Locale

/** Deterministic Maps -> Android Sharesheet -> GPS3D workflow. No destination inference. */
class AutoShareFlow {
    data class Item(val path: String, val text: String = "", val description: String = "",
                    val editable: Boolean = false, val clickable: Boolean = false,
                    val scrollable: Boolean = false) {
        val labels get() = listOf(text, description).map(::normalize).filter { it.isNotBlank() }
    }
    data class Screen(val packageName: String, val items: List<Item>)
    enum class Kind { CLICK, SCROLL }
    data class Action(val kind: Kind, val path: String)
    enum class Stage { IDLE, EXIT_NAVIGATION, OPEN_SHARE, CHOOSE_APP }
    var stage = Stage.IDLE
        private set
    var status = "Esperando una ruta nueva en Google Maps"
        private set
    var lastDelivered = ""
        private set
    private var candidate = ""
    private var candidateSince = 0L
    private var pendingSignature = ""
    private var startedAt = 0L
    private var lastActionAt = -10_000L
    private var attempts = 0
    private var scrolls = 0
    private var deliveredAt = -10_000L
    private var blockedSignature = ""
    val needsPoll get() = stage != Stage.IDLE ||
        (candidate.isNotBlank() && candidate != lastDelivered && candidate != blockedSignature)

    fun restore(signature: String) { lastDelivered = signature }
    fun reset() {
        stage = Stage.IDLE; candidate = ""; pendingSignature = ""; attempts = 0; scrolls = 0
        status = "Esperando una ruta nueva en Google Maps"
    }
    fun forgetDelivered() { lastDelivered = ""; blockedSignature = ""; reset() }
    fun delivered(now: Long) {
        if (pendingSignature.isNotBlank()) lastDelivered = pendingSignature
        deliveredAt = now; reset(); status = "Destino recibido en GPS3D"
    }
    fun next(screen: Screen, now: Long): Action? {
        if (screen.packageName != MAPS && screen.packageName !in SHARE_PACKAGES) {
            if (stage != Stage.IDLE) reset()
            return null
        }
        if (stage != Stage.IDLE && now - startedAt > 20_000) return fail("No pude completar el envío. Puedes compartirlo manualmente")
        if (now - lastActionAt < 900) return null
        if (screen.packageName in SHARE_PACKAGES) {
            if (stage != Stage.CHOOSE_APP) return null // Never touch an unrelated share dialog.
            val target = screen.items.firstOrNull { it.labels.any { label ->
                label == APP_LABEL || label.startsWith("$APP_LABEL,") || label.startsWith("$APP_LABEL ")
            } }
            if (target != null) return act(target, now)
            val more = exact(screen, "mas", "more", "todas las aplicaciones", "all apps", "mostrar mas", "show more")
            if (more != null && attempts < 4) return act(more, now)
            val scroll = screen.items.firstOrNull { it.scrollable }
            if (scroll != null && scrolls++ < 4) { lastActionAt = now; return Action(Kind.SCROLL, scroll.path) }
            status = "Buscando GPS3D en el menú de compartir"
            return null
        }
        if (stage == Stage.CHOOSE_APP) {
            // Some Maps versions host the share panel themselves.
            val target = exact(screen, APP_LABEL)
            if (target != null) return act(target, now)
            return null
        }
        val signature = signature(screen)
        val start = exact(screen, "iniciar", "start", "iniciar navegacion", "start navigation")
        val routeShare = exact(screen, "compartir indicaciones", "share directions")
        val exit = exact(screen, "salir de la navegacion", "exit navigation", "cerrar navegacion", "stop navigation")
        val menu = exact(screen, "mas opciones", "more options", "opciones de ruta", "route options", "mas opciones de ruta", "more route options")
        val routePreview = start != null && signature.isNotBlank()
        if (stage == Stage.IDLE) {
            if (routePreview || routeShare != null && signature.isNotBlank()) {
                if (signature == lastDelivered || signature == blockedSignature) return null
                if (signature != candidate) { candidate = signature; candidateSince = now; return null }
                if (now - candidateSince < 1100) return null // Let addresses/route choice settle.
                pendingSignature = signature; startedAt = now; attempts = 0; scrolls = 0; stage = Stage.OPEN_SHARE
            } else if (exit != null && now - deliveredAt > 6000) {
                startedAt = now; attempts = 0; stage = Stage.EXIT_NAVIGATION
                status = "Abriendo la vista previa para enviar el destino"
                return act(exit, now)
            } else { candidate = ""; return null }
        }
        if (stage == Stage.EXIT_NAVIGATION) {
            if (!routePreview) return null
            if (signature == lastDelivered || signature == blockedSignature) { reset(); return null }
            pendingSignature = signature; stage = Stage.OPEN_SHARE; attempts = 0
        }
        if (stage == Stage.OPEN_SHARE) {
            if (attempts > 4) return fail("Maps no expuso el botón para compartir esta ruta")
            if (routeShare != null) { stage = Stage.CHOOSE_APP; attempts = 0; status = "Enviando destino a GPS3D"; return act(routeShare, now) }
            // The bottom Share button belongs to directions only after route context is verified.
            val share = if (routePreview) exact(screen, "compartir", "share") else null
            if (share != null) { stage = Stage.CHOOSE_APP; attempts = 0; status = "Enviando destino a GPS3D"; return act(share, now) }
            if (routePreview && menu != null) { status = "Abriendo Compartir indicaciones"; return act(menu, now) }
        }
        return null
    }
    private fun act(item: Item, now: Long): Action { attempts++; lastActionAt = now; return Action(Kind.CLICK, item.path) }
    private fun fail(message: String): Action? { blockedSignature = pendingSignature; reset(); status = message; return null }
    private fun exact(screen: Screen, vararg labels: String): Item? = screen.items.firstOrNull {
        item -> item.labels.any { it in labels }
    }
    private fun signature(screen: Screen): String {
        // Exact address fields are only a deduplication key. Navigation uses the actual shared URL.
        val fields = screen.items.filter { it.editable }.map { normalize(it.text.ifBlank { it.description }) }
            .filter { it.isNotBlank() && it !in setOf("destino", "destination", "elige un destino", "choose destination", "buscar aqui", "search here") }
        if (fields.isEmpty()) return ""
        if (fields.size == 1 && fields[0] in setOf("tu ubicacion", "your location")) return ""
        val key = fields.joinToString("|") + "|" + (exact(screen, "a pie", "walking")?.let { "walk" } ?: "route")
        return java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        const val MAPS = "com.google.android.apps.maps"
        const val APP_LABEL = "gps3d ar david"
        val SHARE_PACKAGES = setOf("android", "com.android.intentresolver", "com.android.systemui", "com.samsung.android.app.sharelive")
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}
