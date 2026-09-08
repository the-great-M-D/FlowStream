package com.downloadx.engine

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit state machine for a single download.
 *
 * Rules:
 *  - Every transition goes through [transitionTo], which validates against a table.
 *  - Data-carrying updates within the same state kind (progress ticks, retry attempts)
 *    are always allowed.
 *  - An illegal transition throws [IllegalStateException] — fail loudly, never limp.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    object Queued : DownloadState()
    data class Connecting(val attempt: Int) : DownloadState()
    data class Downloading(val bytesWritten: Long, val totalBytes: Long?, val activeParts: Int) : DownloadState()
    object Paused : DownloadState()
    object Cancelling : DownloadState()
    object Cancelled : DownloadState()
    data class Failed(val reason: FailureReason, val attempts: Int, val canResume: Boolean) : DownloadState()
    data class Completed(val target: Path, val bytes: Long) : DownloadState()

    companion object {
        /** Canonical kind used for the transition table. */
        fun kindOf(s: DownloadState): Kind = when (s) {
            is Idle -> Kind.IDLE
            is Queued -> Kind.QUEUED
            is Connecting -> Kind.CONNECTING
            is Downloading -> Kind.DOWNLOADING
            is Paused -> Kind.PAUSED
            is Cancelling -> Kind.CANCELLING
            is Cancelled -> Kind.CANCELLED
            is Failed -> Kind.FAILED
            is Completed -> Kind.COMPLETED
        }

        /**
         * Legal kind-to-kind transitions. Self-transitions (progress/retry data
         * updates) are always allowed and not listed.
         */
        private val legal: Map<Kind, Set<Kind>> = mapOf(
            Kind.IDLE to setOf(Kind.QUEUED),
            Kind.QUEUED to setOf(Kind.CONNECTING, Kind.CANCELLING, Kind.PAUSED, Kind.FAILED),
            Kind.CONNECTING to setOf(Kind.DOWNLOADING, Kind.FAILED, Kind.CANCELLING, Kind.PAUSED),
            Kind.DOWNLOADING to setOf(Kind.CONNECTING, Kind.PAUSED, Kind.FAILED, Kind.CANCELLING, Kind.COMPLETED),
            Kind.PAUSED to setOf(Kind.QUEUED, Kind.CANCELLING),
            Kind.CANCELLING to setOf(Kind.CANCELLED),
            Kind.FAILED to setOf(Kind.QUEUED), // explicit restart only
            Kind.CANCELLED to setOf(Kind.QUEUED), // explicit resume only
            Kind.COMPLETED to emptySet(),
        )

        fun canTransition(from: DownloadState, to: DownloadState): Boolean {
            val f = kindOf(from)
            val t = kindOf(to)
            if (f == t) return true
            return legal.getValue(f).contains(t)
        }
    }

    enum class Kind { IDLE, QUEUED, CONNECTING, DOWNLOADING, PAUSED, CANCELLING, CANCELLED, FAILED, COMPLETED }
}

/**
 * Thread-safe state holder with validated transitions.
 */
class StateBox(initial: DownloadState = DownloadState.Idle) {
    private val ref = AtomicReference(initial)
    val value: DownloadState get() = ref.get()

    fun transitionTo(newState: DownloadState): DownloadState {
        val prev = ref.updateAndGet { cur ->
            if (!DownloadState.canTransition(cur, newState)) {
                throw IllegalStateException("Illegal state transition: ${DownloadState.kindOf(cur)} -> ${DownloadState.kindOf(newState)}")
            }
            newState
        }
        return prev
    }

    /** Progress-only update; still validated as a self-kind transition. */
    fun updateProgress(bytesWritten: Long, totalBytes: Long?, activeParts: Int): Boolean {
        val cur = ref.get()
        if (cur !is DownloadState.Downloading) return false
        ref.set(DownloadState.Downloading(bytesWritten, totalBytes, activeParts))
        return true
    }
}
