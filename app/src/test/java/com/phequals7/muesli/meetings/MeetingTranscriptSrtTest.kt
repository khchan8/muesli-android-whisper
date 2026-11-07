package com.phequals7.muesli.meetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingTranscriptSrtTest {

    private fun seg(
        startMs: Long,
        endMs: Long,
        text: String,
        label: String = "Alice",
        orderIndex: Int = 0,
    ) = DiarizedTranscriptSegment(
        segmentId = "$startMs-$orderIndex",
        sessionId = "s",
        orderIndex = orderIndex,
        startMs = startMs,
        endMs = endMs,
        clusterId = 0,
        speakerProfileId = null,
        speakerLabel = label,
        badgeColorHex = "#000000",
        text = text
    )

    @Test
    fun `formatTimestamp covers milliseconds seconds minutes and hour rollover`() {
        assertEquals("00:00:00,000", MeetingTranscriptSrt.formatTimestamp(0))
        assertEquals("00:00:00,001", MeetingTranscriptSrt.formatTimestamp(1))
        assertEquals("00:00:59,999", MeetingTranscriptSrt.formatTimestamp(59_999))
        assertEquals("00:01:00,000", MeetingTranscriptSrt.formatTimestamp(60_000))
        assertEquals("00:59:59,999", MeetingTranscriptSrt.formatTimestamp(3_599_999))
        assertEquals("01:00:00,000", MeetingTranscriptSrt.formatTimestamp(3_600_000))
        assertEquals("10:11:12,345", MeetingTranscriptSrt.formatTimestamp(((10L * 3600 + 11 * 60 + 12) * 1000) + 345))
    }

    @Test
    fun `formatTimestamp does not wrap hours at 24`() {
        assertEquals("24:00:00,000", MeetingTranscriptSrt.formatTimestamp(86_400_000))
        assertEquals("25:03:02,001", MeetingTranscriptSrt.formatTimestamp(((25L * 3600 + 3 * 60 + 2) * 1000) + 1))
    }

    @Test
    fun `formatTimestamp clamps negatives to zero`() {
        assertEquals("00:00:00,000", MeetingTranscriptSrt.formatTimestamp(-5))
    }

    @Test
    fun `serialize emits numbered cues with timestamps and inline speaker labels`() {
        val out = MeetingTranscriptSrt.serialize(
            listOf(seg(1_500, 4_000, "Hello team"), seg(5_000, 8_000, "สวัสดีครับ", label = "Somchai"))
        )
        assertEquals(
            "1\n00:00:01,500 --> 00:00:04,000\nAlice: Hello team\n\n" +
                "2\n00:00:05,000 --> 00:00:08,000\nSomchai: สวัสดีครับ\n\n",
            out
        )
    }

    @Test
    fun `serialize preserves Chinese English and combining-mark names`() {
        val out = MeetingTranscriptSrt.serialize(
            listOf(seg(0, 100, "開始吧", label = "王小明"), seg(200, 300, "Let's start", label = "Khun Sòmchai"))
        )
        assertTrue(out.contains("王小明: 開始吧"))
        assertTrue(out.contains("Khun Sòmchai: Let's start"))
    }

    @Test
    fun `blank speaker label becomes Speaker 1`() {
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\nSpeaker 1: hi\n\n",
            MeetingTranscriptSrt.serialize(listOf(seg(0, 1_000, "hi", label = "  ")))
        )
    }

    @Test
    fun `blank-text cues are skipped and numbering stays consecutive`() {
        val out = MeetingTranscriptSrt.serialize(
            listOf(seg(0, 1_000, "first"), seg(2_000, 3_000, "  "), seg(4_000, 5_000, "\n"), seg(6_000, 7_000, "last"))
        )
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\nAlice: first\n\n" +
                "2\n00:00:06,000 --> 00:00:07,000\nAlice: last\n\n",
            out
        )
    }

    @Test
    fun `negative starts clamp to zero and invalid ends become start plus one`() {
        val out = MeetingTranscriptSrt.serialize(
            listOf(seg(-3_000, 1_000, "clamped"), seg(5_000, 5_000, "zero length"), seg(9_000, 8_000, "reversed"))
        )
        assertTrue(out.contains("00:00:00,000 --> 00:00:01,000"))
        assertTrue(out.contains("00:00:05,000 --> 00:00:05,001"))
        assertTrue(out.contains("00:00:09,000 --> 00:00:09,001"))
    }

    @Test
    fun `normalizedSegments sorts by start then orderIndex and keeps overlaps`() {
        val input = listOf(
            seg(5_000, 9_000, "later", orderIndex = 1),
            seg(5_000, 9_500, "same start earlier order", orderIndex = 0),
            seg(0, 2_000, "first", orderIndex = 7),
        )
        val out = MeetingTranscriptSrt.normalizedSegments(input)
        assertEquals(listOf("first", "same start earlier order", "later"), out.map { it.text })
        // Overlapping [5,9.5] (order 0) and [5,9] (order 1) are both preserved untouched.
        assertEquals(5_000L to 9_500L, out[1].startMs to out[1].endMs)
        assertEquals(5_000L to 9_000L, out[2].startMs to out[2].endMs)
    }

    @Test
    fun `internal blank lines and CRLF in cue text cannot split cues`() {
        val messy = seg(0, 1_000, "line one\r\n\r\n\r\nline two\r\nline three")
        assertEquals("Alice: line one\nline two\nline three", MeetingTranscriptSrt.cueText(messy))
        val out = MeetingTranscriptSrt.serialize(listOf(messy))
        assertEquals(1, Regex("(?m)^\\d+$").findAll(out).count())
        assertTrue(out.contains("Alice: line one line two line three"))
    }

    @Test
    fun `empty input serializes to an empty document`() {
        assertEquals("", MeetingTranscriptSrt.serialize(emptyList()))
    }

    @Test
    fun `notes and summary are separate numbered cues after the audio`() {
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nAlice: hello\n\n" +
                "2\n00:00:10,001 --> 00:00:10,002\nMeeting Notes:\nfirst\nsecond\n\n" +
                "3\n00:00:10,002 --> 00:00:10,003\nAI Summary:\n決定事項\n\n",
            MeetingTranscriptSrt.serialize(
                listOf(seg(1_000, 2_000, "hello")),
                manualNotes = "first\r\n\r\nsecond",
                summary = "決定事項",
                audioDurationMs = 10_000,
            )
        )
    }

    @Test
    fun `metadata follows latest cue even when audio duration is shorter`() {
        val out = MeetingTranscriptSrt.serialize(
            listOf(seg(0, 4_000, "hello")),
            summary = "summary",
            audioDurationMs = 2_000,
        )
        assertTrue(out.contains("2\n00:00:04,001 --> 00:00:04,002\nAI Summary:\nsummary"))
        assertTrue(!out.contains("Meeting Notes"))
    }
}
