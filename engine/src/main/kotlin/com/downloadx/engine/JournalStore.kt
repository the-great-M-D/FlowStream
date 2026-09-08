package com.downloadx.engine

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.*

/**
 * In-memory journal for one download. This is the single source of truth for
 * resumable progress. Persisted atomically: tmp file -> fsync -> atomic move.
 */
/** Coarse intent persisted in the journal so recovery is idempotent. */
enum class Intent { ACTIVE, PAUSED, CANCELLED }

class Journal(
    val id: DownloadId,
    var url: String,
    var target: Path,
    var totalBytes: Long?,   // null = unknown to server and journal
    var supportsRange: Boolean,
    var etag: String?,
    var lastModified: String?,
    var attempts: Int,
    /** Mutable part descriptors; writes synchronized on the Journal. */
    val parts: MutableList<PartSpec>,
    /** Persisted intent; drives recovery decisions. */
    @Volatile var intent: Intent = Intent.ACTIVE,
) {
    @Volatile
    var persistBytesSinceFlush: Long = 0

    val sumWritten: Long
        get() = synchronized(this) { parts.sumOf { it.written } }

    fun resetPartsToZero() = synchronized(this) {
        parts.forEach { it.written = 0L }
    }
}

data class PartSpec(
    val index: Int,
    val start: Long,
    /** Exclusive end; null = read to EOF (unknown length). */
    val endExclusive: Long?,
    /** Mutated by part IO threads, read by the progress ticker — must be volatile. */
    @Volatile var written: Long,
) {
    val length: Long? get() = endExclusive?.let { it - start }
    fun isComplete(): Boolean = endExclusive != null && written >= length!!
}

/**
 * On-disk layout for a download with id I and target T:
 *   <T.parent>/.<T.filename>.downloadx/
 *     journal.properties   (atomically persisted state)
 *     part.000, part.001, ...
 *
 * The directory lives next to the target so the whole unit can be cleaned up
 * or resumed independently. A directory without a readable journal is garbage
 * and is removed during recovery.
 */
object JournalStore {
    const val VERSION = 2

    fun partDirFor(target: Path): Path =
        target.toAbsolutePath().parent.resolve("." + target.fileName + ".downloadx")

    fun journalFile(partDir: Path): Path = partDir.resolve("journal.properties")

    fun partFile(partDir: Path, index: Int): Path = partDir.resolve("part.%03d".format(index))

    fun initPartDir(target: Path): Path {
        val dir = partDirFor(target)
        Files.createDirectories(dir)
        return dir
    }

    /**
     * Atomic journal persist. Correctness before speed: full tmp->fsync->move->dir-fsync.
     */
    fun persist(journal: Journal, partDir: Path) = synchronized(journal) {
        // One critical section: part threads reach their sync thresholds concurrently;
        // snapshot, tmp write, and atomic move must all be from a single writer.
        val props = Properties()
        run {
            props.setProperty("version", VERSION.toString())
            props.setProperty("id", journal.id.raw)
            props.setProperty("url", journal.url)
            props.setProperty("target", journal.target.toAbsolutePath().toString())
            props.setProperty("totalBytes", journal.totalBytes?.toString() ?: "-1")
            props.setProperty("supportsRange", journal.supportsRange.toString())
            props.setProperty("etag", journal.etag ?: "")
            props.setProperty("lastModified", journal.lastModified ?: "")
            props.setProperty("attempts", journal.attempts.toString())
            props.setProperty("intent", journal.intent.name)
            props.setProperty("partCount", journal.parts.size.toString())
            journal.parts.forEach { p ->
                props.setProperty("part.${p.index}.start", p.start.toString())
                props.setProperty("part.${p.index}.end", p.endExclusive?.toString() ?: "-1")
                props.setProperty("part.${p.index}.written", p.written.toString())
            }
        }
        val journalPath = journalFile(partDir)
        val tmp = partDir.resolve("journal.properties.tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val bytes = propsToBytes(props)
            var pos = 0L
            val buf = java.nio.ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) pos += ch.write(buf, pos)
            ch.force(true)
        }
        try {
            Files.move(tmp, journalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, journalPath, StandardCopyOption.REPLACE_EXISTING)
        }
        fsyncDir(partDir)
    }

    /**
     * Load and VALIDATE a journal. Returns null if unreadable/corrupt —
     * the caller decides how to degrade. Validation never trusts the
     * journal over the filesystem: a part's actual file is truncated to the
     * journal's `written` value (crash-after-write-before-journal is
     * conservatively discarded).
     */
    fun load(partDir: Path): Journal? {
        val file = journalFile(partDir)
        if (!Files.isRegularFile(file)) return null
        return try {
            val props = Properties()
            Files.newInputStream(file).use { props.load(it) }
            if (props.getProperty("version") != VERSION.toString()) return null
            val target = Paths.get(props.getProperty("target") ?: return null)
            val partCount = props.getProperty("partCount")?.toIntOrNull() ?: return null
            val parts = ArrayList<PartSpec>(partCount)
            for (i in 0 until partCount) {
                val start = props.getProperty("part.$i.start")?.toLongOrNull() ?: return null
                val endRaw = props.getProperty("part.$i.end")?.toLongOrNull() ?: return null
                var written = props.getProperty("part.$i.written")?.toLongOrNull() ?: return null
                val end = if (endRaw == -1L) null else endRaw
                // Clamp written to the part's declared range.
                if (end != null && written > end - start) written = end - start
                if (written < 0) written = 0
                // Reconcile with the actual part file.
                val pf = partFile(partDir, i)
                if (written > 0) {
                    if (!Files.isRegularFile(pf)) {
                        written = 0 // data lost on disk; journal degrades to fresh start for this part
                    } else {
                        val actual = Files.size(pf)
                        if (actual > written) {
                            FileChannel.open(pf, StandardOpenOption.WRITE).use { it.truncate(written) }
                        } else if (actual < written) {
                            written = actual // journal claimed more than the file has — trust the file
                        }
                    }
                }
                parts.add(PartSpec(i, start, end, written))
            }
            Journal(
                id = DownloadId(props.getProperty("id") ?: return null),
                url = props.getProperty("url") ?: return null,
                target = target,
                totalBytes = props.getProperty("totalBytes")?.toLongOrNull()?.let { if (it == -1L) null else it },
                supportsRange = props.getProperty("supportsRange") == "true",
                etag = props.getProperty("etag")?.ifBlank { null },
                lastModified = props.getProperty("lastModified")?.ifBlank { null },
                attempts = props.getProperty("attempts")?.toIntOrNull() ?: 0,
                parts = parts,
                intent = try {
                    Intent.valueOf(props.getProperty("intent") ?: "ACTIVE")
                } catch (_: IllegalArgumentException) {
                    Intent.ACTIVE
                },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Delete a part dir recursively. Best effort; returns false if it remains. */
    fun cleanup(partDir: Path): Boolean {
        if (!Files.exists(partDir)) return true
        return try {
            Files.walk(partDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            !Files.exists(partDir)
        } catch (_: IOException) {
            false
        }
    }

    private fun propsToBytes(props: Properties): ByteArray {
        val out = java.io.ByteArrayOutputStream(1024)
        props.store(out, "DownloadX journal")
        return out.toByteArray()
    }

    internal fun fsyncDir(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Directory fsync is best-effort (not all filesystems support it).
        }
    }
}
