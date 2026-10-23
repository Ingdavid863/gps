package com.david.gps3dar.music

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicNetworkTest {
    @Test fun filteringResponsesCannotConnectThePlayerToNullOrLoopbackAddresses() {
        for (ip in listOf("0.0.0.0","127.0.0.1","::")) {
            val dns=RejectBlockedDns(object : Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName(ip)) })
            try { dns.lookup("ad.example");fail(ip) } catch (_:UnknownHostException) { }
        }
        val good=InetAddress.getByName("8.8.8.8")
        assertEquals(listOf(good),RejectBlockedDns(object : Dns { override fun lookup(hostname:String)=listOf(good) }).lookup("radio.example"))
    }
    @Test fun liveFilteredConnectionReceivesMusicAndBlocksAnAdvertisingDomain() {
        assumeTrue(System.getenv("RUN_MUSIC_LIVE_QA")=="1")
        val network=MusicNetwork { true }
        try {
            for (stream in listOf("mp3-128","rock-128","mellow-128")) network.newCall(Request.Builder().url("https://stream.radioparadise.com/$stream").build()).execute().use { r ->
                assertTrue("live music HTTP ${r.code}",r.isSuccessful)
                assertTrue(r.header("Content-Type").orEmpty().contains("audio"))
                assertEquals(1024,r.body!!.source().readByteArray(1024).size)
            }
            try {
                network.newCall(Request.Builder().url("https://doubleclick.net/").build()).execute().use { fail("The filter must block the ad domain before HTTP, received ${it.code}") }
            } catch (expected:UnknownHostException) { }
        } finally { network.close() }
    }
}
