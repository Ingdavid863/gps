package com.david.gps3dar.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.david.gps3dar.RealisticMapActivity

/** The service owns playback, so rotating/reopening the map never restarts a song. */
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var network: MusicNetwork
    private val main = Handler(Looper.getMainLooper())
    private val duck: (Float) -> Unit = { volume -> session?.player?.volume = volume }
    private val checkpoint = object : Runnable {
        override fun run() {
            saveQueue()
            if (session?.player?.isPlaying == true) main.postDelayed(this, 5000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        network = MusicNetwork { getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(MusicNetwork.FILTER, true) }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this,
            OkHttpDataSource.Factory(network).setUserAgent("GPS3D-AR-David/0.30")))
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = Player.REPEAT_MODE_ALL
            val saved = MusicQueue.decode(getSharedPreferences(PREFS, MODE_PRIVATE).getString(QUEUE, "").orEmpty())
            if (saved.tracks.isNotEmpty()) setMediaItems(saved.tracks.map(::mediaItem), saved.index,
                if (saved.tracks[saved.index].live) 0 else saved.positionMs)
        }
        val openMap = PendingIntent.getActivity(this, 28, Intent(this, RealisticMapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(openMap)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>): com.google.common.util.concurrent.ListenableFuture<List<MediaItem>> {
                    if (controller.packageName != packageName) return Futures.immediateFailedFuture(SecurityException("App queue only"))
                    return Futures.immediateFuture(mediaItems.take(MusicQueue.MAX_TRACKS).map { item ->
                        require(track(item).valid())
                        item.buildUpon().setUri(item.mediaId).build()
                    })
                }
            }).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) saveQueue()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    main.removeCallbacks(checkpoint)
                    if (player.isPlaying) main.postDelayed(checkpoint, 5000)
                }
            }
        })
        NavigationAudioDucker.observe(duck)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    private fun saveQueue() {
        val player = session?.player ?: return
        val tracks = (0 until player.mediaItemCount).map { i ->
            val item = player.getMediaItemAt(i)
            track(item)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(QUEUE,
            MusicQueue(tracks, player.currentMediaItemIndex.coerceAtLeast(0),
                if (tracks.getOrNull(player.currentMediaItemIndex)?.live == true) 0 else player.currentPosition.coerceAtLeast(0)).encode()).apply()
    }
    /** Release our media-key consumer before the user controls another music app. */
    private fun handoff() {
        saveQueue()
        main.removeCallbacksAndMessages(null)
        NavigationAudioDucker.remove(duck)
        val old = session
        session = null
        old?.let { val player = it.player; player.pause(); it.release(); player.release() }
        network.close()
        instance = null
        stopSelf()
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        NavigationAudioDucker.remove(duck)
        saveQueue()
        session?.let { it.player.release(); it.release() }
        network.close()
        session = null
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }
    companion object {
        private var instance: WeakReference<MusicPlaybackService>? = null
        fun handoffToExternal() {
            check(Looper.myLooper() == Looper.getMainLooper())
            instance?.get()?.handoff()
        }
        const val PREFS = "gps3d_music"
        const val QUEUE = "queue"
        private fun track(item: MediaItem) = MusicTrack(item.mediaId,
            item.mediaMetadata.title?.toString() ?: "Canción", item.mediaMetadata.extras?.getBoolean("online") == true,
            item.mediaMetadata.extras?.getBoolean("live") == true)
        fun mediaItem(track: MusicTrack): MediaItem = MediaItem.Builder().setUri(track.uri).setMediaId(track.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(if (track.online) "Audio en GPS3D" else "Canciones del teléfono")
                .setExtras(android.os.Bundle().apply { putBoolean("online",track.online);putBoolean("live",track.live) }).build()).build()
    }
}
