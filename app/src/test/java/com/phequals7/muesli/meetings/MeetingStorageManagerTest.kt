package com.phequals7.muesli.meetings

import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.data.dao.Mp4SegmentProjection
import com.phequals7.muesli.data.dao.toMp4Projection
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import com.phequals7.muesli.data.entity.RecordingSession
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

        // An existing .mp4 at _6 also blocks the stem.
        File(dir, "${base}_6.mp4").writeBytes(ByteArray(64))
        val fourth = MeetingStorageManager.createSessionAudioFile(dir, "Sync", started)
        assertEquals("${base}_7.m4a", fourth.name)
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
    private open class FakeTreeGateway(
        private val treePrefix: String = "tree://",
    ) : MeetingStorageManager.RecordingTreeGateway {
        val contents = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var failDocumentWrite: String? = null
        /** Truncates output writes to at most this many bytes to simulate a short copy. */
        var truncateWriteToBytes: Int? = null
        /** Simulates a revoked folder permission on gateway operations. */
        var throwPermissionDenied = false
        /** Display names whose rename the provider refuses by throwing. */
        var refuseRenameFor: MutableSet<String> = mutableSetOf()
        /** Display names whose rename the provider silently ignores (keeps old name). */
        var ignoreRenameFor: MutableSet<String> = mutableSetOf()
        /** Providers that fail restoring a renamed-aside backup document. */
        var refuseBackupRestore = false
        /** Providers that fail renaming the pending document to the final name. */
        var refusePendingPromotion = false
        /** Providers that hand out a new URI on rename (like external storage). */
        var renameReturnsNewUri = false
        /** Documents whose COLUMN_SIZE is unknown to the provider. */
        val unknownSizes = mutableSetOf<String>()
        private var next = 0

        private fun checkAccess() {
            if (throwPermissionDenied) throw SecurityException("Permission denied for $treePrefix")
        }

        override fun isInTree(documentUri: String): Boolean {
            checkAccess()
            return documentUri.startsWith(treePrefix)
        }

        override fun childDocumentNames(): Set<String> {
            checkAccess()
            return names.values.toSet()
        }

        override fun createDocument(mime: String, displayName: String): String {
            checkAccess()
            check(names.values.none { it == displayName }) { "Provider overwrote $displayName" }
            val uri = "$treePrefix${next++}"
            names[uri] = displayName
            contents[uri] = ByteArray(0)
            return uri
        }

        override fun queryDisplayName(documentUri: String): String? {
            checkAccess()
            return names[documentUri]
        }

        override fun openOutput(documentUri: String): OutputStream {
            checkAccess()
            return object : ByteArrayOutputStream() {
                override fun flush() {
                    checkAccess()
                    check(failDocumentWrite == null) { "Provider write failed" }
                    val bytes = toByteArray()
                    val limit = truncateWriteToBytes
                    contents[documentUri] = if (limit != null && bytes.size > limit) {
                        bytes.copyOf(limit)
                    } else {
                        bytes
                    }
                }
            }
        }

        override fun documentLength(documentUri: String): Long {
            checkAccess()
            return contents[documentUri]?.size?.toLong() ?: -1L
        }

        override fun deleteDocument(documentUri: String): Boolean {
            checkAccess()
            deleted.add(documentUri)
            return contents.remove(documentUri) != null && names.remove(documentUri) != null
        }

        override fun documentSize(documentUri: String): Long? {
            checkAccess()
            return if (documentUri in unknownSizes) null
            else contents[documentUri]?.size?.toLong()
        }

        override fun renameDocument(documentUri: String, newDisplayName: String): String {
            checkAccess()
            val current = names[documentUri]
                ?: throw java.io.IOException("No such document $documentUri")
            check(current !in refuseRenameFor) { "Provider refused to rename $current" }
            check(!(refuseBackupRestore && current.startsWith("muesli_mp4_backup_"))) {
                "Provider refused to restore backup $current"
            }
            check(!(refusePendingPromotion && current.startsWith("muesli_mp4_pending_"))) {
                "Provider refused to promote pending $current"
            }
            if (current in ignoreRenameFor) {
                if (!renameReturnsNewUri) return documentUri
                val uri = "$treePrefix${next++}"
                names[uri] = current
                contents[uri] = contents.remove(documentUri) ?: ByteArray(0)
                names.remove(documentUri)
                return uri
            }
            check(names.values.none { it == newDisplayName }) { "Provider overwrote $newDisplayName" }
            if (!renameReturnsNewUri) {
                names[documentUri] = newDisplayName
                return documentUri
            }
            val uri = "$treePrefix${next++}"
            names[uri] = newDisplayName
            contents[uri] = contents.remove(documentUri) ?: ByteArray(0)
            names.remove(documentUri)
            return uri
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

    // ── MP4: shared stem reservation in Stage 1 ──────────────────────────

    @Test
    fun `audio stage avoids a stem whose mp4 name is taken by an unrelated file`() = runTest {
        val dir = tempFolder.newFolder("mp4stem")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Trip", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(40) { 1 }) }
        val gateway = FakeTreeGateway()
        gateway.createDocument("video/mp4", "$stem.mp4") // unrelated video

        var committed: String? = null
        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s20",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = null,
            ownedSrtUri = null,
            commitUri = { uri -> committed = uri; true }
        )
        assertNull(outcome.exportError)
        assertEquals("${stem}_2.m4a", gateway.names[committed])
        assertEquals(2, gateway.childDocumentNames().size)
    }

    @Test
    fun `audio reuse is refused when an unowned mp4 squats the sidecar name`() = runTest {
        val dir = tempFolder.newFolder("mp4block")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Block", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(48) { 2 }) }
        val gateway = FakeTreeGateway()
        val ownedAudio = gateway.createDocument("audio/mp4", "$stem.m4a")
        gateway.openOutput(ownedAudio).use { it.write(audio.readBytes()); it.flush() }
        // Unrelated MP4 took the sidecar name of the owned audio stem.
        gateway.createDocument("video/mp4", "$stem.mp4")

        var committed: String? = null
        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s21",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = ownedAudio,
            ownedSrtUri = null,
            ownedMp4Uri = null,
            commitUri = { uri -> committed = uri; true }
        )
        assertNull(outcome.exportError)
        assertEquals("A fresh common stem must be allocated", "${stem}_2.m4a", gateway.names[committed])
        assertTrue("Superseded audio is cleaned after the new commit", ownedAudio in gateway.deleted)
        assertEquals(setOf("$stem.mp4", "${stem}_2.m4a"), gateway.childDocumentNames())
    }

    @Test
    fun `audio reuse stands when the sidecar mp4 is owned with the exact name`() = runTest {
        val dir = tempFolder.newFolder("mp4owned")
        val started = 1793975400000L
        val stem = MeetingStorageManager.recordingBaseName("Own", started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(48) { 3 }) }
        val gateway = FakeTreeGateway()
        val ownedAudio = gateway.createDocument("audio/mp4", "$stem.m4a")
        gateway.openOutput(ownedAudio).use { it.write(audio.readBytes()); it.flush() }
        val ownedMp4 = gateway.createDocument("video/mp4", "$stem.mp4")

        val outcome = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "s22",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = ownedAudio,
            ownedSrtUri = null,
            ownedMp4Uri = ownedMp4,
            commitUri = { true }
        )
        assertNull(outcome.exportError)
        assertEquals(ownedAudio, outcome.uri)
        assertTrue(gateway.deleted.isEmpty())
    }

    // ── MP4: exportMp4ToTree ─────────────────────────────────────────────

    private fun generatedMp4(dir: File, bytes: Int) =
        File(dir, "gen.mp4").apply { writeBytes(ByteArray(bytes) { (it % 199).toByte() }) }

    @Test
    fun `first mp4 export creates the exact final name and commits`() = runTest {
        val dir = tempFolder.newFolder("mp4first")
        val stem = "First_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val gen = generatedMp4(dir, 512)

        var committed: String? = null
        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s23", audioUri, ownedMp4Uri = null, generatedMp4 = gen,
            commitUri = { uri -> committed = uri; true },
            restoreOwnedUri = { error("restore must not run on success") }
        )
        assertNull(outcome.error)
        assertFalse(outcome.stale)
        assertEquals("$stem.mp4", gateway.names[committed])
        assertArrayEquals(gen.readBytes(), gateway.contents[committed])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `first mp4 export fails safely on an unowned collision`() = runTest {
        val dir = tempFolder.newFolder("mp4coll")
        val stem = "Coll_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val squatter = gateway.createDocument("video/mp4", "$stem.mp4")
        val before = gateway.contents[squatter]!!.copyOf()

        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s24", audioUri, ownedMp4Uri = null, generatedMp4 = generatedMp4(dir, 64),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(outcome.publishedUri)
        assertTrue(outcome.error!!.contains("Retry the full export"))
        assertArrayEquals("The squatter must stay untouched", before, gateway.contents[squatter])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `mp4 replacement promotes a pending document and deletes the backup`() = runTest {
        val dir = tempFolder.newFolder("mp4repl")
        val stem = "Repl_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        gateway.openOutput(owned).use { it.write(ByteArray(32) { 7 }); it.flush() }
        val gen = generatedMp4(dir, 256)

        var committed: String? = null
        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s25", audioUri, ownedMp4Uri = owned, generatedMp4 = gen,
            commitUri = { uri -> committed = uri; true },
            restoreOwnedUri = { error("restore must not run on success") }
        )
        assertNull(outcome.error)
        assertFalse(outcome.stale)
        assertEquals("$stem.mp4", gateway.names[outcome.publishedUri])
        assertArrayEquals(gen.readBytes(), gateway.contents[outcome.publishedUri])
        assertTrue("Backup must be deleted after the commit", owned in gateway.deleted)
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `rejected commit rolls the replacement back to the old mp4`() = runTest {
        val dir = tempFolder.newFolder("mp4stale")
        val stem = "Stale_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(24) { 9 }
        gateway.openOutput(owned).use { it.write(oldBytes); it.flush() }

        var restored: String? = null
        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s26", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 128),
            commitUri = { false },
            restoreOwnedUri = { uri -> restored = uri }
        )
        assertTrue(outcome.stale)
        assertNull(outcome.error)
        assertNull(outcome.publishedUri)
        assertEquals("Old export keeps the final name", "$stem.mp4", gateway.names[restored ?: owned])
        assertArrayEquals("Old bytes survive untouched", oldBytes, gateway.contents[restored ?: owned])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `rename refusal fails the replacement without touching the old mp4`() = runTest {
        val dir = tempFolder.newFolder("mp4refuse")
        val stem = "Refuse_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(16) { 11 }
        gateway.openOutput(owned).use { it.write(oldBytes); it.flush() }
        gateway.refuseRenameFor.add("$stem.mp4")

        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s27", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 64),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(outcome.publishedUri)
        assertFalse(outcome.stale)
        assertTrue(outcome.error!!.contains("refused"))
        assertEquals("$stem.mp4", gateway.names[owned])
        assertArrayEquals(oldBytes, gateway.contents[owned])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `failed rollback removes pending candidate retains backup and restores recovery pointer`() = runTest {
        val dir = tempFolder.newFolder("mp4rollback")
        val stem = "Roll_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway().apply { renameReturnsNewUri = true }
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(16) { 13 }
        gateway.openOutput(owned).use { it.write(oldBytes); it.flush() }
        // Moving the owned MP4 aside works (assigning a new backup URI); restoring is refused.
        gateway.refuseBackupRestore = true

        var recoveryPointer: String? = null
        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s28", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 64),
            commitUri = { false },
            restoreOwnedUri = { uri -> recoveryPointer = uri }
        )
        assertNull(outcome.publishedUri)
        assertFalse(outcome.stale)
        assertNotNull("The surviving backup URI must be reported", outcome.recoveryUri)
        assertEquals("Room pointer must be updated to the surviving backup URI", outcome.recoveryUri, recoveryPointer)
        assertTrue(outcome.error!!.contains("kept"))
        assertFalse("Error message must never format a literal null reason", outcome.error!!.contains("(null)"))
        assertArrayEquals("Old export bytes survive in the backup", oldBytes, gateway.contents[outcome.recoveryUri])
        val backupName = gateway.names[outcome.recoveryUri]!!
        assertTrue("The backup keeps its aside name for recovery", backupName.startsWith("muesli_mp4_backup_"))
        assertEquals(
            "Failed new/pending MP4 must be removed so only audio and backup remain",
            setOf("$stem.m4a", backupName),
            gateway.childDocumentNames()
        )

        // Subsequent retry using the stored recovery pointer publishes the final name and cleans up the backup.
        gateway.refuseBackupRestore = false
        val retryGen = generatedMp4(dir, 96)
        var retryCommitted: String? = null
        val retried = MeetingStorageManager.exportMp4ToTree(
            gateway, "s28", audioUri, ownedMp4Uri = recoveryPointer, generatedMp4 = retryGen,
            commitUri = { uri -> retryCommitted = uri; true },
            restoreOwnedUri = { error("restore must not run on successful retry") }
        )
        assertNull(retried.error)
        assertEquals("$stem.mp4", gateway.names[retryCommitted])
        assertArrayEquals(retryGen.readBytes(), gateway.contents[retryCommitted])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `mp4 write verification failure removes only the pending document`() = runTest {
        val dir = tempFolder.newFolder("mp4short")
        val stem = "Short_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        gateway.openOutput(owned).use { it.write(ByteArray(8) { 15 }); it.flush() }
        gateway.failDocumentWrite = "provider write failed"

        val outcome = MeetingStorageManager.exportMp4ToTree(
            gateway, "s29", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 96),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(outcome.publishedUri)
        assertTrue(outcome.error != null)
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
        assertEquals("Old bytes survive", 8, gateway.contents[owned]!!.size)
    }

    @Test
    fun `unicode stem with m4a srt and mp4 collisions allocates one common triplet stem`() = runTest {
        val dir = tempFolder.newFolder("unicode_triplet")
        val started = 1793975400000L
        val title = "ประชุม แผนงาน 會議"
        val stem = MeetingStorageManager.recordingBaseName(title, started)
        val audio = File(dir, "tmp.m4a").apply { writeBytes(ByteArray(60) { 0x12 }) }
        val gateway = FakeTreeGateway()
        // _1 is blocked by .m4a, _2 by .srt, _3 by .mp4.
        val u1 = gateway.createDocument("audio/mp4", "$stem.m4a")
        val u2 = gateway.createDocument("application/x-subrip", "${stem}_2.srt")
        val u3 = gateway.createDocument("video/mp4", "${stem}_3.mp4")

        var audioUri: String? = null
        val stage1 = MeetingStorageManager.exportAudioToTree(
            gateway = gateway,
            sessionId = "u1",
            desiredStem = stem,
            sourceAudio = audio,
            ownedAudioUri = null,
            ownedSrtUri = null,
            ownedMp4Uri = null,
            commitUri = { uri -> audioUri = uri; true }
        )
        assertNull(stage1.exportError)
        assertEquals("${stem}_4.m4a", gateway.names[audioUri])

        var srtUri: String? = null
        val stage2 = MeetingStorageManager.exportSrtToTree(
            gateway = gateway,
            sessionId = "u1",
            audioUri = audioUri!!,
            srtContent = "1\n00:00:00,000 --> 00:00:01,000\nสวัสดี 會議\n\n",
            ownedSrtUri = null,
            commitUri = { uri -> srtUri = uri; true }
        )
        assertNull(stage2.exportError)
        assertEquals("${stem}_4.srt", gateway.names[srtUri])

        val genMp4 = generatedMp4(dir, 128)
        var mp4Uri: String? = null
        val stage3 = MeetingStorageManager.exportMp4ToTree(
            gateway = gateway,
            sessionId = "u1",
            audioUri = audioUri!!,
            ownedMp4Uri = null,
            generatedMp4 = genMp4,
            commitUri = { uri -> mp4Uri = uri; true },
            restoreOwnedUri = { error("unexpected rollback") },
            ownedSrtUri = srtUri,
            verifySrtStem = true,
            desiredStem = stem,
        )
        assertNull(stage3.error)
        assertEquals("${stem}_4.mp4", gateway.names[mp4Uri])
        assertTrue(u1 in gateway.names && u2 in gateway.names && u3 in gateway.names)
        assertEquals(
            setOf("$stem.m4a", "${stem}_2.srt", "${stem}_3.mp4", "${stem}_4.m4a", "${stem}_4.srt", "${stem}_4.mp4"),
            gateway.childDocumentNames()
        )
    }

    @Test
    fun `mp4 replacement and rollback handle providers that return a new URI on rename`() = runTest {
        val dir = tempFolder.newFolder("mp4newuri")
        val stem = "NewUri_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway().apply { renameReturnsNewUri = true }
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val initialOwned = gateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(32) { 0x44 }
        gateway.openOutput(initialOwned).use { it.write(oldBytes); it.flush() }

        // 1. Rejected commit with renameReturnsNewUri=true must pass the new restored URI back.
        var restoredUri: String? = null
        val rejected = MeetingStorageManager.exportMp4ToTree(
            gateway, "s30", audioUri, ownedMp4Uri = initialOwned, generatedMp4 = generatedMp4(dir, 100),
            commitUri = { false },
            restoreOwnedUri = { uri -> restoredUri = uri }
        )
        assertTrue(rejected.stale)
        assertNull(rejected.error)
        assertNotNull("New URI from rename-back must be reported to restoreOwnedUri", restoredUri)
        assertTrue(restoredUri != initialOwned)
        assertEquals("$stem.mp4", gateway.names[restoredUri])
        assertArrayEquals(oldBytes, gateway.contents[restoredUri])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())

        // 2. Promotion failure after moving backup aside also restores the backup and updates the URI.
        gateway.refusePendingPromotion = true
        var restoredAfterFail: String? = null
        val failedPromo = MeetingStorageManager.exportMp4ToTree(
            gateway, "s30", audioUri, ownedMp4Uri = restoredUri, generatedMp4 = generatedMp4(dir, 100),
            commitUri = { true },
            restoreOwnedUri = { uri -> restoredAfterFail = uri }
        )
        assertNull(failedPromo.publishedUri)
        assertFalse(failedPromo.stale)
        assertTrue(failedPromo.error!!.contains("refused to promote"))
        assertNotNull(restoredAfterFail)
        assertEquals("$stem.mp4", gateway.names[restoredAfterFail])
        assertArrayEquals(oldBytes, gateway.contents[restoredAfterFail])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())

        // 3. Successful replacement with renameReturnsNewUri=true commits the new URI and deletes backup.
        gateway.refusePendingPromotion = false
        val newGen = generatedMp4(dir, 144)
        var committedUri: String? = null
        val succeeded = MeetingStorageManager.exportMp4ToTree(
            gateway, "s30", audioUri, ownedMp4Uri = restoredAfterFail, generatedMp4 = newGen,
            commitUri = { uri -> committedUri = uri; true },
            restoreOwnedUri = { error("unexpected rollback") }
        )
        assertNull(succeeded.error)
        assertEquals(committedUri, succeeded.publishedUri)
        assertEquals("$stem.mp4", gateway.names[committedUri])
        assertArrayEquals(newGen.readBytes(), gateway.contents[committedUri])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `short copy permission denial and silent rename no-op preserve prior mp4 exports`() = runTest {
        val dir = tempFolder.newFolder("mp4guards")
        val stem = "Guard_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(28) { 0x5A }
        gateway.openOutput(owned).use { it.write(oldBytes); it.flush() }

        // Short copy on replacement: pending document is removed, prior MP4 untouched.
        gateway.truncateWriteToBytes = 12
        val shortCopy = MeetingStorageManager.exportMp4ToTree(
            gateway, "s31", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 80),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(shortCopy.publishedUri)
        assertTrue(shortCopy.error!!.contains("length mismatch"))
        assertArrayEquals(oldBytes, gateway.contents[owned])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
        gateway.truncateWriteToBytes = null

        // Silent rename refusal (provider returns URI without moving existing MP4 aside).
        gateway.ignoreRenameFor.add("$stem.mp4")
        val silentNoOp = MeetingStorageManager.exportMp4ToTree(
            gateway, "s31", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 80),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(silentNoOp.publishedUri)
        assertNull("Old file was never moved aside, so no backup recovery is needed", silentNoOp.recoveryUri)
        assertTrue(silentNoOp.error!!.contains("refused to move"))
        assertEquals("$stem.mp4", gateway.names[owned])
        assertArrayEquals(oldBytes, gateway.contents[owned])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
        gateway.ignoreRenameFor.clear()

        // Permission denial fails safely without throwing out of exportMp4ToTree.
        gateway.throwPermissionDenied = true
        val denied = MeetingStorageManager.exportMp4ToTree(
            gateway, "s31", audioUri, ownedMp4Uri = owned, generatedMp4 = generatedMp4(dir, 80),
            commitUri = { true },
            restoreOwnedUri = { }
        )
        assertNull(denied.publishedUri)
        assertTrue(denied.error!!.contains("Permission denied"))
        gateway.throwPermissionDenied = false
        assertArrayEquals(oldBytes, gateway.contents[owned])
    }

    @Test
    fun `folder change keeps previous tree mp4 untouched and never overwrites same-name file in new tree`() = runTest {
        val dir = tempFolder.newFolder("mp4folder")
        val stem = "Folder_2026-11-06_14-30-00-000"
        val oldGateway = FakeTreeGateway("treeOld://")
        val oldMp4 = oldGateway.createDocument("video/mp4", "$stem.mp4")
        val oldBytes = ByteArray(20) { 0x31 }
        oldGateway.openOutput(oldMp4).use { it.write(oldBytes); it.flush() }

        val newGateway = FakeTreeGateway("treeNew://")
        val newAudio = newGateway.createDocument("audio/mp4", "$stem.m4a")
        val newSrt = newGateway.createDocument("application/x-subrip", "$stem.srt")
        // Unowned squatter in the new folder with the same name: oldMp4 URI from
        // the previous folder must NEVER grant authority to replace it.
        val squatter = newGateway.createDocument("video/mp4", "$stem.mp4")
        val squatterBytes = ByteArray(10) { 0x77 }
        newGateway.openOutput(squatter).use { it.write(squatterBytes); it.flush() }

        val blocked = MeetingStorageManager.exportMp4ToTree(
            gateway = newGateway,
            sessionId = "s32",
            audioUri = newAudio,
            ownedMp4Uri = oldMp4,
            generatedMp4 = generatedMp4(dir, 64),
            commitUri = { true },
            restoreOwnedUri = { },
            ownedSrtUri = newSrt,
            verifySrtStem = true,
            desiredStem = stem,
        )
        assertNull(blocked.publishedUri)
        assertTrue(blocked.error!!.contains("already exists"))
        assertArrayEquals("Squatter in new folder must stay untouched", squatterBytes, newGateway.contents[squatter])
        assertArrayEquals("Old folder MP4 must stay untouched", oldBytes, oldGateway.contents[oldMp4])

        // When the name in the new folder is free, publishing creates the new MP4
        // in the new tree and leaves the previous tree's MP4 untouched.
        newGateway.deleteDocument(squatter)
        val gen = generatedMp4(dir, 72)
        val published = MeetingStorageManager.exportMp4ToTree(
            gateway = newGateway,
            sessionId = "s32",
            audioUri = newAudio,
            ownedMp4Uri = oldMp4,
            generatedMp4 = gen,
            commitUri = { true },
            restoreOwnedUri = { },
            ownedSrtUri = newSrt,
            verifySrtStem = true,
            desiredStem = stem,
        )
        assertNull(published.error)
        assertEquals("$stem.mp4", newGateway.names[published.publishedUri])
        assertArrayEquals(gen.readBytes(), newGateway.contents[published.publishedUri])
        assertTrue("Previous tree MP4 must never be deleted on folder change", oldMp4 in oldGateway.names)
        assertArrayEquals(oldBytes, oldGateway.contents[oldMp4])
    }

    @Test
    fun `same-tree title rename deletes superseded old-name mp4 only after new commit succeeds`() = runTest {
        val dir = tempFolder.newFolder("mp4rename")
        val oldStem = "Old_Title_2026-11-06_14-30-00-000"
        val newStem = "New_Title_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val oldMp4 = gateway.createDocument("video/mp4", "$oldStem.mp4")
        val oldBytes = ByteArray(18) { 0x22 }
        gateway.openOutput(oldMp4).use { it.write(oldBytes); it.flush() }
        val newAudio = gateway.createDocument("audio/mp4", "$newStem.m4a")
        val newSrt = gateway.createDocument("application/x-subrip", "$newStem.srt")
        val gen = generatedMp4(dir, 88)

        // Rejected commit keeps the old-name MP4 intact and deletes only the new candidate.
        val rejected = MeetingStorageManager.exportMp4ToTree(
            gateway = gateway,
            sessionId = "s33",
            audioUri = newAudio,
            ownedMp4Uri = oldMp4,
            generatedMp4 = gen,
            commitUri = { false },
            restoreOwnedUri = { },
            ownedSrtUri = newSrt,
            verifySrtStem = true,
            desiredStem = newStem,
        )
        assertTrue(rejected.stale)
        assertTrue("Old-name MP4 survives when commit rejects", oldMp4 in gateway.names)
        assertEquals(setOf("$oldStem.mp4", "$newStem.m4a", "$newStem.srt"), gateway.childDocumentNames())

        // Accepted commit publishes the new-name MP4 and removes the superseded old-name MP4.
        val accepted = MeetingStorageManager.exportMp4ToTree(
            gateway = gateway,
            sessionId = "s33",
            audioUri = newAudio,
            ownedMp4Uri = oldMp4,
            generatedMp4 = gen,
            commitUri = { true },
            restoreOwnedUri = { },
            ownedSrtUri = newSrt,
            verifySrtStem = true,
            desiredStem = newStem,
        )
        assertNull(accepted.error)
        assertEquals("$newStem.mp4", gateway.names[accepted.publishedUri])
        assertTrue("Superseded old-name MP4 deleted after commit", oldMp4 in gateway.deleted)
        assertEquals(setOf("$newStem.m4a", "$newStem.srt", "$newStem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `cancellation right after mp4 commit keeps the committed document and cleans backup`() = runTest {
        val dir = tempFolder.newFolder("mp4cancel")
        val stem = "CancelMp4_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway().apply { renameReturnsNewUri = true }
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val owned = gateway.createDocument("video/mp4", "$stem.mp4")
        gateway.openOutput(owned).use { it.write(ByteArray(16) { 1 }); it.flush() }
        val gen = generatedMp4(dir, 112)

        lateinit var job: Job
        var committedUri: String? = null
        job = launch {
            MeetingStorageManager.exportMp4ToTree(
                gateway = gateway,
                sessionId = "s34",
                audioUri = audioUri,
                ownedMp4Uri = owned,
                generatedMp4 = gen,
                commitUri = { uri ->
                    committedUri = uri
                    job.cancel()
                    true
                },
                restoreOwnedUri = { }
            )
        }
        job.join()
        val uri = committedUri!!
        assertEquals("$stem.mp4", gateway.names[uri])
        assertArrayEquals(gen.readBytes(), gateway.contents[uri])
        assertEquals(setOf("$stem.m4a", "$stem.mp4"), gateway.childDocumentNames())
    }

    @Test
    fun `mp4 snapshot commit predicate rejects session and segment changes while ignoring notes summary and badges`() {
        val baseSession = RecordingSession(
            id = "s35",
            kind = "meeting",
            title = "Team Sync",
            phase = "completed",
            audioFileName = "Team_Sync_2026.m4a",
            exportedAudioUri = "tree://audio",
            exportedSrtUri = "tree://srt",
            exportedMp4Uri = "tree://mp4_old",
            manualNotes = "initial notes",
            summaryText = "initial summary",
            summaryState = "completed",
        )
        val segEntity = MeetingTranscriptSegmentEntity(
            segmentId = "seg1",
            sessionId = "s35",
            orderIndex = 0,
            startMs = 0L,
            endMs = 1500L,
            clusterId = 1,
            speakerProfileId = "prof1",
            speakerLabel = "Alice",
            badgeColorHex = "#1D63B8",
            text = "Hello team",
            languageCode = "en",
            clusterCentroidBlob = byteArrayOf(1, 2, 3),
        )
        val expectedProjection = listOf(segEntity.toMp4Projection())

        fun check(
            session: RecordingSession? = baseSession,
            segments: List<Mp4SegmentProjection> = expectedProjection,
        ): Boolean = SharedStore.matchesMp4Snapshot(
            session = session,
            currentSegments = segments,
            expectedAudioPath = "Team_Sync_2026.m4a",
            expectedAudioUri = "tree://audio",
            expectedSrtUri = "tree://srt",
            expectedTitle = "Team Sync",
            expectedOldMp4Uri = "tree://mp4_old",
            expectedSegments = expectedProjection,
        )

        assertTrue(check())
        // Notes, summary, embeddings, cluster IDs, profile IDs, and badge colors are ignored.
        val editedNotesAndSummary = baseSession.copy(
            manualNotes = "updated notes",
            summaryText = "## New summary",
            summaryState = "generating",
        )
        val editedBadgeAndEmbedding = segEntity.copy(
            clusterId = 99,
            speakerProfileId = "prof2",
            badgeColorHex = "#FF0000",
            clusterCentroidBlob = byteArrayOf(9, 9, 9),
        ).toMp4Projection()
        assertTrue(check(session = editedNotesAndSummary, segments = listOf(editedBadgeAndEmbedding)))

        // Deleted session or any guarded session field change is rejected.
        assertFalse(check(session = null))
        assertFalse(check(session = baseSession.copy(phase = "failed")))
        assertFalse(check(session = baseSession.copy(title = "Renamed")))
        assertFalse(check(session = baseSession.copy(audioFileName = "Other.m4a")))
        assertFalse(check(session = baseSession.copy(exportedAudioUri = "tree://audio2")))
        assertFalse(check(session = baseSession.copy(exportedSrtUri = "tree://srt2")))
        assertFalse(check(session = baseSession.copy(exportedMp4Uri = "tree://mp4_other")))

        // Any rendered segment projection change is rejected.
        assertFalse(check(segments = emptyList()))
        assertFalse(check(segments = listOf(expectedProjection[0].copy(orderIndex = 1))))
        assertFalse(check(segments = listOf(expectedProjection[0].copy(startMs = 100L))))
        assertFalse(check(segments = listOf(expectedProjection[0].copy(endMs = 2000L))))
        assertFalse(check(segments = listOf(expectedProjection[0].copy(speakerLabel = "Bob"))))
        assertFalse(check(segments = listOf(expectedProjection[0].copy(text = "Edited text"))))
    }

    @Test
    fun `canSkipAutomaticMp4Export requires matching fingerprint in-tree matching name and positive known size`() {
        val stem = "Skip_2026-11-06_14-30-00-000"
        val gateway = FakeTreeGateway()
        val audioUri = gateway.createDocument("audio/mp4", "$stem.m4a")
        val mp4Uri = gateway.createDocument("video/mp4", "$stem.mp4")
        gateway.openOutput(mp4Uri).use { it.write(ByteArray(64) { 1 }); it.flush() }

        assertTrue(
            MeetingStorageManager.canSkipAutomaticMp4Export(
                gateway, force = false, audioUri = audioUri, ownedMp4Uri = mp4Uri,
                ownedMp4Fingerprint = "fp1", currentFingerprint = "fp1"
            )
        )
        assertFalse(
            "Explicit regenerate must bypass skip",
            MeetingStorageManager.canSkipAutomaticMp4Export(
                gateway, force = true, audioUri = audioUri, ownedMp4Uri = mp4Uri,
                ownedMp4Fingerprint = "fp1", currentFingerprint = "fp1"
            )
        )
        assertFalse(
            "Changed fingerprint must rebuild",
            MeetingStorageManager.canSkipAutomaticMp4Export(
                gateway, force = false, audioUri = audioUri, ownedMp4Uri = mp4Uri,
                ownedMp4Fingerprint = "fp1", currentFingerprint = "fp2"
            )
        )
        gateway.unknownSizes.add(mp4Uri)
        assertFalse(
            "Unknown provider size must rebuild",
            MeetingStorageManager.canSkipAutomaticMp4Export(
                gateway, force = false, audioUri = audioUri, ownedMp4Uri = mp4Uri,
                ownedMp4Fingerprint = "fp1", currentFingerprint = "fp1"
            )
        )
        gateway.unknownSizes.clear()
        assertFalse(
            "Out-of-tree MP4 URI must rebuild",
            MeetingStorageManager.canSkipAutomaticMp4Export(
                gateway, force = false, audioUri = audioUri, ownedMp4Uri = "other://0",
                ownedMp4Fingerprint = "fp1", currentFingerprint = "fp1"
            )
        )
    }

    // ── MP4: request queue coalescing ────────────────────────────────────

    @Test
    fun `request queue coalesces duplicates upgrades force and keeps distinct meetings`() {
        val queue = Mp4RequestQueue()
        assertTrue(queue.add("a", force = false))
        assertFalse("Duplicate automatic request coalesces", queue.add("a", force = false))
        assertTrue("Manual force upgrades the queued request", queue.add("a", force = true))
        assertFalse(queue.add("a", force = true))
        assertTrue(queue.add("b", force = false))
        assertEquals(setOf("a", "b"), queue.snapshot())

        assertEquals("a" to true, queue.takeNext())
        assertEquals("b" to false, queue.takeNext())
        assertNull(queue.takeNext())
        assertTrue(queue.remove("a").not()) // already taken
        assertTrue(queue.add("a", force = false))
        assertTrue(queue.remove("a"))
    }
}
