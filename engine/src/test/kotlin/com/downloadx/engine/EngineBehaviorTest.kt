package com.downloadx.engine

import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.Assertions.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Controllable local HTTP server for behavioral tests. Every capability is
 * switchable: HEAD support, Range support, Content-Length, delays, error rates.
 */
class FakeServer {
    val hits = ConcurrentHashMap<String, AtomicInteger>()
    val rangeRequests = AtomicInteger(0)          // GETs with a Range header
    val rangedResumeRequests = AtomicInteger(0)   // GETs with Range starting after 0
    private val behaviors = ConcurrentHashMap<String, Behavior>()
    private val latches = ConcurrentHashMap<String, MutableList<CountDownLatch>>()

    class Behavior(
        val size: Long,
        val supportsHead: Boolean = true,
        val supportsRange: Boolean = true,
        val sendContentLength: Boolean = true,
        val ignoreRangeOnResume: Boolean = false,
        val chunkDelayMs: Long = 0,
        val bytesPerChunk: Int = Int.MAX_VALUE,
        val failProbes: Int = 0,           // fail this many probes with 500 before succeeding
        val failRangeGets: Int = 0,        // fail this many part GETs with 500
        val httpFailures: Int = 0,         // hard fail with this status
        val varyEtagEachProbe: Boolean = false,
    ) {
        val probeCount = AtomicInteger(0)
        val rangeGetCount = AtomicInteger(0)
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        // JDK default executor is a single serial dispatch thread — pool it so slow
        // handlers never block concurrent requests.
        it.executor = java.util.concurrent.Executors.newFixedThreadPool(16)
    }

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun serve(path: String, behavior: Behavior) {
        behaviors[path] = behavior
    }

    /** Test hook: fire when the server has served [n] range GETs on [path]. */
    fun onRangeGetCount(path: String, n: Int): CountDownLatch {
        val latch = CountDownLatch(1)
        latches.getOrPut(path) { mutableListOf() }.add(latch)
        // retro-check in case we already passed it
        behaviors[path]?.let { if (it.rangeGetCount.get() >= n) latch.countDown() }
        return latch
    }

    private fun hit(path: String) = hits.getOrPut(path) { AtomicInteger(0) }.incrementAndGet()

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val b = behaviors[path]
        try {
            if (b == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val content = ByteArray(b.size.toInt()) { (it % 251).toByte() }
            if (b.httpFailures >= 400) {
                hit(path)
                exchange.sendResponseHeaders(b.httpFailures, -1)
                return
            }

            if (exchange.requestMethod == "HEAD") {
                hit(path)
                if (!b.supportsHead) {
                    exchange.sendResponseHeaders(405, -1)
                    return
                }
                if (b.probeCount.incrementAndGet() <= b.failProbes) {
                    exchange.sendResponseHeaders(500, -1)
                    return
                }
                exchange.responseHeaders.add("Accept-Ranges", if (b.supportsRange) "bytes" else "none")
                if (b.sendContentLength) exchange.responseHeaders.add("Content-Length", b.size.toString())
                val etag = if (b.varyEtagEachProbe) "\"etag-${System.nanoTime()}\"" else "\"etag-${b.size}\""
                exchange.responseHeaders.add("ETag", etag)
                exchange.sendResponseHeaders(200, -1)
                return
            }

            // GET
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            var rangeStart = 0L
            var rangeEndIncl: Long? = null
            if (rangeHeader != null) {
                hit(path)
                val m = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)!!
                rangeStart = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) rangeEndIncl = m.groupValues[2].toLong()
                rangeRequests.incrementAndGet()
                if (rangeStart > 0) rangedResumeRequests.incrementAndGet()
                if (b.rangeGetCount.incrementAndGet() <= b.failRangeGets) {
                    exchange.sendResponseHeaders(500, -1)
                    return
                }
                if (b.ignoreRangeOnResume && rangeStart > 0) {
                    // Degraded server: ignores Range for resume, sends full content with 200.
                    exchange.responseHeaders.add("ETag", "\"etag-${b.size}\"")
                    writeBody(exchange, content, 200, null, b)
                    return
                }
            } else {
                hit(path)
                if (b.probeCount.incrementAndGet() <= b.failProbes) {
                    exchange.sendResponseHeaders(500, -1)
                    return
                }
            }

            val etag = if (b.varyEtagEachProbe) "\"etag-${System.nanoTime()}\"" else "\"etag-${b.size}\""
            exchange.responseHeaders.add("ETag", etag)
            if (b.supportsRange) exchange.responseHeaders.add("Accept-Ranges", "bytes")

            if (rangeHeader != null && b.supportsRange) {
                val endIncl = rangeEndIncl ?: b.size - 1
                val slice = content.copyOfRange(rangeStart.toInt(), (endIncl + 1).toInt().coerceAtMost(content.size))
                exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-$endIncl/${b.size}")
                writeBody(exchange, slice, 206, slice.size.toLong(), b)
            } else {
                val code = if (b.httpFailures > 0) b.httpFailures else 200
                if (code >= 400) {
                    exchange.sendResponseHeaders(code, -1)
                    return
                }
                writeBody(exchange, content, code, if (b.sendContentLength) b.size else null, b)
            }
        } catch (_: Exception) {
            // connection closed mid-transfer by the downloader (pause/cancel): fine
            try { exchange.close() } catch (_: Exception) {}
        } finally {
            latches[path]?.forEach { if (behaviors[path]?.rangeGetCount?.get() ?: 0 >= 0) it.countDown() }
            exchange.close()
        }
    }

    private fun writeBody(exchange: HttpExchange, body: ByteArray, code: Int, contentLength: Long?, b: Behavior) {
        val declared = contentLength ?: 0L // 0 -> chunked, no Content-Length
        exchange.sendResponseHeaders(code, if (contentLength == null) declared else contentLength)
        val out: OutputStream = exchange.responseBody
        var off = 0
        while (off < body.size) {
            val len = minOf(body.size - off, b.bytesPerChunk)
            out.write(body, off, len)
            out.flush()
            off += len
            if (b.chunkDelayMs > 0 && off < body.size) Thread.sleep(b.chunkDelayMs)
        }
        out.close()
    }

    fun stop() = server.stop(0)
}

/**
 * Full behavioral suite: the download engine tested independently of any UI,
 * against a real HTTP server over real sockets.
 */
class EngineBehaviorTest {

    private lateinit var server: FakeServer
    private lateinit var dir: Path
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val testConfig = EngineConfig(
        minPartSizeBytes = 64 * 1024,
        maxParts = 4,
        partBufferBytes = 1024,
        journalSyncBytes = 16 * 1024,
        progressIntervalMs = 20,
        maxConcurrentDownloads = 2,
        storageMarginBytes = 8 * 1024 * 1024,
    )

    private val quickRetry = RetryPolicy(maxAttempts = 3, baseDelayMs = 50, maxDelayMs = 200)

    private fun newEngine(retry: RetryPolicy = quickRetry, config: EngineConfig = testConfig): DownloadEngine =
        DownloadEngine(engineScope, JdkHttpTransport(config), config, retry)

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        server = FakeServer()
        dir = Files.createTempDirectory("dx-test")
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        engineScope.cancel()
        server.stop()
        dir.toFile().deleteRecursively()
    }

    private fun expectedDigest(size: Long): String {
        val content = ByteArray(size.toInt()) { (it % 251).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }

    private fun fileDigest(p: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }

    private suspend fun awaitState(h: DownloadHandle, timeoutMs: Long = 15_000, pred: (DownloadState) -> Boolean): DownloadState =
        withTimeout(timeoutMs) { h.states.first { pred(it) } }

    // ── happy paths ─────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `single part download with known size and no range support completes with exact bytes`() = kotlinx.coroutines.runBlocking {
        server.serve("/plain", FakeServer.Behavior(size = 100_000, supportsRange = false))
        val engine = newEngine()
        val target = dir.resolve("plain.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/plain", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(100_000L, (end as DownloadState.Completed).bytes)
        assertEquals(expectedDigest(100_000), fileDigest(target))
        assertFalse(Files.exists(JournalStore.partDirFor(target))) // journal cleaned up
    }

    @org.junit.jupiter.api.Test
    fun `multi part download splits by range and assembles exact bytes`() = kotlinx.coroutines.runBlocking {
        server.serve("/multi", FakeServer.Behavior(size = 300 * 1024, supportsRange = true))
        val engine = newEngine()
        val target = dir.resolve("multi.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/multi", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(300 * 1024L), fileDigest(target))
        // 300KB / 64KB min part -> 4 parts -> at least 4 ranged requests beyond the probe
        assertTrue(server.rangeRequests.get() >= 4, "expected >=4 range requests, got ${server.rangeRequests.get()}")
    }

    @org.junit.jupiter.api.Test
    fun `unknown content length degrades to single part to EOF and still completes`() = kotlinx.coroutines.runBlocking {
        server.serve("/chunked", FakeServer.Behavior(size = 50_000, sendContentLength = false, chunkDelayMs = 1))
        val engine = newEngine()
        val target = dir.resolve("chunked.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/chunked", target))
        // while running, the state must report unknown total
        val downloading = awaitState(h) { it is DownloadState.Downloading || it is DownloadState.Completed }
        if (downloading is DownloadState.Downloading) assertNull(downloading.totalBytes)
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(50_000), fileDigest(target))
    }

    @org.junit.jupiter.api.Test
    fun `server without HEAD support is probed via ranged GET and completes`() = kotlinx.coroutines.runBlocking {
        server.serve("/nohead", FakeServer.Behavior(size = 100_000, supportsHead = false, supportsRange = true))
        val engine = newEngine()
        val target = dir.resolve("nohead.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/nohead", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(100_000), fileDigest(target))
    }

    // ── pause / cancel / resume ─────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `pause mid-download persists progress and resume completes with exact bytes`() = kotlinx.coroutines.runBlocking {
        server.serve("/slow", FakeServer.Behavior(size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048))
        val engine = newEngine()
        val target = dir.resolve("slow.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/slow", target))

        // wait until some bytes land, then pause
        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
        assertTrue(h.pause())
        val paused = awaitState(h) { it is DownloadState.Paused }
        assertTrue(paused is DownloadState.Paused)

        // journal on disk reflects partial progress and PAUSED intent
        val partDir = JournalStore.partDirFor(target)
        assertTrue(Files.exists(JournalStore.journalFile(partDir)), "journal must exist while paused")
        val j = JournalStore.load(partDir)!!
        assertTrue(j.parts.first().written < 150 * 1024, "should be partial, was ${j.parts.first().written}")
        assertEquals(Intent.PAUSED, j.intent)
        assertFalse(Files.exists(target))

        // resume: server must receive a ranged request past zero
        assertTrue(h.resume())
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(150 * 1024), fileDigest(target))
        assertTrue(server.rangedResumeRequests.get() >= 1, "resume must use a Range request past offset 0")
        assertFalse(Files.exists(JournalStore.partDirFor(target)))
    }

    @org.junit.jupiter.api.Test
    fun `cancel mid-download yields cancelled state no target and resumable journal`() = kotlinx.coroutines.runBlocking {
        server.serve("/slow2", FakeServer.Behavior(size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048))
        val engine = newEngine()
        val target = dir.resolve("slow2.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/slow2", target))

        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
        assertTrue(h.cancel())
        val cancelled = awaitState(h) { it is DownloadState.Cancelled }
        assertTrue(cancelled is DownloadState.Cancelled)
        assertFalse(Files.exists(target))
        val j = JournalStore.load(JournalStore.partDirFor(target))!!
        assertEquals(Intent.CANCELLED, j.intent)

        // resume from cancelled works
        assertTrue(h.resume())
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(150 * 1024), fileDigest(target))
    }

    // ── failure handling ───────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `404 fails fast with fatal http error and no retry`() = kotlinx.coroutines.runBlocking {
        server.serve("/gone", FakeServer.Behavior(size = 10, httpFailures = 404))
        val engine = newEngine()
        val target = dir.resolve("gone.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/gone", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Failed, "was $end")
        assertEquals(404, (end as DownloadState.Failed).reason.let { (it as FailureReason.HttpError).code })
        // probe (HEAD falls through to ranged GET) = exactly 2 requests, no retry loop
        assertTrue(server.hits["/gone"]!!.get() <= 2, "404 must not be retried, hits=${server.hits["/gone"]}")
        assertFalse(Files.exists(target))
    }

    @org.junit.jupiter.api.Test
    fun `transient 500s are retried with backoff and then succeed`() = kotlinx.coroutines.runBlocking {
        server.serve("/flaky", FakeServer.Behavior(size = 50_000, failProbes = 2))
        val engine = newEngine()
        val target = dir.resolve("flaky.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/flaky", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(50_000), fileDigest(target))
        assertTrue(server.hits["/flaky"]!!.get() >= 3, "should have hit probe failures then success")
    }

    @org.junit.jupiter.api.Test
    fun `range GET failures are retried without losing completed parts`() = kotlinx.coroutines.runBlocking {
        // first part GET fails once, retry resumes from journal offsets
        server.serve("/flakypart", FakeServer.Behavior(size = 150 * 1024, supportsRange = true, failRangeGets = 1))
        val engine = newEngine()
        val target = dir.resolve("flakypart.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/flakypart", target))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(150 * 1024), fileDigest(target))
    }

    @org.junit.jupiter.api.Test
    fun `etag change between attempts fails with target changed`() = kotlinx.coroutines.runBlocking {
        server.serve("/volatile", FakeServer.Behavior(size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048, varyEtagEachProbe = true))
        val engine = newEngine()
        val target = dir.resolve("volatile.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/volatile", target))
        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
        h.pause()
        awaitState(h) { it is DownloadState.Paused }
        // resume: probe returns a different etag -> TargetChanged, fatal
        h.resume()
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Failed, "was $end")
        assertTrue((end as DownloadState.Failed).reason is FailureReason.TargetChanged)
        assertFalse(Files.exists(target))
    }

    @org.junit.jupiter.api.Test
    fun `server ignoring range on resume degrades to clean restart and completes`() = kotlinx.coroutines.runBlocking {
        server.serve("/ignoresrange", FakeServer.Behavior(
            size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048, ignoreRangeOnResume = true,
        ))
        val engine = newEngine()
        val target = dir.resolve("ignoresrange.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/ignoresrange", target))
        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
        h.pause()
        awaitState(h) { it is DownloadState.Paused }
        h.resume()
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(150 * 1024), fileDigest(target))
        assertTrue(server.rangedResumeRequests.get() >= 1, "a resume range request must have been attempted")
    }

    // ── recovery ───────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `recovery resumes an interrupted ACTIVE download and completes it`() = kotlinx.coroutines.runBlocking {
        server.serve("/recoverable", FakeServer.Behavior(size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048))
        val engine = newEngine()
        val target = dir.resolve("recoverable.bin")
        val h = engine.submit(DownloadRequest("${server.baseUrl}/recoverable", target))
        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }

        // Simulate process death: cancel the scope (cancellation handler persists ACTIVE intent)
        engineScope.cancel()

        // New process, new engine, same directory: recovery finds and finishes it
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine2 = DownloadEngine(scope2, JdkHttpTransport(testConfig), testConfig, quickRetry)
            val recovered = engine2.recover(dir)
            assertEquals(1, recovered.size, "exactly one recoverable download expected")
            val end = recovered[0].awaitCompletion()
            assertTrue(end is DownloadState.Completed, "was $end")
            assertEquals(expectedDigest(150 * 1024), fileDigest(target))
        } finally {
            scope2.cancel()
        }
    }

    @org.junit.jupiter.api.Test
    fun `recovery is idempotent - running twice yields the same handles`() = kotlinx.coroutines.runBlocking {
        server.serve("/idem", FakeServer.Behavior(size = 100_000, supportsRange = false))
        val engine = newEngine()
        val target = dir.resolve("idem.bin")

        // Create a paused download on disk, then drop the engine.
        server.serve("/idemsrc", FakeServer.Behavior(size = 150 * 1024, chunkDelayMs = 5, bytesPerChunk = 2048))
        val h = engine.submit(DownloadRequest("${server.baseUrl}/idemsrc", target))
        awaitState(h) { it is DownloadState.Downloading && it.bytesWritten > 8 * 1024 }
        h.pause()
        awaitState(h) { it is DownloadState.Paused }

        val engine2 = newEngine()
        val first = engine2.recover(dir)
        assertEquals(1, first.size)
        assertTrue(first[0].states.value is DownloadState.Paused, "PAUSED journal must not auto-start")

        val second = engine2.recover(dir)
        assertEquals(1, second.size)
        assertEquals(first[0].id, second[0].id, "same target must map to the same handle")
        assertTrue(second[0].states.value is DownloadState.Paused)

        first[0].resume()
        val end = first[0].awaitCompletion()
        assertTrue(end is DownloadState.Completed, "was $end")
        assertEquals(expectedDigest(150 * 1024), fileDigest(target))
    }

    @org.junit.jupiter.api.Test
    fun `corrupt part dir without journal is cleaned up during recovery`() = kotlinx.coroutines.runBlocking {
        val target = dir.resolve("orphan.bin")
        val dirp = JournalStore.initPartDir(target)
        Files.write(JournalStore.partFile(dirp, 0), ByteArray(128))
        val engine = newEngine()
        val recovered = engine.recover(dir)
        assertTrue(recovered.isEmpty())
        assertFalse(Files.exists(dirp), "garbage dir must be removed")
    }

    // ── concurrency and isolation ───────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `one failing download does not affect a concurrent one`() = kotlinx.coroutines.runBlocking {
        server.serve("/bad", FakeServer.Behavior(size = 10, httpFailures = 404))
        server.serve("/good", FakeServer.Behavior(size = 80_000, supportsRange = true))
        val engine = newEngine()
        val bad = engine.submit(DownloadRequest("${server.baseUrl}/bad", dir.resolve("bad.bin")))
        val good = engine.submit(DownloadRequest("${server.baseUrl}/good", dir.resolve("good.bin")))
        val badEnd = bad.awaitCompletion()
        val goodEnd = good.awaitCompletion()
        assertTrue(badEnd is DownloadState.Failed, "was $badEnd")
        assertTrue(goodEnd is DownloadState.Completed, "was $goodEnd")
        assertEquals(expectedDigest(80_000), fileDigest(dir.resolve("good.bin")))
    }

    @org.junit.jupiter.api.Test
    fun `max concurrent downloads is respected`() = kotlinx.coroutines.runBlocking {
        val cfg = testConfig.copy(maxConcurrentDownloads = 1)
        server.serve("/c1", FakeServer.Behavior(size = 60 * 1024, chunkDelayMs = 2, bytesPerChunk = 4096))
        server.serve("/c2", FakeServer.Behavior(size = 60 * 1024, chunkDelayMs = 2, bytesPerChunk = 4096))
        val engine = newEngine(config = cfg)
        val h1 = engine.submit(DownloadRequest("${server.baseUrl}/c1", dir.resolve("c1.bin")))
        val h2 = engine.submit(DownloadRequest("${server.baseUrl}/c2", dir.resolve("c2.bin")))
        // With one slot, h2 must stay queued while h1 downloads.
        awaitState(h1) { it is DownloadState.Downloading }
        assertTrue(h2.states.value is DownloadState.Queued, "h2 should be queued, was ${h2.states.value}")
        assertTrue(h1.awaitCompletion() is DownloadState.Completed)
        assertTrue(h2.awaitCompletion() is DownloadState.Completed)
    }

    // ── validation ──────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    fun `invalid url fails fast with explicit reason`() = kotlinx.coroutines.runBlocking {
        val engine = newEngine()
        val h = engine.submit(DownloadRequest("ftp://example.com/x", dir.resolve("x.bin")))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Failed, "was $end")
        assertTrue((end as DownloadState.Failed).reason is FailureReason.Invalid)
    }

    @org.junit.jupiter.api.Test
    fun `missing target directory fails fast with storage reason`() = kotlinx.coroutines.runBlocking {
        val engine = newEngine()
        val h = engine.submit(DownloadRequest("${server.baseUrl}/x", dir.resolve("no/such/dir/x.bin")))
        val end = h.awaitCompletion()
        assertTrue(end is DownloadState.Failed, "was $end")
        assertTrue((end as DownloadState.Failed).reason is FailureReason.Storage)
    }
}
