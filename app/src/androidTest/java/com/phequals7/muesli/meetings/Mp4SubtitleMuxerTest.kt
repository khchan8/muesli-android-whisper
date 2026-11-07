package com.phequals7.muesli.meetings

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * End-to-end embedded-subtitle MP4 muxer proof on a real device: AAC fixtures
 * from the real [M4aAudioEncoder], a real AVC canvas encode, byte-exact AAC
 * passthrough (sample count/payload/timestamps/config), tx3g subtitle bytes
 * (transcript cues only — no notes/summary), a 640×360 avc1 track, ≤1 ms
 * duration alignment, and an actual AVC decode proving the dark canvas.
 */
@RunWith(AndroidJUnit4::class)
class Mp4SubtitleMuxerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tempDir = File(context.cacheDir, "mp4-muxer-test-${System.currentTimeMillis()}")

    private fun segment(startMs: Long, endMs: Long, text: String, label: String = "Speaker 1") =
        DiarizedTranscriptSegment(
            sessionId = "test",
            orderIndex = 0,
            startMs = startMs,
            endMs = endMs,
            clusterId = 0,
            speakerProfileId = null,
            speakerLabel = label,
            badgeColorHex = "#1D63B8",
            text = text,
        )

    private fun encodeFixture(file: File, seconds: Double) {
        val pcm = ShortArray((32_000 * seconds).toInt()) { i ->
            (0.5 * sin(2.0 * Math.PI * 440.0 * i / 32_000.0) * 32000).toInt().toShort()
        }
        val encoder = M4aAudioEncoder(file)
        encoder.encodePcmShorts(pcm, pcm.size)
        encoder.stopAndRelease()
    }

    private suspend fun mux(
        m4a: File,
        segments: List<DiarizedTranscriptSegment>,
        summary: String = "",
    ): File {
        val out = File(File(tempDir, "out-${System.nanoTime()}"), "export.mp4")
        out.parentFile!!.mkdirs()
        FileChannel.open(m4a.toPath(), StandardOpenOption.READ).use { channel ->
            Mp4SubtitleMuxer.muxM4aAndSegmentsToMp4(channel, segments, out, summaryText = summary)
        }
        return out
    }

    private class TrackDump(
        val mime: String,
        val sampleCount: Int,
        val durationUs: Long,
        val format: MediaFormat,
        val timestampsUs: List<Long>,
        val sampleHashes: List<String>,
        val sampleSizes: List<Int>,
    ) {
        val csdHash: String? = listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
            .joinToString(",") { bytes ->
                val arr = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
                arr.joinToString("") { b -> "%02x".format(b) }
            }.ifEmpty { null }
    }

    /** Extracts every sample (hash + timestamp + size) from one track of [file]. */
    private fun dumpTrack(file: File, trackIndex: Int): TrackDump {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
            val stamps = mutableListOf<Long>()
            val hashes = mutableListOf<String>()
            val sizes = mutableListOf<Int>()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                stamps.add(extractor.sampleTime)
                sizes.add(size)
                val arr = ByteArray(size)
                buffer.position(0)
                buffer.get(arr, 0, size)
                hashes.add(
                    java.security.MessageDigest.getInstance("SHA-256").digest(arr)
                        .joinToString("") { "%02x".format(it) }
                )
                if (!extractor.advance()) break
            }
            val duration = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            return TrackDump(
                format.getString(MediaFormat.KEY_MIME)!!, stamps.size, duration, format, stamps, hashes, sizes
            )
        } finally {
            extractor.release()
        }
    }

    private fun trackIndexByMime(file: File, prefix: String): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            return (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix)
            }
        } finally {
            extractor.release()
        }
    }

    private fun videoTrackIndex(file: File) = trackIndexByMime(file, "video/")
    private fun textTrackIndex(file: File) = trackIndexByMime(file, "text/")

    /** Decodes tx3g samples (2-byte UTF-8 byte count + payload) into strings. */
    private fun readTextSamples(file: File, trackIndex: Int): List<String> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            extractor.selectTrack(trackIndex)
            val buffer = java.nio.ByteBuffer.allocate(128 * 1024)
            val out = mutableListOf<String>()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val arr = ByteArray(size)
                buffer.position(0)
                buffer.get(arr, 0, size)
                val payload = if (size >= 2) {
                    val declared = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    String(arr, 2, minOf(declared, size - 2), Charsets.UTF_8)
                } else ""
                out.add(payload)
                if (!extractor.advance()) break
            }
            return out
        } finally {
            extractor.release()
        }
    }

    @Test
    fun muxesSubSecondClipWithExactAacPassthroughAndTx3gTimeline() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        val m4a = File(tempDir, "subsecond.m4a")
        encodeFixture(m4a, 0.4)
        val mp4 = mux(
            m4a,
            listOf(
                segment(0, 200, "hello"),
                segment(200, 400, "world", label = "ประชา"),
            ),
        )

        val videoIdx = videoTrackIndex(mp4)
        val audioIdx = trackIndexByMime(mp4, "audio/")
        val textIdx = textTrackIndex(mp4)

        val video = dumpTrack(mp4, videoIdx)
        val audioOut = dumpTrack(mp4, audioIdx)
        val audioSrc = dumpTrack(m4a, 0)
        val text = dumpTrack(mp4, textIdx)

        // 0.4 s → exactly one canvas frame at t=0, 640×360 avc1.
        assertEquals(1, video.sampleCount)
        assertEquals(640, video.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(360, video.format.getInteger(MediaFormat.KEY_HEIGHT))

        // AAC passthrough: byte-exact payloads, config, timestamps, count.
        assertEquals(audioSrc.sampleCount, audioOut.sampleCount)
        assertEquals(audioSrc.sampleHashes, audioOut.sampleHashes)
        assertEquals(audioSrc.timestampsUs, audioOut.timestampsUs)
        assertEquals(audioSrc.sampleSizes, audioOut.sampleSizes)
        assertEquals(audioSrc.csdHash, audioOut.csdHash)

        // Durations align within 1 ms across video, audio and text.
        val videoMs = video.durationUs / 1_000.0
        val audioMs = audioOut.durationUs / 1_000.0
        val textMs = text.durationUs / 1_000.0
        assertTrue("video duration $videoMs vs audio $audioMs", abs(videoMs - audioMs) <= 1.0)
        assertTrue("text duration $textMs vs audio $audioMs", abs(textMs - audioMs) <= 1.0)
        assertTrue("audio must really be ~400ms (got $audioMs)", abs(audioMs - 400.0) <= 150.0)

        // Subtitle payloads: transcript cues only, Unicode preserved.
        val payloads = readTextSamples(mp4, textIdx)
        assertTrue("expected cue payloads, got $payloads", payloads.isNotEmpty())
        assertTrue("expected 'hello' in subtitles: $payloads", payloads.any { it.contains("hello") })
        assertTrue("expected Thai speaker label: $payloads", payloads.any { it.contains("ประชา") })
        assertTrue("no summary/notes may leak into subtitles: $payloads",
            payloads.none { it.contains("Summary") || it.contains("Notes") })

        // Real decode: the one canvas frame is genuinely dark.
        decodeFirstFrameAndCheckDark(mp4, videoIdx)
    }

    @Test
    fun muxesExactSecondAndNonIntegerSecondClips() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        for (seconds in listOf(1.0, 1.9)) {
            val m4a = File(tempDir, "clip-$seconds.m4a")
            encodeFixture(m4a, seconds)
            val mp4 = mux(m4a, listOf(segment(0, 500, "สวัสดี 会议")))

            val videoIdx = videoTrackIndex(mp4)
            val video = dumpTrack(mp4, videoIdx)
            assertEquals("clip $seconds: canvas frames", ceil(seconds).toInt(), video.sampleCount)
            assertEquals(640, video.format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(360, video.format.getInteger(MediaFormat.KEY_HEIGHT))
            assertTrue(
                "video duration must stay off the next whole second",
                video.durationUs <= (seconds * 1_000_000L) + 1_000L
            )
            val payloads = readTextSamples(mp4, textTrackIndex(mp4))
            assertTrue(payloads.any { it.contains("สวัสดี") && it.contains("会议") })
        }
    }

    @Test
    fun silentTranscriptStillProducesFullDurationTextTrack() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        val m4a = File(tempDir, "silent.m4a")
        encodeFixture(m4a, 0.5)
        val mp4 = mux(m4a, emptyList())

        val textIdx = textTrackIndex(mp4)
        val text = dumpTrack(mp4, textIdx)
        assertEquals("one full-duration blank cue", 1, text.sampleCount)
        assertEquals("blank sample is just the 2-byte length prefix", 2, text.sampleSizes[0])
        val audioMs = dumpTrack(mp4, trackIndexByMime(mp4, "audio/")).durationUs / 1_000.0
        assertTrue(abs(text.durationUs / 1_000.0 - audioMs) <= 1.0)
    }

    @Test
    fun overlappingCuesStackInsteadOfFailing() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        val m4a = File(tempDir, "overlap.m4a")
        encodeFixture(m4a, 1.0)
        val mp4 = mux(
            m4a,
            listOf(
                segment(100, 600, "first"),
                segment(300, 900, "second", label = "Speaker 2"),
            ),
        )
        val payloads = readTextSamples(mp4, textTrackIndex(mp4))
        assertTrue(
            "stacked overlap text expected, got $payloads",
            payloads.any { it.contains("first") && it.contains("second") }
        )
    }

    @Test
    fun summaryPagesRenderOnCanvasAboveSubtitleArea() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        val m4a = File(tempDir, "summary.m4a")
        encodeFixture(m4a, 11.0)
        val summary = (1..40).joinToString("\n") { "- Decision $it: ship the build on Friday" }
        val mp4 = mux(m4a, emptyList(), summary)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertTrue("decoder returned no frame", frame != null)
            // Summary text occupies the top 232 px; the tx3g subtitle band stays dark.
            assertTrue(
                "expected bright summary pixels in the top region",
                brightPixelCount(frame!!, 0, 0, 640, 232) > 200,
            )
            assertEquals("subtitle band must stay dark", 0, brightPixelCount(frame, 0, 248, 640, 360))
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    @Test
    fun summaryCanvasPagesCycleAtTheTenSecondKeyFrame() = runTest {
        tempDir.mkdirs()
        assumeTrue(hasAvcEncoder())
        val m4a = File(tempDir, "summary-cycle.m4a")
        encodeFixture(m4a, 11.0)
        val summary = (1..40).joinToString("\n") { "- Decision $it: ship the build on Friday" }
        val mp4 = mux(m4a, emptyList(), summary)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4.absolutePath)
            val first = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)!!
            val second = retriever.getFrameAtTime(10_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)!!
            var changed = 0
            for (y in 0 until 232) {
                for (x in 0 until 640) {
                    val a = first.getPixel(x, y)
                    val b = second.getPixel(x, y)
                    if (abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                        abs((a and 0xFF) - (b and 0xFF)) > 30
                    ) changed++
                }
            }
            first.recycle()
            second.recycle()
            assertTrue("expected a page change at 10 s, only $changed pixels differ", changed > 200)
        } finally {
            retriever.release()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun hasAvcEncoder(): Boolean {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, Mp4SubtitleMuxer.VIDEO_WIDTH, Mp4SubtitleMuxer.VIDEO_HEIGHT
        )
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format)
                }.getOrDefault(false)
        }
    }

    /** Pixels whose Rec.601 luma exceeds 128 inside [left, right) × [top, bottom). */
    private fun brightPixelCount(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val p = frame.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (luma > 128) count++
            }
        }
        return count
    }

    /** Decodes the first AVC frame and proves a real dark-slate canvas (Y≈24). */
    private fun decodeFirstFrameAndCheckDark(file: File, trackIndex: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        try {
            while (true) {
                assertTrue("video decoder stalled", SystemClock.elapsedRealtime() < deadline)
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex < 0) continue
                val image = decoder.getOutputImage(outIndex)
                if (image == null) {
                    decoder.releaseOutputBuffer(outIndex, false)
                    assertTrue("decoder ended without a readable frame", info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0)
                    continue
                }
                run {
                    val plane = image.planes[0]
                    val buf = plane.buffer.duplicate()
                    var total = 0L
                    var count = 0
                    for (row in 0 until image.height step 24) {
                        val base = row * plane.rowStride
                        for (col in 0 until image.width step 24) {
                            total += buf.get(base + col).toInt() and 0xFF
                            count++
                        }
                    }
                    val avg = (total / count).toInt()
                    assertTrue("canvas Y average $avg is not a dark slate (~24)", abs(avg - 24) <= 8)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                return // first frame verified
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            extractor.release()
        }
    }
}
