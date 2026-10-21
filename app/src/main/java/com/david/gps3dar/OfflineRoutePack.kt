package com.david.gps3dar

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Protected route resources: independent of the evictable browsing HTTP cache. */
class OfflineRoutePack(private val directory: File,
                       private val fetch: (String) -> Pair<ByteArray, String>?) {
    data class Progress(val saved: Int, val total: Int, val state: String) {
        val ready get() = total > 0 && saved == total && state == "ready"
    }
    private val coordinator = Executors.newSingleThreadScheduledExecutor()
    private val downloads = Executors.newFixedThreadPool(3)
    private val generation = AtomicInteger()
    @Volatile private var online = true
    @Volatile private var closed = false
    @Volatile private var active = false
    @Volatile private var progress = Progress(0, 0, "planning")
    @Volatile private var listener: (Progress) -> Unit = {}
    private var signature = ""
    private var resources = emptyList<String>()

    init { directory.mkdirs() }
    private fun name(url: String): String {
        // Credentials may rotate; public map data remains the same.
        val address = url.replace(Regex("([?&])key=[^&]*&?"), "$1").trimEnd('?', '&')
        return MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    internal fun read(url: String): Pair<ByteArray, String>? = runCatching {
        DataInputStream(File(directory, name(url)).inputStream().buffered()).use { input ->
            val mime = input.readUTF(); val size = input.readInt()
            require(size in 0..16 * 1024 * 1024)
            val bytes = ByteArray(size); input.readFully(bytes)
            require(input.read() == -1)
            bytes to mime
        }
    }.getOrNull()
    internal fun contains(url: String): Boolean = runCatching {
        DataInputStream(File(directory, name(url)).inputStream().buffered()).use {
            it.readUTF(); val size = it.readInt()
            size in 0..16 * 1024 * 1024 && it.available() == size
        }
    }.getOrDefault(false)

    @Synchronized internal fun write(url: String, resource: Pair<ByteArray, String>) {
        if (closed) return
        if (directory.usableSpace < resource.first.size + 32L * 1024 * 1024)
            throw IOException("No hay espacio suficiente")
        val target = File(directory, name(url)); val temporary = File(directory, "${target.name}.part")
        DataOutputStream(temporary.outputStream().buffered()).use {
            it.writeUTF(resource.second); it.writeInt(resource.first.size); it.write(resource.first)
        }
        if (!temporary.renameTo(target)) { temporary.delete(); throw IOException("No se pudo guardar el mapa") }
    }
    internal fun protectStyle(url: String, resource: Pair<ByteArray, String>) {
        if (active && "/style/1/" in url) runCatching { write(url, resource) }
    }
    fun current() = progress
    private fun publish(value: Progress) { progress = value; listener(value) }
    private fun enqueue(task: () -> Unit) {
        if (closed) return
        try { coordinator.execute(task) } catch (e: RejectedExecutionException) { if (!closed) throw e }
    }

    /** Planning and all disk/network work stay off the UI thread. Same route resumes missing files. */
    fun prepare(id: String, plan: () -> List<String>, done: (Progress) -> Unit) {
        listener = done
        val version = generation.incrementAndGet()
        enqueue {
            if (closed || version != generation.get()) return@enqueue
            active = true
            if (signature == id && resources.isNotEmpty()) {
                if (progress.ready) listener(progress) else runPass(version)
                return@enqueue
            }
            publish(Progress(0, 0, "planning"))
            val planned = runCatching { plan().distinct() }.getOrElse {
                if (version == generation.get()) publish(Progress(0, 0, "error"))
                return@enqueue
            }
            if (closed || version != generation.get()) return@enqueue
            signature = id; resources = planned
            // Keep only this chosen corridor. These files are never removed by browsing/HTTP LRU.
            val keep = resources.mapTo(hashSetOf()) { name(it) }
            directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            runPass(version)
        }
    }
    private fun runPass(version: Int) {
        if (closed || generation.get() != version || !active) return
        val missing = resources.filter { !contains(it) }
        var saved = resources.size - missing.size
        var storageFailure = false
        publish(Progress(saved, resources.size, if (online) "downloading" else "offline"))
        if (online) for (batch in missing.chunked(3)) {
            if (closed || version != generation.get() || !online) break
            val results = batch.map { url -> downloads.submit<Boolean> {
                if (closed || version != generation.get() || !online) return@submit false
                val resource = fetch(url) ?: return@submit false
                if (closed || version != generation.get()) return@submit false
                try { write(url, resource); true } catch (_: IOException) { storageFailure = true; false }
            } }
            saved += results.count { runCatching { it.get() }.getOrDefault(false) }
            if (version != generation.get() || closed) return
            publish(Progress(saved, resources.size, if (online) "downloading" else "offline"))
            if (storageFailure) break
        }
        if (version != generation.get() || closed) return
        val state = when {
            resources.isEmpty() -> "error"
            saved == resources.size -> "ready"
            storageFailure -> "storage"
            !online -> "offline"
            else -> "retrying"
        }
        publish(Progress(saved, resources.size, state))
        // A failed tile does not abandon the rest of the trip. Retry only the missing resources.
        if (state == "retrying") coordinator.schedule({ runPass(version) }, 10, TimeUnit.SECONDS)
    }
    fun setOnline(available: Boolean) {
        val restored = !online && available
        online = available
        if (closed) return
        enqueue {
            if (closed || !active) return@enqueue
            if (restored && !progress.ready) runPass(generation.get())
            else if (!available && !progress.ready) publish(progress.copy(state = "offline"))
        }
    }
    fun retry() { enqueue { if (active && !progress.ready) runPass(generation.get()) } }
    fun cancel() { active = false; generation.incrementAndGet() }
    fun close() { closed = true; cancel(); coordinator.shutdownNow(); downloads.shutdownNow() }
}
