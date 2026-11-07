package com.phequals7.muesli.meetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cue-timeline construction and transcript fingerprinting for the MP4 export. */
class Mp4SubtitleMuxerCuesTest {

    private fun segment(
        startMs: Long,
        endMs: Long,
        text: String,
        label: String = "Speaker 1",
        orderIndex: Int = 0,
    ) = DiarizedTranscriptSegment(
        sessionId = "s",
        orderIndex = orderIndex,
        startMs = startMs,
        endMs = endMs,
        clusterId = 0,
        speakerProfileId = null,
        speakerLabel = label,
        badgeColorHex = "#1D63B8",
        text = text,
    )

    @Test
    fun `silent transcript yields one full-duration blank cue`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(emptyList(), audioDurationMs = 5_000)
        assertEquals(listOf(Mp4SubtitleMuxer.Mp4TimedCue(0, 5_000, "")), timeline)
    }

    @Test
    fun `cues are clamped to the audio duration and out-of-range cues drop`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(
            listOf(
                segment(-500, 1_000, "early"),      // clamped to [0, 1000]
                segment(4_800, 9_000, "late"),      // clamped to [4800, 5000]
                segment(6_000, 7_000, "gone"),      // fully out of range
            ),
            audioDurationMs = 5_000,
        )
        assertEquals(
            listOf(
                Mp4SubtitleMuxer.Mp4TimedCue(0, 1_000, "Speaker 1: early"),
                Mp4SubtitleMuxer.Mp4TimedCue(1_000, 4_800, ""),
                Mp4SubtitleMuxer.Mp4TimedCue(4_800, 5_000, "Speaker 1: late"),
            ),
            timeline,
        )
    }

    @Test
    fun `overlaps stack active cues and end wins at shared boundaries`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(
            listOf(
                segment(1_000, 3_000, "first", orderIndex = 0),
                segment(2_000, 4_000, "second", label = "Speaker 2", orderIndex = 1),
            ),
            audioDurationMs = 5_000,
        )
        assertEquals(
            listOf(
                Mp4SubtitleMuxer.Mp4TimedCue(0, 1_000, ""),
                Mp4SubtitleMuxer.Mp4TimedCue(1_000, 2_000, "Speaker 1: first"),
                // Overlap: both cues shown, in stable start/order order.
                Mp4SubtitleMuxer.Mp4TimedCue(
                    2_000, 3_000, "Speaker 1: first\nSpeaker 2: second"
                ),
                // The first cue ended exactly at 3s: only the second shows.
                Mp4SubtitleMuxer.Mp4TimedCue(3_000, 4_000, "Speaker 2: second"),
                Mp4SubtitleMuxer.Mp4TimedCue(4_000, 5_000, ""),
            ),
            timeline,
        )
    }

    @Test
    fun `adjacent intervals with identical text merge`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(
            listOf(
                segment(0, 1_000, "hello"),
                segment(1_000, 2_000, "hello"),
            ),
            audioDurationMs = 3_000,
        )
        assertEquals(
            listOf(
                Mp4SubtitleMuxer.Mp4TimedCue(0, 2_000, "Speaker 1: hello"),
                Mp4SubtitleMuxer.Mp4TimedCue(2_000, 3_000, ""),
            ),
            timeline,
        )
    }

    @Test
    fun `sub-second audio keeps its real duration instead of stretching to a second`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(
            listOf(segment(100, 300, "quick")),
            audioDurationMs = 400,
        )
        assertEquals(400, timeline.last().endMs)
        assertEquals(0, timeline.first().startMs)
        assertTrue(timeline.all { it.endMs <= 400 })
    }

    @Test
    fun `unicode cue text survives normalization`() {
        val timeline = Mp4SubtitleMuxer.buildSubtitleCueTimeline(
            listOf(segment(0, 1_000, "สวัสดี 会议 ☕")),
            audioDurationMs = 2_000,
        )
        assertEquals("Speaker 1: สวัสดี 会议 ☕", timeline[0].text)
    }

    @Test
    fun `fingerprint ignores notes title and badge colors`() {
        val base = listOf(segment(0, 1_000, "hello"))
        val a = Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "")
        // Same rendered content: everything the muxer does not render is invisible.
        assertEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = ""))
        val otherBadge = base.map { it.copy(badgeColorHex = "#FFFFFF", clusterId = 9) }
        assertEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, otherBadge, summary = ""))
    }

    @Test
    fun `fingerprint changes with speaker text timing and audio identity`() {
        val base = listOf(segment(0, 1_000, "hello"))
        val a = Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "")

        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L,
            listOf(segment(0, 1_000, "hello", label = "Alice")), summary = ""))
        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L,
            listOf(segment(0, 1_000, "hello world")), summary = ""))
        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L,
            listOf(segment(0, 1_500, "hello")), summary = ""))
        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 101L, 200L, base, summary = ""))
        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 201L, base, summary = ""))
        assertNotEquals(a, Mp4SubtitleMuxer.mp4TranscriptFingerprint("s2", 100L, 200L, base, summary = ""))
    }

    @Test
    fun `fingerprint changes when the summary changes`() {
        val base = listOf(segment(0, 1_000, "hello"))
        val noSummary = Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "")
        val withSummary = Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "Summary")

        assertNotEquals(noSummary, withSummary)
        assertNotEquals(
            withSummary,
            Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "Other summary"),
        )
    }

    @Test
    fun `whitespace-only summary fingerprints like no summary`() {
        val base = listOf(segment(0, 1_000, "hello"))
        assertEquals(
            Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = ""),
            Mp4SubtitleMuxer.mp4TranscriptFingerprint("s1", 100L, 200L, base, summary = "  \n\t "),
        )
    }
}
