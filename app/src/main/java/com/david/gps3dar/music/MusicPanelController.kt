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
    private var external = if (preferences.getBoolean(NATIVE_DEFAULT, false)) preferences.getString(SOURCE, LOCAL) == EXTERNAL
        else false.also { preferences.edit().putBoolean(NATIVE_DEFAULT,true).putString(SOURCE,LOCAL).apply() }
    private val remote = ExternalMusicControls(activity)
    private var controller: MediaController? = null
    private val files = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addDocuments(uris)
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
        override fun onPlayerError(error: PlaybackException) {
            toast("No se pudo reproducir. Revisa la conexión para audio en línea o vuelve a elegir el archivo.")
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
            if (controller == null || controller?.mediaItemCount == 0) { showInAppMusic(); return@setOnClickListener }
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
        val online = player?.mediaMetadata?.extras?.getBoolean("online") == true
        title.text = if (external) "Tu app de música" else if (hasMusic) player?.mediaMetadata?.title ?: "Canción" else "Escuchar en GPS3D"
        subtitle.text = when {
            external -> "Controla aquí el reproductor activo"
            importing -> "Añadiendo canciones…"
            player?.playerError != null -> "Audio no disponible · toca ♫ para elegir otro"
            !hasMusic -> "Toca ▶ para elegir música aquí"
            player?.playbackState == Player.STATE_BUFFERING -> "Cargando canción…"
            player?.isPlaying == true -> "${if (online) "Audio en línea" else "Canciones del teléfono"} · ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}"
            else -> "En pausa · ${if (online) "audio en línea" else "canciones del teléfono"}"
        }
        for (button in listOf(previous, play, next)) { button.visibility = View.VISIBLE; button.isEnabled = external || hasMusic }
        previous.isEnabled = external || (hasMusic && (player?.mediaItemCount ?: 0) > 1)
        next.isEnabled = previous.isEnabled
        play.isEnabled = true
        val playing = player?.playWhenReady == true
        play.setImageResource(if (external) R.drawable.ic_music_toggle else if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        play.contentDescription = if (external) "Reproducir o pausar tu app de música" else if (playing) "Pausar música" else "Reproducir música"
    }
    private fun showSources() {
        val items = arrayOf("Escuchar dentro de GPS3D", "Añadir enlace de audio", "Añadir canciones del teléfono", "Mi lista de canciones",
            "Bloqueo de anuncios en audio", "Controlar otra app de música", "Abrir Spotify", "Abrir YouTube Music", "Abrir otra aplicación de música", "Vaciar mi lista")
        AlertDialog.Builder(activity).setTitle("Música para tu viaje")
            .setItems(items) { _, which -> when (which) {
                0 -> showInAppMusic()
                1 -> showAudioLink()
                2 -> useLocal { files.launch(arrayOf("audio/*")) }
                3 -> useLocal { showQueue() }
                4 -> showAdFilter()
                5 -> { useExternal(); toast("La otra app debe permitir música en segundo plano. Para reproducir aquí, elige Escuchar dentro de GPS3D.") }
                6 -> openMusicApp("com.spotify.music", "Spotify")
                7 -> openMusicApp("com.google.android.apps.youtube.music", "YouTube Music")
                8 -> openOtherApps()
                9 -> useLocal { player -> if (player.mediaItemCount > 0) AlertDialog.Builder(activity)
                    .setTitle("¿Vaciar tu lista?").setMessage("Los archivos del teléfono se conservan.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Vaciar") { _, _ ->
                        val uris = (0 until player.mediaItemCount).map { Uri.parse(player.getMediaItemAt(it).mediaId) }
                        player.stop(); player.clearMediaItems()
                        io.execute { uris.forEach { uri -> runCatching { activity.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
                    }.show() }
            } }.setNegativeButton("Cerrar", null).show()
    }
    private fun showInAppMusic() {
        AlertDialog.Builder(activity).setTitle("Escuchar dentro de GPS3D")
            .setItems(arrayOf("Radio Paradise · mezcla general", "Radio Paradise · rock", "Radio Paradise · relajante",
                "Mis canciones guardadas", "Añadir canciones del teléfono", "Añadir enlace de audio")) { _, which ->
                when (which) {
                    in 0..2 -> {
                        val streams = arrayOf("mp3-128", "rock-128", "mellow-128")
                        val labels = arrayOf("Radio Paradise · mezcla general", "Radio Paradise · rock", "Radio Paradise · relajante")
                        val queue = streams.indices.map { MusicTrack("https://stream.radioparadise.com/${streams[it]}",labels[it],true,true) }
                        useLocal { player ->
                            // Add the station set once, preserving the user's selected files and links.
                            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                            val additions = queue.filter { it.uri !in existing }.take((MusicQueue.MAX_TRACKS-player.mediaItemCount).coerceAtLeast(0))
                            player.addMediaItems(additions.map(MusicPlaybackService::mediaItem))
                            val index=(0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == queue[which].uri }
                            if (index == null) toast("La lista está llena. Vacía espacio para añadir emisoras.")
                            else { player.seekToDefaultPosition(index); player.prepare(); player.play() }
                        }
                    }
                    3 -> useLocal { showQueue() }
                    4 -> useLocal { files.launch(arrayOf("audio/*")) }
                    5 -> showAudioLink()
                }
            }.setNeutralButton("Acerca de la radio") { _, _ -> AlertDialog.Builder(activity)
                .setTitle("Radio Paradise").setMessage("Emisoras en vivo sostenidas por sus oyentes. Requieren internet. Anterior y siguiente cambian de emisora; no eligen la canción emitida.")
                .setPositiveButton("Cerrar",null).setNeutralButton("Sitio de la emisora") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://radioparadise.com"))) }.show()
            }.setNegativeButton("Cerrar",null).show()
    }
    private fun showAudioLink() {
        val box=android.widget.LinearLayout(activity).apply { orientation=android.widget.LinearLayout.VERTICAL;setPadding(28,12,28,0) }
        val name=android.widget.EditText(activity).apply { hint="Nombre de la canción o emisora";isSingleLine=true }
        val link=android.widget.EditText(activity).apply { hint="https://… (audio o emisora)";isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(name);box.addView(link)
        val dialog=AlertDialog.Builder(activity).setTitle("Añadir enlace de audio")
            .setMessage("Usa un enlace directo de audio o radio HTTPS. Las páginas de YouTube Music y Spotify se escuchan con sus propias apps y requisitos de cuenta.")
            .setView(box).setPositiveButton("Reproducir",null).setNegativeButton("Cancelar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url=link.text.toString().trim()
            if (!MusicTrack.directAudio(url)) { link.error="Introduce un enlace directo de audio HTTPS";return@setOnClickListener }
            val track=MusicTrack(url,name.text.toString().trim().ifBlank { "Audio en línea" }.take(200),true)
            useLocal { player ->
                val existing=(0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId==url }
                if (existing == null && player.mediaItemCount >= MusicQueue.MAX_TRACKS) toast("Tu lista alcanzó las 100 entradas.")
                else { val index=existing ?: player.mediaItemCount.also { player.addMediaItem(MusicPlaybackService.mediaItem(track)) }
                    player.seekToDefaultPosition(index);player.prepare();player.play();dialog.dismiss() }
            }
        } };dialog.show()
    }
    private fun showAdFilter() {
        val enabled=preferences.getBoolean(MusicNetwork.FILTER,true)
        AlertDialog.Builder(activity).setTitle("Bloqueo de anuncios en audio")
            .setMessage("${if (enabled) "Activado" else "Desactivado"} · AdGuard DNS\n\nFiltra dominios de anuncios y rastreadores de los enlaces que reproduce GPS3D. Se aplica al iniciar o volver a preparar un audio. No filtra el mapa ni otras apps. No elimina anuncios insertados dentro de una emisión o de YouTube Music. Los dominios de audio se consultan al proveedor DNS.")
            .setPositiveButton(if (enabled) "Desactivar" else "Activar") { _, _ -> preferences.edit().putBoolean(MusicNetwork.FILTER,!enabled).apply();toast("Filtro ${if (enabled) "desactivado" else "activado"} para las nuevas conexiones de audio.") }
            .setNegativeButton("Cerrar",null).show()
    }
    private fun showQueue() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) { showInAppMusic(); return }
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
        const val NATIVE_DEFAULT = "native_player_030"
    }
}
