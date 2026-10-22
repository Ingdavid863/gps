package com.david.gps3dar.music

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.david.gps3dar.R
import java.util.concurrent.Executors
import com.google.common.util.concurrent.ListenableFuture

/** Local files and Android's active music app share the same visible map transport controls. */
class MusicPanelController(private val activity: AppCompatActivity) : AutoCloseable {
    private val source = activity.findViewById<ImageButton>(R.id.musicSourceButton)
    private val title = activity.findViewById<TextView>(R.id.musicTitle)
    private val subtitle = activity.findViewById<TextView>(R.id.musicSubtitle)
    private val previous = activity.findViewById<ImageButton>(R.id.musicPrevious)
    private val play = activity.findViewById<ImageButton>(R.id.musicPlayPause)
    private val next = activity.findViewById<ImageButton>(R.id.musicNext)
    private val io = Executors.newSingleThreadExecutor()
    private var closed = false
    private var importing = false
    private val preferences = activity.getSharedPreferences(MusicPlaybackService.PREFS, android.content.Context.MODE_PRIVATE)
    private var external = preferences.getString(SOURCE, null)?.let { it == EXTERNAL }
        ?: MusicQueue.decode(preferences.getString(MusicPlaybackService.QUEUE, "").orEmpty()).tracks.isEmpty()
    private val remote = ExternalMusicControls(activity)
    private var controller: MediaController? = null
    private val files = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addDocuments(uris)
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
        override fun onPlayerError(error: PlaybackException) {
            toast("No se pudo reproducir esta canción. Elige otra o vuelve a añadir el archivo.")
            render()
        }
    }
    private var future: ListenableFuture<MediaController>? = null
    private val pendingLocalActions = mutableListOf<(MediaController) -> Unit>()

    init {
        source.setOnClickListener { showSources() }
        title.setOnClickListener { showSources() }
        subtitle.setOnClickListener { showSources() }
        previous.setOnClickListener { skip(forward = false) }
        next.setOnClickListener { skip(forward = true) }
        play.setOnClickListener {
            if (external) { sendExternal(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return@setOnClickListener }
            controller?.let { player ->
                if (player.playWhenReady) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    player.play()
                }
            }
        }
        if (!external) connectLocal()
        render()
    }
    private fun connectLocal(ready: ((MediaController) -> Unit)? = null) {
        controller?.let { ready?.invoke(it); return }
        ready?.let { pendingLocalActions.add(it) }
        if (future != null) return
        val connecting = MediaController.Builder(activity,
            SessionToken(activity, ComponentName(activity, MusicPlaybackService::class.java))).buildAsync()
        future = connecting
        connecting.addListener({
            if (closed || external || future !== connecting) return@addListener
            runCatching { connecting.get() }.onSuccess {
                controller = it; it.addListener(listener); render()
                val actions = pendingLocalActions.toList(); pendingLocalActions.clear()
                actions.forEach { action -> if (!closed && !external) action(it) }
            }.onFailure {
                pendingLocalActions.clear()
                future = null
                subtitle.text = "No se pudo abrir el reproductor · toca ♫ para reintentar"
            }
        }, ContextCompat.getMainExecutor(activity))
    }
    private fun useLocal(ready: (MediaController) -> Unit) {
        external = false; preferences.edit().putString(SOURCE, LOCAL).apply()
        connectLocal(ready); render()
    }
    private fun useExternal() {
        external = true; preferences.edit().putString(SOURCE, EXTERNAL).apply()
        controller?.removeListener(listener)
        MusicPlaybackService.handoffToExternal()
        future?.let(MediaController::releaseFuture)
        future = null; controller = null
        pendingLocalActions.clear()
        render()
    }
    private fun sendExternal(key: Int) {
        if (!remote.send(key)) toast("No se pudo enviar el control. Abre tu app de música, elige una canción y vuelve al GPS.")
    }
    private fun skip(forward: Boolean) {
        if (external) { sendExternal(if (forward) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS); return }
        controller?.let { player ->
            if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
        }
    }
    private fun render() {
        if (closed) return
        val player = controller
        val hasMusic = !external && (player?.mediaItemCount ?: 0) > 0
        title.text = if (external) "Tu app de música" else if (hasMusic) player?.mediaMetadata?.title ?: "Canción" else "Tus canciones"
        subtitle.text = when {
            external -> "Controla aquí el reproductor activo"
            importing -> "Añadiendo canciones…"
            player?.playerError != null -> "Archivo no disponible · elige otra canción"
            !hasMusic -> "Añadir canciones o abrir una app"
            player?.playbackState == Player.STATE_BUFFERING -> "Cargando canción…"
            player?.isPlaying == true -> "Canciones del teléfono · ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}"
            else -> "En pausa · canciones del teléfono"
        }
        for (button in listOf(previous, play, next)) { button.visibility = View.VISIBLE; button.isEnabled = external || hasMusic }
        previous.isEnabled = external || (hasMusic && (player?.mediaItemCount ?: 0) > 1)
        next.isEnabled = previous.isEnabled
        val playing = player?.playWhenReady == true
        play.setImageResource(if (external) R.drawable.ic_music_toggle else if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        play.contentDescription = if (external) "Reproducir o pausar tu app de música" else if (playing) "Pausar música" else "Reproducir música"
    }
    private fun showSources() {
        val items = arrayOf("Controlar otra app de música", "Abrir Spotify", "Abrir YouTube Music", "Abrir otra aplicación de música", "Añadir canciones del teléfono", "Mi lista de canciones", "Vaciar mi lista")
        AlertDialog.Builder(activity).setTitle("Música para tu viaje")
            .setItems(items) { _, which -> when (which) {
                0 -> { useExternal(); toast("Usa los botones del mapa. La primera vez, elige una canción en tu app de música.") }
                1 -> openMusicApp("com.spotify.music", "Spotify")
                2 -> openMusicApp("com.google.android.apps.youtube.music", "YouTube Music")
                3 -> openOtherApps()
                4 -> useLocal { files.launch(arrayOf("audio/*")) }
                5 -> useLocal { showQueue() }
                6 -> useLocal { player -> if (player.mediaItemCount > 0) AlertDialog.Builder(activity)
                    .setTitle("¿Vaciar tu lista?").setMessage("Los archivos del teléfono se conservan.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Vaciar") { _, _ ->
                        val uris = (0 until player.mediaItemCount).map { Uri.parse(player.getMediaItemAt(it).mediaId) }
                        player.stop(); player.clearMediaItems()
                        io.execute { uris.forEach { uri -> runCatching { activity.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
                    }.show() }
            } }.setNegativeButton("Cerrar", null).show()
    }
    private fun showQueue() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) { toast("Añade canciones del teléfono para crear tu lista."); return }
        val names = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "Canción" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Mis canciones")
            .setSingleChoiceItems(names, player.currentMediaItemIndex) { dialog, index ->
                player.seekToDefaultPosition(index); player.prepare(); player.play(); dialog.dismiss()
            }.setPositiveButton("Cerrar", null).show()
    }
    private fun addDocuments(uris: List<Uri>) {
        val player = controller ?: return
        if (importing) return
        val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val selected = uris.distinct().filter { it.scheme == "content" && it.toString() !in existing }
            .take((MusicQueue.MAX_TRACKS - existing.size).coerceAtLeast(0))
        if (selected.isEmpty()) { toast("La lista ya contiene esas canciones o alcanzó las 100 canciones."); return }
        importing = true; render()
        io.execute {
            val tracks = selected.mapNotNull { uri -> runCatching {
                activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = runCatching { activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } }.getOrNull() ?: "Canción"
                MusicTrack(uri.toString(), name.substringBeforeLast('.').ifBlank { name }.take(200))
            }.getOrNull() }
            activity.runOnUiThread {
                importing = false
                if (!closed && !external && controller === player) {
                    if (tracks.isEmpty()) toast("No se pudieron añadir los archivos. Vuelve a elegirlos desde el teléfono.")
                    else {
                        val wasEmpty = player.mediaItemCount == 0
                        player.addMediaItems(tracks.map(MusicPlaybackService::mediaItem))
                        if (wasEmpty) { player.prepare(); player.play() }
                        if (tracks.size < selected.size) toast("Algunos archivos no se pudieron añadir.")
                    }
                    render()
                }
            }
        }
    }
    private fun openMusicApp(packageName: String, name: String) {
        val launch = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            toast("$name no está instalado. Puedes usar las canciones del teléfono u otra aplicación.")
            return
        }
        launchMusic(launch)
    }
    private fun openOtherApps() = launchMusic(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
    private fun launchMusic(intent: Intent) {
        runCatching { activity.startActivity(intent) }.onSuccess {
            useExternal()
            toast("Elige una canción en tu app y regresa al GPS. Aquí tendrás anterior, reproducir/pausar y siguiente.")
        }.onFailure { toast("No se encontró una aplicación de música instalada.") }
    }
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    override fun close() {
        closed = true
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        io.shutdown()
    }
    companion object {
        const val SOURCE = "control_source"
        const val LOCAL = "local"
        const val EXTERNAL = "external"
    }
}
