package com.david.gps3dar

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.graphics.drawable.BitmapDrawable
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gps3dar.music.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalMusicControlTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val fixture=Uri.parse("content://com.david.gps3dar.test.music/state")
    private val prefs get()=context.getSharedPreferences(MusicPlaybackService.PREFS,Context.MODE_PRIVATE)
    private val documents=mutableListOf<Uri>()
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(message: String, details: (() -> String)? = null, check: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<end) { if(check())return;Thread.sleep(60) }
        fail(message + (details?.let { " · " + it() } ?: ""))
    }
    private fun state(): JSONObject=context.contentResolver.query(fixture,null,null,null,null)!!.use {
        check(it.moveToFirst());JSONObject(it.getString(0))
    }
    private fun connect(): MediaController {
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        main { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync() }
        return future!!.get(10,TimeUnit.SECONDS)
    }
    private fun click(scenario: ActivityScenario<RealisticMapActivity>, id: Int) {
        scenario.onActivity { it.findViewById<android.view.View>(id).performClick() }
    }
    private fun clickText(text: String, dialogTitle: String = "Música para tu viaje") {
        await("dialog option appears: $text") {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            // The song title is also in the map behind the dialog. Wait for the dialog window,
            // otherwise a fast accessibility click can reopen the source menu instead of selecting a track.
            if(root?.findAccessibilityNodeInfosByText(dialogTitle).orEmpty().none { it.text?.toString()==dialogTitle }) false
            else {
                val node=root?.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { it.text?.toString().equals(text,ignoreCase=true) }
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true ||
                    node?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            }
        }
    }
    private fun startExternalAudio() {
        context.startActivity(Intent().setComponent(ComponentName("com.david.gps3dar.test",
            "com.david.gps3dar.fixture.MusicFixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("another application is actually playing PCM audio") { state().optBoolean("playing") && state().optLong("position")>150 }
        assertNotEquals("media player must run in a different Android UID",context.applicationInfo.uid,state().getInt("uid"))
    }
    private fun access(enabled: Boolean) {
        val action=if(enabled) "allow_listener" else "disallow_listener"
        val component=ComponentName(context,MusicSessionAccessService::class.java).flattenToString()
        instrumentation.uiAutomation.executeShellCommand("cmd notification $action $component").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        await("Android notification access changes") { ExternalMusicSessions.hasAccess(context)==enabled }
    }
    private fun text(scenario: ActivityScenario<RealisticMapActivity>, id: Int):String {
        var value="";scenario.onActivity { value=it.findViewById<TextView>(id).text.toString() };return value
    }
    @Before fun prepare() {
        access(true)
        context.deleteFile("saved-navigation.json")
        context.contentResolver.call(fixture,"stop",null,null)
        val old=connect();main { old.stop();old.clearMediaItems();old.release() }
        context.stopService(Intent(context,MusicPlaybackService::class.java))
        prefs.edit().clear().putBoolean(MusicPanelController.NATIVE_DEFAULT,true)
            .putString(MusicPanelController.SOURCE,MusicPanelController.EXTERNAL).commit()
    }
    @After fun cleanup() {
        access(false)
        context.contentResolver.call(fixture,"stop",null,null)
        val local=connect();main { local.stop();local.clearMediaItems();local.release() }
        context.stopService(Intent(context,MusicPlaybackService::class.java))
        documents.forEach { context.contentResolver.delete(it,null,null) }
        prefs.edit().clear().commit()
    }
    @Test fun emptyLocalListStillHasButtonsThatControlAnIndependentMusicApp() {
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for(id in listOf(R.id.musicPrevious,R.id.musicPlayPause,R.id.musicNext)) {
                    assertTrue("transport buttons visible without local files",activity.findViewById<ImageButton>(id).isShown)
                }
                assertEquals("Conecta tu música",activity.findViewById<TextView>(R.id.musicTitle).text.toString())
                assertFalse(activity.findViewById<ImageButton>(R.id.musicNext).isEnabled)
            }
            startExternalAudio()
            await("actual title and artist arrive from another app's session") {
                text(scenario,R.id.musicTitle)=="External track 1" && text(scenario,R.id.musicSubtitle).contains("Artista de prueba 1")
            }
            scenario.onActivity { activity ->
                assertEquals("Pausar música",activity.findViewById<ImageButton>(R.id.musicPlayPause).contentDescription)
                val bitmap=(activity.findViewById<ImageView>(R.id.musicArtwork).drawable as BitmapDrawable).bitmap
                assertEquals(android.graphics.Color.rgb(28,120,90),bitmap.getPixel(bitmap.width/2,bitmap.height/2))
                val seek=activity.findViewById<SeekBar>(R.id.musicProgress)
                assertTrue(seek.isEnabled)
                assertTrue(seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,500f) }))
            }
            await("the map seeks the external decoder rather than only moving a fake bar") { state().optLong("position") in 9000L..14000L }
            await("duration is actual media metadata") { text(scenario,R.id.musicTime).contains("0:20") }
            click(scenario,R.id.musicNext)
            await("next arrives at external player and changes its audio track") { state().optInt("index")==1 && state().optInt("next")==1 && state().optBoolean("playing") }
            await("the mini-player title follows the external next track") { text(scenario,R.id.musicTitle)=="External track 2" }
            click(scenario,R.id.musicPrevious)
            await("previous arrives at external player") { state().optInt("index")==0 && state().optInt("previous")==1 }
            click(scenario,R.id.musicPlayPause)
            await("map pauses external audio") { !state().optBoolean("playing") && state().optInt("pause")>0 }
            await("paused state is reflected by the play icon") {
                var paused=false;scenario.onActivity { paused=it.findViewById<ImageButton>(R.id.musicPlayPause).contentDescription=="Reproducir música" };paused
            }
            click(scenario,R.id.musicPlayPause)
            await("map resumes external audio") { state().optBoolean("playing") && state().optInt("play")>=2 }
            scenario.recreate()
            await("the title reconnects after reopening the map") { text(scenario,R.id.musicTitle)=="External track 1" }
            click(scenario,R.id.musicNext)
            await("reopening GPS3D does not steal the external media session") { state().optInt("next")==2 && state().optBoolean("playing") }
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,"android-external-music-controls.png")
                put(MediaStore.Downloads.MIME_TYPE,"image/png");put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DARQA")
            })!!
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun notificationAccessIsExplainedAndRevocationClearsTheMiniPlayer() {
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            startExternalAudio()
            await("the external session connects") { text(scenario,R.id.musicTitle)=="External track 1" }
            access(false)
            await("revocation removes stale track information") { text(scenario,R.id.musicTitle)=="Conecta tu música" }
            scenario.onActivity { assertFalse(it.findViewById<ImageButton>(R.id.musicNext).isEnabled) }
            click(scenario,R.id.musicPlayPause)
            await("manual Android access is explained before opening settings") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Conectar tu música").orEmpty().isNotEmpty()
            }
            clickText("Ahora no","Conectar tu música")
            assertEquals("a denied connection must not send an unrelated global command",0,state().optInt("pause"))
            access(true)
            await("granting access reconnects to the real track") { text(scenario,R.id.musicTitle)=="External track 1" }
        }
    }
    @Test fun anUnavailableSelectedAppCannotControlAnotherActivePlayer() {
        prefs.edit().putString(ExternalMusicSessions.PREFERRED,"com.example.unavailable.music").commit()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            startExternalAudio()
            scenario.onActivity { assertFalse(it.findViewById<ImageButton>(R.id.musicNext).isEnabled) }
            assertNotEquals("External track 1",text(scenario,R.id.musicTitle))
            assertEquals(0,state().optInt("next"))
            prefs.edit().putString(ExternalMusicSessions.PREFERRED,"").commit()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await("automatic selection reconnects after explicitly clearing the preferred app") { text(scenario,R.id.musicTitle)=="External track 1" }
            click(scenario,R.id.musicNext)
            await("the actual selected session receives next") { state().optInt("next")==1 }
        }
    }
    private fun localTrack(): MusicTrack {
        val uri=context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"GPS3D-QA-local-handoff.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/GPS3DQA")
        })!!
        documents.add(uri)
        val count=8000*20
        val wav=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+count*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count*2)
        repeat(count) { wav.putShort(0) }
        context.contentResolver.openOutputStream(uri)!!.use { it.write(wav.array()) }
        return MusicTrack(uri.toString(),"Mi archivo conservado")
    }
    @Test fun switchingToAnotherAppReleasesLocalMediaKeysAndKeepsTheSavedPlaylist() {
        val song=localTrack()
        prefs.edit().putString(MusicPanelController.SOURCE,MusicPanelController.LOCAL)
            .putString(MusicPlaybackService.QUEUE,MusicQueue(listOf(song),0,5000).encode()).commit()
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            val local=connect()
            try {
                click(scenario,R.id.musicPlayPause)
                await("local music starts") { var playing=false;main { playing=local.isPlaying };playing }
                click(scenario,R.id.musicSourceButton);clickText("Controlar otra app de música")
                await("our media session is released before controlling the other app") { var connected=true;main { connected=local.isConnected };!connected }
                val saved=MusicQueue.decode(prefs.getString(MusicPlaybackService.QUEUE,"").orEmpty())
                assertEquals(listOf(song),saved.tracks);assertTrue(saved.positionMs>=5000)
                startExternalAudio()
                click(scenario,R.id.musicNext)
                await("local queue cannot intercept the external next command") { state().optInt("next")==1 && state().optInt("index")==1 }
                click(scenario,R.id.musicSourceButton);clickText("Mi lista de canciones");clickText("Mi archivo conservado","Mis canciones")
                val restored=connect()
                try { await("saved local music can be selected again",details={
                    var detail=""
                    main { detail="connected=${restored.isConnected}, playing=${restored.isPlaying}, requested=${restored.playWhenReady}, state=${restored.playbackState}, item=${restored.currentMediaItem?.mediaId}, error=${restored.playerError}" }
                    detail
                }) {
                    var playing=false;main { playing=restored.isPlaying && restored.currentMediaItem?.mediaId==song.uri };playing
                } } finally { main { restored.release() } }
                await("returning to local music uses Android audio focus to pause the other player") { !state().optBoolean("playing") }
            } finally { main { local.release() } }
        }
    }
}
