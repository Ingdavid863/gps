package com.david.gps3dar.music

import android.os.Handler
import android.os.Looper

/** Android's inter-app ducking does not cover music and speech sharing our UID. */
object NavigationAudioDucker {
    private val main = Handler(Looper.getMainLooper())
    private val state = SpeechDuckingState()
    private val listeners = mutableSetOf<(Float) -> Unit>()
    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
    fun begin(owner: String, token: String) = dispatch {
        state.begin(owner, token); listeners.toList().forEach { it(state.volume) }
    }
    fun end(owner: String, token: String? = null) = dispatch {
        state.end(owner, token); listeners.toList().forEach { it(state.volume) }
    }
    fun observe(listener: (Float) -> Unit) = dispatch { listeners.add(listener); listener(state.volume) }
    fun remove(listener: (Float) -> Unit) = dispatch { listeners.remove(listener); Unit }
}
