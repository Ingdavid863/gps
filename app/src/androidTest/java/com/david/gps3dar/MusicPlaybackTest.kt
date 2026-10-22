package com.david.gps3dar

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gps3dar.music.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MusicPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val documents = mutableListOf<Uri>()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, check: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        var okay=false
        while(!okay && SystemClock.elapsedRealtime()<deadline) {
            onMain { okay=check() }
            if(!okay) Thread.sleep(40)
        }
        assertTrue(message,okay)
    }
    private fun connect(): MediaController {
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        onMain { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
        return future!!.get(10,TimeUnit.SECONDS)
    }
    private fun track(name: String): MusicTrack {
        val uri=context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"GPS3D-QA-$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/GPS3DQA")
        })!!
        documents.add(uri)
        val samples=8000*40
        val wav=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        repeat(samples) { wav.putShort(0) }
        context.contentResolver.openOutputStream(uri)!!.use { it.write(wav.array()) }
        return MusicTrack(uri.toString(),name)
    }
    @After fun cleanup() {
        onMain { NavigationAudioDucker.end("qa");NavigationAudioDucker.end("qa-overlap") }
        val controller=connect()
        onMain { controller.stop();controller.clearMediaItems();controller.release() }
        context.stopService(Intent(context,MusicPlaybackService::class.java))
        documents.forEach { context.contentResolver.delete(it,null,null) }
        context.getSharedPreferences(MusicPlaybackService.PREFS,Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun mapButtonsPlayPauseAndChangeRealLocalAudioWithoutChangingNavigation() {
        val tracks=listOf(track("Primera canción"),track("Segunda canción"))
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val player=connect()
            try {
                onMain { player.setMediaItems(tracks.map(MusicPlaybackService::mediaItem));player.prepare();player.play() }
                await("actual ExoPlayer starts the selected local WAV") { player.isPlaying }
                await("map receives music metadata") {
                    var okay=false
                    scenario.onActivity { okay=it.findViewById<TextView>(R.id.musicTitle).text.toString()=="Primera canción" }
                    okay
                }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.musicNext).performClick() }
                await("next track") { player.currentMediaItemIndex==1 }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.musicPrevious).performClick() }
                await("previous track") { player.currentMediaItemIndex==0 }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.musicPlayPause).performClick() }
                await("pause") { !player.playWhenReady }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.musicPlayPause).performClick() }
                await("resume") { player.isPlaying }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<ImageButton>(R.id.musicNext).isShown)
                    assertTrue(activity.findViewById<TextView>(R.id.avoidTollsButton).isShown)
                    assertTrue(activity.findViewById<ImageButton>(R.id.viewModeButton).isShown)
                    val panel=activity.findViewById<android.view.View>(R.id.bottomPanel)
                    val map=activity.findViewById<android.view.View>(R.id.webMapView)
                    assertTrue("music remains inside the footer measured by the map",panel.top>map.height/2)
                }
                val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val values=ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,"android-music-playing.png")
                    put(MediaStore.Downloads.MIME_TYPE,"image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DARQA")
                }
                val screenshot=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                context.contentResolver.openOutputStream(screenshot)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            } finally { onMain { player.release() } }
        }
    }
    @Test fun navigationDucksMusicAndStaleCompletionCannotRestoreItEarly() {
        val music=track("Avisos sobre la música")
        ActivityScenario.launch(RealisticMapActivity::class.java).use {
            val player=connect()
            try {
                onMain { player.setMediaItem(MusicPlaybackService.mediaItem(music));player.prepare();player.play() }
                await("music playing") { player.isPlaying }
                onMain { NavigationAudioDucker.begin("qa","old");NavigationAudioDucker.begin("qa","new") }
                await("navigation reduces actual player volume") { kotlin.math.abs(player.volume-.2f)<.01f && player.isPlaying }
                onMain { NavigationAudioDucker.end("qa","old");NavigationAudioDucker.begin("qa-overlap","car");NavigationAudioDucker.end("qa","new") }
                await("overlap remains ducked") { kotlin.math.abs(player.volume-.2f)<.01f }
                onMain { NavigationAudioDucker.end("qa-overlap","car") }
                await("normal volume restored and music still playing") { kotlin.math.abs(player.volume-1f)<.01f && player.isPlaying }
            } finally { onMain { player.release() } }
        }
    }
    @Test fun playbackSurvivesMapRecreationAndQueueRestoresPausedAfterServiceRestart() {
        val music=track("Mi canción guardada")
        val player=connect()
        try {
            ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
                onMain { player.setMediaItem(MusicPlaybackService.mediaItem(music));player.prepare();player.play() }
                await("music playing") { player.isPlaying }
                onMain { player.seekTo(12000) }
                scenario.recreate()
                await("reopening the map preserves song and position") { player.isPlaying && player.currentPosition>=12000 }
                onMain { player.pause() }
                await("checkpoint persisted") {
                    val saved=MusicQueue.decode(context.getSharedPreferences(MusicPlaybackService.PREFS,Context.MODE_PRIVATE)
                        .getString(MusicPlaybackService.QUEUE,"").orEmpty())
                    saved.tracks.singleOrNull()?.uri==music.uri && saved.positionMs>=12000
                }
            }
        } finally { onMain { player.release() } }
        context.stopService(Intent(context,MusicPlaybackService::class.java))
        instrumentation.waitForIdleSync()
        val restored=connect()
        try {
            await("saved whole queue restored") { restored.mediaItemCount==1 }
            onMain {
                assertEquals(music.uri,restored.currentMediaItem?.mediaId)
                assertFalse("reopening never autoplays",restored.playWhenReady)
                assertTrue("position restored",restored.currentPosition>=12000)
            }
        } finally { onMain { restored.release() } }
    }
}
