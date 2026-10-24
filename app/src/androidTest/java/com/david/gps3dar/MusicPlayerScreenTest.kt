package com.david.gps3dar

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.david.gps3dar.music.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real catalogue HTTP, native audio decoding and the full-screen -> map handoff. */
@RunWith(AndroidJUnit4::class)
class MusicPlayerScreenTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(message:String,timeout:Long=45000,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){var okay=false;main{okay=check()};if(okay)return;Thread.sleep(80)}
        fail(message)
    }
    @Test fun audiusSearchPlaysInGps3dAndBackMinimizesWithoutRestarting() = catalogue(0,"piano","audius")
    @Test fun soundCloudSearchResolvesAndDecodesItsPublicHlsInsideGps3d() = catalogue(1,"Kevin MacLeod","soundcloud")
    private fun catalogue(provider:Int,term:String,origin:String) {
        context.deleteFile("saved-navigation.json")
        context.stopService(Intent(context,MusicPlaybackService::class.java));instrumentation.waitForIdleSync()
        val preferences=context.getSharedPreferences(MusicPlaybackService.PREFS,Context.MODE_PRIVATE)
        preferences.edit().clear().putString(MusicPanelController.SOURCE,MusicPanelController.LOCAL).commit()
        var screen:MusicPlayerActivity?=null
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
            main{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync()}
            val player=future!!.get(10,TimeUnit.SECONDS)
            try {
                scenario.onActivity{it.findViewById<TextView>(R.id.musicTitle).performClick()}
                await("The music area must open a screen owned by GPS3D") {
                    screen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MusicPlayerActivity>().firstOrNull()
                    screen!=null
                }
                main {
                    screen!!.findViewById<Spinner>(R.id.musicCatalogueSource).setSelection(provider)
                    screen!!.findViewById<EditText>(R.id.musicSearch).setText(term)
                    screen!!.findViewById<View>(R.id.musicSearchGo).performClick()
                }
                await("The real $origin catalogue must return selectable music") { screen!!.findViewById<LinearLayout>(R.id.musicResults).childCount>0 }
                var expectedTitle="";var expectedArtist=""
                main {
                    val row=screen!!.findViewById<LinearLayout>(R.id.musicResults).getChildAt(0) as LinearLayout
                    expectedTitle=(row.getChildAt(0) as TextView).text.toString()
                    expectedArtist=(row.getChildAt(1) as TextView).text.toString()
                    row.performClick()
                }
                await("The native decoder must actually advance through a $origin song") {
                    if(player.playerError!=null)fail("$origin decoder error: ${player.playerError!!.errorCodeName}; ${player.playerError!!.cause?.javaClass?.simpleName}")
                    player.isPlaying && player.currentPosition>1000 && player.duration>1000
                }
                await("Full player must show the actual title and artist") {
                    screen!!.findViewById<TextView>(R.id.musicTitle).text.toString()==expectedTitle &&
                        screen!!.findViewById<TextView>(R.id.musicSubtitle).text.toString().contains(expectedArtist)
                }
                main {
                    assertEquals(origin,player.mediaMetadata.extras?.getString("track_source"))
                    screen!!.findViewById<View>(R.id.musicPlayPause).performClick()
                }
                await("The full player pauses real playback"){!player.playWhenReady}
                main{screen!!.findViewById<View>(R.id.musicPlayPause).performClick()}
                await("The full player resumes real playback"){player.isPlaying}
                instrumentation.waitForIdleSync()
                screenshot("android-music-$origin-full.png")
                var before=0L;main{before=player.currentPosition}
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back must return to the existing map while the same song keeps playing") {
                    val active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    active.any{it is RealisticMapActivity} && active.none{it is MusicPlayerActivity} &&
                        player.isPlaying && player.currentPosition>before+300
                }
                scenario.onActivity {
                    assertEquals(Lifecycle.State.RESUMED,it.lifecycle.currentState)
                    assertEquals(expectedTitle,it.findViewById<TextView>(R.id.musicTitle).text.toString())
                    assertTrue(it.findViewById<View>(R.id.avoidTollsButton).isShown)
                }
                val saved=MusicQueue.decode(preferences.getString(MusicPlaybackService.QUEUE,"").orEmpty())
                assertEquals(origin,saved.tracks.single().source)
                assertEquals(expectedArtist,saved.tracks.single().artist)
                screenshot("android-music-$origin-minimized.png")
            } finally {
                main{screen?.takeUnless{it.isDestroyed}?.finish();player.stop();player.clearMediaItems();player.release()}
            }
        }
        context.stopService(Intent(context,MusicPlaybackService::class.java));preferences.edit().clear().commit()
    }
    private fun screenshot(name:String) {
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"image/png")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/GPS3DARQA")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
