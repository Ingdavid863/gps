package com.david.gps3dar.music

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicCatalogueTest {
    @Test fun realAudiusCatalogueReturnsPublicAudioThroughThePlayersFilter() = live(MusicCatalogue.Source.AUDIUS,"piano")
    @Test fun realSoundCloudCatalogueReturnsPublicHlsThroughThePlayersFilter() = live(MusicCatalogue.Source.SOUNDCLOUD,"Kevin MacLeod")
    private fun live(source:MusicCatalogue.Source,query:String) {
        assumeTrue(System.getenv("RUN_MUSIC_LIVE_QA")=="1")
        val network=MusicNetwork { true }
        try {
            val catalogue=MusicCatalogue(network)
            val before=System.nanoTime()
            val tracks=catalogue.search(query,source)
            println("${source.label}: ${tracks.size} tracks in ${(System.nanoTime()-before)/1000000} ms")
            assertTrue("Real public catalogue has music",tracks.isNotEmpty())
            val track=tracks.first()
            assertTrue(track.title.isNotBlank());assertTrue(track.artist.isNotBlank())
            val address=if(source==MusicCatalogue.Source.SOUNDCLOUD) {
                val id=java.net.URI(track.uri).path.split('/')[1]
                val key=MusicCatalogue::class.java.getDeclaredMethod("soundCloudKey").apply { isAccessible=true }.invoke(catalogue) as String
                val info=org.json.JSONObject(catalogue.get("https://api-v2.soundcloud.com/tracks/$id?client_id=$key"))
                val formats=info.getJSONObject("media").getJSONArray("transcodings")
                println("SoundCloud public track $id: policy=${info.optString("policy")}, formats="+(0 until formats.length()).map {
                    val item=formats.getJSONObject(it)
                    listOf(item.optString("preset"),item.optJSONObject("format")?.toString(),item.optBoolean("snipped"))
                })
                catalogue.soundCloudStream(id)
            } else track.uri
            network.newCall(Request.Builder().url(address).header("User-Agent",MusicCatalogue.AGENT)
                .header("Range","bytes=0-1023").build()).execute().use { response ->
                assertTrue("${source.label} audio HTTP ${response.code}",response.isSuccessful)
                assertEquals(1024,response.body!!.source().readByteArray(1024).size)
                assertTrue(response.header("Content-Type").orEmpty().contains(if(source==MusicCatalogue.Source.SOUNDCLOUD)"mpegurl" else "audio"))
            }
        } finally { network.close() }
    }
}
