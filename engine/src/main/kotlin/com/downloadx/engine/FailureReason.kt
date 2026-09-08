package com.downloadx.engine

/**
 * Why a download failed. Classified so callers can decide what to retry
 * versus what needs user intervention.
 */
sealed class FailureReason {
    /** Client-side HTTP error (4xx except 408/429). Retrying will not help. */
    data class HttpError(val code: Int, val message: String?) : FailureReason()

    /** Network-level failure (timeout, reset, unreachable, 5xx). Retryable. */
    data class Network(val detail: String) : FailureReason()

    /** Storage is unavailable, unwritable, or full. */
    data class Storage(val detail: String) : FailureReason()

    /** The target file changed on the server between attempts (etag/last-modified mismatch). */
    data object TargetChanged : FailureReason()

    /** Journal exists but is corrupt or inconsistent with files on disk. */
    data class JournalCorrupt(val detail: String) : FailureReason()

    /** A request was malformed or rejected before it started (bad URL, etc). */
    data class Invalid(val detail: String) : FailureReason()
}

/** Classification used by the retry policy. */
enum class Severity { FATAL, TRANSIENT }

fun FailureReason.severity(): Severity = when (this) {
    is FailureReason.HttpError -> Severity.FATAL
    is FailureReason.Storage -> Severity.FATAL
    is FailureReason.TargetChanged -> Severity.FATAL
    is FailureReason.JournalCorrupt -> Severity.FATAL
    is FailureReason.Invalid -> Severity.FATAL
    is FailureReason.Network -> Severity.TRANSIENT
}

/**
 * Internal engine exception that carries a pre-classified reason, so storage
 * and validation failures are not misreported as network errors.
 */
class EngineFailure(val reason: FailureReason) : Exception(reason.toString())
