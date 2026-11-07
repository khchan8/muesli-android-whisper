package com.phequals7.muesli.engine

import com.phequals7.muesli.meetings.splitTimedChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Qwen3WindowingTest {
    private val h = SherpaRecognizerHolder

    @Test
    fun `short single chunk is passed through untouched`() {
        val c = FloatArray(240_000)
        assertSame(c, h.slicePcmIntoWindows(listOf(c)).single())
    }

    @Test
    fun `122s segment splits into 20s windows and preserves samples`() {
        val pcm = FloatArray(1_954_560) { it.toFloat() }
        val w = h.slicePcmIntoWindows(listOf(pcm))
        assertEquals(listOf(320_000, 320_000, 320_000, 320_000, 320_000, 320_000, 34_560), w.map { it.size })
        assertEquals(pcm.toList(), w.flatMap { it.toList() })
    }

    @Test
    fun `tiny tail merges into previous window`() {
        val w = h.slicePcmIntoWindows(listOf(FloatArray(643_200)))
        assertEquals(listOf(320_000, 323_200), w.map { it.size })
    }

    @Test
    fun `many small chunks are re-windowed`() {
        val w = h.slicePcmIntoWindows(List(450) { FloatArray(1_600) })
        assertEquals(listOf(320_000, 320_000, 80_000), w.map { it.size })
    }

    @Test
    fun `scaffold-only output is dropped and prefix stripped`() {
        for (s in listOf("Language", "language", "language Chinese", " Language English ")) {
            assertEquals(s, "", h.cleanQwen3ScaffoldText(s))
        }
        assertEquals("零乘以任何数值", h.cleanQwen3ScaffoldText("language Chinese<asr_text>零乘以任何数值"))
        assertEquals(
            "Language models are useful in meetings.",
            h.cleanQwen3ScaffoldText("Language models are useful in meetings.")
        )
    }

    @Test
    fun `join omits space between CJK and Thai but not Latin`() {
        assertEquals("地球处于二这个位置它继续朝这边走", h.joinDecodedWindows("地球处于二这个位置", "它继续朝这边走"))
        assertEquals("สวัสดีครับ", h.joinDecodedWindows("สวัสดี", "ครับ"))
        assertEquals("hello world", h.joinDecodedWindows("hello", " world"))
        assertEquals("a", h.joinDecodedWindows("a", ""))
    }

    @Test
    fun `splitTimedChunk keeps contiguous timestamps`() {
        val n = (65.744f * 16000).toInt()
        val parts = splitTimedChunk(FloatArray(n), 298.732f)
        assertEquals(listOf(320_000, 320_000, 320_000, n - 960_000), parts.map { it.pcm.size })
        assertEquals(298.732f, parts.first().startSec, 1e-3f)
        for (i in 1 until parts.size) assertEquals(parts[i - 1].endSec, parts[i].startSec, 1e-3f)
        assertEquals(298.732f + 65.744f, parts.last().endSec, 1e-3f)
    }

    @Test
    fun `splitTimedChunk drops sub-half-second segments`() {
        assertEquals(0, splitTimedChunk(FloatArray(7_999), 0f).size)
    }
}
