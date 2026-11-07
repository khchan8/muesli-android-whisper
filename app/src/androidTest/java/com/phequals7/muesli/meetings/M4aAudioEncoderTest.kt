package com.phequals7.muesli.meetings

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * End-to-end M4A encoder proof on a real device codec: uneven PCM block
 * feeding, extractor-level format/timestamp/duration verification, a full
 * platform decode of the produced audio, repeat-release safety, and start
 * failure on an invalid destination. Temporary files only; no microphone.
 */
@RunWith(AndroidJUnit4::class)
class M4aAudioEncoderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tempDir = File(context.cacheDir, "m4a-encoder-test-${System.currentTimeMillis()}")

    private fun tonePcm32k(seconds: Double, freqHz: Double = 440.0, amplitude: Double = 0.5): ShortArray {
        val total = (32_000 * seconds).toInt()
        return ShortArray(total) { i ->
            (amplitude * sin(2.0 * Math.PI * freqHz * i / 32_000.0) * 32000).toInt().toShort()
        }
    }

    private fun feedUneven(encoder: M4aAudioEncoder, pcm: ShortArray) {
        val blockSizes = intArrayOf(3_200, 1_777, 4_096, 511, 32_000)
        var offset = 0
        var i = 0
        while (offset < pcm.size) {
            val len = minOf(blockSizes[i % blockSizes.size], pcm.size - offset)
            encoder.encodePcmShorts(pcm.copyOfRange(offset, offset + len), len)
            offset += len
            i++
        }
    }

    private fun encodeClip(file: File, seconds: Double): Int {
        val encoder = M4aAudioEncoder(file)
        feedUneven(encoder, tonePcm32k(seconds))
        encoder.stopAndRelease()
        encoder.stopAndRelease() // repeated release must be safe
        return (32_000 * seconds).toInt()
    }

    @Test
    fun producesValidAacLc32kMonoClipWithMonotonicTimestampsAndRoughDuration() {
        tempDir.mkdirs()
        val out = File(tempDir, "clip1.m4a")
        val inputSamples = encodeClip(out, 3.0)
        assertTrue("M4A must be nonempty", out.length() > 0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.absolutePath)
            assertEquals("exactly one audio track", 1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(32_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))

            // AAC-LC profile from the AudioSpecificConfig (csd-0) when the
            // extractor does not expose KEY_AAC_PROFILE.
            val profile = runCatching { format.getInteger(MediaFormat.KEY_AAC_PROFILE) }.getOrNull()
                ?: aacObjectTypeFromCsd(format)
            assertEquals("AAC-LC (AudioObjectType 2)", 2, profile)

            extractor.selectTrack(0)
            val buffer = ByteBufferAllocator.newBuffer(64 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var samples = 0
            var lastUs = -1L
            var firstUs = -1L
            var lastSeenUs = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                assertTrue("compressed sample must be readable", size > 0)
                samples++
                val us = extractor.sampleTime
                assertTrue("timestamps must be monotonic ($us after $lastUs)", us >= lastUs)
                if (firstUs < 0) firstUs = us
                lastSeenUs = us
                lastUs = us
                if (!extractor.advance()) break
            }
            assertTrue("must contain many AAC frames", samples > 50)
            // AAC priming/padding: allow up to two 1024-sample frames of slack.
            val expectedMs = inputSamples / 32L
            val spanMs = (lastSeenUs - firstUs) / 1_000L
            assertTrue(
                "cue span ${spanMs}ms too far from expected ${expectedMs}ms",
                abs(spanMs - expectedMs) <= 2 * 1_024 * 1_000L / 32_000L + 40L
            )
            val durationMs = runCatching { format.getLong(MediaFormat.KEY_DURATION) / 1_000L }.getOrDefault(-1L)
            if (durationMs >= 0) {
                assertTrue(
                    "container duration $durationMs too far from expected $expectedMs ms",
                    abs(durationMs - expectedMs) <= 150L
                )
            }
        } finally {
            extractor.release()
        }

        // Decode the produced audio with a platform decoder: same rate,
        // roughly expected duration, and non-silent PCM.
        val decoded = decodeToPcm(out)
        assertEquals(32_000, decoded.first)
        assertTrue(
            "decoded ${decoded.second} samples, expected ~$inputSamples",
            abs(decoded.second - inputSamples) <= 2 * 1_024
        )
        assertTrue("decoded PCM must not be silent (peak ${decoded.third})", decoded.third > 8_000)
    }

    @Test
    fun secondFreshEncodeAfterRepeatedReleaseProducesAnotherValidClip() {
        tempDir.mkdirs()
        encodeClip(File(tempDir, "a.m4a"), 1.0)
        val second = File(tempDir, "b.m4a")
        val samples = encodeClip(second, 1.0)
        assertTrue(second.length() > 0)
        val decoded = decodeToPcm(second)
        assertEquals(32_000, decoded.first)
        assertTrue(abs(decoded.second - samples) <= 2 * 1_024)
        assertTrue(decoded.third > 8_000)
    }

    @Test
    fun invalidOutputLocationFailsWithoutBlockingSubsequentEncoding() {
        tempDir.mkdirs()
        // A regular file occupying the parent-directory slot makes the muxer
        // output location impossible.
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val invalid = File(blocker, "x.m4a")
        try {
            M4aAudioEncoder(invalid)
            fail("encoder construction at an invalid location must fail")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
        // A subsequent fresh encode still works.
        val good = File(tempDir, "after-failure.m4a")
        val samples = encodeClip(good, 0.5)
        assertTrue(good.length() > 0)
        assertTrue(abs(decodeToPcm(good).second - samples) <= 2 * 1_024)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private object ByteBufferAllocator {
        fun newBuffer(size: Int): java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(size)
    }

    /** AAC AudioSpecificConfig starts with 5 bits of AudioObjectType; LC = 2. */
    private fun aacObjectTypeFromCsd(format: MediaFormat): Int {
        val csd = format.getByteBuffer("csd-0")
            ?: throw AssertionError("no csd-0 in track format")
        return (csd.get(0).toInt() and 0xF8) ushr 3
    }

    /** Full decode → (sampleRate, totalPcmSamples, peakAmplitude). */
    private fun decodeToPcm(file: File): Triple<Int, Long, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val trackFormat = extractor.getTrackFormat(0)
        val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        decoder.configure(trackFormat, null, null, 0)
        decoder.start()
        val info = android.media.MediaCodec.BufferInfo()
        var totalSamples = 0L
        var peak = 0
        var inputEos = false
        var outputEos = false
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        try {
            while (!outputEos) {
                assertTrue("decoder stalled", SystemClock.elapsedRealtime() < deadline)
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
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> {
                        assertTrue("decoder output index must be valid", outIndex >= 0)
                        if (info.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            outBuf.asShortBuffer().get(shorts)
                            totalSamples += shorts.size
                            for (s in shorts) {
                                val a = abs(s.toInt())
                                if (a > peak) peak = a
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            extractor.release()
        }
        return Triple(sampleRate, totalSamples, peak)
    }

    @Test
    fun encodeAfterStopThrowsAndZeroLengthIsNoOp() {
        tempDir.mkdirs()
        val out = File(tempDir, "edge.m4a")
        val encoder = M4aAudioEncoder(out)
        encoder.encodePcmShorts(ShortArray(0), 0) // zero length: no-op while open
        val pcm = tonePcm32k(0.2)
        encoder.encodePcmShorts(pcm, pcm.size)
        encoder.stopAndRelease()
        try {
            encoder.encodePcmShorts(pcm, pcm.size)
            fail("encode after stop must throw")
        } catch (expected: IllegalStateException) {
        }
    }
}
