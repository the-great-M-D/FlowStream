package com.downloadx.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private fun writeSlow(out: OutputStream, body: ByteArray, delayMs: Long, perChunk: Int) {
    var off = 0
    while (off < body.size) {
        val len = minOf(body.size - off, perChunk)
        out.write(body, off, len)
        out.flush()
        off += len
        if (delayMs > 0 && off < body.size) Thread.sleep(delayMs)
    }
    out.close()
}

/** Minimal pause/resume repro with full state logging. */
fun main() = runBlocking {
    val t0 = System.currentTimeMillis()
    val size = 150L * 1024
    val chunkDelay = 5L
    val bytesPerChunk = 2048

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val rangeReqs = AtomicInteger(0)
    server.createContext("/slow") { ex ->
        try {
            val content = ByteArray(size.toInt()) { (it % 251).toByte() }
            val range = ex.requestHeaders.getFirst("Range")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (range != null) {
                rangeReqs.incrementAndGet()
                val start = m!!.groupValues[1].toLong()
                val endIncl = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toLong() else size - 1
                val slice = content.copyOfRange(start.toInt(), (endIncl + 1).toInt())
                ex.responseHeaders.add("Content-Range", "bytes $start-$endIncl/$size")
                ex.sendResponseHeaders(206, slice.size.toLong())
                writeSlow(ex.responseBody, slice, chunkDelay, bytesPerChunk)
            } else {
                ex.sendResponseHeaders(200, size)
                writeSlow(ex.responseBody, content, chunkDelay, bytesPerChunk)
            }
        } catch (_: Exception) {
            try { ex.close() } catch (_: Exception) {}
        }
    }
    server.start()
    val url = "http://127.0.0.1:${server.address.port}/slow"

    val dir = Files.createTempDirectory("dx-debug")
    val target = dir.resolve("slow.bin")
    val config = EngineConfig(
        minPartSizeBytes = 64 * 1024, maxParts = 4, partBufferBytes = 1024,
        journalSyncBytes = 16 * 1024, progressIntervalMs = 20, maxConcurrentDownloads = 2,
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = DownloadEngine(scope, JdkHttpTransport(config), config, RetryPolicy(3, 50, 200))
    val h = engine.submit(DownloadRequest(url, target))

    // log every state change
    val logger = launch {
        h.states.collectLatest { println("[state +${System.currentTimeMillis() - t0}ms] $it") }
    }

    withTimeout(10_000) {
        h.states.first { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
    }
    println("[t] pausing...")
    val paused = h.pause()
    println("[t] pause() returned $paused in ${System.currentTimeMillis() - t0}ms")

    withTimeout(5_000) { h.states.first { it is DownloadState.Paused } }
    println("[t] PAUSED observed in ${System.currentTimeMillis() - t0}ms")

    println("[t] journal on disk: ${Files.exists(JournalStore.partDirFor(target).resolve("journal.properties"))}")
    val j = JournalStore.load(JournalStore.partDirFor(target))
    println("[t] journal: written=${j?.parts?.first()?.written} intent=${j?.intent} rangeReqs=${rangeReqs.get()}")

    println("[t] resuming...")
    h.resume()
    val end = h.awaitCompletion()
    println("[t] end state: $end")
    val expected = ByteArray(size.toInt()) { (it % 251).toByte() }
    println("[t] content match: ${java.util.Arrays.equals(expected, Files.readAllBytes(target))}")

    logger.cancel()
    scope.cancel()
    server.stop(0)
}
