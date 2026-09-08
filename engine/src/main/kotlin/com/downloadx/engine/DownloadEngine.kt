package com.downloadx.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.min

/** A live handle onto one download. All lifecycle calls are idempotent. */
interface DownloadHandle {
    val id: DownloadId
    val url: String
    val target: Path
    val states: StateFlow<DownloadState>

    /** Cooperative pause. Safe to call repeatedly. Returns true if a pause was initiated. */
    fun pause(): Boolean

    /** Resume a paused/cancelled download. Returns true if it was resumed. */
    fun resume(): Boolean

    /** Stop and keep partial data for a later [resume]. Returns true if cancelled. */
    fun cancel(): Boolean

    /** Wipe partial data and start over. Returns true if a restart was initiated. */
    fun restart(): Boolean

    /** Suspends until the download reaches a terminal state (Completed/Failed/Cancelled). */
    suspend fun awaitCompletion(): DownloadState
}

/**
 * DownloadX V2 engine.
 *
 * Design contracts (from the implementation rules):
 *  - Structured concurrency: every download runs as a child job of [scope];
 *    parts run as children of the download; cancelling the engine scope tears
 *    everything down deterministically. One download failing never affects
 *    another (supervision at the download level).
 *  - Safe cancellation: CancellationException is never swallowed, never
 *    retried; part progress is persisted in cancellation handlers.
 *  - Transactional persistence: journals move on disk atomically; merges
 *    appear via atomic rename; fsync before declaring progress.
 *  - Idempotent recovery: [recover] can run any number of times with the
 *    same outcome.
 *  - No assumptions about HEAD, Range, Content-Length, network or storage —
 *    every capability is probed and every absence degrades gracefully.
 */
class DownloadEngine(
    private val scope: CoroutineScope, // must use a SupervisorJob
    private val transport: HttpTransport,
    private val config: EngineConfig = EngineConfig(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : AutoCloseable {

    private val concurrency = Semaphore(config.maxConcurrentDownloads)
    private val partDownloader = PartDownloader(transport, config)
    private val merger = MergeCoordinator()
    private val records = java.util.concurrent.ConcurrentHashMap<DownloadId, Record>()
    @Volatile private var closed = false

    private class Record(
        val journal: Journal,
        val box: StateBox,
        val flow: MutableStateFlow<DownloadState>,
        val gate: Mutex,
        @Volatile var job: Job? = null,
        @Volatile var pauseRequested: Boolean = false,
        @Volatile var cancelRequested: Boolean = false,
    ) {
        lateinit var handle: DownloadHandle
    }

    // ── public API ─────────────────────────────────────────────────────────

    /** Submit a new download. Fails fast on invalid requests. */
    fun submit(request: DownloadRequest): DownloadHandle {
        require(!closed) { "Engine is closed" }
        val id = DownloadId.generate()
        val journal = Journal(
            id = id, url = request.url, target = request.target.toAbsolutePath(),
            totalBytes = null, supportsRange = false, etag = null, lastModified = null,
            attempts = 0, parts = mutableListOf(), intent = Intent.ACTIVE,
        )
        val rec = newRecord(journal)
        records[id] = rec
        val validation = validateRequest(request, config)
        if (validation != null) {
            rec.box.transitionTo(DownloadState.Queued)
            rec.box.transitionTo(DownloadState.Failed(validation, 0, canResume = false))
            rec.flow.value = rec.box.value
            return rec.handle
        }
        rec.box.transitionTo(DownloadState.Queued)
        rec.flow.value = rec.box.value
        launchJob(rec)
        return rec.handle
    }

    /**
     * Idempotent recovery. Scans [dirs] for DownloadX part directories, loads
     * journals, reconciles with disk, and re-enqueues downloads that were
     * ACTIVE when the process died. Running it twice yields the same state.
     */
    fun recover(vararg dirs: Path): List<DownloadHandle> {
        val handles = mutableListOf<DownloadHandle>()
        for (dir in dirs) {
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                stream.filter { p: Path ->
                    Files.isDirectory(p) && p.fileName.toString().endsWith(".downloadx")
                }.forEach { partDir ->
                    val h = recoverOne(partDir)
                    if (h != null) handles.add(h)
                }
            }
        }
        return handles
    }

    fun handles(): List<DownloadHandle> = records.values.map { it.handle }

    /** Graceful shutdown: pauses all active downloads (persisting journals), keeping data resumable. */
    override fun close() {
        closed = true
        records.values.forEach { r -> runBlocking { stop(r, Intent.PAUSED) } }
    }

    // ── handle construction ────────────────────────────────────────────────

    private fun buildHandle(rec: Record): DownloadHandle = object : DownloadHandle {
        override val id get() = rec.journal.id
        override val url get() = rec.journal.url
        override val target get() = rec.journal.target
        override val states: StateFlow<DownloadState> get() = rec.flow

        override fun pause(): Boolean = runBlocking { stop(rec, Intent.PAUSED) }
        override fun cancel(): Boolean = runBlocking { stop(rec, Intent.CANCELLED) }

        override fun resume(): Boolean = runBlocking { startLocked(rec) }

        override fun restart(): Boolean = runBlocking {
            rec.gate.withLock {
                if (rec.box.value is DownloadState.Completed) return@withLock false
                rec.journal.resetPartsToZero()
                rec.journal.intent = Intent.ACTIVE
                startLocked(rec)
            }
        }

        override suspend fun awaitCompletion(): DownloadState =
            rec.flow.first { it is DownloadState.Completed || it is DownloadState.Failed || it is DownloadState.Cancelled }
    }

    /** Stop a download cooperatively. Idempotent. Runs under the record gate. */
    private suspend fun stop(rec: Record, intent: Intent): Boolean = rec.gate.withLock {
        val cur = rec.box.value
        when {
            cur is DownloadState.Completed || cur is DownloadState.Failed -> false
            cur is DownloadState.Paused && intent == Intent.PAUSED -> true
            cur is DownloadState.Paused && intent == Intent.CANCELLED -> {
                rec.journal.intent = Intent.CANCELLED
                persistQuietly(rec.journal)
                transition(rec, DownloadState.Cancelling, DownloadState.Cancelled)
                true
            }
            cur is DownloadState.Cancelled -> intent == Intent.CANCELLED
            cur is DownloadState.Queued || cur is DownloadState.Connecting || cur is DownloadState.Downloading -> {
                when (intent) {
                    Intent.PAUSED -> rec.pauseRequested = true
                    Intent.CANCELLED -> rec.cancelRequested = true
                    Intent.ACTIVE -> return@withLock false
                }
                rec.job?.cancel(CancellationException("stop:$intent"))
                // If the job hadn't reached a cancellable point yet, the CancellationException
                // handler in runDownload sets the final state. Nothing else to do here.
                true
            }
            else -> false
        }
    }

    /** Start (or restart) a download from a non-active state. Idempotent. */
    private suspend fun startLocked(rec: Record): Boolean = rec.gate.withLock {
        val cur = rec.box.value
        when (cur) {
            is DownloadState.Paused, is DownloadState.Cancelled, is DownloadState.Failed, is DownloadState.Idle -> {
                rec.box.transitionTo(DownloadState.Queued)
                rec.flow.value = rec.box.value
                launchJob(rec)
                true
            }
            else -> false
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun newRecord(journal: Journal): Record {
        val box = StateBox(DownloadState.Idle)
        val flow = MutableStateFlow<DownloadState>(DownloadState.Idle)
        return Record(journal, box, flow, Mutex()).also { rec -> rec.handle = buildHandle(rec) }
    }

    private fun recoverOne(partDir: Path): DownloadHandle? {
        if (!Files.isRegularFile(JournalStore.journalFile(partDir))) {
            JournalStore.cleanup(partDir) // garbage from a crashed first write
            return null
        }
        val journal = JournalStore.load(partDir)
        if (journal == null) {
            JournalStore.cleanup(partDir) // unreadable journal is unrecoverable garbage
            return null
        }
        // Already-present record for the same target wins (idempotency).
        val existing = records.values.firstOrNull { it.journal.target == journal.target }
        if (existing != null) return existing.handle

        val rec = newRecord(journal)
        records[journal.id] = rec

        // Crash-after-merge-before-cleanup: all parts complete and target exists -> finalize.
        if (Files.exists(journal.target) && journal.parts.isNotEmpty() && journal.parts.all { it.isComplete() }) {
            JournalStore.cleanup(partDir)
            rec.box.transitionTo(DownloadState.Queued)
            rec.box.transitionTo(DownloadState.Completed(journal.target, Files.size(journal.target)))
            rec.flow.value = rec.box.value
            return rec.handle
        }

        when (journal.intent) {
            Intent.PAUSED -> {
                rec.box.transitionTo(DownloadState.Queued)
                rec.box.transitionTo(DownloadState.Paused)
                rec.flow.value = rec.box.value
            }
            Intent.CANCELLED -> {
                rec.box.transitionTo(DownloadState.Queued)
                rec.box.transitionTo(DownloadState.Cancelling)
                rec.box.transitionTo(DownloadState.Cancelled)
                rec.flow.value = rec.box.value
            }
            Intent.ACTIVE -> {
                rec.box.transitionTo(DownloadState.Queued)
                rec.flow.value = rec.box.value
                launchJob(rec) // resume the interrupted work automatically
            }
        }
        return rec.handle
    }

    private fun launchJob(rec: Record) {
        require(!closed) { "Engine is closed" }
        rec.pauseRequested = false
        rec.cancelRequested = false
        rec.journal.intent = Intent.ACTIVE
        rec.job = scope.launch { runDownload(rec) }
    }

    /** The download's full lifecycle — one coroutine, explicit states, safe cancellation. */
    private suspend fun runDownload(rec: Record) {
        val journal = rec.journal
        var acquired = false
        try {
            concurrency.acquire()
            acquired = true
            var attempt = 1
            var rangeCollapseResets = 0
            while (true) {
                try {
                    setState(rec) { DownloadState.Connecting(attempt) }

                    // Probe capabilities — assume nothing.
                    val probe = withContext(Dispatchers.IO) { transport.probe(journal.url, emptyMap()) }

                    // Target changed on the server? Never resume across a changed target.
                    if (probe.etag != null && journal.etag != null && journal.etag != probe.etag) throw TargetChangedException()
                    if (probe.lastModified != null && journal.lastModified != null && journal.lastModified != probe.lastModified) throw TargetChangedException()

                    val havePartialWork = journal.parts.isNotEmpty() && journal.parts.any { it.written > 0 }
                    if (havePartialWork && probe.totalBytes != null && journal.totalBytes != null && probe.totalBytes != journal.totalBytes) {
                        throw TargetChangedException()
                    }

                    if (journal.parts.isEmpty()) {
                        // Fresh start (or post-collapse): decide the layout.
                        // After a range collapse the parts list is already a forced single
                        // part — never re-split multi-part on a server that just lied about
                        // honoring ranges.
                        layout(journal, probe)
                    } else {
                        // Resuming: refresh validators, keep part layout.
                        journal.totalBytes = probe.totalBytes ?: journal.totalBytes
                        journal.etag = probe.etag ?: journal.etag
                        journal.lastModified = probe.lastModified ?: journal.lastModified
                    }

                    // Storage checks — fail explicitly, never corrupt.
                    checkDiskSpace(journal.target, journal.totalBytes, config)?.let {
                        throw EngineFailure(it)
                    }

                    val partDir = JournalStore.initPartDir(journal.target)
                    journal.attempts = attempt
                    withContext(Dispatchers.IO) { JournalStore.persist(journal, partDir) }

                    setState(rec) { DownloadState.Downloading(journal.sumWritten, journal.totalBytes, activePartCount(journal)) }

                    // Run parts under a coroutineScope: one part failure cancels siblings,
                    // then we classify and decide retry vs fail. Structured concurrency.
                    coroutineScope {
                        // Progress ticker.
                        val ticker = launch {
                            while (isActive) {
                                delay(config.progressIntervalMs)
                                rec.box.updateProgress(journal.sumWritten, journal.totalBytes, activePartCount(journal))
                                rec.flow.value = rec.box.value
                            }
                        }
                        try {
                            // Launch each part, then AWAIT them — without joinAll, launch returns
                            // instantly and the finally would cancel the ticker before it ever ticks.
                            val partJobs = journal.parts.filter { !it.isComplete() }.map { part ->
                                launch(Dispatchers.IO) {
                                    partDownloader.run(journal, part, partDir, emptyMap()) { delta, final ->
                                        // Persist journal transactionally on threshold or part completion.
                                        synchronized(journal) { journal.persistBytesSinceFlush += delta }
                                        if (final || journal.persistBytesSinceFlush >= config.journalSyncBytes) {
                                            journal.persistBytesSinceFlush = 0
                                            JournalStore.persist(journal, partDir)
                                        }
                                    }
                                }
                            }
                            partJobs.joinAll()
                        } finally {
                            ticker.cancel()
                        }
                    }

                    val bytes = journal.sumWritten
                    withContext(Dispatchers.IO) {
                        merger.merge(journal, partDir)
                        JournalStore.cleanup(partDir)
                    }
                    setState(rec) { DownloadState.Completed(journal.target, bytes) }
                    return
                } catch (ce: CancellationException) {
                    // Safe cancellation: persist progress, mark intent, set state — then rethrow.
                    when {
                        rec.cancelRequested -> {
                            journal.intent = Intent.CANCELLED
                            persistQuietly(journal)
                            transition(rec, DownloadState.Cancelling, DownloadState.Cancelled)
                        }
                        rec.pauseRequested -> {
                            journal.intent = Intent.PAUSED
                            persistQuietly(journal)
                            transition(rec, DownloadState.Paused)
                        }
                        else -> {
                            // Engine scope cancelled — leave data ACTIVE for auto-resume on recovery.
                            persistQuietly(journal)
                            transition(rec, DownloadState.Paused)
                        }
                    }
                    throw ce
                } catch (t: RangeNotHonoredException) {
                    // Server ignored a resume/segment range mid-flight: collapse to a fresh
                    // single-part restart. Degrade gracefully; bounded to avoid loops.
                    if (++rangeCollapseResets > 2) {
                        persistQuietly(journal)
                        val reason = FailureReason.Network("Server refuses ranges repeatedly (HTTP ${t.responseCode})")
                        transition(rec, DownloadState.Failed(reason, attempt, canResume = false))
                        return
                    }
                    journal.resetPartsToZero()
                    journal.supportsRange = false
                    journal.parts.clear()
                    journal.parts.add(PartSpec(0, 0, null, 0))
                    continue // retry without burning a network attempt
                } catch (t: Throwable) {
                    val reason = RetryPolicy.classify(t)
                    journal.attempts = attempt
                    persistQuietly(journal)
                    if (retryPolicy.shouldRetry(attempt, reason)) {
                        attempt++
                        delay(retryPolicy.delayFor(attempt)) // cancellable backoff
                        continue
                    }
                    transition(rec, DownloadState.Failed(reason, attempt, canResume = journal.parts.any { it.written > 0 }))
                    return
                }
            }
        } finally {
            if (acquired) concurrency.release()
        }
    }

    /** Decide part layout from probe results. Never assumes range support or known size. */
    private fun layout(journal: Journal, probe: ProbeResult) {
        journal.totalBytes = probe.totalBytes
        journal.supportsRange = probe.supportsRange
        journal.etag = probe.etag
        journal.lastModified = probe.lastModified
        val size = probe.totalBytes
        journal.parts.clear()
        if (size == null || !probe.supportsRange || size <= config.minPartSizeBytes) {
            // Single part to EOF (or known size); resumable only if ranges supported.
            journal.parts.add(PartSpec(0, 0, size, 0))
            return
        }
        val n = min(config.maxParts.toLong(), ceil(size.toDouble() / config.minPartSizeBytes).toLong()).toInt().coerceAtLeast(1)
        val base = size / n
        for (i in 0 until n) {
            val start = i * base
            val end = if (i == n - 1) size else (i + 1) * base
            journal.parts.add(PartSpec(i, start, end, 0))
        }
    }

    private fun activePartCount(journal: Journal): Int =
        journal.parts.count { !it.isComplete() }

    private fun setState(rec: Record, build: () -> DownloadState) {
        rec.box.transitionTo(build())
        rec.flow.value = rec.box.value
    }

    private fun transition(rec: Record, vararg states: DownloadState) {
        states.forEach { rec.box.transitionTo(it) }
        rec.flow.value = rec.box.value
    }

    private fun persistQuietly(journal: Journal) {
        try {
            JournalStore.persist(journal, JournalStore.partDirFor(journal.target))
        } catch (_: IOException) {
            // Graceful degradation: persistence failure must not mask the primary error.
        }
    }
}
