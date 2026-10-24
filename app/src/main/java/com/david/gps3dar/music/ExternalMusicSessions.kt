package com.david.gps3dar.music

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/** A real controller for the selected app, rather than untargeted headset key events. */
class ExternalMusicSessions(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(MusicPlaybackService.PREFS, Context.MODE_PRIVATE)
    private var active = false
    private var registered = false
    private var controllers = emptyList<MediaController>()
    private var selected: MediaController? = null
    private var metadata: MediaMetadata? = null
    private var playback: PlaybackState? = null
    private var icon: Drawable? = null
    private val labels = mutableMapOf<String, String>()
    var access = false; private set
    val preferredPackage get() = prefs.getString(PREFERRED, "").orEmpty()
    val component get() = ComponentName(app, MusicSessionAccessService::class.java)
    data class App(val packageName: String, val label: String)
    data class State(val connected: Boolean, val packageName: String, val appName: String,
        val title: String, val artist: String, val artwork: Bitmap?, val icon: Drawable?,
        val playing: Boolean, val buffering: Boolean, val position: Long, val duration: Long, val actions: Long)
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(value: MediaMetadata?) { metadata = value; changed() }
        override fun onPlaybackStateChanged(value: PlaybackState?) { playback = value; changed() }
        override fun onSessionDestroyed() {
            val token = selected?.sessionToken
            controllers = controllers.filter { it.sessionToken != token }
            choose(); changed()
        }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { values ->
        if (active) { controllers = values.orEmpty().filter { it.packageName != app.packageName }; choose(); changed() }
    }
    fun label(packageName: String): String = labels.getOrPut(packageName) {
        when (packageName) {
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "" -> "Tu música"
            else -> runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName.substringAfterLast('.'))
        }
    }
    fun apps(): List<App> = controllers.map { App(it.packageName, label(it.packageName)) }.distinctBy { it.packageName }
    fun start() { active = true; refreshAccess() }
    fun refreshAccess() {
        val allowed = hasAccess(app)
        if (!active) return
        if (!allowed) {
            val notify = access || registered || selected != null
            access = false; releaseControllers()
            if (notify) changed()
            return
        }
        access = true
        if (registered) return
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessions, component, main)
            registered = true
            controllers = manager.getActiveSessions(component).filter { it.packageName != app.packageName }
            choose()
        }.onFailure { releaseControllers() }
        changed()
    }
    fun select(packageName: String) {
        prefs.edit().putString(PREFERRED, packageName).apply()
        choose(); changed()
    }
    private fun choose() {
        val candidates = controllers.filter { preferredPackage.isBlank() || it.packageName == preferredPackage }
        val next = candidates.firstOrNull { it.playbackState?.state in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING) }
            ?: candidates.firstOrNull()
        if (next?.sessionToken == selected?.sessionToken) return
        selected?.let { runCatching { it.unregisterCallback(callback) } }
        selected = next
        metadata = next?.metadata; playback = next?.playbackState
        icon = next?.let { runCatching { app.packageManager.getApplicationIcon(it.packageName) }.getOrNull() }
        next?.registerCallback(callback, main)
    }
    fun state(): State {
        val controller = selected
        val packageName = controller?.packageName ?: preferredPackage
        val m = metadata; val p = playback
        val playing = p?.state in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING)
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        val elapsed = if (p != null && p.state == PlaybackState.STATE_PLAYING && p.lastPositionUpdateTime > 0)
            ((SystemClock.elapsedRealtime() - p.lastPositionUpdateTime).coerceAtLeast(0) * p.playbackSpeed).toLong() else 0
        val position = ((p?.position ?: 0).coerceAtLeast(0) + elapsed).coerceAtLeast(0)
        return State(controller != null && access, packageName, label(packageName),
            m?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: m?.description?.title?.toString().orEmpty(),
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: m?.description?.subtitle?.toString().orEmpty(),
            m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART), icon,
            playing, p?.state in listOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING),
            if (duration > 0) position.coerceAtMost(duration) else position, duration, p?.actions ?: 0)
    }
    fun toggle(): Boolean {
        refreshAccess()
        val player = selected ?: return false
        val s = state()
        return runCatching {
            if (s.playing && s.actions and PlaybackState.ACTION_PAUSE != 0L) player.transportControls.pause()
            else if (!s.playing && s.actions and PlaybackState.ACTION_PLAY != 0L) player.transportControls.play()
            else {
                val time = SystemClock.uptimeMillis()
                // This key is sent to this exact session, never to an unrelated player.
                player.dispatchMediaButtonEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0))
                player.dispatchMediaButtonEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0))
            }
        }.isSuccess
    }
    fun skip(forward: Boolean): Boolean {
        refreshAccess()
        val player = selected ?: return false
        val action = if (forward) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state().actions and action == 0L) return false
        return runCatching { if (forward) player.transportControls.skipToNext() else player.transportControls.skipToPrevious() }.isSuccess
    }
    fun seek(position: Long): Boolean {
        refreshAccess()
        val player = selected ?: return false
        val s = state()
        if (s.duration <= 0 || s.actions and PlaybackState.ACTION_SEEK_TO == 0L) return false
        return runCatching { player.transportControls.seekTo(position.coerceIn(0, s.duration)) }.isSuccess
    }
    fun openIntent(): PendingIntent? = selected?.sessionActivity
    fun stop() { active = false; releaseControllers() }
    private fun releaseControllers() {
        if (registered) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        registered = false
        selected?.let { runCatching { it.unregisterCallback(callback) } }
        selected = null; metadata = null; playback = null; icon = null; controllers = emptyList()
    }
    override fun close() = stop()
    companion object {
        const val PREFERRED = "external_music_package"
        fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 27)
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, MusicSessionAccessService::class.java))
            else context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
