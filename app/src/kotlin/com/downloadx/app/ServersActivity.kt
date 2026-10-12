package com.downloadx.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.downloadx.libdex.ConfigStore
import com.downloadx.libdex.StarkConfig
import java.nio.charset.StandardCharsets

/**
 * Server list over the recovered Stark tunnel configs, parsed at runtime by
 * libdex from the bundled asset.
 *
 * Degrade-gracefully contract: if the asset is missing or corrupt the screen
 * shows the parse warnings instead of crashing — libdex never throws on bad
 * data, and this view layer adds no failure modes of its own.
 */
class ServersActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servers)

        listContainer = findViewById(R.id.servers_list)
        statusText = findViewById(R.id.servers_status)

        val store = loadConfigs()
        if (store.all.isEmpty()) {
            statusText.text = "No configs loaded.\n${store.warnings.joinToString("\n")}"
            return
        }
        statusText.text = "${store.all.size} configs recovered"

        store.all.forEachIndexed { idx, cfg -> listContainer.addView(row(idx, cfg)) }
    }

    /** Asset load is fully local; failures degrade to an empty store with warnings. */
    private fun loadConfigs(): ConfigStore {
        return try {
            val bytes = resources.assets.open("stark_configs.json").use { it.readBytes() }
            ConfigStore.fromServerList(String(bytes, StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            ConfigStore.empty(listOf("asset read failed: ${t.message}"))
        }
    }

    private fun row(index: Int, cfg: StarkConfig): View {
        val mode = when (cfg.connectionMode) {
            3 -> "HTTP-INJECT"
            4 -> "V2RAY"
            1 -> "SSL"
            else -> "MODE ${cfg.connectionMode ?: '?'}"
        }
        val title = cfg.sniHost ?: cfg.primaryHost ?: "(no host)"

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(28, 24, 28, 24)

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 16f
        titleView.setTypeface(Typeface.DEFAULT_BOLD)

        val subView = TextView(this)
        subView.text = "$mode  ·  ${cfg.primaryHost ?: "?"}  ·  port ${cfg.serverPort ?: "—"}"
        subView.textSize = 12f
        subView.setTextColor(Color.parseColor("#666666"))

        row.addView(titleView)
        row.addView(subView)
        row.setOnClickListener { showDetail(index, cfg) }

        if (index % 2 == 1) row.setBackgroundColor(Color.parseColor("#F5F5F0"))
        return row
    }

    /** Full config detail; payload shown with CRLF escapes so lines stay readable. */
    private fun showDetail(index: Int, cfg: StarkConfig) {
        val body = buildString {
            append("connection_mode:  ${cfg.connectionMode}\n")
            append("server_port:      ${cfg.serverPort ?: "—"}\n")
            append("use_v2ray_mod:    ${cfg.useV2rayMod}\n\n")
            append("custom_host:      ${cfg.customHost ?: "—"}\n")
            append("custom_sni:       ${cfg.customSni ?: "—"}\n")
            append("primary_host:     ${cfg.primaryHost ?: "—"}\n")
            append("nameserver:       ${cfg.primaryNameserver ?: "—"}\n")
            append("resolver:         ${cfg.customResolver ?: "—"}\n\n")
            if (cfg.customPayload.isNotBlank()) {
                append("custom_payload:\n")
                val lines = cfg.decodedPayload.split("\r\n")
                lines.forEachIndexed { i, ln ->
                    if (i > 0) append("    ")
                    append(ln.replace("\r", "\\r").replace("\n", "\\n"))
                    append("\n")
                }
            } else {
                append("custom_payload:   (none)")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("#${index + 1}  ${cfg.sniHost ?: cfg.primaryHost ?: "config"}")
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
