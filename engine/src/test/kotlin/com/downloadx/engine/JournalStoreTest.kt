package com.downloadx.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class JournalStoreTest {

    @TempDir
    lateinit var tmp: Path

    private fun journal(target: Path, parts: List<PartSpec>) = Journal(
        id = DownloadId.generate(), url = "http://example.com/f.bin", target = target,
        totalBytes = 1000, supportsRange = true, etag = "\"v1\"", lastModified = "Mon, 01 Jan 2026 00:00:00 GMT",
        attempts = 2, parts = parts.toMutableList(), intent = Intent.PAUSED,
    )

    @Test
    fun `persist and load round-trip`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 500, 123), PartSpec(1, 500, 1000, 456)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(123))
        Files.write(JournalStore.partFile(dir, 1), ByteArray(456))
        JournalStore.persist(j, dir)

        val loaded = JournalStore.load(dir)!!
        assertEquals(j.id, loaded.id)
        assertEquals(j.url, loaded.url)
        assertEquals(target.toAbsolutePath(), loaded.target.toAbsolutePath())
        assertEquals(1000L, loaded.totalBytes)
        assertTrue(loaded.supportsRange)
        assertEquals("\"v1\"", loaded.etag)
        assertEquals(2, loaded.attempts)
        assertEquals(Intent.PAUSED, loaded.intent)
        assertEquals(2, loaded.parts.size)
        assertEquals(0, loaded.parts[0].start)
        assertEquals(123L, loaded.parts[0].written)
        assertEquals(456L, loaded.parts[1].written)
    }

    @Test
    fun `part missing on disk degrades to zero written`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 500, 123)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(123))
        JournalStore.persist(j, dir)
        // data loss between persist and load
        Files.delete(JournalStore.partFile(dir, 0))

        val loaded = JournalStore.load(dir)!!
        assertEquals(0L, loaded.parts[0].written)
    }

    @Test
    fun `part file larger than journal is truncated to journal`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 500, 100)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(300)) // crash after write, before journal
        JournalStore.persist(j, dir)

        val loaded = JournalStore.load(dir)!!
        assertEquals(100L, loaded.parts[0].written)
        assertEquals(100L, Files.size(JournalStore.partFile(dir, 0))) // conservatively truncated
    }

    @Test
    fun `part file smaller than journal trusts the file`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 500, 100)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(40))
        JournalStore.persist(j, dir)

        val loaded = JournalStore.load(dir)!!
        assertEquals(40L, loaded.parts[0].written)
    }

    @Test
    fun `corrupt journal returns null`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        Files.write(JournalStore.journalFile(dir), "this is not properties!".toByteArray())
        assertNull(JournalStore.load(dir))
    }

    @Test
    fun `wrong version returns null`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 100, 0)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(0))
        JournalStore.persist(j, dir)
        val text = Files.readString(JournalStore.journalFile(dir)).replace("version=2", "version=99")
        Files.write(JournalStore.journalFile(dir), text.toByteArray())
        assertNull(JournalStore.load(dir))
    }

    @Test
    fun `unknown size round-trips as null`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, null, 10)))
        j.totalBytes = null
        Files.write(JournalStore.partFile(dir, 0), ByteArray(10))
        JournalStore.persist(j, dir)

        val loaded = JournalStore.load(dir)!!
        assertNull(loaded.totalBytes)
        assertNull(loaded.parts[0].endExclusive)
        assertTrue(!loaded.parts[0].isComplete())
    }

    @Test
    fun `persist is atomic - no tmp residue and journal always parseable`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        val j = journal(target, listOf(PartSpec(0, 0, 100, 5)))
        Files.write(JournalStore.partFile(dir, 0), ByteArray(5))
        repeat(50) {
            j.parts[0].written = 5L + it
            JournalStore.persist(j, dir)
            assertNotNull(JournalStore.load(dir)) // never observe a torn write
            assertFalse(Files.exists(dir.resolve("journal.properties.tmp")))
        }
    }

    @Test
    fun `cleanup removes the part dir`() {
        val target = tmp.resolve("f.bin")
        val dir = JournalStore.initPartDir(target)
        Files.write(JournalStore.partFile(dir, 0), ByteArray(1))
        assertTrue(JournalStore.cleanup(dir))
        assertFalse(Files.exists(dir))
    }
}
