package com.david.gps3dar.music

import org.json.JSONArray
import org.json.JSONObject

/** Sources are selected inside the player; no scan of the phone or extraction from other apps. */
data class MusicTrack(val uri: String, val title: String, val online: Boolean = false, val live: Boolean = false,
    val artist: String = "", val artwork: String = "", val durationMs: Long = 0, val source: String = "") {
    fun valid(): Boolean = if (source == "soundcloud") online && !live && uri.matches(Regex("gps3d-music://soundcloud/[0-9]{1,20}/audio\\.m3u8"))
        else if (!online) uri.startsWith("content://") && uri.length in 11..4096
        else directAudio(uri)
    companion object {
        fun directAudio(uri: String): Boolean = runCatching {
            val url = java.net.URI(uri)
            val host = url.host?.lowercase() ?: return false
            val pages = listOf("youtube.com", "youtu.be", "spotify.com")
            uri.length <= 4096 && url.scheme == "https" && url.userInfo == null &&
                pages.none { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
    }
}
data class MusicQueue(val tracks: List<MusicTrack>, val index: Int = 0, val positionMs: Long = 0) {
    fun encode(): String = JSONObject().put("version", 3).put("index", index).put("positionMs", positionMs)
        .put("tracks", JSONArray(tracks.take(MAX_TRACKS).map {
            JSONObject().put("uri", it.uri).put("title", it.title.take(200)).put("online", it.online).put("live", it.live).put("artist",it.artist.take(200)).put("artwork",it.artwork.take(2048)).put("durationMs",it.durationMs).put("source",it.source)
        })).toString()

    companion object {
        const val MAX_TRACKS = 100
        fun decode(json: String): MusicQueue = runCatching {
            val root = JSONObject(json)
            val version = root.getInt("version"); require(version in 1..3)
            val list = root.getJSONArray("tracks")
            val tracks = (0 until minOf(list.length(), MAX_TRACKS)).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                val uri = item.optString("uri")
                val track = MusicTrack(uri, item.optString("title", "Canción").take(200).ifBlank { "Canción" },
                    version >= 2 && item.optBoolean("online"), version >= 2 && item.optBoolean("live"),
                    if(version>=3)item.optString("artist").take(200) else "",
                    if(version>=3)item.optString("artwork").take(2048) else "",
                    if(version>=3)item.optLong("durationMs").coerceIn(0,604800000) else 0,
                    if(version>=3)item.optString("source") else "")
                track.takeIf { it.valid() }
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
