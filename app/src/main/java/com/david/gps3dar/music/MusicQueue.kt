package com.david.gps3dar.music

import org.json.JSONArray
import org.json.JSONObject

/** Only user-selected documents are remembered; no scan of the phone's storage. */
data class MusicTrack(val uri: String, val title: String)
data class MusicQueue(val tracks: List<MusicTrack>, val index: Int = 0, val positionMs: Long = 0) {
    fun encode(): String = JSONObject().put("version", 1).put("index", index).put("positionMs", positionMs)
        .put("tracks", JSONArray(tracks.take(MAX_TRACKS).map {
            JSONObject().put("uri", it.uri).put("title", it.title.take(200))
        })).toString()

    companion object {
        const val MAX_TRACKS = 100
        fun decode(json: String): MusicQueue = runCatching {
            val root = JSONObject(json)
            require(root.getInt("version") == 1)
            val list = root.getJSONArray("tracks")
            val tracks = (0 until minOf(list.length(), MAX_TRACKS)).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                val uri = item.optString("uri")
                if (!uri.startsWith("content://") || uri.length !in 11..4096) null
                else MusicTrack(uri, item.optString("title", "Canción").take(200).ifBlank { "Canción" })
            }.distinctBy { it.uri }
            MusicQueue(tracks, root.optInt("index").coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
                root.optLong("positionMs").coerceIn(0, 7 * 24 * 60 * 60 * 1000L))
        }.getOrDefault(MusicQueue(emptyList()))
    }
}

/** A late completion from an interrupted instruction must not unduck the next one. */
internal class SpeechDuckingState {
    private val speaking = mutableMapOf<String, String>()
    val volume: Float get() = if (speaking.isEmpty()) 1f else .20f
    fun begin(owner: String, token: String) { speaking[owner] = token }
    fun end(owner: String, token: String? = null) {
        if (token == null || speaking[owner] == token) speaking.remove(owner)
    }
}
