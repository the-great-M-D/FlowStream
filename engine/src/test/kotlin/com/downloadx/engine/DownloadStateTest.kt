package com.downloadx.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DownloadStateTest {

    private fun box() = StateBox(DownloadState.Idle)

    @Test
    fun `legal forward path transitions`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Connecting(1))
        b.transitionTo(DownloadState.Downloading(0, 100, 1))
        b.transitionTo(DownloadState.Paused)
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Connecting(2))
        b.transitionTo(DownloadState.Downloading(50, 100, 1))
        b.transitionTo(DownloadState.Completed(java.nio.file.Paths.get("/x"), 100))
    }

    @Test
    fun `cancel path transitions`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Cancelling)
        b.transitionTo(DownloadState.Cancelled)
    }

    @Test
    fun `cancelled can be resumed via queued`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Cancelling)
        b.transitionTo(DownloadState.Cancelled)
        b.transitionTo(DownloadState.Queued)
    }

    @Test
    fun `failed can be restarted via queued`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Failed(FailureReason.HttpError(404, null), 1, false))
        b.transitionTo(DownloadState.Queued)
    }

    @Test
    fun `illegal transitions throw`() {
        assertThrows<IllegalStateException> {
            val b = box()
            b.transitionTo(DownloadState.Downloading(0, null, 1)) // IDLE -> DOWNLOADING illegal
        }
        assertThrows<IllegalStateException> {
            val b = box()
            b.transitionTo(DownloadState.Queued)
            b.transitionTo(DownloadState.Downloading(0, null, 1)) // QUEUED -> DOWNLOADING illegal
        }
        assertThrows<IllegalStateException> {
            val b = box()
            b.transitionTo(DownloadState.Queued)
            b.transitionTo(DownloadState.Completed(java.nio.file.Paths.get("/x"), 0)) // QUEUED -> COMPLETED illegal
        }
        assertThrows<IllegalStateException> {
            val b = box()
            b.transitionTo(DownloadState.Queued)
            b.transitionTo(DownloadState.Connecting(1))
            b.transitionTo(DownloadState.Completed(java.nio.file.Paths.get("/x"), 0)) // CONNECTING -> COMPLETED illegal
        }
    }

    @Test
    fun `completed is terminal`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Connecting(1))
        b.transitionTo(DownloadState.Downloading(1, 1, 1))
        b.transitionTo(DownloadState.Completed(java.nio.file.Paths.get("/x"), 1))
        assertThrows<IllegalStateException> { b.transitionTo(DownloadState.Queued) }
        assertThrows<IllegalStateException> { b.transitionTo(DownloadState.Paused) }
    }

    @Test
    fun `progress updates allowed only while downloading`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Connecting(1))
        b.transitionTo(DownloadState.Downloading(0, 100, 1))
        assertTrue(b.updateProgress(42, 100, 1))
        assertEquals(42L, (b.value as DownloadState.Downloading).bytesWritten)
        b.transitionTo(DownloadState.Paused)
        assertFalse(b.updateProgress(50, 100, 1)) // paused: no progress
    }

    @Test
    fun `pause from queued is legal`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Paused)
        b.transitionTo(DownloadState.Queued)
    }

    @Test
    fun `retry attempts are same-kind transitions`() {
        val b = box()
        b.transitionTo(DownloadState.Queued)
        b.transitionTo(DownloadState.Connecting(1))
        b.transitionTo(DownloadState.Connecting(2)) // same kind: retry data update
    }
}

class FailureClassificationTest {

    @Test
    fun `404 is fatal http error`() {
        val r = RetryPolicy.classify(HttpCodeException(404, "nope"))
        assertTrue(r is FailureReason.HttpError)
        assertEquals(Severity.FATAL, r.severity())
    }

    @Test
    fun `500 is transient network`() {
        val r = RetryPolicy.classify(HttpCodeException(500, "boom"))
        assertTrue(r is FailureReason.Network)
        assertEquals(Severity.TRANSIENT, r.severity())
    }

    @Test
    fun `429 and 408 are transient`() {
        assertEquals(Severity.TRANSIENT, RetryPolicy.classify(HttpCodeException(429, "slow down")).severity())
        assertEquals(Severity.TRANSIENT, RetryPolicy.classify(HttpCodeException(408, "timeout")).severity())
    }

    @Test
    fun `storage failures are fatal`() {
        val r = RetryPolicy.classify(EngineFailure(FailureReason.Storage("full")))
        assertTrue(r is FailureReason.Storage)
    }

    @Test
    fun `cancellation is never classified - it propagates`() {
        val ce = kotlin.coroutines.cancellation.CancellationException("stop")
        assertThrows<kotlin.coroutines.cancellation.CancellationException> { RetryPolicy.classify(ce) }
    }

    @Test
    fun `retry policy bounds`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertTrue(p.shouldRetry(1, FailureReason.Network("x")))
        assertTrue(p.shouldRetry(2, FailureReason.Network("x")))
        assertFalse(p.shouldRetry(3, FailureReason.Network("x")))
        assertFalse(p.shouldRetry(1, FailureReason.HttpError(403, null)))
    }

    @Test
    fun `backoff delay is bounded and monotone-ish`() {
        val p = RetryPolicy(maxDelayMs = 1_000)
        repeat(20) { assertTrue(p.delayFor(5) <= 1_000) }
        assertTrue(p.delayFor(3) >= 0)
    }
}
