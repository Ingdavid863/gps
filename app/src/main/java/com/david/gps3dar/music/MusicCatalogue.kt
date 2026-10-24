package com.david.gps3dar.music

import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public, ungated catalogues. No account credentials or installed music app are used. */
class MusicCatalogue(private val network: Call.Factory) {
    enum class Source(val label: String) { AUDIUS("Audius"), SOUNDCLOUD("SoundCloud") }
    private val streams = mutableMapOf<String, Pair<Long,String>>()
    fun search(query: String, source: Source): List<MusicTrack> {
        require(query.trim().length in 1..160)
        return when(source) {
            Source.AUDIUS -> {
                val address="https://discoveryprovider.audius.co/v1/tracks/search".toHttpUrl().newBuilder()
                    .addQueryParameter("query",query.trim()).addQueryParameter("limit","25").addQueryParameter("app_name","GPS3D").build()
                val data=JSONObject(get(address.toString())).optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    val item=data.optJSONObject(i) ?: return@mapNotNull null
                    if(item.optBoolean("is_stream_gated") || item.optJSONObject("access")?.optBoolean("stream",true)==false) return@mapNotNull null
                    val id=item.optString("id");val title=item.optString("title")
                    if(!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || title.isBlank()) return@mapNotNull null
                    MusicTrack("https://discoveryprovider.audius.co/v1/tracks/$id/stream?app_name=GPS3D",title.take(200),true,
                        artist=item.optJSONObject("user")?.optString("name").orEmpty().take(200),
                        artwork=item.optJSONObject("artwork")?.optString("480x480").orEmpty(),
                        durationMs=item.optLong("duration").coerceAtLeast(0)*1000,source="audius")
                }
            }
            Source.SOUNDCLOUD -> {
                val address="https://api-v2.soundcloud.com/search/tracks".toHttpUrl().newBuilder()
                    .addQueryParameter("q",query.trim()).addQueryParameter("limit","25").addQueryParameter("client_id",soundCloudKey()).build()
                val data=JSONObject(get(address.toString())).optJSONArray("collection") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    val item=data.optJSONObject(i) ?: return@mapNotNull null
                    if(!item.optBoolean("streamable") || item.optString("policy") !in PUBLIC_POLICIES || publicFormats(item).isEmpty()) return@mapNotNull null
                    val id=item.optLong("id");if(id<=0)return@mapNotNull null
                    MusicTrack("gps3d-music://soundcloud/$id/audio.m3u8",item.optString("title").take(200),true,
                        artist=(item.optJSONObject("publisher_metadata")?.opt("artist") as? String)?.takeIf { it.isNotBlank() }
                            ?: item.optJSONObject("user")?.optString("username").orEmpty().take(200),
                        artwork=item.optString("artwork_url").takeUnless { it=="null" }.orEmpty().replace("-large.","-t300x300."),
                        durationMs=item.optLong("duration").coerceAtLeast(0),source="soundcloud")
                }
            }
        }.filter { it.valid() }
    }
    @Synchronized fun soundCloudStream(id: String): String {
        require(id.matches(Regex("[0-9]{1,20}")))
        streams[id]?.takeIf { System.currentTimeMillis()-it.first < 240000 }?.let { return it.second }
        val key=soundCloudKey()
        val item=JSONObject(get("https://api-v2.soundcloud.com/tracks/$id?client_id=$key"))
        if(!item.optBoolean("streamable") || item.optString("policy") !in PUBLIC_POLICIES) throw IOException("La pista no ofrece reproducción pública")
        val choices=publicFormats(item)
        for(format in choices) {
            val url=format.optString("url").toHttpUrl()
            if(url.scheme!="https" || url.host!="api-v2.soundcloud.com") continue
            val builder=url.newBuilder().addQueryParameter("client_id",key)
            (item.opt("track_authorization") as? String)?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("track_authorization",it) }
            val data=try { JSONObject(get(builder.build().toString())) } catch (_:Exception) { continue }
            if(!data.isNull("licenseAuthToken") && data.optString("licenseAuthToken").isNotBlank()) continue
            val stream=data.optString("url")
            if(MusicTrack.directAudio(stream)) {
                streams[id]=System.currentTimeMillis() to stream
                return stream
            }
        }
        throw IOException("La pista no ofrece un flujo público compatible")
    }
    private fun publicFormats(item:JSONObject):List<JSONObject> {
        val formats=item.optJSONObject("media")?.optJSONArray("transcodings") ?: return emptyList()
        return (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }.filter {
            val format=it.optJSONObject("format")
            val mime=format?.optString("mime_type").orEmpty()
            format?.optString("protocol")=="hls" && (mime.contains("mpeg") || mime.contains("mp4") || mime.contains("aac")) && !it.optBoolean("snipped")
        }.sortedBy { if(it.optJSONObject("format")?.optString("mime_type").orEmpty().contains("mpeg"))0 else 1 }
    }
    @Synchronized private fun soundCloudKey(): String {
        if(clientId.isNotBlank()) return clientId
        val mobile=runCatching { get("https://m.soundcloud.com") }.getOrDefault("")
        Regex("\"clientId\"\\s*:\\s*\"([A-Za-z0-9]{20,64})\"").find(mobile)?.groupValues?.get(1)?.let { clientId=it;return it }
        val web=get("https://soundcloud.com")
        val files=Regex("https://[A-Za-z0-9.-]*sndcdn\\.com/[^\"'<>\\s]+\\.js").findAll(web).map { it.value }.distinct().take(16)
        for(file in files) {
            val script=runCatching { get(file) }.getOrNull() ?: continue
            Regex("client_id\\s*:\\s*\"([A-Za-z0-9]{20,64})\"").find(script)?.groupValues?.get(1)?.let { clientId=it;return it }
        }
        throw IOException("No se pudo conectar con el catálogo")
    }
    fun get(url: String): String {
        val host=url.toHttpUrl().host
        val builder=Request.Builder().url(url).header("User-Agent",if(host=="m.soundcloud.com")MOBILE_AGENT else AGENT)
        if(url.toHttpUrl().host.endsWith("soundcloud.com"))builder.header("Origin","https://soundcloud.com").header("Referer","https://soundcloud.com/")
        val request=builder.build()
        val call=network.newCall(request);call.timeout().timeout(12,TimeUnit.SECONDS)
        call.execute().use { response ->
            if(!response.isSuccessful) { if(response.code==401||response.code==403)clientId="";throw IOException("Catálogo no disponible (${response.code})") }
            val body=response.body ?: throw IOException("Respuesta vacía")
            val out=ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break
                    if(out.size()+count>4*1024*1024)throw IOException("Respuesta demasiado grande")
                    out.write(buffer,0,count)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
    companion object {
        private val PUBLIC_POLICIES=setOf("ALLOW","MONETIZE")
        @Volatile private var clientId = ""
        const val AGENT="Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0"
        private const val MOBILE_AGENT="Mozilla/5.0 (iPhone; CPU iPhone OS 16_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/99.0.4844.47 Mobile/15E148 Safari/604.1"
    }
}
