package com.downloadx.libdex

/**
 * One recovered Stark tunnel configuration.
 *
 * Schema recovered from runtime memory (heap dumps of the DexProtector-native
 * app). All fields are tolerant: the source emits schema violations in the
 * wild (ports in host fields, URI fragments in SNI fields, empty strings for
 * numbers), so every accessor degrades to null instead of throwing.
 *
 * Raw JSON is always retained in [raw] for forensics.
 */
data class StarkConfig(
    /** 3 = HTTP-inject / SSL tunnel, 4 = v2ray mod; other values seen: keep as-is. */
    val connectionMode: Int?,
    /** "" is used as "unset" by the source. */
    val serverPort: Int?,
    val useProxy: Boolean,
    val useV2rayMod: Boolean,
    /** Hostname or IP; source also stuffs ports ("0", "8080") here. */
    val proxyHost: String?,
    val proxyPort: Int?,
    /** HTTP inject payload with literal [crlf] markers. */
    val customPayload: String,
    val customHost: String?,
    /** SNI to present; may carry ":port" or even a "scheme://" prefix. */
    val customSni: String?,
    /** The actual VPN endpoint (e.g. spoon.momslaps.xyz). */
    val primaryHost: String?,
    val primaryNameserver: String?,
    val customResolver: String?,
    /** Unmodified original JSON object. */
    val raw: JsonValue.Object
) {
    /** Payload with [crlf] markers expanded to CRLF, as the tunnel engine sends it. */
    val decodedPayload: String
        get() = customPayload.replace("[crlf]", "\r\n")

    /** SNI host without a trailing ":port", if the field only holds host:port. */
    val sniHost: String?
        get() = customSni?.substringBefore(':')?.takeIf { it.isNotBlank() }

    /** True when the primary endpoint is usable (non-blank and not the "0" placeholder). */
    val hasPrimaryHost: Boolean
        get() = !primaryHost.isNullOrBlank() && primaryHost != "0"

    companion object {
        /**
         * Parse one JSON object into a StarkConfig.
         * @throws JsonError on malformed JSON — caller decides whether to skip or fail.
         */
        fun fromJson(obj: JsonValue.Object): StarkConfig {
            val payload = obj.str("custom_payload") ?: ""
            return StarkConfig(
                connectionMode = obj.num("connection_mode")?.toInt(),
                serverPort = obj.str("server_port")?.toIntOrNull() ?: obj.num("server_port")?.toInt(),
                useProxy = obj.bool("use_proxy") ?: false,
                useV2rayMod = obj.bool("use_v2ray_mod") ?: false,
                proxyHost = obj.str("proxy_host")?.takeIf { it.isNotBlank() && it != "0" },
                proxyPort = obj.str("proxy_port")?.toIntOrNull() ?: obj.num("proxy_port")?.toInt(),
                customPayload = payload,
                customHost = obj.str("custom_host")?.takeIf { it.isNotBlank() && it != "0" },
                customSni = obj.str("custom_sni")?.takeIf { it.isNotBlank() && it != "0" },
                primaryHost = obj.str("primary_host")?.takeIf { it.isNotBlank() && it != "0" },
                primaryNameserver = obj.str("primary_nameserver")?.takeIf { it.isNotBlank() },
                customResolver = obj.str("custom_resolver")?.takeIf { it.isNotBlank() },
                raw = obj
            )
        }
    }
}
