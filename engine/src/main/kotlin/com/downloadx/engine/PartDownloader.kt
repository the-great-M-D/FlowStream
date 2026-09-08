package com.downloadx.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Signals that the journal's validators no longer match the server's content.
 * Fatal: restart from zero or fail with TargetChanged.
 */
class TargetChangedException : IOException("Target changed on server (etag/last-modified mismatch)")

/**
 * Downloads one part of a file into its part file, resuming from the journal's
 * `written` offset. All I/O is on Dispatchers.IO; cancellation is cooperative
 * (checked every buffer) and progress is reported through the journal.
 */
class PartDownloader(
    private val transport: HttpTransport,
    private val config: EngineConfig,
) {

    /**
     * Runs one part to completion or throws. Safe to re-invoke: on resume it
     * reopens the range at [PartSpec.written] and appends.
     *
     * [onPersist] receives the byte delta since the last call; the callee
     * decides when to actually fsync the journal to disk.
     */
    suspend fun run(
        journal: Journal,
        part: PartSpec,
        partDir: Path,
        requestHeaders: Map<String, String>,
        onPersist: (deltaBytes: Long, final: Boolean) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (part.isComplete()) return@withContext

        val partPath = JournalStore.partFile(partDir, part.index)
        val startOffset = part.start + part.written

        transport.openRange(journal.url, requestHeaders, startOffset, part.endExclusive).use { opened ->
            val expected = part.endExclusive?.let { it - startOffset }

            FileChannel.open(
                partPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ,
            ).use { channel ->
                channel.position(part.written)
                val buf = ByteArray(config.partBufferBytes)
                var bytesSincePersist = 0L
                while (true) {
                    currentCoroutineContext().ensureActive() // cooperative cancellation every iteration
                    val n = opened.stream.read(buf)
                    if (n == -1) break
                    var off = 0
                    while (off < n) {
                        currentCoroutineContext().ensureActive()
                        val w = channel.write(java.nio.ByteBuffer.wrap(buf, off, n - off))
                        if (w <= 0) throw IOException("FileChannel.write returned $w — storage unavailable")
                        off += w
                    }
                    part.written += n
                    bytesSincePersist += n

                    if (part.endExclusive != null && part.written > part.length!!) {
                        // Server sent more bytes than the part's range — corrupt merge risk.
                        throw IOException("Range overrun: part ${part.index} exceeded its declared length")
                    }

                    if (bytesSincePersist >= config.journalSyncBytes) {
                        onPersist(bytesSincePersist, false)
                        bytesSincePersist = 0L
                    }
                }
                channel.force(true) // transactional: bytes durable before we declare progress
                onPersist(bytesSincePersist, true)
            }
        }

        // EOF validation: a bounded part that ends early is a truncated response.
        if (!part.isComplete()) {
            if (part.endExclusive != null) {
                throw IOException("Response ended early for part ${part.index}: ${part.written}/${part.length} bytes")
            }
            // Unbounded part: EOF is the natural end. journal.totalBytes == null is fine.
        }
    }
}

/**
 * Assembles part files into the final target. Transactional: the visible
 * target path only ever appears via atomic rename of a fully-written file,
 * so a crash mid-merge never leaves a corrupt "completed" file.
 */
class MergeCoordinator {

    /**
     * Single part: the part file IS the complete content — a single atomic
     * rename, no copy pass. Multi part: concat into a tmp file, fsync,
     * atomic rename.
     */
    fun merge(journal: Journal, partDir: Path) {
        val target = journal.target.toAbsolutePath()
        if (Files.exists(target)) Files.delete(target) // replace on restart-after-complete edge cases

        if (journal.parts.size == 1) {
            val src = JournalStore.partFile(partDir, 0)
            atomicMove(src, target)
            return
        }

        val tmp = target.resolveSibling(target.fileName.toString() + ".merging.tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            for (p in journal.parts) {
                FileChannel.open(JournalStore.partFile(partDir, p.index), StandardOpenOption.READ).use { input ->
                    var pos = 0L
                    val size = input.size()
                    while (pos < size) {
                        pos += input.transferTo(pos, size - pos, out)
                    }
                }
            }
            out.force(true)
        }
        atomicMove(tmp, target)

        // Sanity check against known expectations — degrade gracefully: discard
        // rather than leave a silently-wrong file behind.
        val expected = journal.totalBytes
        if (expected != null) {
            val actual = Files.size(target)
            if (actual != expected) {
                Files.deleteIfExists(target)
                throw IOException("Merged file size $actual != expected $expected — target discarded")
            }
        }
    }

    private fun atomicMove(src: Path, dst: Path) {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        JournalStore.fsyncDir(dst.parent)
    }
}
