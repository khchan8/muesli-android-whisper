package com.phequals7.muesli.meetings

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.coremedia.iso.boxes.Container
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.TextTrackImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Muesli v2.3 — Option 1 Embedded Subtitle MP4 Muxer (`Mp4SubtitleMuxer.kt`).
 *
 * Produces `<baseName>.mp4` alongside `<baseName>.m4a` and `<baseName>.srt` in `/sdcard/Recordings/Muesli/`:
 * 1. Track 1 (Video Canvas): Generates an ultra-lightweight 1-fps 640x360 dark slate H.264 (`avc1`) video
 *    track using Android's hardware `MediaCodec` so ALL video players (VLC, MX Player, Android Gallery,
 *    Windows Media Player, QuickTime) open a video canvas and render the embedded subtitles on screen.
 * 2. Track 2 (Audio): Losslessly copies the existing 32 kHz AAC-LC (`mp4a`) audio track from `<baseName>.m4a`
 *    without re-encoding.
 * 3. Track 3 (Subtitles): Embeds the speaker-diarized transcript (`<baseName>.srt` or `List<DiarizedTranscriptSegment>`)
 *    as an official MPEG-4 Part 17 `tx3g` (3GPP Timed Text, UTF-8 for English, Thai, and Chinese) soft-subtitle track.
 * 4. Excludes AI Summary text (only the speaker-attributed transcript blocks are embedded as timed subtitles).
 *
 * Gradle Dependency Required in `app/build.gradle.kts`:
 *     implementation("com.googlecode.mp4parser:isoparser:1.1.22")
 */
object Mp4SubtitleMuxer {
    private const val TAG = "Mp4SubtitleMuxer"
    private const val VIDEO_WIDTH = 640
    private const val VIDEO_HEIGHT = 360
    private const val VIDEO_FPS = 1
    private const val VIDEO_BITRATE = 16_000 // ~16 kbps (~120 KB per minute)
    private const val TIMEOUT_US = 10_000L

    private val SRT_BLOCK_REGEX = Regex(
        pattern = "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*\\r?\\n([\\s\\S]*?)(?=(\\r?\\n){2,}|\\z)"
    )

    /**
     * Remuxes an existing `<baseName>.m4a` and `<baseName>.srt` (or `segments`) into `<baseName>.mp4`
     * with a 1-fps dark video canvas and an embedded `tx3g` UTF-8 subtitle track.
     */
    suspend fun muxM4aAndSrtToMp4(
        m4aFile: File,
        srtFile: File,
        outputMp4File: File,
        segments: List<DiarizedTranscriptSegment>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!m4aFile.exists() || m4aFile.length() == 0L) {
            Log.e(TAG, "Source .m4a file does not exist: ${m4aFile.absolutePath}")
            return@withContext false
        }

        val tempVideoFile = File(outputMp4File.parentFile, "${outputMp4File.nameWithoutExtension}_temp_canvas.mp4")
        try {
            // 1. Determine total audio duration in milliseconds from the .m4a file
            val durationMs = getAudioDurationMs(m4aFile).coerceAtLeast(1000L)

            // 2. Generate the ultra-small 1-fps 640x360 dark slate H.264 canvas track
            val videoOk = generateBlankH264CanvasMp4(
                tempVideoFile = tempVideoFile,
                durationMs = durationMs
            )
            if (!videoOk || !tempVideoFile.exists()) {
                Log.e(TAG, "Failed to generate 1-fps H.264 canvas track")
                return@withContext false
            }

            // 3. Build the MPEG-4 Movie combining Video + M4A Audio + tx3g Subtitles
            val finalMovie = Movie()

            val videoMovie = MovieCreator.build(tempVideoFile.absolutePath)
            for (track in videoMovie.tracks) {
                if (track.handler == "vide") {
                    finalMovie.addTrack(track)
                }
            }

            val audioMovie = MovieCreator.build(m4aFile.absolutePath)
            for (track in audioMovie.tracks) {
                if (track.handler == "soun") {
                    finalMovie.addTrack(track)
                }
            }

            // 4. Construct the tx3g Timed Text subtitle track from segments or .srt file
            val textTrack = buildTx3gSubtitleTrack(
                srtFile = srtFile,
                segments = segments,
                totalDurationMs = durationMs
            )
            if (textTrack.subs.isNotEmpty()) {
                finalMovie.addTrack(textTrack)
            }

            // 5. Write the final container to `<baseName>.mp4`
            outputMp4File.parentFile?.mkdirs()
            if (outputMp4File.exists()) outputMp4File.delete()

            val container: Container = DefaultMp4Builder().build(finalMovie)
            FileOutputStream(outputMp4File).use { fos ->
                container.writeContainer(fos.channel)
            }

            Log.i(TAG, "Successfully muxed embedded subtitle MP4: ${outputMp4File.absolutePath} (${outputMp4File.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to mux .m4a + .srt into .mp4", t)
            false
        } finally {
            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }
        }
    }

    /**
     * Builds a `TextTrackImpl` (`tx3g` 3GPP Timed Text track) supporting UTF-8 English, Thai, and Chinese.
     * Note: `mp4parser` requires subtitle cues to be strictly non-overlapping and sorted by `startMs`.
     */
    private fun buildTx3gSubtitleTrack(
        srtFile: File,
        segments: List<DiarizedTranscriptSegment>?,
        totalDurationMs: Long
    ): TextTrackImpl {
        val textTrack = TextTrackImpl()
        textTrack.trackMetaData.language = "und" // Undetermined/multilingual (EN/TH/ZH)

        data class RawCue(val startMs: Long, val endMs: Long, val text: String)

        val rawCues = if (!segments.isNullOrEmpty()) {
            segments.map { seg ->
                RawCue(
                    startMs = seg.startMs.coerceAtLeast(0L),
                    endMs = seg.endMs.coerceAtLeast(seg.startMs + 400L),
                    text = "${seg.speakerLabel}: ${seg.text.trim()}"
                )
            }
        } else if (srtFile.exists()) {
            parseSrtFileToCues(srtFile).map { (start, end, txt) ->
                RawCue(start, end, txt)
            }
        } else {
            emptyList()
        }

        val sorted = rawCues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        var lastEndMs = 0L

        for (cue in sorted) {
            val start = cue.startMs.coerceAtLeast(lastEndMs)
            val end = cue.endMs.coerceAtMost(totalDurationMs).coerceAtLeast(start + 300L)
            if (start < totalDurationMs && end > start) {
                textTrack.subs.add(TextTrackImpl.Line(start, end, cue.text))
                lastEndMs = end
            }
        }

        return textTrack
    }

    private fun parseSrtFileToCues(srtFile: File): List<Triple<Long, Long, String>> {
        val content = runCatching { srtFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (content.isBlank()) return emptyList()

        val results = mutableListOf<Triple<Long, Long, String>>()
        for (match in SRT_BLOCK_REGEX.findAll(content)) {
            val h1 = match.groupValues[1].toLongOrNull() ?: 0L
            val m1 = match.groupValues[2].toLongOrNull() ?: 0L
            val s1 = match.groupValues[3].toLongOrNull() ?: 0L
            val ms1 = match.groupValues[4].toLongOrNull() ?: 0L

            val h2 = match.groupValues[5].toLongOrNull() ?: 0L
            val m2 = match.groupValues[6].toLongOrNull() ?: 0L
            val s2 = match.groupValues[7].toLongOrNull() ?: 0L
            val ms2 = match.groupValues[8].toLongOrNull() ?: 0L

            val text = match.groupValues[9].trim()
            val startMs = ((h1 * 3600L + m1 * 60L + s1) * 1000L) + ms1
            val endMs = ((h2 * 3600L + m2 * 60L + s2) * 1000L) + ms2
            if (text.isNotEmpty() && endMs > startMs) {
                results.add(Triple(startMs, endMs, text))
            }
        }
        return results
    }

    private fun getAudioDurationMs(m4aFile: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(m4aFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(1000L)
                }
            }
            5000L
        } catch (_: Throwable) {
            5000L
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Uses Android's hardware AVC encoder (`video/avc`) to encode a 1-fps 640x360 dark navy/slate
     * YUV420 frame stream matching `durationMs`.
     */
    private fun generateBlankH264CanvasMp4(tempVideoFile: File, durationMs: Long): Boolean {
        if (tempVideoFile.exists()) tempVideoFile.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1

            // Pre-build one 640x360 YUV420 frame filled with dark slate (Y=24, U=128, V=128)
            val ySize = VIDEO_WIDTH * VIDEO_HEIGHT
            val uvSize = ySize / 2
            val darkYuvFrame = ByteArray(ySize + uvSize).apply {
                java.util.Arrays.fill(this, 0, ySize, 24.toByte())
                java.util.Arrays.fill(this, ySize, ySize + uvSize, 128.toByte())
            }

            val totalSeconds = ((durationMs + 999L) / 1000L).toInt().coerceAtLeast(1)
            var frameIndex = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        if (frameIndex < totalSeconds && inBuf != null) {
                            val bytesToWrite = darkYuvFrame.size.coerceAtMost(inBuf.remaining())
                            inBuf.put(darkYuvFrame, 0, bytesToWrite)
                            val ptsUs = frameIndex * 1_000_000L
                            codec.queueInputBuffer(inIdx, 0, bytesToWrite, ptsUs, 0)
                            frameIndex++
                        } else {
                            val eosPtsUs = totalSeconds * 1_000_000L
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                eosPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Hardware H.264 canvas generation failed", t)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }
}