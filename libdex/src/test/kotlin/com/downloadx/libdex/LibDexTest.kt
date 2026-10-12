package com.downloadx.libdex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LibDexTest {

    // Verbatim config recovered from the 384MB dalvik-heap dump (2026-10-12).
    private val realConfig =
        """{"connection_mode":3,"server_port":"","use_proxy":false,"use_v2ray_mod":true,"proxy_host":"0","proxy_port":"0","custom_payload":"POST http:\/\/ncdc.gov.ng HTTP\/1.1[crlf]Host: http:\/\/ncdc.gov.ng[crlf]Connection: keep-alive[crlf]User-Agent: Dalvik\/2.1.0 (Linux; U; Android 11; TECNO PR651E Build\/RP1A.201005.001)[crlf]X-Online-Host: ncdc.gov.ng[crlf]X-Forward-Host: ncdc.gov.ng[crlf][crlf]","custom_host":"172.67.72.14","custom_sni":"trending.tiktok.com:2082","primary_host":"spoon.momslaps.xyz"}"""

    private val v2rayConfig =
        """{"connection_mode":4,"server_port":"8080","use_proxy":false,"use_v2ray_mod":true,"proxy_host":"8080","proxy_port":"8080","custom_payload":"","custom_host":"www.snapchat.com","custom_sni":"104.22.71.205","primary_host":"spoon.momslaps.xyz","primary_nameserver":"ns.starkdns.com","custom_resolver":"8.8.4.4"}"""

    @Test
    fun `parses a real recovered config`() {
        val cfg = StarkConfig.fromJson(JsonValue.parse(realConfig) as JsonValue.Object)
        assertEquals(3, cfg.connectionMode)
        assertEquals(null, cfg.serverPort)                 // "" degrades to null
        assertFalse(cfg.useProxy)
        assertTrue(cfg.useV2rayMod)
        assertNull(cfg.proxyHost)                          // "0" placeholder -> null
        assertEquals("172.67.72.14", cfg.customHost)
        assertEquals("trending.tiktok.com:2082", cfg.customSni)
        assertEquals("trending.tiktok.com", cfg.sniHost)
        assertEquals("spoon.momslaps.xyz", cfg.primaryHost)
        assertTrue(cfg.hasPrimaryHost)
        assertTrue(cfg.customPayload.contains("[crlf]"))
        // escape handling: \/ became /
        assertTrue(cfg.customPayload.startsWith("POST http://ncdc.gov.ng HTTP/1.1[crlf]"))
        assertFalse(cfg.decodedPayload.contains("[crlf]"))
        assertTrue(cfg.decodedPayload.contains("\r\nHost: http://ncdc.gov.ng"))
    }

    @Test
    fun `tolerates schema violations seen in the wild`() {
        val cfg = StarkConfig.fromJson(JsonValue.parse(v2rayConfig) as JsonValue.Object)
        assertEquals(8080, cfg.serverPort)
        assertEquals("8080", cfg.proxyHost)                // port stuffed in host field: kept raw
        assertEquals(8080, cfg.proxyPort)
        assertEquals("", cfg.customPayload)
        assertEquals("104.22.71.205", cfg.customSni)
        assertEquals("ns.starkdns.com", cfg.primaryNameserver)
        assertEquals("8.8.4.4", cfg.customResolver)
    }

    @Test
    fun `sni with scheme prefix degrades safely`() {
        val json =
            """{"connection_mode":3,"server_port":"53","custom_sni":"hysteria:\/\/89.38.227.2:1-65535@myudp:udpvpn","primary_host":"spoon.momslaps.xyz"}"""
        val cfg = StarkConfig.fromJson(JsonValue.parse(json) as JsonValue.Object)
        assertEquals("hysteria", cfg.sniHost)              // host-only view is lossy but safe
        assertEquals(53, cfg.serverPort)
    }

    @Test
    fun `heap carver extracts configs from a synthetic heap blob`() {
        val noise = ByteArray(4096) { (it * 31).toByte() }
        val heap = ByteArray(0)
        // noise + config + noise + duplicate config + trunc config + v2ray config
        val parts = mutableListOf<ByteArray>()
        parts += noise
        parts += realConfig.toByteArray()
        parts += "GARBAGE-BETWEEN".toByteArray()
        parts += realConfig.toByteArray()                    // duplicate -> deduped
        parts += noise
        parts += v2rayConfig.toByteArray()
        val blob = parts.reduce { a, b -> a + b }
        val res = HeapCarver.extractConfigs(blob)
        assertEquals(2, res.configs.size)                    // duplicate removed
        assertEquals(3, res.configs[0].connectionMode)
        assertEquals(4, res.configs[1].connectionMode)
        assertTrue(res.warnings.isEmpty())
    }

    @Test
    fun `heap carver survives a truncated config at region end`() {
        val truncated = realConfig.substring(0, 120).toByteArray()
        val res = HeapCarver.extractConfigs(truncated)
        assertEquals(0, res.configs.size)
        assertTrue(res.warnings.isNotEmpty())                // reported, not fatal
    }

    @Test
    fun `raw carve export parses with blank-line records`() {
        val export = "$realConfig\n\n$v2rayConfig\n\n{not-json\n\n"
        val store = ConfigStore.fromRawCarve(export)
        assertEquals(2, store.all.size)
        assertEquals(1, store.warnings.size)
        assertEquals(3, store.all[0].connectionMode)
        assertEquals(4, store.all[1].connectionMode)
    }

    @Test
    fun `serverlist export parses name plus config pairs`() {
        val list =
            """[{"name":"NG - Airtel 48MB Daily","config":$realConfig},{"name":"UDP Tunnel Free","config":$v2rayConfig}]"""
        val store = ConfigStore.fromServerList(list)
        assertEquals(2, store.named.size)
        assertEquals("NG - Airtel 48MB Daily", store.named[0].name)
        assertEquals("spoon.momslaps.xyz", store.all[1].primaryHost)
    }

    @Test
    fun `json parser rejects trailing garbage and reports offset`() {
        assertThrows(JsonError::class.java) { JsonValue.parse("""{"a":1}x""") }
        assertThrows(JsonError::class.java) { JsonValue.parse("""{"a":}""") }
        assertThrows(JsonError::class.java) { JsonValue.parse("{") }
    }

    @Test
    fun `round trip through dump dir works on real export file`() {
        val tmp = Files.createTempDirectory("libdex-test")
        try {
            // fake a dump region: binary noise with an embedded config
            val region = ByteArray(8192) { 0x55 }
            val cfgBytes = realConfig.toByteArray()
            System.arraycopy(cfgBytes, 0, region, 1000, cfgBytes.size)
            Files.write(tmp.resolve("r-12c00000.bin"), region)
            val store = ConfigStore.fromDumpDir(tmp)
            assertEquals(1, store.all.size)
            assertEquals("spoon.momslaps.xyz", store.all[0].primaryHost)
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
