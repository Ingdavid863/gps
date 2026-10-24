package com.david.gps3dar.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Small shared cover cache; fetching a cover never blocks map or music controls. */
class MusicArtwork(private val network: MusicNetwork,private val io: Executor) {
    private val main=Handler(Looper.getMainLooper())
    fun load(uri:String,ready:(Bitmap?)->Unit) {
        cache.get(uri)?.let {ready(it);return}
        if(!uri.startsWith("https://")){ready(null);return}
        io.execute {
            val bitmap=runCatching {
                val call=network.newCall(Request.Builder().url(uri).build());call.timeout().timeout(10,TimeUnit.SECONDS)
                call.execute().use { response ->
                    if(!response.isSuccessful)return@use null
                    val body=response.body ?: return@use null
                    val bytes=ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer=ByteArray(8192)
                        while(true){val count=input.read(buffer);if(count<0)break
                            if(bytes.size()+count>2*1024*1024)throw java.io.IOException("Carátula demasiado grande")
                            bytes.write(buffer,0,count)
                        }
                    }
                    val raw=bytes.toByteArray()
                    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(raw,0,raw.size,options)
                    options.inJustDecodeBounds=false;options.inSampleSize=1
                    while(options.outWidth/options.inSampleSize>512 || options.outHeight/options.inSampleSize>512)options.inSampleSize*=2
                    BitmapFactory.decodeByteArray(raw,0,raw.size,options)
                }
            }.getOrNull()
            bitmap?.let {cache.put(uri,it)}
            main.post{ready(bitmap)}
        }
    }
    companion object {
        private val cache=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    }
}
