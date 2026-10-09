package com.david.gps3dar

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Only operates Maps and the share panel opened by its own current route transfer. */
class AutoRouteShareService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val flow = AutoShareFlow()
    private val tick = Runnable { inspect() }
    override fun onServiceConnected() {
        active = this
        flow.restore(prefs(this).getString("last_signature", "").orEmpty())
        updateStatus("Esperando una ruta nueva en Google Maps")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled(this)) { handler.removeCallbacks(tick); flow.reset(); return }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != AutoShareFlow.MAPS && pkg !in AutoShareFlow.SHARE_PACKAGES) return
        handler.removeCallbacks(tick); handler.postDelayed(tick, 150)
    }
    private fun inspect() {
        if (!enabled(this)) { flow.reset(); return }
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString().orEmpty()
            // Never inspect the nodes of any other app, including DiDi.
            if (pkg != AutoShareFlow.MAPS && pkg !in AutoShareFlow.SHARE_PACKAGES) { flow.reset(); return }
            if (pkg in AutoShareFlow.SHARE_PACKAGES && flow.stage != AutoShareFlow.Stage.CHOOSE_APP) return
            val items = ArrayList<AutoShareFlow.Item>()
            collect(root, "", items, 0)
            val action = flow.next(AutoShareFlow.Screen(pkg, items), SystemClock.elapsedRealtime())
            updateStatus(flow.status)
            if (action != null) perform(root, action)
            // Finite polling covers asynchronous menu/chooser transitions; no continuous background scan.
            if (flow.needsPoll) {
                handler.removeCallbacks(tick); handler.postDelayed(tick, 700)
            }
        } finally { root.recycle() }
    }
    private fun collect(node: AccessibilityNodeInfo, path: String, items: MutableList<AutoShareFlow.Item>, depth: Int) {
        if (depth > 26 || items.size >= 450 || !node.isVisibleToUser || node.isPassword) return
        items.add(AutoShareFlow.Item(path, node.text?.toString()?.take(600).orEmpty(),
            node.contentDescription?.toString()?.take(600).orEmpty(), node.isEditable || node.className?.toString()?.endsWith("EditText") == true,
            node.isClickable, node.isScrollable))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { collect(child, if (path.isEmpty()) "$i" else "$path/$i", items, depth + 1) }
            finally { child.recycle() }
        }
    }
    private fun perform(root: AccessibilityNodeInfo, action: AutoShareFlow.Action): Boolean {
        var current = AccessibilityNodeInfo.obtain(root)
        try {
            for (part in action.path.split('/').filter { it.isNotBlank() }) {
                val next = current.getChild(part.toInt()) ?: return false
                current.recycle(); current = next
            }
            if (action.kind == AutoShareFlow.Kind.SCROLL) return current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            repeat(7) {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = current.parent ?: return false
                current.recycle(); current = parent
            }
            return false
        } finally { current.recycle() }
    }
    private fun updateStatus(value: String) { if (prefs(this).getString("status", "") != value) prefs(this).edit().putString("status", value).apply() }
    private fun received() {
        flow.delivered(SystemClock.elapsedRealtime())
        prefs(this).edit().putString("last_signature", flow.lastDelivered).putString("status", flow.status).apply()
        handler.removeCallbacks(tick)
    }
    private fun forget() { flow.forgetDelivered(); prefs(this).edit().remove("last_signature").apply() }
    override fun onInterrupt() { handler.removeCallbacks(tick); flow.reset() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (active === this) active = null; super.onDestroy() }
    companion object {
        private var active: AutoRouteShareService? = null
        const val PREFS = "automatic_route_share"
        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
        fun authorized(context: Context): Boolean {
            val expected = ComponentName(context, AutoRouteShareService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
        fun destinationReceived() { active?.received() }
        fun routeStopped(context: Context) { active?.forget(); prefs(context).edit().remove("last_signature").apply() }
    }
}
