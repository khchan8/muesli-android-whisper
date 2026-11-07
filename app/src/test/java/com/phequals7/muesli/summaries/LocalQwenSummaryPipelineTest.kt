package com.phequals7.muesli.summaries

import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.MeetingTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalQwenSummaryPipelineTest {

    private fun seg(index: Int, startMs: Long, text: String, speaker: String = "Alice") =
        DiarizedTranscriptSegment(
            segmentId = "seg-$index",
            sessionId = "s",
            orderIndex = index,
            startMs = startMs,
            endMs = startMs + 2_000L,
            clusterId = 0,
            speakerProfileId = null,
            speakerLabel = speaker,
            badgeColorHex = "#000000",
            text = text
        )

    @Test
    fun `stripQwenThinkBlocks removes complete and unclosed trailing think blocks`() {
        assertEquals(
            "## Meeting Summary\nDone.",
            stripQwenThinkBlocks("<think>\nlet me reason...\n</think>\n\n## Meeting Summary\nDone.")
        )
        assertEquals(
            "## Decisions\n- Ship v2.1",
            stripQwenThinkBlocks("<THINK>first</THINK>## Decisions\n- Ship v2.1\n<think>unfinished thought")
        )
    }

    @Test
    fun `packUnitsIntoWindows respects exact token budget and splits one token over`() {
        // 1 token per character for deterministic boundary verification.
        val tokenCounter: (String) -> Int = { it.length }
        val units = listOf(
            LocalQwenSummaryPipeline.CueUnit("[00:00] A: ", "12345"),
            LocalQwenSummaryPipeline.CueUnit("[00:02] B: ", "67890")
        )
        val bothJoined = "${units[0].render()}\n${units[1].render()}"

        val exactFit = LocalQwenSummaryPipeline.packUnitsIntoWindows(
            units = units,
            maxPromptTokens = bothJoined.length,
            tokenCount = tokenCounter,
            wrapWindow = { it }
        )
        assertEquals(1, exactFit.size)
        assertEquals(bothJoined, exactFit.single())

        val oneOver = LocalQwenSummaryPipeline.packUnitsIntoWindows(
            units = units,
            maxPromptTokens = bothJoined.length - 1,
            tokenCount = tokenCounter,
            wrapWindow = { it }
        )
        assertEquals(2, oneOver.size)
        assertEquals(units[0].render(), oneOver[0])
        assertEquals(units[1].render(), oneOver[1])
    }

    @Test
    fun `packUnitsIntoWindows splits an oversized multilingual cue without corrupting Unicode or losing speaker prefix`() {
        // Surrogate-pair emoji + Thai + Chinese in one cue that exceeds a tight window budget.
        val longText = "สวัสดีครับ 🚀 測試報告 ".repeat(12).trim()
        val unit = LocalQwenSummaryPipeline.CueUnit("[01:05] สมชาย: ", longText)
        val budget = 70
        val windows = LocalQwenSummaryPipeline.packUnitsIntoWindows(
            units = listOf(unit),
            maxPromptTokens = budget,
            tokenCount = { it.length },
            wrapWindow = { it }
        )
        assertTrue("Expected cue to split across multiple windows", windows.size >= 3)
        for (w in windows) {
            assertTrue("Window exceeded budget: ${w.length} > $budget", w.length <= budget)
            assertTrue("Every split piece must retain speaker/time prefix", w.startsWith("[01:05] สมชาย: "))
            // Ensure no broken surrogate pairs exist.
            for (i in w.indices) {
                val c = w[i]
                if (Character.isHighSurrogate(c)) {
                    assertTrue("Unpaired high surrogate at $i", i + 1 < w.length && Character.isLowSurrogate(w[i + 1]))
                }
            }
        }
        // Reconstruct spoken text from all windows to verify nothing was dropped.
        val reconstructed = windows.joinToString(" ") { it.removePrefix("[01:05] สมชาย: ").trim() }
        assertEquals(longText.replace(Regex("\\s+"), " "), reconstructed.replace(Regex("\\s+"), " "))
    }

    @Test
    fun `run reports stages tokens and part counts through onProgress`() {
        val segments = listOf(seg(1, 0, "hello world", "Alice"))
        val events = mutableListOf<String>()
        val result = LocalQwenSummaryPipeline.run(
            transcript = "",
            meetingTitle = "T",
            template = MeetingTemplate.GENERAL,
            manualNotesToRetain = null,
            segments = segments,
            modelId = "m",
            contextTokens = 8192,
            summaryOutputTokens = 100,
            titleOutputTokens = 20,
            tokenCount = { it.length },
            generate = { _, maxTokens, onTokens ->
                if (maxTokens <= 20) {
                    onTokens(3)
                    "Title"
                } else {
                    onTokens(7)
                    onTokens(9)
                    "Summary body"
                }
            },
            onProgress = { stage, tokens, done, total -> events.add("$stage:$tokens:$done:$total") }
        )
        assertEquals("Title", result.title)
        assertEquals("Summary body", result.notes)
        assertTrue(events.contains("Preparing transcript:0:0:0"))
        assertTrue("Expected single-pass Summarizing events, got $events", events.contains("Summarizing:0:0:1"))
        assertTrue(events.contains("Summarizing:7:0:1"))
        assertTrue(events.contains("Summarizing:9:0:1"))
        assertTrue(events.contains("Generating title:0:1:1"))
        assertTrue(events.contains("Generating title:3:1:1"))
    }

    @Test
    fun `map-reduce progress reports window counts and combining stage`() {
        val segments = (1..6).map { i ->
            seg(i, i * 10_000L, "point $i " + "detail ".repeat(40), speaker = "Speaker $i")
        }
        val events = mutableListOf<String>()
        LocalQwenSummaryPipeline.run(
            transcript = "",
            meetingTitle = "Meeting",
            template = MeetingTemplate.GENERAL,
            manualNotesToRetain = null,
            segments = segments,
            modelId = "m",
            contextTokens = 2300,
            summaryOutputTokens = 100,
            titleOutputTokens = 20,
            tokenCount = { it.length },
            generate = { _, maxTokens, onTokens ->
                onTokens(5)
                if (maxTokens <= 20) "Title" else "part"
            },
            onProgress = { stage, _, done, total -> events.add("$stage:$done:$total") }
        )
        val mapEvents = events.filter { it.startsWith("Summarizing:") }
        val maxTotal = mapEvents.maxOf { it.substringAfterLast(':').toInt() }
        assertTrue("Expected multi-window map phase, got $events", maxTotal >= 2)
        assertTrue("Expected a Combining summaries stage, got $events", events.any { it.startsWith("Combining summaries:") })
        assertTrue(events.contains("Generating title:${maxTotal + 1}:${maxTotal + 1}"))
    }

    @Test
    fun `checkCancelled is observed before every generate call`() {
        var calls = 0
        try {
            LocalQwenSummaryPipeline.run(
                transcript = "line one\nline two",
                meetingTitle = "T",
                template = MeetingTemplate.GENERAL,
                manualNotesToRetain = null,
                segments = emptyList(),
                modelId = "m",
                contextTokens = 8192,
                summaryOutputTokens = 100,
                titleOutputTokens = 20,
                checkCancelled = { if (calls >= 1) throw kotlinx.coroutines.CancellationException("stop") },
                tokenCount = { it.length },
                generate = { _, _, _ -> calls++; "x" }
            )
            fail("Expected CancellationException from checkCancelled")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun `recursive map-reduce carries a distinct fact from the final chunk into the synthesized summary`() {
        val segments = (1..6).map { i ->
            val fact = if (i == 6) "FINAL_WINDOW_FACT_42" else "earlier discussion point $i"
            seg(i, i * 10_000L, "$fact " + "detail ".repeat(40), speaker = "Speaker $i")
        }

        val seenPrompts = mutableListOf<String>()
        // Under 1-token-per-char: system+frame is ~1,300 chars; 6 segments are ~1,900 chars
        // (~3,200 total > 2,200 maxPromptTokens), forcing multi-window map + reduce.
        val result = LocalQwenSummaryPipeline.run(
            transcript = "",
            meetingTitle = "Meeting",
            template = MeetingTemplate.GENERAL,
            manualNotesToRetain = "Keep budget under 50k",
            segments = segments,
            modelId = "qwen3-summary-1.7b-q4-k-m",
            contextTokens = 2300,
            summaryOutputTokens = 100,
            titleOutputTokens = 20,
            tokenCount = { it.length },
            generate = { prompt, maxTokens, _ ->
                seenPrompts.add(prompt)
                val facts = Regex("FINAL_WINDOW_FACT_\\d+|earlier discussion point \\d+")
                    .findAll(prompt)
                    .map { it.value }
                    .distinct()
                    .joinToString(", ")
                if (maxTokens <= 20) {
                    "<think>picking title</think>Q4 Budget Review"
                } else {
                    "<think>reasoning</think>Summary covering: $facts"
                }
            }
        )
        assertTrue("Expected multi-window map-reduce, got ${seenPrompts.size} calls", seenPrompts.size >= 4)
        assertTrue(
            "Final summary must include the last chunk's fact, got: ${result.notes}",
            result.notes.contains("FINAL_WINDOW_FACT_42")
        )
        assertFalse("Think tags must be stripped", result.notes.contains("<think>"))
        assertEquals("Q4 Budget Review", result.title)
        assertEquals("local_qwen", result.backend)
    }

    @Test
    fun `pipeline fails clearly when partial summaries cannot make reduction progress`() {
        val segments = listOf(
            seg(1, 1_000L, "part one ".repeat(55)),
            seg(2, 2_000L, "part two ".repeat(55))
        )
        try {
            LocalQwenSummaryPipeline.run(
                transcript = "",
                meetingTitle = "Meeting",
                template = MeetingTemplate.GENERAL,
                manualNotesToRetain = null,
                segments = segments,
                modelId = "qwen3-summary-1.7b-q4-k-m",
                contextTokens = 2000,
                summaryOutputTokens = 100,
                titleOutputTokens = 20,
                tokenCount = { it.length },
                generate = { _, _, _ ->
                    // Emit an oversized partial summary so two partials can never fit together.
                    "X".repeat(1200)
                }
            )
            fail("Expected SummaryException when reduction cannot combine partial summaries")
        } catch (e: MeetingSummaryClient.SummaryException) {
            assertEquals("Summary context is too small to combine the meeting notes", e.message)
        }
    }
}
