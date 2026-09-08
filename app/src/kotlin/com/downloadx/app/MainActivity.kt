package com.downloadx.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.downloadx.engine.DownloadEngine
import com.downloadx.engine.DownloadHandle
import com.downloadx.engine.DownloadRequest
import com.downloadx.engine.DownloadState
import com.downloadx.engine.EngineConfig
import com.downloadx.engine.JdkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

/**
 * Minimal single-activity UI over the DownloadX V2 engine. Pure platform APIs —
 * no AndroidX — so the engine and its UI stay independently testable: this
 * activity is a thin view layer that only forwards user intent to the engine
 * and renders its state flow.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: DownloadEngine
    private var handle: DownloadHandle? = null
    private var collectorJob: kotlinx.coroutines.Job? = null

    private lateinit var urlInput: EditText
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var stateText: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url_input)
        startButton = findViewById(R.id.start_button)
        pauseButton = findViewById(R.id.pause_button)
        resumeButton = findViewById(R.id.resume_button)
        cancelButton = findViewById(R.id.cancel_button)
        progressBar = findViewById(R.id.progress_bar)
        stateText = findViewById(R.id.state_text)

        // Show the previous run's crash, if any, so it can be reported without adb.
        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val crash = crashFile.readText()
            if (crash.isNotBlank()) {
                stateText.setTextColor(0xFFB91C1C.toInt())
                stateText.text = "PREVIOUS RUN CRASHED:\n$crash"
            }
        }

        try {
            val config = EngineConfig()
            engine = DownloadEngine(scope, JdkHttpTransport(config), config)

            // Idempotent recovery: finish what previous process runs left behind.
            val base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val downloadDir = File(base, "files")
            downloadDir.mkdirs()

        startButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (!url.startsWith("http")) {
                Toast.makeText(this, "Enter a valid http(s) URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = try {
                val path = URI(url).path
                path.substringAfterLast('/').ifBlank { "download.bin" }
            } catch (_: Exception) {
                "download.bin"
            }
            handle?.let { h ->
                h.cancel()
            }
            val h = engine.submit(DownloadRequest(url, File(downloadDir, name).toPath()))
            attach(h)
        }

        pauseButton.setOnClickListener { handle?.pause() }
        resumeButton.setOnClickListener { handle?.resume() }
        cancelButton.setOnClickListener { handle?.cancel() }

            // After engine construction so handles exist before observing.
            val recovered = engine.recover(downloadDir.toPath())
            recovered.firstOrNull()?.let { attach(it) }
        } catch (t: Throwable) {
            // Never crash at startup without leaving the reason visible.
            runCatching {
                File(filesDir, "last_crash.txt").writeText(t.stackTraceToString())
            }
            stateText.setTextColor(0xFFB91C1C.toInt())
            stateText.text = "STARTUP ERROR:\n${t.stackTraceToString()}"
        }
    }

    /** Observe one download's state flow and drive the UI from it. */
    private fun attach(h: DownloadHandle) {
        handle = h
        collectorJob?.cancel()
        collectorJob = scope.launch {
            h.states.collect { render(it) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun render(state: DownloadState) {
        when (state) {
            is DownloadState.Idle -> stateText.text = "Idle"
            is DownloadState.Queued -> stateText.text = "Queued…"
            is DownloadState.Connecting -> stateText.text = "Connecting (attempt ${state.attempt})…"
            is DownloadState.Downloading -> {
                val written = state.bytesWritten
                val total = state.totalBytes
                if (total != null && total > 0) {
                    progressBar.progress = ((written * 1000) / total).toInt()
                    stateText.text = "Downloading ${fmt(written)} / ${fmt(total)} — ${state.activeParts} part(s)"
                } else {
                    progressBar.isIndeterminate = true
                    stateText.text = "Downloading ${fmt(written)} (size unknown) — ${state.activeParts} part(s)"
                }
            }
            is DownloadState.Paused -> {
                progressBar.isIndeterminate = false
                stateText.text = "Paused — progress saved"
            }
            is DownloadState.Cancelling -> stateText.text = "Cancelling…"
            is DownloadState.Cancelled -> stateText.text = "Cancelled — resume to continue"
            is DownloadState.Failed -> stateText.text = "Failed after ${state.attempts} attempt(s): ${state.reason}"
            is DownloadState.Completed -> {
                progressBar.progress = 1000
                stateText.text = "Completed: ${state.target.lastFileName()} (${fmt(state.bytes)})"
            }
        }

        val active = state is DownloadState.Downloading || state is DownloadState.Connecting || state is DownloadState.Queued
        pauseButton.isEnabled = active
        resumeButton.isEnabled = state is DownloadState.Paused || state is DownloadState.Cancelled || state is DownloadState.Failed
        cancelButton.isEnabled = active
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1 shl 20 -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun java.nio.file.Path.lastFileName(): String = fileName?.toString() ?: toString()

    override fun onDestroy() {
        // Graceful shutdown: pause everything, persist journals, keep data resumable.
        engine.close()
        scope.cancel()
        super.onDestroy()
    }
}
