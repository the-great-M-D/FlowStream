package com.downloadx.libdex

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the recovered config inventory from the two export formats produced
 * by the APK-analysis pipeline:
 *
 *  - stark_configs_extracted.json  — one JSON object per blank-line-separated
 *    chunk (raw carve output, each line an independent JSON object)
 *  - stark_serverlist.json         — JSON array of { name, config } entries
 *
 * Loading is transactional and idempotent: each record is parsed
 * independently; one bad record produces a warning, never a partial state.
 * The returned store is immutable, so re-reading the same source always
 * yields an identical result.
 */
class ConfigStore private constructor(
    /** Entries carrying the human-readable list name from the app UI, when known. */
    val named: List<NamedConfig>,
    /** Configs with no known display name, deduplicated. */
    val unnamed: List<StarkConfig>,
    val warnings: List<String>
) {
    data class NamedConfig(val name: String?, val config: StarkConfig)

    val all: List<StarkConfig> get() = named.map { it.config } + unnamed

    companion object {
        /** Parse the raw carve export: JSON objects separated by blank lines. */
        fun fromRawCarve(text: String): ConfigStore {
            val named = ArrayList<NamedConfig>()
            val warnings = ArrayList<String>()
            val seen = HashSet<String>()
            for ((lineNo, chunk) in text.split("\n\n").map { it.trim() }.withIndex()) {
                if (chunk.isEmpty()) continue
                try {
                    val obj = JsonValue.parse(chunk) as? JsonValue.Object
                        ?: throw JsonError("chunk is not an object")
                    val cfg = StarkConfig.fromJson(obj)
                    val key = chunk.take(300)
                    if (key in seen) continue
                    seen.add(key)
                    named.add(NamedConfig(null, cfg))
                } catch (e: JsonError) {
                    warnings.add("chunk ${lineNo + 1}: ${e.message}")
                }
            }
            return ConfigStore(named, emptyList(), warnings)
        }

        /** Parse the serverlist export: a JSON array of { name, config }. */
        fun fromServerList(text: String): ConfigStore {
            val named = ArrayList<NamedConfig>()
            val warnings = ArrayList<String>()
            val seen = HashSet<String>()
            val root = JsonValue.parse(text)
            val arr = root as? JsonValue.Arr
                ?: throw JsonError("serverlist root is not an array")
            for (idx in arr.items.indices) {
                val item = arr.items[idx]
                val wrapper = item as? JsonValue.Object
                if (wrapper == null) {
                    warnings.add("entry $idx: not an object")
                    continue
                }
                val name = wrapper.str("name")
                val configValue = wrapper.fields["config"]
                val cfgObj = when (configValue) {
                    is JsonValue.Object -> configValue
                    is JsonValue.Str -> try {
                        JsonValue.parse(configValue.value) as? JsonValue.Object
                            ?: throw JsonError("config string is not an object")
                    } catch (e: JsonError) {
                        warnings.add("entry $idx: ${e.message}"); continue
                    }
                    else -> { warnings.add("entry $idx: missing config"); continue }
                }
                val key = name ?: cfgObj.toString().take(300)
                if (key in seen) continue
                seen.add(key)
                named.add(NamedConfig(name, StarkConfig.fromJson(cfgObj)))
            }
            return ConfigStore(named, emptyList(), warnings)
        }

        fun loadRawCarve(path: Path): ConfigStore =
            fromRawCarve(String(Files.readAllBytes(path), StandardCharsets.UTF_8))

        fun loadServerList(path: Path): ConfigStore =
            fromServerList(String(Files.readAllBytes(path), StandardCharsets.UTF_8))

        /** Scan a memory-dump directory: every *.bin is carved, maps.txt ignored. */
        fun fromDumpDir(dir: Path): ConfigStore {
            val configs = ArrayList<StarkConfig>()
            val warnings = ArrayList<String>()
            val seen = HashSet<String>()
            Files.list(dir).use { stream ->
                for (p in stream.filter { it.fileName.toString().endsWith(".bin") }.sorted()) {
                    val bytes = Files.readAllBytes(p)
                    if (bytes.isEmpty()) continue
                    val res = HeapCarver.extractConfigs(bytes)
                    warnings.addAll(res.warnings.map { "${p.fileName}: $it" })
                    for (cfg in res.configs) {
                        val key = cfg.customPayload.take(200)
                        if (key in seen) continue
                        seen.add(key)
                        configs.add(cfg)
                    }
                }
            }
            return ConfigStore(configs.map { NamedConfig(null, it) }, emptyList(), warnings)
        }
    }
}
