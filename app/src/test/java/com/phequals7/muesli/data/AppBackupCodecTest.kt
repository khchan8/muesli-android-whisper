package com.phequals7.muesli.data

import com.phequals7.muesli.data.entity.CustomWord
import com.phequals7.muesli.data.entity.SpeakerProfileEntity
import com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppBackupCodecTest {
    private val blob1 = ByteArray(768) { (it * 7).toByte() }
    private val blob2 = ByteArray(768) { (255 - it).toByte() }

    private fun snapshot(prefs: Map<String, Any>) = BackupSnapshot(
        prefs,
        listOf(
            CustomWord("w1", "kubernetes", "Kubernetes", 0.9, 11L, false),
            CustomWord("w2", "muesli", null, 0.85, 22L, true),
        ),
        listOf(
            ProfileWithSamples(
                SpeakerProfileEntity("p1", "Alice", "#FF0000", 2, blob1, 1L, 2L),
                listOf(
                    SpeakerProfileSampleEntity("s1", "p1", "sess", 1500L, blob1, 3L),
                    SpeakerProfileSampleEntity("s2", "p1", null, 900L, blob2, 4L),
                ),
            )
        ),
    )

    @Test
    fun roundTripsEveryPrefTypeWordsAndProfiles() {
        val prefs = mapOf<String, Any>("b" to true, "s" to "hi", "i" to 7, "l" to (1L shl 40), "f" to 0.1f)
        val d = AppBackupCodec.decode(AppBackupCodec.encode(snapshot(prefs), 99L))
        assertEquals(99L, d.exportedAt)
        assertEquals(prefs, d.snapshot.prefs)
        assertEquals(snapshot(prefs).words, d.snapshot.words)
        assertNull(d.snapshot.words[1].replacement)
        val p = d.snapshot.profiles.single()
        assertEquals("Alice", p.profile.displayName)
        assertArrayEquals(blob1, p.profile.centroidEmbeddingBlob)
        assertEquals(listOf("s1", "s2"), p.samples.map { it.sampleId })
        assertArrayEquals(blob2, p.samples[1].embeddingBlob)
        assertNull(p.samples[1].sourceSessionId)
        assertEquals("p1", p.samples[0].profileId)
    }

    private val excluded = listOf(
        "openrouter_api_key", "custom_summary_api_key", "recordings_tree_uri",
        "onboarding_completed", "selected_model_id", "backup_last_hash",
    )

    @Test
    fun secretsAndDeviceLocalKeysAreDroppedOnEncode() {
        val prefs = excluded.associateWith { "x" as Any } + ("accent_theme" to "green")
        val d = AppBackupCodec.decode(AppBackupCodec.encode(snapshot(prefs), 1L))
        assertEquals(mapOf<String, Any>("accent_theme" to "green"), d.snapshot.prefs)
        assertFalse(AppBackupCodec.encode(snapshot(prefs), 1L).contains("openrouter_api_key"))
    }

    @Test
    fun excludedKeysInjectedIntoAFileAreIgnoredOnDecode() {
        val json = """{"version":1,"exportedAt":1,"customWords":[],"speakerProfiles":[],"prefs":{
            "openrouter_api_key":{"t":"s","v":"sk-secret"},"accent_theme":{"t":"s","v":"blue"}}}"""
        assertEquals(setOf("accent_theme"), AppBackupCodec.decode(json).snapshot.prefs.keys)
    }

    @Test
    fun decodeRejectsNonBackups() {
        val bad = listOf(
            "garbage",
            """{"version":2,"exportedAt":1,"prefs":{},"customWords":[],"speakerProfiles":[]}""",
            """{"version":1,"exportedAt":1,"prefs":{"a":{"t":"z","v":1}},"customWords":[],"speakerProfiles":[]}""",
            """{"version":1,"exportedAt":1,"prefs":{},"customWords":[],"speakerProfiles":[{"profileId":"p","displayName":"n",
                "badgeColorHex":"#0","sampleCount":0,"centroidHex":"abc","createdAtMs":1,"updatedAtMs":1,"samples":[]}]}""",
        )
        for (json in bad) {
            try {
                AppBackupCodec.decode(json)
                fail("accepted: $json")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun autoWriteDecisions() {
        assertTrue(AppBackupCodec.shouldAutoWrite(foreignNewer = false, newHash = "a", lastHash = "b"))
        assertFalse(AppBackupCodec.shouldAutoWrite(foreignNewer = false, newHash = "a", lastHash = "a"))
        assertFalse(AppBackupCodec.shouldAutoWrite(foreignNewer = true, newHash = "a", lastHash = "b"))
    }
}
