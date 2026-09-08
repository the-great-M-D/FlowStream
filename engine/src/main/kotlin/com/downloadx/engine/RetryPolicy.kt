package com.downloadx.engine

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Bounded retry with exponential backoff and jitter. Transient failures only;
 * cancellation never retries.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    private val jitter: (Long) -> Long = { d -> (d * (0.5 + Math.random() * 0.5)).toLong() },
) {
    /** True if another attempt should be made. */
    fun shouldRetry(attempts: Int, reason: FailureReason): Boolean =
        reason.severity() == Severity.TRANSIENT && attempts < maxAttempts

    /** Backoff delay in ms for the given attempt number (1-based). */
    fun delayFor(attempt: Int): Long {
        val exp = baseDelayMs shl (attempt - 1).coerceAtMost(20)
        return jitter(exp.coerceAtMost(maxDelayMs))
    }

    companion object {
        /**
         * Translate a raw throwable into a FailureReason. Cancellation is NEVER
         * converted — it must propagate (structured concurrency rule).
         */
        fun classify(t: Throwable, httpCode: Int? = null): FailureReason = when (t) {
            is kotlin.coroutines.cancellation.CancellationException -> throw t // never swallow cancellation
            is EngineFailure -> t.reason
            is HttpCodeException ->
                if (t.code == 408 || t.code == 429 || t.code >= 500) FailureReason.Network("HTTP ${t.code}: ${t.message}")
                else FailureReason.HttpError(t.code, t.message)
            is TargetChangedException -> FailureReason.TargetChanged
            is RangeNotHonoredException -> FailureReason.Network("Server stopped honoring ranges (HTTP ${t.responseCode})")
            is UnknownHostException -> FailureReason.Network("Unknown host")
            is SocketTimeoutException -> FailureReason.Network("Timeout")
            is SocketException -> FailureReason.Network(t.message ?: "Socket error")
            is javax.net.ssl.SSLException -> FailureReason.Network(t.message ?: "SSL error")
            else -> {
                val msg = t.message ?: t.javaClass.simpleName
                if (httpCode != null) {
                    if (httpCode == 408 || httpCode == 429 || httpCode >= 500) FailureReason.Network("HTTP $httpCode: $msg")
                    else FailureReason.HttpError(httpCode, msg)
                } else if (t is IOException) {
                    FailureReason.Network(msg)
                } else {
                    FailureReason.Invalid(msg)
                }
            }
        }
    }
}
