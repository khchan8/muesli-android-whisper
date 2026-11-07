package com.phequals7.muesli.meetings

import com.phequals7.muesli.ui.dashboard.meetings.findActiveTranscriptSegmentIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the v2 diarization pure logic: embedding serialization,
 * alignment/grouping, and synchronized-playback binary search.
 */
class SpeakerDiarizerLogicTest {

    // ── SpeakerEmbeddingSerializer ─────────────────────────────────────

    @Test
    fun `blob roundtrip preserves float vector`() {
        val vec = FloatArray(192) { (it % 17) / 17f }
        val blob = SpeakerEmbeddingSerializer.floatArrayToBlob(vec)
        assertEquals(192 * 4, blob.size)
        val back = SpeakerEmbeddingSerializer.blobToFloatArray(blob)
        assertEquals(192, back.size)
        vec.indices.forEach { assertEquals(vec[it], back[it], 1e-7f) }
    }

    @Test
    fun `blobToFloatArray rejects malformed blobs`() {
        assertEquals(0, SpeakerEmbeddingSerializer.blobToFloatArray(ByteArray(0)).size)
        assertEquals(0, SpeakerEmbeddingSerializer.blobToFloatArray(ByteArray(7)).size)
    }

    @Test
    fun `l2Normalize yields unit length and handles zero vector`() {
        val normalized = SpeakerEmbeddingSerializer.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, SpeakerEmbeddingSerializer.cosineSimilarity(normalized, normalized), 1e-6f)
        assertEquals(0.6f, normalized[0], 1e-6f)

        val zero = SpeakerEmbeddingSerializer.l2Normalize(FloatArray(4))
        assertEquals(0f, zero.sum(), 1e-6f) // guard against div-by-zero NaN
    }

    @Test
    fun `cosineSimilarity detects identical and opposite vectors`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val c = floatArrayOf(-1f, 0f, 0f)
        assertEquals(1f, SpeakerEmbeddingSerializer.cosineSimilarity(a, a), 1e-6f)
        assertEquals(0f, SpeakerEmbeddingSerializer.cosineSimilarity(a, b), 1e-6f)
        assertEquals(-1f, SpeakerEmbeddingSerializer.cosineSimilarity(a, c), 1e-6f)
        assertEquals(0f, SpeakerEmbeddingSerializer.cosineSimilarity(a, FloatArray(0)))
    }

    // ── Chunk alignment (Stage: turn → transcript block grouping) ──────

    private fun seg(
        startMs: Long, endMs: Long, cluster: Int,
        label: String = "Speaker ${cluster + 1}"
    ) = SpeakerDiarizer.DiarizationTurn(
        startMs = startMs, endMs = endMs, clusterId = cluster,
        speakerProfileId = null, speakerLabel = label, badgeColorHex = "#1D63B8",
        clusterCentroid = FloatArray(0)
    )

    @Test
    fun `consecutive same-speaker chunks merge into one block`() {
        val turns = listOf(seg(0, 5_000, 0), seg(4_500, 9_100, 0), seg(9_200, 10_000, 1))
        val diarizer = SpeakerDiarizer(context = null)
        val chunks = listOf(
            RawTranscriptChunk(0, 4_000, "hello"),
            RawTranscriptChunk(4_200, 9_000, "world"),
            RawTranscriptChunk(9_500, 9_800, "对"),
        )
        val blocks = diarizer.alignTurnsWithTranscriptChunks("s1", turns, chunks)
        // First two chunks are speaker 0 and close in time -> merged; the short
        // backchannel lands on speaker 1 (max overlap with the 10..500ms turn).
        assertEquals(2, blocks.size)
        assertEquals("hello world", blocks[0].text)
        assertEquals(0, blocks[0].clusterId)
        assertEquals(9_000L, blocks[0].endMs)
        assertEquals(1, blocks[1].clusterId)
        assertEquals(0, blocks[0].orderIndex)
        assertEquals(1, blocks[1].orderIndex)
    }

    @Test
    fun `no turns yields single-speaker fallback blocks`() {
        val diarizer = SpeakerDiarizer(context = null)
        val chunks = listOf(
            RawTranscriptChunk(0, 2_000, "a"),
            RawTranscriptChunk(3_000, 5_000, "b"),
        )
        val blocks = diarizer.alignTurnsWithTranscriptChunks("s1", emptyList(), chunks)
        assertEquals(2, blocks.size)
        blocks.forEach { assertEquals("Speaker 1", it.speakerLabel) }
    }

    @Test
    fun `thai and cjk merges skip inter-block spaces`() {
        val turns = listOf(seg(0, 60_000, 0))
        val diarizer = SpeakerDiarizer(context = null)
        val chunks = listOf(
            RawTranscriptChunk(0, 1_000, "สวัสดี"),
            RawTranscriptChunk(1, 2_000, "ครับ"),
        )
        val blocks = diarizer.alignTurnsWithTranscriptChunks("s1", turns, chunks)
        assertEquals(1, blocks.size)
        assertEquals("สวัสดีครับ", blocks[0].text)
    }

    // ── Synchronized playback binary search (FR-04) ────────────────────

    private fun segment(startMs: Long, endMs: Long) = DiarizedTranscriptSegment(
        segmentId = "$startMs-$endMs", sessionId = "s", orderIndex = 0,
        startMs = startMs, endMs = endMs, clusterId = 0,
        speakerProfileId = null, speakerLabel = "S", badgeColorHex = "#1D63B8", text = "t"
    )

    @Test
    fun `binary search finds the block containing the playhead`() {
        val segments = listOf(segment(0, 1_000), segment(1_500, 3_000), segment(3_500, 6_000))
        assertEquals(0, findActiveTranscriptSegmentIndex(segments, 500))
        assertEquals(1, findActiveTranscriptSegmentIndex(segments, 2_000))
        assertEquals(2, findActiveTranscriptSegmentIndex(segments, 5_999))
    }

    @Test
    fun `binary search keeps recent block during sub-500ms gaps and -1 after`() {
        val segments = listOf(segment(0, 1_000), segment(2_000, 4_000))
        assertEquals(0, findActiveTranscriptSegmentIndex(segments, 1_350)) // 350ms past end
        assertEquals(-1, findActiveTranscriptSegmentIndex(segments, 1_600)) // 600ms past end
        assertEquals(-1, findActiveTranscriptSegmentIndex(emptyList(), 0))
        assertEquals(-1, findActiveTranscriptSegmentIndex(segments, 10_000))
    }

    // ── v2.1 crash-safety & enrollment contract guards ────────────────

    @Test
    fun `enrollment rejects clips shorter than 6 seconds`() = runBlocking {
        val diarizer = SpeakerDiarizer(context = null, profileStore = null)
        try {
            diarizer.enrollSpeakerFromAudioSamples(FloatArray(16000 * 5), "Alice") // 5 s
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // The duration gate fires before any model or IO work.
        }
    }

    @Test
    fun `enrollment throws when the embedding model is unavailable`() = runBlocking {
        val diarizer = SpeakerDiarizer(context = null, profileStore = null)
        try {
            diarizer.enrollSpeakerFromAudioSamples(FloatArray(16000 * 8), "Alice")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("model", ignoreCase = true))
        }
    }

    @Test
    fun `identification without enrolled profiles returns empty without loading models`() = runBlocking {
        val diarizer = SpeakerDiarizer(context = null, profileStore = null)
        assertTrue(diarizer.identifySpeakerFromAudioSamples(FloatArray(16000 * 8)).isEmpty())
    }

    @Test
    fun `diarization degrades to no turns when models are unavailable`() = runBlocking {
        // v2.1 Root Cause C: missing model files must never reach JNI; the
        // caller falls back to timestamped single-speaker segments.
        val turns = SpeakerDiarizer(context = null).diarizeSamples(FloatArray(16000 * 2))
        assertTrue(turns.isEmpty())
    }
}
