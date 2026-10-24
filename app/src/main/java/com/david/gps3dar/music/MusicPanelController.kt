package com.david.gps3dar.music

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.media.session.PlaybackState
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
class MusicPanelController(private val activity: AppCompatActivity, private val expanded: Boolean = false) : AutoCloseable {
    private val source = activity.findViewById<ImageButton>(R.id.musicSourceButton)
    private val title = activity.findViewById<TextView>(R.id.musicTitle)
    private val subtitle = activity.findViewById<TextView>(R.id.musicSubtitle)
    private val previous = activity.findViewById<ImageButton>(R.id.musicPrevious)
    private val play = activity.findViewById<ImageButton>(R.id.musicPlayPause)
    private val next = activity.findViewById<ImageButton>(R.id.musicNext)
    private val artwork = activity.findViewById<ImageView>(R.id.musicArtwork)
    private val progress = activity.findViewById<SeekBar>(R.id.musicProgress)
    private val time = activity.findViewById<TextView>(R.id.musicTime)
    private var lastArtwork: Any? = null
    private var dragging = false
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (closed || !foreground) return
            if (external) remote.refreshAccess()
            render()
            main.postDelayed(this, 1000)
        }
    }
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            foreground = true
            val wantedExternal = preferences.getString(SOURCE, LOCAL) == EXTERNAL
            if (wantedExternal != external) { if (wantedExternal) useExternal() else useLocal {} }
            if (external) remote.start()
            main.removeCallbacks(ticker); main.post(ticker)
        }
        override fun onPause(owner: LifecycleOwner) {
            foreground = false; main.removeCallbacks(ticker); remote.stop()
        }
    }
    private val io = Executors.newSingleThreadExecutor()
    private val artworkNetwork=MusicNetwork { preferences.getBoolean(MusicNetwork.FILTER,true) }
    private val covers=MusicArtwork(artworkNetwork,io)
    private var artworkUri=""
    private var localArtworkActive = false
    private var closed = false
    private var importing = false
    private val preferences = activity.getSharedPreferences(MusicPlaybackService.PREFS, android.content.Context.MODE_PRIVATE)
    private var external = preferences.getString(SOURCE,
        LOCAL) == EXTERNAL
    private val remote = ExternalMusicSessions(activity) { render() }
    private var controller: MediaController? = null
    private val files = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addDocuments(uris)
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            render()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) onLibraryChanged?.invoke()
        }
        override fun onPlayerError(error: PlaybackException) {
            toast("No se pudo reproducir. Revisa la conexión para audio en línea o vuelve a elegir el archivo.")
            render()
        }
    }
    var onLibraryChanged: (() -> Unit)? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pendingLocalActions = mutableListOf<(MediaController) -> Unit>()

    init {
        activity.lifecycle.addObserver(lifecycle)
        source.setOnClickListener { showSources() }
        artwork.setOnClickListener { if (expanded) showSources() else expand() }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                if (user && !dragging) seekMusic(value)
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seekMusic(bar.progress)
                dragging = false
            }
        })
        title.setOnClickListener { if (expanded) showSources() else expand() }
        subtitle.setOnClickListener { if (expanded) showSources() else expand() }
        previous.setOnClickListener { skip(forward = false) }
        next.setOnClickListener { skip(forward = true) }
        play.setOnClickListener {
            if (external) {
                remote.refreshAccess()
                if (!remote.access) showConnection()
                else if (!remote.toggle()) {
                    if (remote.preferredPackage.isNotBlank()) openMusicApp(remote.preferredPackage, remote.label(remote.preferredPackage))
                    else showSources()
                }
                return@setOnClickListener
            }
            if (controller == null || controller?.mediaItemCount == 0) { if (expanded) showInAppMusic() else expand(); return@setOnClickListener }
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
    private fun expand() { activity.startActivity(Intent(activity, MusicPlayerActivity::class.java)) }
    /** X closes GPS3D audio; unlike minimizing, it stops playback and clears the local queue. */
    fun stopAndClearLocalPlayback() {
        useLocal { player ->
            player.stop()
            player.clearMediaItems()
        }
    }
    fun importFiles() { useLocal { files.launch(arrayOf("audio/*")) } }
    fun radio() = showInAppMusic()
    fun library(ready: (List<androidx.media3.common.MediaItem>) -> Unit) {
        useLocal { p -> ready((0 until p.mediaItemCount).map(p::getMediaItemAt)) }
    }
    fun playAt(index: Int) { useLocal { p ->
        if (index in 0 until p.mediaItemCount) { p.seekToDefaultPosition(index);p.prepare();p.play() }
    } }
    fun playTrack(track: MusicTrack) = playTracks(listOf(track), 0)
    fun playTracks(tracks: List<MusicTrack>, selected: Int) { useLocal { p ->
        val chosen = tracks.getOrNull(selected) ?: return@useLocal
        val existing = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
        val additions = tracks.filter { it.valid() && it.uri !in existing }.distinctBy { it.uri }
            .take((MusicQueue.MAX_TRACKS-p.mediaItemCount).coerceAtLeast(0))
        p.addMediaItems(additions.map(MusicPlaybackService::mediaItem))
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == chosen.uri }
        if (index == null) toast("Tu lista alcanzó las 100 canciones.")
        else { p.seekToDefaultPosition(index); p.prepare(); p.play() }
    } }
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
        external = false; remote.stop(); preferences.edit().putString(SOURCE, LOCAL).apply()
        connectLocal(ready); render()
    }
    private fun useExternal() {
        external = true; preferences.edit().putString(SOURCE, EXTERNAL).apply()
        controller?.removeListener(listener)
        MusicPlaybackService.handoffToExternal()
        future?.let(MediaController::releaseFuture)
        future = null; controller = null
        pendingLocalActions.clear()
        if (foreground) remote.start()
        render()
    }
    private fun skip(forward: Boolean) {
        if (external) {
            if (!remote.skip(forward)) toast("El reproductor no ofrece ese control ahora.")
            return
        }
        controller?.let { player ->
            if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
        }
    }
    private fun render() {
        if (closed) return
        time.setTextColor(subtitle.currentTextColor)
        if (external) { renderExternal(); return }
        val player = controller
        val hasMusic = (player?.mediaItemCount ?: 0) > 0
        val online = player?.mediaMetadata?.extras?.getBoolean("online") == true
        title.text = if (hasMusic) player?.mediaMetadata?.title ?: "Canción" else "Escuchar en GPS3D"
        subtitle.text = when {
            importing -> "Añadiendo canciones…"
            player?.playerError != null -> "Audio no disponible · toca ♫ para elegir otro"
            !hasMusic -> "Toca ▶ para elegir música aquí"
            player?.playbackState == Player.STATE_BUFFERING -> "Cargando canción…"
            player?.isPlaying == true -> "${if (online) "Audio en línea" else "Canciones del teléfono"} · ${player.currentMediaItemIndex + 1}/${player.mediaItemCount}"
            else -> "En pausa · ${if (online) "audio en línea" else "canciones del teléfono"}"
        }
        val cover=player?.mediaMetadata?.artworkUri?.toString().orEmpty()
        if(cover!=artworkUri || !localArtworkActive){artworkUri=cover;localArtworkActive=true;setArtwork(null,null)
            if(cover.isNotBlank())covers.load(cover){bitmap->if(!closed && !external && artworkUri==cover)setArtwork(bitmap,null)}
        } else if(cover.isBlank())setArtwork(null,null)
        val artist=player?.mediaMetadata?.extras?.getString("track_artist").orEmpty()
        val origin=player?.mediaMetadata?.extras?.getString("track_source").orEmpty()
        if(hasMusic && artist.isNotBlank() && player?.playerError==null)subtitle.text=artist+(if(origin.isNotBlank()) " · ${origin.replaceFirstChar{it.uppercase()}}" else "")
        for (button in listOf(previous, play, next)) button.visibility = View.VISIBLE
        previous.isEnabled = hasMusic && (player?.mediaItemCount ?: 0) > 1
        next.isEnabled = previous.isEnabled; play.isEnabled = true
        val playing = player?.playWhenReady == true
        play.setImageResource(if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        play.contentDescription = if (playing) "Pausar música" else "Reproducir música"
        setProgress(player?.currentPosition ?: 0, (player?.duration ?: 0).coerceAtLeast(0), hasMusic && player?.isCurrentMediaItemSeekable == true)
    }
    private fun renderExternal() {
        localArtworkActive = false
        val s = remote.state()
        title.text = when {
            s.connected -> s.title.ifBlank { "Reproducción en ${s.appName}" }
            remote.preferredPackage.isNotBlank() -> s.appName
            else -> "Conecta tu música"
        }
        subtitle.text = when {
            !remote.access -> "Toca ▶ para vincular tu reproductor"
            !s.connected -> "Elige música en ${if (s.packageName.isBlank()) "tu app" else s.appName} y vuelve al mapa"
            s.buffering -> "${s.appName} · Cargando…"
            s.artist.isNotBlank() -> "${s.artist} · ${s.appName}"
            else -> "${s.appName} · ${if (s.playing) "Reproduciendo" else "En pausa"}"
        }
        setArtwork(s.artwork, s.icon)
        previous.isEnabled = s.connected && s.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        next.isEnabled = s.connected && s.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        play.isEnabled = true
        for (button in listOf(previous, play, next)) button.visibility = View.VISIBLE
        play.setImageResource(if (s.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        play.contentDescription = if (s.playing) "Pausar música" else if (s.connected) "Reproducir música" else "Conectar reproductor de música"
        setProgress(s.position, s.duration, s.connected && s.actions and PlaybackState.ACTION_SEEK_TO != 0L)
    }
    private fun setArtwork(bitmap: android.graphics.Bitmap?, icon: android.graphics.drawable.Drawable?) {
        val value: Any? = bitmap ?: icon
        if (lastArtwork !== value || value == null) {
            lastArtwork = value
            artwork.imageTintList = null
            if (bitmap != null) artwork.setImageBitmap(bitmap)
            else if (icon != null) artwork.setImageDrawable(icon)
            else { artwork.setImageResource(R.drawable.ic_music); artwork.imageTintList = android.content.res.ColorStateList.valueOf(title.currentTextColor) }
        }
    }
    private fun seekMusic(value: Int) {
        if (external) remote.state().let { remote.seek(it.duration * value / 1000) }
        else controller?.let { if (it.duration > 0) it.seekTo(it.duration * value / 1000) }
    }
    private fun setProgress(position: Long, duration: Long, seekable: Boolean) {
        if (dragging) return
        progress.isEnabled = seekable && duration > 0
        progress.progress = if (duration > 0) (position.coerceIn(0, duration) * 1000 / duration).toInt() else 0
        time.text = if (duration > 0) "${clock(position)}\n${clock(duration)}" else if (position > 0) clock(position) else "—:—"
    }
    private fun clock(ms: Long): String {
        val sec = ms.coerceAtLeast(0) / 1000
        return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
    }
    private fun showConnection() {
        AlertDialog.Builder(activity).setTitle("Conectar tu música")
            .setMessage("Para mostrar canción, artista, carátula y controles de Spotify, YouTube Music u otro reproductor, Android pide activar Controles de música GPS3D en Acceso a notificaciones.\n\nGPS3D utiliza sólo las sesiones multimedia; no procesa ni guarda mensajes o el contenido de tus notificaciones. Puedes revocar este acceso en Ajustes.")
            .setPositiveButton("Conectar") { _, _ ->
                val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, remote.component.flattenToString()) else null
                val intent = detail?.takeIf { it.resolveActivity(activity.packageManager) != null } ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                runCatching { activity.startActivity(intent) }.onFailure { toast("Abre Ajustes · Acceso a notificaciones · Controles de música GPS3D.") }
            }.setNegativeButton("Ahora no", null).show()
    }
    private fun openSelectedApp() {
        val launch = remote.openIntent()
        if (launch != null && runCatching { launch.send() }.isSuccess) return
        val packageName = remote.state().packageName
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) runCatching { activity.startActivity(intent) }
    }
    private fun playerPicker(): android.widget.LinearLayout {
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        box.addView(TextView(activity).apply { text = "Música para tu viaje"; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        box.addView(TextView(activity).apply { text = "Selecciona una aplicación de audio"; textSize = 13f; setPadding(0, dp(6), 0, dp(8)) })
        val available = linkedMapOf<String, String>()
        listOf("com.spotify.music", "com.google.android.apps.youtube.music").forEach { pkg ->
            if (activity.packageManager.getLaunchIntentForPackage(pkg) != null) available[pkg] = remote.label(pkg)
        }
        activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .forEach { r -> available.putIfAbsent(r.activityInfo.packageName, r.loadLabel(activity.packageManager).toString()) }
        remote.apps().forEach { available[it.packageName] = it.label }
        val row = android.widget.LinearLayout(activity)
        for ((pkg, label) in available) {
            val item = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(94), dp(100))
                isClickable = true; isFocusable = true; contentDescription = "Conectar $label"
                setOnClickListener { sourceDialog?.dismiss(); openMusicApp(pkg, label) }
            }
            item.addView(ImageView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), dp(52))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(runCatching { activity.packageManager.getApplicationIcon(pkg) }.getOrNull())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            item.addView(TextView(activity).apply { text = label; textSize = 12f; gravity = android.view.Gravity.CENTER; maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            row.addView(item)
        }
        if (available.isEmpty()) box.addView(TextView(activity).apply { text = "Abre tu app de música para conectarla, o usa tus archivos."; textSize = 12f })
        else box.addView(android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return box
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private var sourceDialog: AlertDialog? = null
    private fun showSources() {
        val items = arrayOf("Escuchar dentro de GPS3D", "Añadir enlace de audio", "Añadir canciones del teléfono", "Mi lista de canciones",
            "Bloqueo de anuncios en audio", "Controlar otra app de música", "Abrir Spotify", "Abrir YouTube Music", "Abrir otra aplicación de música", "Vaciar mi lista")
        AlertDialog.Builder(activity).setCustomTitle(playerPicker())
            .setItems(items) { _, which -> when (which) {
                0 -> if (expanded) useLocal { showInAppMusic() } else expand()
                1 -> showAudioLink()
                2 -> useLocal { files.launch(arrayOf("audio/*")) }
                3 -> useLocal { showQueue() }
                4 -> showAdFilter()
                5 -> { useExternal(); remote.select(""); if (!remote.access) showConnection() else if (!remote.state().connected) toast("Elige música en tu app y vuelve al mapa.") }
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
            } }.setNegativeButton("Cerrar", null).create().also { sourceDialog = it; it.show() }
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
                        playTracks(queue, which)
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
        useExternal(); remote.select(packageName)
        if (!remote.access) { showConnection(); return }
        if (remote.state().connected) { render(); return }
        val launch = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) { toast("$name no tiene un reproductor disponible. Abre la app y elige una canción."); return }
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
        if (closed) return
        closed = true
        sourceDialog?.dismiss()
        main.removeCallbacksAndMessages(null); remote.close(); activity.lifecycle.removeObserver(lifecycle)
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        artworkNetwork.close();io.shutdown()
    }
    companion object {
        const val SOURCE = "control_source"
        const val LOCAL = "local"
        const val EXTERNAL = "external"
        const val NATIVE_DEFAULT = "native_player_030"
    }
}
