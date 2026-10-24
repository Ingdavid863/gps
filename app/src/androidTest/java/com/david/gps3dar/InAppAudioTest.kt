package com.david.gps3dar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gps3dar.music.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InAppAudioTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(message:String,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+20000
        while(SystemClock.elapsedRealtime()<end) { if(check())return;Thread.sleep(80) }
        fail(message)
    }
    @Test fun theMapStartsAndOwnsInternetAudioWithoutLaunchingAnotherApplication() {
        context.deleteFile("saved-navigation.json")
        context.stopService(Intent(context,MusicPlaybackService::class.java))
        val prefs=context.getSharedPreferences(MusicPlaybackService.PREFS,Context.MODE_PRIVATE)
        prefs.edit().clear().putString(MusicPanelController.SOURCE,MusicPanelController.LOCAL).commit()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            main { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
            val player=future!!.get(10,TimeUnit.SECONDS)
            try {
                scenario.onActivity { activity ->
                    assertEquals("The selected built-in player remains available", "Escuchar en GPS3D",activity.findViewById<TextView>(R.id.musicTitle).text.toString())
                    activity.findViewById<android.view.View>(R.id.musicPlayPause).performClick()
                }
                var expanded:MusicPlayerActivity?=null
                await("Tapping play opens GPS3D's own full player") {
                    main { expanded=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MusicPlayerActivity>().firstOrNull() }
                    expanded!=null
                }
                main { expanded!!.findViewById<View>(R.id.musicRadio).performClick()
                    expanded!!.findViewById<android.widget.LinearLayout>(R.id.musicResults).getChildAt(0).performClick() }
                fun playing(uri:String):Boolean { var okay=false;main { okay=player.isPlaying&&player.currentMediaItem?.mediaId==uri&&player.currentPosition>200 };return okay }
                await("GPS3D itself must decode and play actual HTTPS audio") { playing("https://stream.radioparadise.com/mp3-128") }
                main { expanded!!.findViewById<View>(R.id.musicMinimize).performClick() }
                await("Minimizing returns to the map while audio keeps playing") { var ready=false;scenario.onActivity { ready=it.lifecycle.currentState==Lifecycle.State.RESUMED };ready }
                scenario.onActivity { it.findViewById<View>(R.id.musicNext).performClick() }
                await("Map next changes the actual stream") { playing("https://stream.radioparadise.com/rock-128") }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.musicPrevious).performClick() }
                await("Map previous returns to the first stream") { playing("https://stream.radioparadise.com/mp3-128") }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.musicPlayPause).performClick() }
                await("Map pauses its own stream") { var paused=false;main { paused=!player.playWhenReady };paused }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.musicPlayPause).performClick() }
                await("Map resumes its own stream") { playing("https://stream.radioparadise.com/mp3-128") }
                scenario.recreate()
                await("Reopening the map preserves actual playback") { playing("https://stream.radioparadise.com/mp3-128") }
                scenario.moveToState(Lifecycle.State.CREATED)
                var before=0L;main { before=player.currentPosition };Thread.sleep(700)
                main { assertTrue("GPS3D continues audio in background",player.isPlaying);assertTrue(player.currentPosition>before+200) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                val saved=MusicQueue.decode(prefs.getString(MusicPlaybackService.QUEUE,"").orEmpty())
                assertTrue(saved.tracks.any { it.online && it.live });assertEquals(0L,saved.positionMs)
                // Releasing live TLS audio must also be safe from the service's main thread.
                main { MusicPlaybackService.handoffToExternal() }
                await("The live session releases without crashing the map") { var closed=false;main { closed=!player.isConnected };closed }
                scenario.onActivity { assertEquals(Lifecycle.State.RESUMED,it.lifecycle.currentState) }
            } finally { main { player.stop();player.clearMediaItems();player.release() } }
        }
        context.stopService(Intent(context,MusicPlaybackService::class.java));prefs.edit().clear().commit()
    }
}
