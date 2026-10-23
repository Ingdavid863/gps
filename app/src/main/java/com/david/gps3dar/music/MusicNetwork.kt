package com.david.gps3dar.music

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Filtering is confined to this player's HTTP client. Maps/routing/GNSS never use it. */
class MusicNetwork(private val filterEnabled: () -> Boolean) : Call.Factory {
    private val closed = AtomicBoolean()
    private val plain = OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS).followSslRedirects(false).build()
    private val filtered by lazy {
        plain.newBuilder().dns(RejectBlockedDns(DnsOverHttps.Builder().client(plain)
            .url("https://dns.adguard-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("94.140.14.14"),InetAddress.getByName("94.140.15.15"))
            .build())).build()
    }
    override fun newCall(request: Request): Call {
        check(!closed.get()) { "Music connection has been released" }
        return (if (filterEnabled()) filtered else plain).newCall(request)
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing an idle TLS socket can write a close-notify packet. A service is
        // destroyed on the UI thread, where Android forbids even this network I/O.
        Thread({
            plain.dispatcher.cancelAll()
            plain.connectionPool.evictAll()
            plain.dispatcher.executorService.shutdown()
        }, "GPS3D-music-close").start()
        // Both clients share the same dispatcher/pool; release them exactly once.
    }
    companion object { const val FILTER = "music_ad_filter" }
}

/** DNS block responses must never become connections to localhost or 0.0.0.0. */
internal class RejectBlockedDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress })
            throw UnknownHostException("Dominio bloqueado o no disponible")
        return addresses
    }
}
