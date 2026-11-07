package com.phequals7.muesli.meetings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders the AI summary onto the MP4 video canvas: the plain-text summary
 * is laid out once, split into fixed-height pages, and each page is drawn
 * as a 640×360 YUV420 frame (dark background, light text) that the canvas
 * encoder consumes directly. Pages cycle every [PAGE_SECONDS] seconds,
 * which is also the encoder key-frame interval, so every page change starts
 * on a sync frame.
 */
internal object Mp4SummaryCanvas {

    private const val WIDTH = Mp4SubtitleMuxer.VIDEO_WIDTH
    private const val HEIGHT = Mp4SubtitleMuxer.VIDEO_HEIGHT

    const val PAGE_SECONDS = 10

    /**
     * Markdown summary → displayable plain text: blank lines dropped,
     * heading markers stripped (text kept), list markers become `• `,
     * emphasis/code punctuation (`**`, `__`, backticks) removed. Lines are
     * trimmed and joined with `\n`. Blank input returns "".
     */
    fun toPlainText(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (raw in lines) {
            var line = raw
            line = Regex("^\\s*#{1,6}\\s+").replaceFirst(line, "")
            line = Regex("^\\s*[-*+]\\s+").replaceFirst(line, "• ")
            line = line.replace("**", "").replace("__", "").replace("`", "")
            line = line.trim()
            if (line.isNotEmpty()) out.add(line)
        }
        return out.joinToString("\n").trim()
    }

    /**
     * Greedy pagination over consecutive line heights: a line never splits
     * across pages, a page holds at least one line (an oversize line gets a
     * page of its own), and a line that exactly fills the page height stays
     * on it. Returns inclusive line-index ranges; empty input returns [].
     */
    fun paginate(lineHeightsPx: IntArray, pageHeightPx: Int): List<IntRange> {
        require(pageHeightPx > 0) { "pageHeightPx must be positive" }
        val pages = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        for (i in lineHeightsPx.indices) {
            val height = lineHeightsPx[i]
            if (i > start && used + height > pageHeightPx) {
                pages.add(start..i - 1)
                start = i
                used = 0
            }
            used += height
        }
        if (start < lineHeightsPx.size) pages.add(start..lineHeightsPx.size - 1)
        return pages
    }

    /** One 640×360 limited-range BT.601 YUV420 frame. */
    class Yuv420(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    /** Laid-out summary pages rendered on demand; null when there is no text. */
    class Pages private constructor(
        private val layout: StaticLayout,
        private val pages: List<IntRange>,
        private val footerPaint: TextPaint,
    ) {
        companion object {
            private const val LEFT = 24
            private const val TEXT_TOP = 8
            private const val PAGE_HEIGHT_PX = 198
            private const val FOOTER_BASELINE_Y = 224f

            fun create(summaryText: String): Pages? {
                val text = toPlainText(summaryText)
                if (text.isBlank()) return null
                val paint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 17f
                    color = Color.rgb(230, 230, 230)
                }
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, WIDTH - 2 * LEFT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build()
                val heights = IntArray(layout.lineCount) { i ->
                    layout.getLineBottom(i) - layout.getLineTop(i)
                }
                val footerPaint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 13f
                    color = Color.rgb(150, 150, 150)
                }
                return Pages(layout, paginate(heights, PAGE_HEIGHT_PX), footerPaint)
            }
        }

        val pageCount: Int get() = pages.size

        // shortcut: pages cycle every 10 s with no cap, so a very long summary on a
        // short recording shows only its first pages; upgrade by scaling
        // PAGE_SECONDS to duration/pageCount.
        fun pageForFrame(frameIndex: Int): Int = (frameIndex / PAGE_SECONDS) % pageCount

        private var cachedPage = -1
        private var cachedYuv: Yuv420? = null

        fun yuv(page: Int): Yuv420 {
            if (cachedPage == page) cachedYuv?.let { return it }
            val range = pages[page]
            val firstLine = range.first
            val lastLine = range.last
            val pageHeight = layout.getLineBottom(lastLine) - layout.getLineTop(firstLine)

            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(9, 9, 9)) // Y=24, U=V=128, same as the slate canvas
                val canvas = Canvas(bitmap)
                canvas.save()
                canvas.clipRect(0f, TEXT_TOP.toFloat(), WIDTH.toFloat(), (TEXT_TOP + pageHeight).toFloat())
                canvas.translate(LEFT.toFloat(), (TEXT_TOP - layout.getLineTop(firstLine)).toFloat())
                layout.draw(canvas)
                canvas.restore()
                canvas.drawText("AI Summary · ${page + 1}/$pageCount", LEFT.toFloat(), FOOTER_BASELINE_Y, footerPaint)

                val pixels = IntArray(WIDTH * HEIGHT)
                bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                val result = toYuv420(pixels)
                cachedPage = page
                cachedYuv = result
                return result
            } finally {
                bitmap.recycle()
            }
        }

        /** Limited-range BT.601: chroma is averaged per 2×2 block. */
        private fun toYuv420(pixels: IntArray): Yuv420 {
            val y = ByteArray(WIDTH * HEIGHT)
            val u = ByteArray((WIDTH / 2) * (HEIGHT / 2))
            val v = ByteArray((WIDTH / 2) * (HEIGHT / 2))
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                y[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            var c = 0
            for (row in 0 until HEIGHT / 2) {
                for (col in 0 until WIDTH / 2) {
                    val p00 = pixels[2 * row * WIDTH + 2 * col]
                    val p01 = pixels[2 * row * WIDTH + 2 * col + 1]
                    val p10 = pixels[(2 * row + 1) * WIDTH + 2 * col]
                    val p11 = pixels[(2 * row + 1) * WIDTH + 2 * col + 1]
                    val r = ((p00 shr 16 and 0xFF) + (p01 shr 16 and 0xFF) +
                        (p10 shr 16 and 0xFF) + (p11 shr 16 and 0xFF) + 2) shr 2
                    val g = ((p00 shr 8 and 0xFF) + (p01 shr 8 and 0xFF) +
                        (p10 shr 8 and 0xFF) + (p11 shr 8 and 0xFF) + 2) shr 2
                    val b = ((p00 and 0xFF) + (p01 and 0xFF) +
                        (p10 and 0xFF) + (p11 and 0xFF) + 2) shr 2
                    u[c] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[c] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                    c++
                }
            }
            return Yuv420(y, u, v)
        }
    }

}
