package com.david.gps3dar

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class MapBufferTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun mapTileSurvivesLossOfNetworkAndAnAppRestart() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("api.tomtom.com").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("vector-tile").addHeader("Content-Type", "application/x-protobuf")
            .addHeader("Cache-Control", "public, max-age=0"))
        server.start()
        val transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build()
        val directory = temp.newFolder("tiles")
        val url = "https://api.tomtom.com:${server.port}/map/1/tile/basic/main/18/1/1.pbf?key=test"
        val first = MapResourceCache(directory, transport)
        assertEquals("vector-tile", first.bytes(url)!!.first.toString(Charsets.UTF_8))
        assertEquals(1, server.requestCount)
        first.close()
        server.shutdown()
        val restarted = MapResourceCache(directory, transport)
        assertEquals("vector-tile", restarted.bytes(url)!!.first.toString(Charsets.UTF_8))
        assertNull(restarted.bytes("https://api.tomtom.com/search/2/geocode/private-address.json"))
        assertNull(restarted.bytes("https://example.com/map/1/tile/basic/main/18/1/1.pbf"))
        restarted.close()
    }
}
