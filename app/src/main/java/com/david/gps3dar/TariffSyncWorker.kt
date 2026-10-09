package com.david.gps3dar

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object TollCatalogStore {
    fun file(context: Context) = File(context.filesDir, "mx-tolls.json")
    fun load(context: Context): String {
        val downloaded = runCatching { AtomicFile(file(context)).openRead().bufferedReader().use { it.readText() }
            .also { TollCatalog(JSONObject(it)) } }.getOrNull()
        return downloaded ?: context.assets.open("mx-tolls.json").bufferedReader().use { it.readText() }
    }
}

/** The phone downloads a small validated catalog, never the national road database. */
class TariffSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val context = applicationContext
        val preferences=context.getSharedPreferences("tariff-sync", Context.MODE_PRIVATE)
        val request=Request.Builder().url(FEED).header("User-Agent", "GPS3D-AR-David/0.14")
        preferences.getString("etag", null)?.let { request.header("If-None-Match",it) }
        return runCatching {
            OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
                .newCall(request.build()).execute().use { response ->
                    if (response.code==304) {
                        preferences.edit().putLong("checked",System.currentTimeMillis()).apply()
                        return@use Result.success()
                    }
                    if (!response.isSuccessful) return@use Result.retry()
                    val input=response.body?.byteStream() ?: return@use Result.retry()
                    val bytes=input.use { stream ->
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while (output.size()<=3*1024*1024) {
                            val count=stream.read(buffer)
                            if(count<0) break
                            output.write(buffer,0,count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size>3*1024*1024) return@use Result.failure()
                    val json=bytes.toString(Charsets.UTF_8)
                    val catalog=TollCatalog(JSONObject(json))
                    require(catalog.plazas.size>=1200)
                    val current=TollCatalog(JSONObject(TollCatalogStore.load(context)))
                    require(catalog.revision>=current.revision)
                    if (catalog.revision>current.revision) {
                        val atomic=AtomicFile(TollCatalogStore.file(context))
                        val stream=atomic.startWrite()
                        try { stream.write(bytes);atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
                    }
                    preferences.edit().putLong("checked",System.currentTimeMillis()).putString("etag",response.header("ETag")).apply()
                    Result.success()
                }
        }.getOrElse { Result.retry() }
    }
    companion object {
        const val FEED="https://raw.githubusercontent.com/Ingdavid863/gps/codex/gps3d-0.14.0/app/src/main/assets/mx-tolls.json"
        fun schedule(context: Context) {
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            val manager=WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork("mx-tolls-daily",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TariffSyncWorker>(24,TimeUnit.HOURS).setConstraints(constraints).build())
            if (System.currentTimeMillis()-context.getSharedPreferences("tariff-sync",Context.MODE_PRIVATE).getLong("checked",0)>TimeUnit.HOURS.toMillis(24))
                manager.enqueueUniqueWork("mx-tolls-now",ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<TariffSyncWorker>().setConstraints(constraints).build())
        }
    }
}
