package com.phequals7.muesli.meetings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.TimeZone

class MeetingStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `recordingBaseName orders sanitized title before timestamp and keeps Unicode`() {
        val prevTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // 2026-11-06 14:30:00.123 UTC = 1793975400123L
            val started = 1793975400123L
            assertEquals(
                "Weekly_Planning_2026-11-06_14-30-00-123",
                MeetingStorageManager.recordingBaseName("Weekly Planning", started)
            )
            assertEquals(
                "ประชุม_แผนงาน_會議_2026-11-06_14-30-00-123",
                MeetingStorageManager.recordingBaseName("ประชุม แผนงาน / 會議", started)
            )
        } finally {
            TimeZone.setDefault(prevTz)
        }
    }

    @Test
    fun `sanitizeTitleForFilename strips traversal separators control chars and falls back to Meeting`() {
        assertEquals("Meeting", MeetingStorageManager.sanitizeTitleForFilename(null))
        assertEquals("Meeting", MeetingStorageManager.sanitizeTitleForFilename("   ... ___ / \\ : * ? \" < > | \n\t"))
        assertEquals("etc_passwd", MeetingStorageManager.sanitizeTitleForFilename("../../etc/passwd"))
    }

    @Test
    fun `sanitizeTitleForFilename caps at 160 UTF8 bytes without splitting Thai or Chinese code points`() {
        val longThai = "ก".repeat(100) // 300 UTF-8 bytes (3 bytes per char)
        val out = MeetingStorageManager.sanitizeTitleForFilename(longThai)
        val utf8 = out.toByteArray(Charsets.UTF_8)
        assertTrue("Expected <= 160 UTF-8 bytes, got ${utf8.size}", utf8.size <= 160)
        // Round-trip proves no broken UTF-8 / surrogate split.
        assertEquals(out, String(utf8, Charsets.UTF_8))
        assertEquals(53, out.length) // 53 * 3 = 159 <= 160
    }

    @Test
    fun `createSessionWavFile checks both wav and srt collisions when picking a suffix`() {
        val dir = tempFolder.newFolder("recordings")
        val started = 1793975400000L
        val base = MeetingStorageManager.recordingBaseName("Sync", started)

        val first = MeetingStorageManager.createSessionWavFile(dir, "Sync", started)
        assertEquals("$base.wav", first.name)

        // Pre-existing .srt at _2 forces the next reservation to _3.
        File(dir, "${base}_2.srt").writeText("occupied")
        val second = MeetingStorageManager.createSessionWavFile(dir, "Sync", started)
        assertEquals("${base}_3.wav", second.name)
    }

    @Test
    fun `syncSessionArtifacts renames wav and writes matching srt then removes old pair after DB commit`() = runTest {
        val dir = tempFolder.newFolder("pub")
        val started = 1793975400000L
        val oldWav = File(dir, "Initial_Title_2026-11-06_14-30-00-000.wav").apply {
            writeBytes(ByteArray(128) { (it and 0x7F).toByte() })
        }
        val oldSrt = File(dir, "Initial_Title_2026-11-06_14-30-00-000.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:01,000\nSpeaker 1: hi\n\n")
        }
        val expectedBytes = oldWav.readBytes()
        var committedOld: String? = null
        var committedNew: String? = null

        val srt = "1\n00:00:00,000 --> 00:00:02,000\nAlice: Updated text\n\n"
        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s1",
            title = "AI Generated Title",
            startedAtMs = started,
            sourceWav = oldWav,
            srtContent = srt,
            targetDirectory = dir,
            storedAudioPath = oldWav.absolutePath,
            commitPath = { expectedOld, newPath ->
                committedOld = expectedOld
                committedNew = newPath
                true
            }
        )

        assertNull(outcome.exportError)
        assertEquals(oldWav.absolutePath, committedOld)
        assertNotNull(committedNew)
        val newWav = File(committedNew!!)
        val newSrt = File(dir, newWav.nameWithoutExtension + ".srt")
        assertTrue(newWav.exists())
        assertTrue(newSrt.exists())
        assertFalse("Old WAV should be deleted after commit", oldWav.exists())
        assertFalse("Old SRT should be deleted after commit", oldSrt.exists())
        assertArrayEquals(expectedBytes, newWav.readBytes())
        assertEquals(srt, newSrt.readText())
    }

    @Test
    fun `collision suffix is stable across repeated syncs instead of drifting`() = runTest {
        val dir = tempFolder.newFolder("stable")
        val started = 1793975400000L
        val base = MeetingStorageManager.recordingBaseName("Sync", started)
        // Another recording occupies the desired stem.
        val unrelatedWav = File(dir, "$base.wav").apply { writeBytes(byteArrayOf(9)) }
        // Our session already lives at the _2 suffix from an earlier collision.
        val srcWav = File(dir, "${base}_2.wav").apply { writeBytes(ByteArray(64) { 0x11 }) }
        val srcSrt = File(dir, "${base}_2.srt").apply { writeText("old") }

        var storedPath = srcWav.name
        repeat(2) {
            val outcome = MeetingStorageManager.syncSessionArtifacts(
                sessionId = "s3",
                title = "Sync",
                startedAtMs = started,
                sourceWav = srcWav,
                srtContent = "1\n00:00:00,000 --> 00:00:01,000\nAlice: hi\n\n",
                targetDirectory = dir,
                storedAudioPath = storedPath,
                commitPath = { expectedOld, newPath ->
                    assertNull(expectedOld)
                    storedPath = File(newPath).name
                    true
                }
            )
            assertNull(outcome.exportError)
            // The owned _2 stem is not a conflict: the file keeps its name.
            assertEquals(srcWav.absolutePath, outcome.wavPath)
        }
        assertTrue("Existing WAV must stay", unrelatedWav.exists())
        assertTrue("No _3 drift expected", !File(dir, "${base}_3.wav").exists())
        assertTrue("No _3 SRT drift expected", !File(dir, "${base}_3.srt").exists())
        assertTrue(File(dir, "${base}_2.srt").exists())
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `cancellation just after a successful DB commit keeps the new pair and removes the source`() = runTest {
        val dir = tempFolder.newFolder("cancelcommit")
        val started = 1793975400000L
        val oldWav = File(dir, "Old_Title_2026-11-06_14-30-00-000.wav").apply {
            writeBytes(ByteArray(96) { 0x33 })
        }
        val expectedBytes = oldWav.readBytes()
        var committedNew: String? = null
        lateinit var job: Job
        job = launch {
            val outcome = MeetingStorageManager.syncSessionArtifacts(
                sessionId = "s4",
                title = "New Title",
                startedAtMs = started,
                sourceWav = oldWav,
                srtContent = "1\n00:00:00,000 --> 00:00:01,000\nAlice: hi\n\n",
                targetDirectory = dir,
                storedAudioPath = oldWav.absolutePath,
                commitPath = { _, newPath ->
                    committedNew = newPath
                    job.cancel() // cancellation lands right after the commit
                    true
                }
            )
            assertNull(outcome.exportError)
        }
        job.join()

        val newWav = File(committedNew!!)
        assertTrue("Committed pair must survive post-commit cancellation", newWav.exists())
        assertArrayEquals(expectedBytes, newWav.readBytes())
        assertFalse("Source must be cleaned inside the non-cancellable commit", oldWav.exists())
        assertTrue(File(dir, newWav.nameWithoutExtension + ".srt").exists())
    }

    @Test
    fun `partial copy failure removes the reserved destination and reports an export error`() = runTest {
        val dir = tempFolder.newFolder("partial")
        val started = 1793975400000L
        // A directory masquerading as the source WAV: reading it fails mid-copy.
        val sourceDir = File(dir, "Broken_2026-11-06_14-30-00-000.wav").apply { mkdirs() }
        File(sourceDir, "child.txt").writeText("x")
        val desiredStem = MeetingStorageManager.recordingBaseName("Renamed", started)

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s5",
            title = "Renamed",
            startedAtMs = started,
            sourceWav = sourceDir,
            srtContent = null,
            targetDirectory = dir,
            storedAudioPath = sourceDir.absolutePath,
            commitPath = { _, _ -> true }
        )

        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertEquals(sourceDir.absolutePath, outcome.wavPath)
        assertTrue("Source directory must remain", sourceDir.exists())
        assertFalse("Partial copy must be removed", File(dir, "$desiredStem.wav").exists())
        assertFalse("No stray SRT", File(dir, "$desiredStem.srt").exists())
    }

    @Test
    fun `failed SRT replacement keeps the old sidecar intact`() = runTest {
        val dir = tempFolder.newFolder("srtfail")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("T", started)
        val wav = File(dir, "$stem.wav").apply { writeBytes(ByteArray(64) { 0x44 }) }
        val oldSrt = File(dir, "$stem.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nold\n\n") }
        val oldBytes = oldSrt.readBytes()

        val wasWritable = dir.canWrite()
        try {
            dir.setWritable(false)
            val outcome = MeetingStorageManager.syncSessionArtifacts(
                sessionId = "s6",
                title = "T",
                startedAtMs = started,
                sourceWav = wav,
                srtContent = "1\n00:00:00,000 --> 00:00:01,000\nnew\n\n",
                targetDirectory = dir,
                storedAudioPath = wav.name,
                commitPath = { _, _ -> true }
            )
            assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
            assertArrayEquals("Old SRT must survive a failed replacement untouched", oldBytes, oldSrt.readBytes())
            assertTrue(wav.exists())
            assertEquals("Only the original pair may remain", 2, dir.listFiles()!!.size)
        } finally {
            dir.setWritable(wasWritable)
        }
    }

    /** In-memory SAF provider: URI strings, display names, byte contents. */
    private open class FakeTreeGateway : MeetingStorageManager.RecordingTreeGateway {
        val contents = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var corruptLengthFor: String? = null
        private var next = 0

        override fun isInTree(documentUri: String) = documentUri.startsWith("tree://")
        override fun childDocumentNames() = names.values.toSet()

        override fun createDocument(mime: String, displayName: String): String {
            check(names.values.none { it == displayName }) { "Provider overwrote $displayName" }
            val uri = "tree://${next++}"
            names[uri] = displayName
            contents[uri] = ByteArray(0)
            return uri
        }

        override fun queryDisplayName(documentUri: String) = names[documentUri]
        override fun openOutput(documentUri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                contents[documentUri] = toByteArray()
            }
        }

        override fun documentLength(documentUri: String): Long =
            if (documentUri == corruptLengthFor) (contents[documentUri]?.size ?: 0).toLong() + 7
            else contents[documentUri]?.size?.toLong() ?: -1L

        override fun deleteDocument(documentUri: String): Boolean {
            deleted.add(documentUri)
            return contents.remove(documentUri) != null && names.remove(documentUri) != null
        }
    }

    @Test
    fun `exportPairToTree creates a verified collision-free pair and commits both uris`() = runTest {
        val dir = tempFolder.newFolder("tree1")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Tree", started)
        val wav = File(dir, "tmp.wav").apply { writeBytes(ByteArray(300) { (it % 251).toByte() }) }
        val srt = "1\n00:00:00,000 --> 00:00:01,000\nAlice: hi\n\n"
        val gateway = FakeTreeGateway()
        // An unrelated file already owns the desired stem.
        gateway.createDocument("audio/wav", "$stem.wav")

        var committedWav: String? = null
        var committedSrt: String? = null
        val outcome = MeetingStorageManager.exportPairToTree(
            gateway = gateway,
            sessionId = "s7",
            desiredStem = stem,
            sourceWav = wav,
            srtContent = srt,
            ownedWavUri = null,
            ownedSrtUri = null,
            commitUris = { w, s ->
                committedWav = w
                committedSrt = s
                true
            }
        )
        assertNull(outcome.exportError)
        assertNull(outcome.cleanupError)
        assertEquals("${stem}_2.wav", gateway.names[committedWav])
        assertEquals("${stem}_2.srt", gateway.names[committedSrt])
        assertArrayEquals(wav.readBytes(), gateway.contents[committedWav])
        assertEquals(srt, String(gateway.contents[committedSrt]!!, Charsets.UTF_8))
        // The unrelated file was never touched or deleted.
        assertTrue(gateway.contents.keys.any { gateway.names[it] == "$stem.wav" })
    }

    @Test
    fun `exportPairToTree reuses owned documents idempotently without creating duplicates`() = runTest {
        val dir = tempFolder.newFolder("tree2")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Tree", started)
        val wav = File(dir, "tmp.wav").apply { writeBytes(ByteArray(120) { 0x55 }) }
        val srt = "1\n00:00:00,000 --> 00:00:01,000\nBob: yo\n\n"
        val gateway = FakeTreeGateway()
        val ownedWav = gateway.createDocument("audio/wav", "$stem.wav")
        val ownedSrt = gateway.createDocument("application/x-subrip", "$stem.srt")
        val before = gateway.names.toMap()

        val outcome = MeetingStorageManager.exportPairToTree(
            gateway = gateway,
            sessionId = "s8",
            desiredStem = stem,
            sourceWav = wav,
            srtContent = srt,
            ownedWavUri = ownedWav,
            ownedSrtUri = ownedSrt,
            commitUris = { w, s -> w == ownedWav && s == ownedSrt }
        )
        assertNull(outcome.exportError)
        assertEquals(before, gateway.names)
        assertEquals(wav.readBytes().toList(), gateway.contents[ownedWav]!!.toList())
        assertEquals(srt, String(gateway.contents[ownedSrt]!!, Charsets.UTF_8))
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `shorter notes replace the previous exported SRT without leaving a suffix`() = runTest {
        val wav = File(tempFolder.newFolder("shorter-srt"), "audio.wav").apply { writeBytes(ByteArray(120)) }
        val gateway = FakeTreeGateway()
        val wavUri = gateway.createDocument("audio/wav", "meeting.wav")
        val srtUri = gateway.createDocument("application/x-subrip", "meeting.srt")
        gateway.openOutput(wavUri).use { it.write(wav.readBytes()); it.flush() }
        gateway.openOutput(srtUri).use { it.write("longer original notes".toByteArray()); it.flush() }

        val result = MeetingStorageManager.exportPairToTree(
            gateway, "session", "meeting", wav, "short", wavUri, srtUri,
            commitUris = { w, s -> w == wavUri && s == srtUri },
        )

        assertNull(result.exportError)
        assertEquals("short", String(gateway.contents[srtUri]!!, Charsets.UTF_8))
        assertEquals(setOf("meeting.wav", "meeting.srt"), gateway.childDocumentNames())
    }

    @Test
    fun `title change prepares a new pair before deleting the old documents`() = runTest {
        val dir = tempFolder.newFolder("tree3")
        val started = 1793975400000L
        val oldStem = MeetingStorageManager.recordingBaseName("Old", started)
        val newStem = MeetingStorageManager.recordingBaseName("New Title", started)
        val wav = File(dir, "tmp.wav").apply { writeBytes(ByteArray(90) { 0x66 }) }
        val gateway = FakeTreeGateway()
        val oldWav = gateway.createDocument("audio/wav", "$oldStem.wav")
        val oldSrt = gateway.createDocument("application/x-subrip", "$oldStem.srt")

        val outcome = MeetingStorageManager.exportPairToTree(
            gateway = gateway,
            sessionId = "s9",
            desiredStem = newStem,
            sourceWav = wav,
            srtContent = "srt",
            ownedWavUri = oldWav,
            ownedSrtUri = oldSrt,
            commitUris = { _, _ -> true }
        )
        assertNull(outcome.exportError)
        assertEquals("$newStem.wav", gateway.names[outcome.wavUri])
        assertTrue("Old pair deleted only after commit", oldWav in gateway.deleted && oldSrt in gateway.deleted)
    }

    @Test
    fun `rejected commit removes only attempt-owned documents`() = runTest {
        val dir = tempFolder.newFolder("tree4")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("T", started)
        val wav = File(dir, "tmp.wav").apply { writeBytes(ByteArray(50) { 0x77 }) }
        val gateway = FakeTreeGateway()
        val oldWav = gateway.createDocument("audio/wav", "$stem.wav")

        val outcome = MeetingStorageManager.exportPairToTree(
            gateway = gateway,
            sessionId = "s10",
            desiredStem = stem,
            sourceWav = wav,
            srtContent = "srt",
            ownedWavUri = oldWav,
            ownedSrtUri = null,
            commitUris = { _, _ -> false } // row deleted concurrently
        )
        assertNull(outcome.exportError)
        assertNull(outcome.wavUri)
        // The freshly created _2 pair was removed; the old owned doc remains.
        assertTrue(oldWav !in gateway.deleted)
        assertTrue(oldWav in gateway.names)
        assertEquals(setOf("$stem.wav"), gateway.childDocumentNames())
    }

    @Test
    fun `length verification failure removes attempt-owned documents and reports an error`() = runTest {
        val dir = tempFolder.newFolder("tree5")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("T", started)
        val wav = File(dir, "tmp.wav").apply { writeBytes(ByteArray(80) { 0x88.toByte() }) }
        val gateway = object : FakeTreeGateway() {
            override fun documentLength(documentUri: String): Long =
                if (names[documentUri]?.endsWith(".wav") == true) super.documentLength(documentUri) + 7
                else super.documentLength(documentUri)
        }

        val outcome = MeetingStorageManager.exportPairToTree(
            gateway = gateway,
            sessionId = "s11",
            desiredStem = stem,
            sourceWav = wav,
            srtContent = null,
            ownedWavUri = null,
            ownedSrtUri = null,
            commitUris = { _, _ -> true }
        )
        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertTrue(outcome.exportError!!.contains("length mismatch"))
        assertEquals("Attempt-owned documents must be removed", emptySet<String>(), gateway.childDocumentNames().toSet())
        assertNull(outcome.wavUri)
    }

    @Test
    fun `syncSessionArtifacts preserves source and existing destination when DB commit rejects update`() = runTest {
        val dir = tempFolder.newFolder("pub2")
        val started = 1793975400000L
        val oldWav = File(dir, "Old_2026-11-06_14-30-00-000.wav").apply {
            writeBytes(ByteArray(200) { 0x42 })
        }
        // Pre-existing unrelated recording with the target stem must stay untouched.
        val desiredStem = MeetingStorageManager.recordingBaseName("Renamed", started)
        val unrelated = File(dir, "$desiredStem.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s2",
            title = "Renamed",
            startedAtMs = started,
            sourceWav = oldWav,
            srtContent = "1\n00:00:00,000 --> 00:00:01,000\nAlice: hi\n\n",
            targetDirectory = dir,
            storedAudioPath = oldWav.absolutePath,
            commitPath = { _, _ -> false } // DB row deleted/stale -> reject
        )

        assertNull(outcome.exportError)
        assertEquals(oldWav.absolutePath, outcome.wavPath)
        assertTrue("Original WAV must remain when DB update fails", oldWav.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), unrelated.readBytes())
        // Temporary _2 candidate copies must have been cleaned up.
        assertFalse(File(dir, "${desiredStem}_2.wav").exists())
        assertFalse(File(dir, "${desiredStem}_2.srt").exists())
    }
}
