package com.david.gps3dar.music

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
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

/** Small map controls for local audio, plus explicit shortcuts to streaming apps. */
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
    private val future = MediaController.Builder(activity,
        SessionToken(activity, ComponentName(activity, MusicPlaybackService::class.java))).buildAsync()

    init {
        source.setOnClickListener { showSources() }
        title.setOnClickListener { showSources() }
        subtitle.setOnClickListener { showSources() }
        previous.setOnClickListener { skip(forward = false) }
        next.setOnClickListener { skip(forward = true) }
        play.setOnClickListener {
            controller?.let { player ->
                if (player.playWhenReady) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    player.play()
                }
            }
        }
        future.addListener({
            if (!closed) runCatching { future.get() }.onSuccess {
                controller = it; it.addListener(listener); render()
            }.onFailure { subtitle.text = "Toca para volver a abrir la música" }
        }, ContextCompat.getMainExecutor(activity))
        render()
    }
    private fun skip(forward: Boolean) {
        controller?.let { player ->
            if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
        }
    }
    private fun render() {
        if (closed) return
        val player = controller
        val hasMusic = (player?.mediaItemCount ?: 0) > 0
        title.text = if (hasMusic) player?.mediaMetadata?.title ?: "Canción" else "Música"
        subtitle.text = when {
            importing -> "Añadiendo canciones…"
            player?.playerError != null -> "Archivo no disponible · elige otra canción"
            !hasMusic -> "Añadir canciones o abrir una app"
            player?.playbackState == Player.STATE_BUFFERING -> "Cargando canción…"
            player?.isPlaying == true -> "Canciones del teléfono · ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}"
            else -> "En pausa · canciones del teléfono"
        }
        for (button in listOf(previous, play, next)) button.visibility = if (hasMusic) View.VISIBLE else View.GONE
        previous.isEnabled = hasMusic && (player?.mediaItemCount ?: 0) > 1
        next.isEnabled = previous.isEnabled
        val playing = player?.playWhenReady == true
        play.setImageResource(if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        play.contentDescription = if (playing) "Pausar música" else "Reproducir música"
    }
    private fun showSources() {
        val player = controller
        val items = arrayOf("Añadir canciones del teléfono", "Mi lista de canciones", "Abrir Spotify", "Abrir YouTube Music", "Otras aplicaciones de música", "Vaciar mi lista")
        AlertDialog.Builder(activity).setTitle("Música para tu viaje")
            .setItems(items) { _, which -> when (which) {
                0 -> if (player == null) toast("El reproductor se está preparando. Inténtalo de nuevo.") else files.launch(arrayOf("audio/*"))
                1 -> showQueue()
                2 -> openMusicApp("com.spotify.music", "Spotify")
                3 -> openMusicApp("com.google.android.apps.youtube.music", "YouTube Music")
                4 -> openOtherApps()
                5 -> if (player != null && player.mediaItemCount > 0) AlertDialog.Builder(activity)
                    .setTitle("¿Vaciar tu lista?").setMessage("Los archivos del teléfono se conservan.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Vaciar") { _, _ ->
                        val uris = (0 until player.mediaItemCount).map { Uri.parse(player.getMediaItemAt(it).mediaId) }
                        player.stop(); player.clearMediaItems()
                        io.execute { uris.forEach { uri -> runCatching { activity.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
                    }.show()
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
                if (!closed) {
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
            controller?.pause()
            toast("Elige tu música en esa aplicación y regresa al GPS. Sus controles siguen en la notificación de Android.")
        }.onFailure { toast("No se encontró una aplicación de música instalada.") }
    }
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    override fun close() {
        closed = true
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        io.shutdown()
    }
}
