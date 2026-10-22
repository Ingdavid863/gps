package com.david.gps3dar.music

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/** Uses the same Android media-button path as a headset; never reads other apps' notifications. */
class ExternalMusicControls(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    fun send(key: Int): Boolean = runCatching {
        require(key in setOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0))
        true
    }.getOrDefault(false)
}
