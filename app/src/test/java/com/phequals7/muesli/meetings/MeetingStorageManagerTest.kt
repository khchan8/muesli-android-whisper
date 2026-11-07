package com.phequals7.muesli.meetings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `createSessionAudioFile checks m4a wav and srt collisions when picking a suffix`() {
        val dir = tempFolder.newFolder("recordings")
        val started = 1793975400000L
        val base = MeetingStorageManager.recordingBaseName("Sync", started)

        val first = MeetingStorageManager.createSessionAudioFile(dir, "Sync", started)
        assertEquals("$base.m4a", first.name)

        // Pre-existing .srt at _2 forces the next reservation to _3.
        File(dir, "${base}_2.srt").writeText("occupied")
        val second = MeetingStorageManager.createSessionAudioFile(dir, "Sync", started)
        assertEquals("${base}_3.m4a", second.name)

        // A legacy .wav at _4 also blocks the stem.
        File(dir, "${base}_4.wav").writeBytes(ByteArray(64))
        val third = MeetingStorageManager.createSessionAudioFile(dir, "Sync", started)
        assertEquals("${base}_5.m4a", third.name)
    }

    // ── private normalization (syncSessionArtifacts) ─────────────────────

    @Test
    fun `syncSessionArtifacts renames m4a keeps its extension and copies an existing srt sidecar`() = runTest {
        val dir = tempFolder.newFolder("pub")
        val started = 1793975400000L
        val oldAudio = File(dir, "Initial_Title_2026-11-06_14-30-00-000.m4a").apply {
            writeBytes(ByteArray(128) { (it and 0x7F).toByte() })
        }
        val oldSrt = File(dir, "Initial_Title_2026-11-06_14-30-00-000.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:01,000\nSpeaker 1: hi\n\n")
        }
        val expectedBytes = oldAudio.readBytes()
        var committedOld: String? = null
        var committedNew: String? = null

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s1",
            title = "AI Generated Title",
            startedAtMs = started,
            sourceAudio = oldAudio,
            targetDirectory = dir,
            storedAudioPath = oldAudio.name,
            commitPath = { expectedOld, newPath ->
                committedOld = expectedOld
                committedNew = newPath
                true
            }
        )

        assertNull(outcome.exportError)
        assertEquals(oldAudio.name, committedOld)
        val newAudio = File(committedNew!!)
        val newSrt = File(dir, newAudio.nameWithoutExtension + ".srt")
        assertTrue(newAudio.name.endsWith(".m4a"))
        assertTrue(newAudio.exists())
        assertTrue(newSrt.exists())
        assertFalse("Old audio must be deleted after commit", oldAudio.exists())
        assertFalse("Old SRT must be deleted after commit", oldSrt.exists())
        assertArrayEquals(expectedBytes, newAudio.readBytes())
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nSpeaker 1: hi\n\n", newSrt.readText())
    }

    @Test
    fun `syncSessionArtifacts never creates an srt when none exists`() = runTest {
        val dir = tempFolder.newFolder("nosrt")
        val started = 1793975400000L
        val oldWav = File(dir, "Legacy_2026-11-06_14-30-00-000.wav").apply {
            writeBytes(ByteArray(200) { 0x21 })
        }

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s2",
            title = "Renamed",
            startedAtMs = started,
            sourceAudio = oldWav,
            targetDirectory = dir,
            storedAudioPath = oldWav.name,
            commitPath = { _, _ -> true }
        )

        assertNull(outcome.exportError)
        val newWav = File(outcome.audioPath!!)
        assertTrue(newWav.name.endsWith(".wav"))
        assertFalse(File(dir, newWav.nameWithoutExtension + ".srt").exists())
        assertFalse(oldWav.exists())
    }

    @Test
    fun `collision suffix is stable across repeated syncs instead of drifting`() = runTest {
        val dir = tempFolder.newFolder("stable")
        val started = 1793975400000L
        val base = MeetingStorageManager.recordingBaseName("Sync", started)
        // Another recording occupies the desired stem.
        val unrelated = File(dir, "$base.m4a").apply { writeBytes(byteArrayOf(9)) }
        // Our session already lives at the _2 suffix from an earlier collision.
        val srcAudio = File(dir, "${base}_2.m4a").apply { writeBytes(ByteArray(64) { 0x11 }) }
        val srcSrt = File(dir, "${base}_2.srt").apply { writeText("old") }

        var storedPath = srcAudio.name
        repeat(2) {
            val outcome = MeetingStorageManager.syncSessionArtifacts(
                sessionId = "s3",
                title = "Sync",
                startedAtMs = started,
                sourceAudio = srcAudio,
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
            assertEquals(srcAudio.absolutePath, outcome.audioPath)
        }
        assertTrue("Existing recording must stay", unrelated.exists())
        assertTrue("No _3 drift expected", !File(dir, "${base}_3.m4a").exists())
        assertTrue("No _3 SRT drift expected", !File(dir, "${base}_3.srt").exists())
        assertTrue(File(dir, "${base}_2.srt").exists())
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `cancellation just after a successful DB commit keeps the new files and removes the source`() = runTest {
        val dir = tempFolder.newFolder("cancelcommit")
        val started = 1793975400000L
        val oldAudio = File(dir, "Old_Title_2026-11-06_14-30-00-000.m4a").apply {
            writeBytes(ByteArray(96) { 0x33 })
        }
        val oldSrt = File(dir, "Old_Title_2026-11-06_14-30-00-000.srt").apply { writeText("old") }
        val expectedBytes = oldAudio.readBytes()
        var committedNew: String? = null
        lateinit var job: Job
        job = launch {
            val outcome = MeetingStorageManager.syncSessionArtifacts(
                sessionId = "s4",
                title = "New Title",
                startedAtMs = started,
                sourceAudio = oldAudio,
                targetDirectory = dir,
                storedAudioPath = oldAudio.name,
                commitPath = { _, newPath ->
                    committedNew = newPath
                    job.cancel() // cancellation lands right after the commit
                    true
                }
            )
            assertNull(outcome.exportError)
        }
        job.join()

        val newAudio = File(committedNew!!)
        assertTrue("Committed files must survive post-commit cancellation", newAudio.exists())
        assertArrayEquals(expectedBytes, newAudio.readBytes())
        assertFalse("Source must be cleaned inside the non-cancellable commit", oldAudio.exists())
        assertFalse(oldSrt.exists())
        assertTrue(File(dir, newAudio.nameWithoutExtension + ".srt").exists())
    }

    @Test
    fun `partial copy failure removes the reserved destination and reports an export error`() = runTest {
        val dir = tempFolder.newFolder("partial")
        val started = 1793975400000L
        // A directory masquerading as the source audio: reading it fails mid-copy.
        val sourceDir = File(dir, "Broken_2026-11-06_14-30-00-000.m4a").apply { mkdirs() }
        File(sourceDir, "child.txt").writeText("x")
        val desiredStem = MeetingStorageManager.recordingBaseName("Renamed", started)

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s5",
            title = "Renamed",
            startedAtMs = started,
            sourceAudio = sourceDir,
            targetDirectory = dir,
            storedAudioPath = sourceDir.name,
            commitPath = { _, _ -> true }
        )

        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertEquals(sourceDir.absolutePath, outcome.audioPath)
        assertTrue("Source directory must remain", sourceDir.exists())
        assertFalse("Partial copy must be removed", File(dir, "$desiredStem.m4a").exists())
        assertFalse("No stray SRT", File(dir, "$desiredStem.srt").exists())
    }

    @Test
    fun `syncSessionArtifacts preserves source and existing destination when DB commit rejects update`() = runTest {
        val dir = tempFolder.newFolder("pub2")
        val started = 1793975400000L
        val oldAudio = File(dir, "Old_2026-11-06_14-30-00-000.wav").apply {
            writeBytes(ByteArray(200) { 0x42 })
        }
        // Pre-existing unrelated recording with the target stem must stay untouched.
        val desiredStem = MeetingStorageManager.recordingBaseName("Renamed", started)
        val unrelated = File(dir, "$desiredStem.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }

        val outcome = MeetingStorageManager.syncSessionArtifacts(
            sessionId = "s6",
            title = "Renamed",
            startedAtMs = started,
            sourceAudio = oldAudio,
            targetDirectory = dir,
            storedAudioPath = oldAudio.name,
            commitPath = { _, _ -> false } // DB row deleted/stale -> reject
        )

        assertNull(outcome.exportError)
        assertEquals(oldAudio.absolutePath, outcome.audioPath)
        assertTrue("Original audio must remain when DB update fails", oldAudio.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), unrelated.readBytes())
        // Temporary _2 candidate copies must have been cleaned up.
        assertFalse(File(dir, "${desiredStem}_2.wav").exists())
        assertFalse(File(dir, "${desiredStem}_2.srt").exists())
    }

    /** In-memory SAF provider: URI strings, display names, byte contents. */
    private open class FakeTreeGateway : MeetingStorageManager.RecordingTreeGateway {
        val contents = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var failDocumentWrite: String? = null
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
                check(failDocumentWrite == null) { "Provider write failed" }
                contents[documentUri] = toByteArray()
            }
        }

        override fun documentLength(documentUri: String): Long =
            contents[documentUri]?.size?.toLong() ?: -1L

        override fun deleteDocument(documentUri: String): Boolean {
            deleted.add(documentUri)
            return contents.remove(documentUri) != null && names.remove(documentUri) != null
        }
    }

    // ── Stage 1: exportAudioToTree ───────────────────────────────────────

    @Test
    fun `audio stage creates only the audio document with a shared free stem`() = runTest {
        val dir = tempFolder.newFolder("tree1")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Tree", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(300) { (it % 251).toByte() }) }
        val gateway = FakeTreeGateway()
        // Unrelated files already own the desired stem's .m4a and .srt names.
        gateway.createDocument("audio/mp4", "$stem.m4a")
        gateway.createDocument("application/x-subrip", "$stem.srt")

        var committedAudio: String? = null
        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s7",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = null,
            ownedSrtUri = null,
            commitUri = { uri ->
                committedAudio = uri
                true
            }
        )
        assertNull(outcome.exportError)
        assertNull(outcome.cleanupError)
        assertEquals("${stem}_2.m4a", gateway.names[committedAudio])
        assertArrayEquals(audio.readBytes(), gateway.contents[committedAudio])
        // Only the audio document was created — Stage 1 never writes an SRT.
        assertEquals(setOf("$stem.m4a", "$stem.srt", "${stem}_2.m4a"), gateway.childDocumentNames())
        // The unrelated files were never touched or deleted.
        assertTrue(gateway.contents.keys.none { gateway.names[it] == "${stem}_2.srt" })
    }

    @Test
    fun `audio stage reuses the owned audio idempotently without an owned srt`() = runTest {
        val dir = tempFolder.newFolder("tree2")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Tree", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(120) { 0x55 }) }
        val gateway = FakeTreeGateway()
        val ownedAudio = gateway.createDocument("audio/mp4", "$stem.m4a")
        gateway.openOutput(ownedAudio).use {
            it.write(audio.readBytes()); it.flush()
        }
        val bytesBefore = gateway.contents[ownedAudio]!!.copyOf()
        val before = gateway.names.toMap()

        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s8",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = ownedAudio,
            ownedSrtUri = null,
            commitUri = { uri -> uri == ownedAudio }
        )
        assertNull(outcome.exportError)
        assertEquals(before, gateway.names)
        // Matching length: no rewrite on every sync.
        assertArrayEquals(bytesBefore, gateway.contents[ownedAudio])
        assertTrue(gateway.deleted.isEmpty())
        assertEquals(ownedAudio, outcome.uri)
    }

    @Test
    fun `audio rename deletes superseded audio but never the old srt`() = runTest {
        val dir = tempFolder.newFolder("tree3")
        val started = 1793975400000L
        val oldStem = MeetingStorageManager.recordingBaseName("Old", started)
        val newStem = MeetingStorageManager.recordingBaseName("New Title", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(90) { 0x66 }) }
        val gateway = FakeTreeGateway()
        val oldAudio = gateway.createDocument("audio/mp4", "$oldStem.m4a")
        val oldSrt = gateway.createDocument("application/x-subrip", "$oldStem.srt")

        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s9",
            desiredStem = newStem,
            sourceAudio = audio,
            ownedAudioUri = oldAudio,
            ownedSrtUri = oldSrt,
            commitUri = { true }
        )
        assertNull(outcome.exportError)
        assertEquals("$newStem.m4a", gateway.names[outcome.uri])
        assertTrue("Old audio deleted only after commit", oldAudio in gateway.deleted)
        assertFalse("Stage 1 must never delete the old SRT", oldSrt in gateway.deleted)
        assertTrue(oldSrt in gateway.names)
    }

    @Test
    fun `rejected audio commit removes only attempt-owned documents`() = runTest {
        val dir = tempFolder.newFolder("tree4")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("T", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(50) { 0x77 }) }
        val gateway = FakeTreeGateway()
        val oldAudio = gateway.createDocument("audio/mp4", "$stem.m4a")

        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s10",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = oldAudio,
            ownedSrtUri = null,
            commitUri = { false } // row deleted concurrently
        )
        assertNull(outcome.exportError)
        assertNull(outcome.uri)
        // The freshly created _2 audio was removed; the old owned doc remains.
        assertTrue(oldAudio !in gateway.deleted)
        assertTrue(oldAudio in gateway.names)
        assertEquals(setOf("$stem.m4a"), gateway.childDocumentNames())
    }

    @Test
    fun `audio write failure removes attempt-owned documents and reports an error`() = runTest {
        val dir = tempFolder.newFolder("tree5")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("T", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(80) { 0x88.toByte() }) }
        val gateway = FakeTreeGateway()
        gateway.failDocumentWrite = "provider down"

        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s11",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = null,
            ownedSrtUri = null,
            commitUri = { true }
        )
        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertEquals("Attempt-owned documents must be removed", emptySet<String>(), gateway.childDocumentNames().toSet())
        assertNull(outcome.uri)
    }

    @Test
    fun `cancellation right after the audio URI commit keeps the committed document`() = runTest {
        val dir = tempFolder.newFolder("tree6")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Cancel", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(70) { 0x99.toByte() }) }
        val gateway = FakeTreeGateway()
        lateinit var job: Job
        var committedUri: String? = null
        job = launch {
            val outcome = MeetingStorageManager.exportAudioToTree(
                gateway = gateway,
                sessionId = "s12",
                desiredStem = stem,
                sourceAudio = audio,
                ownedAudioUri = null,
                ownedSrtUri = null,
                commitUri = { uri ->
                    committedUri = uri
                    job.cancel()
                    true
                }
            )
            assertNull(outcome.exportError)
        }
        job.join()
        val uri = committedUri!!
        assertTrue("Committed audio must survive post-commit cancellation", uri in gateway.names)
        assertArrayEquals(audio.readBytes(), gateway.contents[uri])
    }

    // ── Stage 2: exportSrtToTree ─────────────────────────────────────────

    private fun gatewayWithAudioPair(stem: String): Triple<FakeTreeGateway, String, String?> {
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        return Triple(gateway, audioUri, null)
    }

    @Test
    fun `srt stage publishes the same-stem subtitle without altering the audio`() = runTest {
        val stem = "Meeting_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        val audioBytes = gateway.contents[audioUri]!!.copyOf()
        val srt = "1\n00:00:00,000 --> 00:00:01,000\nAlice: hi\n\n"

        var committed: String? = null
        val outcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s13",
            audioUri = audioUri,
            srtContent = srt,
            ownedSrtUri = null,
            commitUri = { uri ->
                committed = uri
                true
            }
        )
        assertNull(outcome.exportError)
        assertEquals("$stem.srt", gateway.names[committed])
        assertEquals(srt, String(gateway.contents[committed]!!, Charsets.UTF_8))
        assertArrayEquals("Audio bytes must be untouched by Stage 2", audioBytes, gateway.contents[audioUri])
    }

    @Test
    fun `shorter notes replace the previous exported SRT without leaving a suffix`() = runTest {
        val stem = "meeting"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        val srtUri = gateway.createDocument("application/x-subrip", "$stem.srt")
        gateway.openOutput(srtUri).use { it.write("longer original notes".toByteArray()); it.flush() }
        val audioBytes = gateway.contents[audioUri]!!.copyOf()

        val result = MeetingStorageManager.exportSrtToTree(
            gateway, "session", audioUri, "short", srtUri,
            commitUri = { uri -> uri == srtUri },
        )

        assertNull(result.exportError)
        assertEquals("short", String(gateway.contents[srtUri]!!, Charsets.UTF_8))
        assertArrayEquals(audioBytes, gateway.contents[audioUri])
        assertEquals(setOf("$stem.m4a", "$stem.srt"), gateway.childDocumentNames())
    }

    @Test
    fun `srt stage refuses an unrelated same-name file and leaves the audio unchanged`() = runTest {
        val stem = "Meeting_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        // Between stages someone dropped an unrelated file on the sidecar name.
        val unrelated = gateway.createDocument("application/x-subrip", "$stem.srt")
        gateway.openOutput(unrelated).use { it.write("not ours".toByteArray()); it.flush() }
        val audioBytes = gateway.contents[audioUri]!!.copyOf()

        val outcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s14",
            audioUri = audioUri,
            srtContent = "srt",
            ownedSrtUri = null,
            commitUri = { true }
        )
        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertEquals("not ours", String(gateway.contents[unrelated]!!, Charsets.UTF_8))
        assertArrayEquals(audioBytes, gateway.contents[audioUri])
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `srt commit deletes the superseded owned subtitle only after the replacement commits`() = runTest {
        val stem = "Renamed_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        val oldStem = "Old_Title_2026-11-06_14-30-00-000"
        val oldSrt = gateway.createDocument("application/x-subrip", "$oldStem.srt")

        val outcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s15",
            audioUri = audioUri,
            srtContent = "new subtitle",
            ownedSrtUri = oldSrt,
            commitUri = { true }
        )
        assertNull(outcome.exportError)
        assertEquals("$stem.srt", gateway.names[outcome.uri])
        assertTrue("Old SRT deleted after its replacement commits", oldSrt in gateway.deleted)
        assertEquals(setOf("$stem.m4a", "$stem.srt"), gateway.childDocumentNames())
    }

    @Test
    fun `rejected srt commit removes only the created subtitle`() = runTest {
        val stem = "Meeting_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)

        val outcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s16",
            audioUri = audioUri,
            srtContent = "srt",
            ownedSrtUri = null,
            commitUri = { false } // stale path/audio-URI guard
        )
        assertNull(outcome.exportError)
        assertNull(outcome.uri)
        assertEquals("Only the audio document remains", setOf("$stem.m4a"), gateway.childDocumentNames())
    }

    @Test
    fun `failing subtitle write never deletes the already-committed audio`() = runTest {
        val stem = "Meeting_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        gateway.failDocumentWrite = "provider down"
        val audioBytes = gateway.contents[audioUri]!!.copyOf()

        val outcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s17",
            audioUri = audioUri,
            srtContent = "srt",
            ownedSrtUri = null,
            commitUri = { true }
        )
        assertTrue(outcome.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))
        assertArrayEquals("Committed M4A must survive a Stage 2 failure", audioBytes, gateway.contents[audioUri])
        assertTrue(audioUri in gateway.names)
    }

    @Test
    fun `title change preserves the previous subtitle until its replacement commits`() = runTest {
        val started = 1793975400000L
        val oldStem = MeetingStorageManager.recordingBaseName("Old", started)
        val newStem = MeetingStorageManager.recordingBaseName("New Title", started)
        val audio = File(tempFolder.newFolder("seq"), "tmp.m4a").apply { writeBytes(ByteArray(64) { 0x0A }) }
        val gateway = FakeTreeGateway()
        val oldAudio = gateway.createDocument("audio/mp4", "$oldStem.m4a")
        val oldSrt = gateway.createDocument("application/x-subrip", "$oldStem.srt")

        // Full retry after a title change: audio stage relocates the audio.
        val audioOutcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s18",
            desiredStem = newStem,
            sourceAudio = audio,
            ownedAudioUri = oldAudio,
            ownedSrtUri = oldSrt,
            commitUri = { true }
        )
        assertNull(audioOutcome.exportError)
        // The old subtitle is still present and owned between the stages.
        assertTrue(oldSrt in gateway.names)

        val srtOutcome = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "s18",
            audioUri = audioOutcome.uri!!,
            srtContent = "relocated subtitle",
            ownedSrtUri = oldSrt,
            commitUri = { true }
        )
        assertNull(srtOutcome.exportError)
        assertEquals("$newStem.srt", gateway.names[srtOutcome.uri])
        assertTrue(oldSrt in gateway.deleted)
        assertEquals(setOf("$newStem.m4a", "$newStem.srt"), gateway.childDocumentNames())
    }

    @Test
    fun `stage failure then successful retry preserves the audio document`() = runTest {
        val stem = "Retry_2026-11-06_14-30-00-000"
        val (gateway, audioUri, _) = gatewayWithAudioPair(stem)
        val audioBytes = gateway.contents[audioUri]!!.copyOf()

        gateway.failDocumentWrite = "transient"
        val failed = MeetingStorageManager.exportSrtToTree(
            gateway, "s19", audioUri, "attempt 1", null, commitUri = { true }
        )
        assertTrue(failed.exportError!!.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX))

        gateway.failDocumentWrite = null
        val retried = MeetingStorageManager.exportSrtToTree(
            gateway, "s19", audioUri, "attempt 2", null, commitUri = { true }
        )
        assertNull(retried.exportError)
        assertArrayEquals(audioBytes, gateway.contents[audioUri])
        assertEquals("attempt 2", String(gateway.contents[retried.uri]!!, Charsets.UTF_8))
    }
}
