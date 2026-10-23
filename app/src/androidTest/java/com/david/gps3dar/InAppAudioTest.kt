package com.david.gps3dar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
        prefs.edit().clear().putString(MusicPanelController.SOURCE,MusicPanelController.EXTERNAL).commit()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            main { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
            val player=future!!.get(10,TimeUnit.SECONDS)
            try {
                scenario.onActivity { activity ->
                    assertEquals("Upgrade offers the built-in player", "Escuchar en GPS3D",activity.findViewById<TextView>(R.id.musicTitle).text.toString())
                    activity.findViewById<android.view.View>(R.id.musicPlayPause).performClick()
                }
                await("The station chooser appears inside GPS3D") {
                    val root=instrumentation.uiAutomation.rootInActiveWindow
                    if(root?.findAccessibilityNodeInfosByText("Escuchar dentro de GPS3D").orEmpty().none { it.text?.toString()=="Escuchar dentro de GPS3D" }) false
                    else root?.findAccessibilityNodeInfosByText("Radio Paradise · mezcla general").orEmpty()
                        .firstOrNull { it.text?.toString()=="Radio Paradise · mezcla general" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                }
                fun playing(uri:String):Boolean { var okay=false;main { okay=player.isPlaying&&player.currentMediaItem?.mediaId==uri&&player.currentPosition>200 };return okay }
                await("GPS3D itself must decode and play actual HTTPS audio") { playing("https://stream.radioparadise.com/mp3-128") }
                scenario.onActivity { assertEquals(Lifecycle.State.RESUMED,it.lifecycle.currentState);it.findViewById<android.view.View>(R.id.musicNext).performClick() }
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
            } finally { main { player.stop();player.clearMediaItems();player.release() } }
        }
        context.stopService(Intent(context,MusicPlaybackService::class.java));prefs.edit().clear().commit()
    }
}
