package com.downloadx.engine

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of probing a URL's download capabilities.
 * Everything here is nullable/flagged — the engine assumes none of it.
 */
data class ProbeResult(
    /** Content length in bytes, or null if the server does not reveal it. */
    val totalBytes: Long?,
    /** Server honors "Range: bytes=..." with a 206. */
    val supportsRange: Boolean,
    /** Validators to detect target changes across attempts. */
    val etag: String?,
    val lastModified: String?,
    /** Filename from Content-Disposition, if any. */
    val filename: String?,
    /** True if we had to degrade (no HEAD support, etc) — informational. */
    val degradedProbe: Boolean,
)

/**
 * An opened byte stream. [rangeHonored] tells the caller whether the server
 * actually honored the requested start offset — a 200 response to a ranged
 * request means it did NOT and the caller must degrade.
 */
class OpenedStream(
    val stream: InputStream,
    val rangeHonored: Boolean,
    val contentLength: Long?,
    val responseCode: Int,
) : AutoCloseable {
    override fun close() {
        try {
            stream.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * Abstraction over raw HTTP so tests can swap implementations and the engine
 * never touches java.net directly.
 */
interface HttpTransport {
    /**
     * Discover what the server supports. Never throws for capability reasons —
     * only for genuine network failures (IOException -> FailureReason.Network
     * is handled by the caller).
     */
    fun probe(url: String, headers: Map<String, String>): ProbeResult

    /**
     * Open a stream for a byte range. [endExclusive] is null for "until EOF".
     * The returned stream must be closed by the caller.
     */
    fun openRange(url: String, headers: Map<String, String>, start: Long, endExclusive: Long?): OpenedStream
}

/** Raised inside part downloaders when a server ignores a Range header. */
class RangeNotHonoredException(val responseCode: Int) : IOException("Server ignored Range (HTTP $responseCode)")

/** Carries an HTTP status code so the retry policy can classify correctly. */
class HttpCodeException(val code: Int, message: String) : IOException(message)

/**
 * Production transport on top of HttpURLConnection — zero dependencies.
 */
class JdkHttpTransport(
    private val config: EngineConfig,
) : HttpTransport {

    private fun open(url: String, headers: Map<String, String>, method: String, range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = config.connectTimeoutMs
        conn.readTimeout = config.readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DownloadX/2.0")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    override fun probe(url: String, headers: Map<String, String>): ProbeResult {
        // Attempt 1: HEAD — least cost, but never assume it works.
        try {
            val conn = open(url, headers, "HEAD", null)
            val code = conn.responseCode
            try {
                if (code in 200..299) {
                    val total = parseContentLength(conn)
                    return ProbeResult(
                        totalBytes = total,
                        supportsRange = acceptsRanges(conn),
                        etag = conn.getHeaderField("ETag"),
                        lastModified = conn.getHeaderField("Last-Modified"),
                        filename = parseFilename(conn.getHeaderField("Content-Disposition")),
                        degradedProbe = false,
                    )
                }
                // 4xx/5xx on HEAD is inconclusive for capabilities — fall through to ranged GET.
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            // HEAD failed at the network level: fall through to ranged GET.
        }

        // Attempt 2: minimal ranged GET (bytes=0-0). Works on servers without HEAD.
        val conn = open(url, headers, "GET", "bytes=0-0")
        val code = conn.responseCode
        try {
            when {
                code == 206 -> {
                    // 206 with Content-Range gives us both range support and the total size.
                    val total = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    return ProbeResult(
                        totalBytes = total,
                        supportsRange = true,
                        etag = conn.getHeaderField("ETag"),
                        lastModified = conn.getHeaderField("Last-Modified"),
                        filename = parseFilename(conn.getHeaderField("Content-Disposition")),
                        degradedProbe = true,
                    )
                }
                code == 200 -> {
                    // Server ignores Range. Only Content-Length is trustworthy, if present.
                    return ProbeResult(
                        totalBytes = parseContentLength(conn),
                        supportsRange = false,
                        etag = conn.getHeaderField("ETag"),
                        lastModified = conn.getHeaderField("Last-Modified"),
                        filename = parseFilename(conn.getHeaderField("Content-Disposition")),
                        degradedProbe = true,
                    )
                }
                else -> throw HttpCodeException(code, "Probe failed with HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun openRange(url: String, headers: Map<String, String>, start: Long, endExclusive: Long?): OpenedStream {
        val rangeHeader = if (endExclusive == null) {
            "bytes=$start-"
        } else {
            "bytes=$start-${endExclusive - 1}"
        }
        val conn = open(url, headers, "GET", rangeHeader)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpCodeException(code, "HTTP $code for range request")
        }
        val rangeHonored = when {
            code == 206 -> true
            code == 200 && start == 0L -> true // full-content request, nothing to honor
            else -> false // 200 when we asked to resume — server ignored the Range
        }
        if (!rangeHonored) {
            conn.disconnect()
            throw RangeNotHonoredException(code)
        }
        // For multi-part requests we asked for a bounded range; 206 responses may still
        // be shorter than requested (server truncation) — we validate length in the part downloader.
        return OpenedStream(
            stream = conn.inputStream,
            rangeHonored = true,
            contentLength = parseContentLength(conn),
            responseCode = code,
        )
    }

    private fun parseContentLength(conn: HttpURLConnection): Long? {
        val raw = conn.getHeaderField("Content-Length") ?: return null
        // Never assume it parses cleanly.
        return raw.trim().toLongOrNull()
    }

    private fun acceptsRanges(conn: HttpURLConnection): Boolean =
        conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true

    private fun parseContentRangeTotal(value: String?): Long? {
        // "bytes 0-0/12345" or "bytes 0-0/*"
        if (value == null) return null
        val slash = value.lastIndexOf('/')
        if (slash < 0 || slash == value.length - 1) return null
        return value.substring(slash + 1).trim().toLongOrNull()
    }

    private fun parseFilename(disposition: String?): String? {
        if (disposition == null) return null
        val m = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
            ?: Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)
        return m?.groupValues?.get(1)
    }
}
