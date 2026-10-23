package com.david.gps3dar.music

import org.junit.Assert.*
import org.junit.Test

class MusicQueueTest {
    @Test fun selectedHttpsSourcesAndLiveFlagsSurviveWithoutAcceptingServiceWebPages() {
        val queue=MusicQueue(listOf(MusicTrack("content://music/one","Mi canción"),
            MusicTrack("https://stream.radioparadise.com/mp3-128","Radio",true,true)),1,0)
        assertEquals(queue,MusicQueue.decode(queue.encode()))
        for (url in listOf("http://example.com/song.mp3","file:///song.mp3","https://music.youtube.com/watch?v=abc","https://open.spotify.com/track/abc","https://user:password@example.com/song.mp3"))
            assertFalse(url,MusicTrack.directAudio(url))
        assertTrue(MusicTrack.directAudio("https://example.com/music/stream.m3u8"))
    }
    @Test fun remembersSelectedDocumentsAndPlaybackPosition() {
        val queue=MusicQueue(listOf(MusicTrack("content://music/one", "Canción ñ"),MusicTrack("content://music/two", "Otra")),1,85123)
        assertEquals(queue,MusicQueue.decode(queue.encode()))
    }
    @Test fun corruptOrUnselectedSourcesDoNotBecomePlayable() {
        assertEquals(emptyList<MusicTrack>(),MusicQueue.decode("broken").tracks)
        val loaded=MusicQueue.decode("""{"version":1,"index":9,"positionMs":-1,"tracks":[{"uri":"https://example.com/song"},{"uri":"file:///private/song"},{"uri":"content://music/one","title":"Bien"},{"uri":"content://music/one"}]}""")
        assertEquals(listOf(MusicTrack("content://music/one","Bien")),loaded.tracks)
        assertEquals(0,loaded.index);assertEquals(0L,loaded.positionMs)
    }
    @Test fun interruptedAndOverlappingGuidanceRestoresVolumeOnlyAtTheEnd() {
        val state=SpeechDuckingState()
        state.begin("phone","old");state.begin("phone","new")
        state.end("phone","old");assertEquals(.2f,state.volume,0f)
        state.begin("car","turn");state.end("phone","new")
        assertEquals(.2f,state.volume,0f)
        state.end("car","turn");assertEquals(1f,state.volume,0f)
        state.begin("walking","turn");state.end("walking")
        assertEquals(1f,state.volume,0f)
    }
}
