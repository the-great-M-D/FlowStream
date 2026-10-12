package com.downloadx.libdex

import java.io.ByteArrayOutputStream

/**
 * Carves decrypted Stark tunnel configs out of raw memory dumps.
 *
 * Why this exists: the app virtualizes its code with DexProtector's native
 * VM, so no decrypted DEX ever materializes in memory. The valuable runtime
 * data — the decrypted tunnel configuration JSON — lives as plain strings
 * inside the dalvik heap. This scanner replicates the recovery step that ran
 * against a 384 MB heap region.
 *
 * Strategy: find every '{"connection_mode"' seed, then balance braces while
 * honouring JSON string escapes, parse the candidate, and keep it only if it
 * looks like a config. Malformed candidates are skipped, never fatal —
 * graceful degradation is a hard requirement for the dump pipeline.
 */
object HeapCarver {

    private const val SEED = "{\"connection_mode\""

    /** Maximum bytes between seed and closing brace we are willing to accept. */
    private const val MAX_BLOB = 64 * 1024

    /** Scan an arbitrary heap region (or any byte blob) and extract configs. */
    fun extractConfigs(bytes: ByteArray): CarveResult {
        val configs = ArrayList<StarkConfig>()
        val warnings = ArrayList<String>()
        val seen = HashSet<String>()

        var idx = bytes.indexOf(SEED.toByteArray(Charsets.US_ASCII))
        while (idx >= 0) {
            val blob = balanceObject(bytes, idx)
            if (blob == null) {
                warnings.add("unbalanced object at offset $idx")
            } else {
                val text = blob.toString(Charsets.US_ASCII)
                val key = text.take(300)
                if (key !in seen) {
                    seen.add(key)
                    try {
                        val parsed = JsonValue.parse(text) as? JsonValue.Object
                            ?: throw JsonError("config candidate is not an object")
                        val cfg = StarkConfig.fromJson(parsed)
                        if (cfg.connectionMode == null && cfg.primaryHost == null) {
                            warnings.add("candidate at $idx has neither mode nor primary_host")
                        } else {
                            configs.add(cfg)
                        }
                    } catch (e: JsonError) {
                        warnings.add("skipped malformed candidate at $idx: ${e.message}")
                    }
                }
            }
            idx = bytes.indexOf(SEED.toByteArray(Charsets.US_ASCII), idx + 1)
        }
        return CarveResult(configs, warnings)
    }

    /**
     * Extract bytes from [start] through the matching closing brace, honouring
     * string escapes. Returns null when the object does not close within MAX_BLOB
     * (typical for a string that was truncated at a region boundary).
     */
    private fun balanceObject(bytes: ByteArray, start: Int): ByteArrayOutputStream? {
        val out = ByteArrayOutputStream()
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        val end = minOf(bytes.size.toLong(), start.toLong() + MAX_BLOB)
        while (i < end) {
            val b = bytes[i]
            out.write(b.toInt())
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == '\\'.code.toByte() -> escaped = true
                    b == '"'.code.toByte() -> inString = false
                }
            } else {
                when (b) {
                    '"'.code.toByte() -> inString = true
                    '{'.code.toByte() -> depth++
                    '}'.code.toByte() -> {
                        depth--
                        if (depth == 0) return out
                    }
                }
            }
            i++
        }
        return null
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    data class CarveResult(val configs: List<StarkConfig>, val warnings: List<String>)
}
