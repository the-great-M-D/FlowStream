package com.downloadx.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A download request. The engine never assumes anything about the target:
 * the destination directory must exist and be writable — it will verify.
 */
data class DownloadRequest(
    val url: String,
    val target: Path,
    /** Extra headers sent with every request (auth, referer, etc). */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Tunables. Defaults are conservative; every value is injectable for tests.
 */
data class EngineConfig(
    /** Files smaller than this are downloaded as a single part. */
    val minPartSizeBytes: Long = 8L * 1024 * 1024,
    /** Maximum concurrent part connections per download. */
    val maxParts: Int = 6,
    /** Buffer used for copying bytes from the network to a part file. */
    val partBufferBytes: Int = 64 * 1024,
    /** Persist the journal after at least this many new bytes land on disk. */
    val journalSyncBytes: Long = 1024 * 1024,
    /** UI-facing progress tick rate. */
    val progressIntervalMs: Long = 250,
    /** How many downloads may actively transfer at once. */
    val maxConcurrentDownloads: Int = 4,
    /** Connect/read timeout for HTTP. */
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 20_000,
    /** Margin required beyond the expected file size when checking disk space. */
    val storageMarginBytes: Long = 4L * 1024 * 1024,
)

/** Globally-unique, URL-safe download identifier. */
@JvmInline
value class DownloadId(val raw: String) {
    companion object {
        fun generate(): DownloadId = DownloadId(UUID.randomUUID().toString())
    }
}

/**
 * Verifies the request before any network activity. Never assume storage
 * is available — check and fail with an explicit reason.
 */
fun validateRequest(req: DownloadRequest, config: EngineConfig): FailureReason? {
    if (!req.url.startsWith("http://") && !req.url.startsWith("https://")) {
        return FailureReason.Invalid("URL must be http(s): ${req.url}")
    }
    val dir = req.target.toAbsolutePath().parent
    if (dir == null || !Files.isDirectory(dir)) {
        return FailureReason.Storage("Target directory does not exist: $dir")
    }
    if (!Files.isWritable(dir)) {
        return FailureReason.Storage("Target directory is not writable: $dir")
    }
    return null
}

/** Validates a known total size against available disk space; null when size is unknown. */
fun checkDiskSpace(target: Path, totalBytes: Long?, config: EngineConfig): FailureReason? {
    if (totalBytes == null) return null // unknown size: we will fail later on ENOSPC, gracefully
    val dir = target.toAbsolutePath().parent
    val usable = Files.getFileStore(dir).usableSpace
    return if (usable < totalBytes + config.storageMarginBytes) {
        FailureReason.Storage("Insufficient space: need ${totalBytes + config.storageMarginBytes}, have $usable")
    } else null
}
