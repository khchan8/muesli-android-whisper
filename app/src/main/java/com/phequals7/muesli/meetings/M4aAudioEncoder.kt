package com.phequals7.muesli.meetings

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Streaming mono 32 kHz PCM16 → AAC-LC 64 kbps `.m4a` encoder
 * (MediaCodec + MPEG-4 MediaMuxer) for retained meeting audio.
 *
 * The caller reserves an empty destination file; this class never deletes
 * or overwrites a pre-existing recording. Every codec interaction is bound
 * by a 10 s no-progress watchdog so a wedged vendor encoder surfaces as an
 * exception instead of an infinite loop.
 */
class M4aAudioEncoder(private val outputFile: File) {

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 32_000
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 64_000
        private const val TIMEOUT_US = 10_000L
        private const val NO_PROGRESS_TIMEOUT_MS = 10_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var released = false
    private var eosQueued = false
    private var totalSamples = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val format = audioFormat()
        var openedCodec: MediaCodec? = null
        try {
            openedCodec = createConfiguredEncoder(format)
            openedCodec.start()
            codec = openedCodec
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            openedCodec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { muxer?.release() }
            codec = null
            muxer = null
            throw IllegalStateException("AAC encoder could not be started: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun audioFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

    /** Compatible AAC encoder names; hardware-accelerated first on API 29+,
     *  otherwise the platform's own preference order. */
    private fun compatibleEncoderNames(): List<String> {
        val format = audioFormat()
        val hardware = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME_TYPE, ignoreCase = true) }) continue
            val capabilities = try {
                info.getCapabilitiesForType(MIME_TYPE)
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (!capabilities.isFormatSupported(format)) continue
            if (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated) hardware.add(info.name)
            else other.add(info.name)
        }
        return if (Build.VERSION.SDK_INT >= 29) hardware + other else other
    }

    private fun createConfiguredEncoder(format: MediaFormat): MediaCodec {
        var lastError: Throwable? = null
        for (name in compatibleEncoderNames()) {
            try {
                val candidate = MediaCodec.createByCodecName(name)
                try {
                    candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return candidate
                } catch (t: Throwable) {
                    runCatching { candidate.release() }
                    lastError = t
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "No compatible AAC encoder for 32 kHz mono AAC-LC 64 kbps",
            lastError
        )
    }

    private fun ptsUs(): Long = totalSamples * 1_000_000L / SAMPLE_RATE

    /**
     * Queues [length] little-endian mono 32 kHz PCM shorts. Zero-length is a
     * no-op while open; calling after [stopAndRelease] throws.
     */
    fun encodePcmShorts(pcm32k: ShortArray, length: Int) {
        check(!released) { "M4A encoder already stopped" }
        require(length in 0..pcm32k.size) { "length $length outside 0..${pcm32k.size}" }
        if (length == 0) return
        val activeCodec = requireNotNull(codec) { "M4A encoder already closed" }
        var offset = 0
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (offset < length) {
            val inputIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = activeCodec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException("AAC encoder returned a null input buffer")
                buffer.clear()
                val maxShorts = (buffer.remaining() / 2).coerceAtMost(length - offset)
                if (maxShorts <= 0) {
                    throw IllegalStateException("AAC encoder input buffer has zero capacity")
                }
                for (i in 0 until maxShorts) {
                    val s = pcm32k[offset + i].toInt()
                    buffer.put((s and 0xFF).toByte())
                    buffer.put((s shr 8 and 0xFF).toByte())
                }
                activeCodec.queueInputBuffer(inputIndex, 0, maxShorts * 2, ptsUs(), 0)
                totalSamples += maxShorts
                offset += maxShorts
                lastProgressAt = SystemClock.elapsedRealtime()
            }
            drainOutput(activeCodec, endOfStream = false)
            if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                throw IllegalStateException("AAC encoder stalled for ${NO_PROGRESS_TIMEOUT_MS / 1000}s during capture")
            }
        }
    }

    /**
     * Queues EOS (successfully, before entering the final drain), drains the
     * remaining output, and releases codec + muxer. Safe to call repeatedly.
     * A failed finalize throws so the caller never reports an invalid file as
     * an exportable M4A.
     */
    fun stopAndRelease() {
        if (released) return
        released = true
        var primary: Throwable? = null
        val activeCodec = codec
        val activeMuxer = muxer
        if (activeCodec != null) {
            try {
                var lastProgressAt = SystemClock.elapsedRealtime()
                while (!eosQueued) {
                    val inputIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        activeCodec.queueInputBuffer(
                            inputIndex, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    } else {
                        // Input temporarily unavailable: make progress by draining output.
                        drainOutput(activeCodec, endOfStream = false)
                    }
                    if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                        throw IllegalStateException("AAC encoder stalled for ${NO_PROGRESS_TIMEOUT_MS / 1000}s while queueing end of stream")
                    }
                }
                lastProgressAt = SystemClock.elapsedRealtime()
                while (!drainOutput(activeCodec, endOfStream = true)) {
                    if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                        throw IllegalStateException("AAC encoder did not finish within ${NO_PROGRESS_TIMEOUT_MS / 1000}s")
                    }
                }
            } catch (t: Throwable) {
                primary = t
            } finally {
                codec = null
                try {
                    activeCodec.stop()
                } catch (t: Throwable) {
                    if (primary == null) primary = t
                }
                try {
                    activeCodec.release()
                } catch (t: Throwable) {
                    if (primary == null) primary = t
                }
            }
        }
        if (activeMuxer != null) {
            muxer = null
            try {
                if (muxerStarted) activeMuxer.stop()
            } catch (t: Throwable) {
                if (primary == null) primary = t
            } finally {
                try {
                    activeMuxer.release()
                } catch (t: Throwable) {
                    if (primary == null) primary = t
                }
            }
            muxerStarted = false
        }
        primary?.let { throw it }
    }

    /**
     * Drains encoded output into the muxer. Returns true when the EOS output
     * buffer was seen. The muxer track is added/started once, on the first
     * output format change; codec-config buffers are skipped.
     */
    private fun drainOutput(activeCodec: MediaCodec, endOfStream: Boolean): Boolean {
        val activeMuxer = requireNotNull(muxer) { "M4A muxer already released" }
        while (true) {
            val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        audioTrackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    try {
                        if (muxerStarted && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            activeCodec.getOutputBuffer(outIndex)?.let { encoded ->
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                activeMuxer.writeSampleData(audioTrackIndex, encoded, bufferInfo)
                            }
                        }
                    } finally {
                        activeCodec.releaseOutputBuffer(outIndex, false)
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
            }
        }
    }
}
