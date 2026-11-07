package com.phequals7.muesli.meetings

/**
 * UTF-8 SubRip (.srt) serializer shared by the detail-screen cue list and the
 * recording sidecar files saved beside retained WAVs. One implementation so
 * on-screen cues and disk output can never diverge.
 */
object MeetingTranscriptSrt {

    /** `HH:mm:ss,SSS` with non-wrapping hours (Locale.US digits). */
    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000L
        val minutes = (total % 3_600_000L) / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val millis = total % 1_000L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /** `Speaker Label: spoken text`, one line with interior blank lines collapsed. */
    fun cueText(segment: DiarizedTranscriptSegment): String {
        val label = segment.speakerLabel.trim().ifEmpty { "Speaker 1" }
        val text = segment.text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("\n[ \t]*\n+"), "\n")
            .trim()
        return "$label: $text"
    }

    /**
     * Valid cues only: non-blank text, sorted by `startMs` then `orderIndex`,
     * negative starts clamped to 0 and invalid/zero-length ends raised to
     * start+1ms. Overlapping turns are preserved as-is.
     */
    fun normalizedSegments(segments: List<DiarizedTranscriptSegment>): List<DiarizedTranscriptSegment> =
        segments.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.startMs }, { it.orderIndex }))
            .map { seg ->
                val start = seg.startMs.coerceAtLeast(0L)
                val end = if (seg.endMs <= start) start + 1L else seg.endMs
                if (start == seg.startMs && end == seg.endMs) seg
                else seg.copy(startMs = start, endMs = end)
            }
            .toList()

    fun serialize(
        segments: List<DiarizedTranscriptSegment>,
        manualNotes: String = "",
        summary: String = "",
        audioDurationMs: Long = 0,
    ): String = buildString {
        val cues = normalizedSegments(segments)
        for ((index, seg) in cues.withIndex()) {
            append(index + 1).append('\n')
            append(formatTimestamp(seg.startMs))
            append(" --> ")
            append(formatTimestamp(seg.endMs))
            append('\n')
            append(cueText(seg).replace('\n', ' '))
            append("\n\n")
        }
        var footerStart = maxOf(audioDurationMs, cues.maxOfOrNull { it.endMs } ?: 0L) + 1
        var number = cues.size
        for ((label, text) in listOf("Meeting Notes" to manualNotes, "AI Summary" to summary)) {
            if (text.isBlank()) continue
            append(++number).append('\n')
            append(formatTimestamp(footerStart)).append(" --> ").append(formatTimestamp(footerStart + 1)).append('\n')
            append(label).append(":\n")
            append(text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n[ \t]*\n+"), "\n").trim())
            append("\n\n")
            footerStart++
        }
    }
}
