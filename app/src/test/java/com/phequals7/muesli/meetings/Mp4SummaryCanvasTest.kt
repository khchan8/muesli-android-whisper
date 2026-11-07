package com.phequals7.muesli.meetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure text helpers behind the MP4 summary canvas. */
class Mp4SummaryCanvasTest {

    @Test
    fun `toPlainText strips headings bullets and emphasis`() {
        val markdown = """
            # Meeting summary

            - **Decide**: ship the build
            * second bullet
            + third bullet

            ## Risks
            __underline__ and `code` stay as plain text
        """.trimIndent()
        assertEquals(
            "Meeting summary\n" +
                "• Decide: ship the build\n" +
                "• second bullet\n" +
                "• third bullet\n" +
                "Risks\n" +
                "underline and code stay as plain text",
            Mp4SummaryCanvas.toPlainText(markdown),
        )
    }

    @Test
    fun `toPlainText normalizes line endings and drops blank or emptiable lines`() {
        assertEquals("a\nb", Mp4SummaryCanvas.toPlainText("a\r\n\r\nb\r"))
        assertEquals("", Mp4SummaryCanvas.toPlainText(""))
        assertEquals("", Mp4SummaryCanvas.toPlainText("  \n\t \n"))
        // Indented bullets keep their text but lose the indentation.
        assertEquals("• x", Mp4SummaryCanvas.toPlainText("   - x"))
    }

    @Test
    fun `paginate fills pages greedily without splitting lines`() {
        assertEquals(
            listOf(0..1, 2..2),
            Mp4SummaryCanvas.paginate(intArrayOf(10, 10, 10), pageHeightPx = 25),
        )
        // Exact fit stays on the same page.
        assertEquals(
            listOf(0..2),
            Mp4SummaryCanvas.paginate(intArrayOf(10, 10, 5), pageHeightPx = 25),
        )
        assertEquals(
            listOf(0..2, 3..3),
            Mp4SummaryCanvas.paginate(intArrayOf(10, 10, 5, 5), pageHeightPx = 25),
        )
    }

    @Test
    fun `paginate gives an oversize line its own page`() {
        assertEquals(
            listOf(0..0, 1..1, 2..2),
            Mp4SummaryCanvas.paginate(intArrayOf(10, 100, 10), pageHeightPx = 50),
        )
        assertEquals(
            listOf(0..0),
            Mp4SummaryCanvas.paginate(intArrayOf(100), pageHeightPx = 50),
        )
    }

    @Test
    fun `paginate returns no pages for empty input`() {
        assertTrue(Mp4SummaryCanvas.paginate(intArrayOf(), pageHeightPx = 50).isEmpty())
    }
}
