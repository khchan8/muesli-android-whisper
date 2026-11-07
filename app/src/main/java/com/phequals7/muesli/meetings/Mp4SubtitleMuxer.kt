package com.phequals7.muesli.meetings

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.coremedia.iso.boxes.sampleentry.TextSampleEntry
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.TextTrackImpl
import com.googlecode.mp4parser.boxes.threegpp26245.FontTableBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.io.File
import java.util.Arrays
import kotlin.math.roundToLong

/**
 * Muesli v2.3 embedded-subtitle MP4 muxer.
 *
 * Muxes an existing, stable AAC M4A with a generated 640×360 dark-slate
 * 1 fps H.264 (baseline) canvas and a `tx3g` timed-text track built from
 * the persisted diarized transcript:
 *
 *  - Audio is passed through byte-identical (samples, config, timing, edit
 *    list) — never re-encoded.
 *  - Subtitle cues come only from transcript segments
 *    ([MeetingTranscriptSrt.normalizedSegments] + [MeetingTranscriptSrt.cueText]);
 *    notes and AI summaries stay in the standalone SRT.
 *  - Overlaps are resolved by a boundary sweep (ending cues close before
 *    starting cues open; simultaneous cues stack in stable order), gaps get
 *    empty samples so old subtitles clear, and a terminal blank stretches
 *    the text track to the audio duration.
 *
 * Failures throw descriptively; cancellation propagates. The caller owns a
 * finally-close of [m4aChannel] (closing twice is a no-op) and of the
 * unique cache directory holding [outputMp4File].
 */
object Mp4SubtitleMuxer {
    private const val TAG = "Mp4SubtitleMuxer"
    internal const val VIDEO_WIDTH = 640
    internal const val VIDEO_HEIGHT = 360
    private const val VIDEO_FPS = 1
    private const val VIDEO_BITRATE = 16_000
    private const val I_FRAME_INTERVAL_S = 10
    private const val TIMEOUT_US = 10_000L
    private const val NO_PROGRESS_TIMEOUT_MS = 10_000L
    private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    /** TextTrackImpl writes a 16-bit UTF-8 byte count per sample. */
    internal const val MAX_TX3G_TEXT_BYTES = 65_535

    /**
     * Muxes [m4aChannel] (a readable, nonempty, finalized AAC M4A with
     * exactly one audio track) plus [segments] into [outputMp4File].
     *
     * [outputMp4File] must not exist yet and lives in a caller-reserved
     * unique cache directory. Returns normally only after the written file
     * was flushed, fsynced, reopened and validated.
     */
    suspend fun muxM4aAndSegmentsToMp4(
        m4aChannel: FileChannel,
        segments: List<DiarizedTranscriptSegment>,
        outputMp4File: File,
    ): Unit = withContext(Dispatchers.IO) {
        check(!outputMp4File.exists()) { "MP4 output already exists: ${outputMp4File.name}" }
        check(m4aChannel.size() > 0L) { "Source M4A is empty" }

        val audioSource = FileDataSourceImpl(m4aChannel, "meeting.m4a")
        var canvasSource: FileDataSourceImpl? = null
        var verifySource: FileDataSourceImpl? = null
        val canvasFile = File(outputMp4File.parentFile, "canvas.mp4")
        try {
            // 1. Audio passthrough validation + real duration from the track timeline.
            val audioMovie = MovieCreator.build(audioSource)
            val audioTrack = audioMovie.tracks.filter { it.handler == "soun" }.let { tracks ->
                check(tracks.size == 1) {
                    "Expected exactly one audio track in the source M4A, found ${tracks.size}"
                }
                tracks[0]
            }
            check(audioTrack.sampleDescriptionBox.boxes.singleOrNull()?.type == "mp4a") {
                "Source audio track is not AAC"
            }
            val audioTimescale = audioTrack.trackMetaData.timescale
            check(audioTimescale > 0) { "Source audio track has no timescale" }
            val audioDurationTicks = audioTrack.duration
            check(audioDurationTicks > 0) { "Source audio track has no duration" }
            val durationSeconds = audioTrack.edits.takeIf { !it.isNullOrEmpty() }
                ?.sumOf { it.segmentDuration }
                ?: (audioDurationTicks.toDouble() / audioTimescale)
            check(durationSeconds.isFinite() && durationSeconds > 0) { "Invalid audio presentation duration" }
            val audioDurationMs = (durationSeconds * 1000).roundToLong()
            check(audioDurationMs > 0) { "Audio duration is shorter than one millisecond" }
            val audioSampleCount = audioTrack.samples.size
            check(audioSampleCount > 0) { "Source audio track has no samples" }

            // 2. Subtitle timeline (transcript cues only; gaps blank; ends at audio end).
            val timeline = buildSubtitleCueTimeline(segments, audioDurationMs)

            // 3. Encode the AVC canvas into the same cache directory.
            encodeCanvasTrack(audioDurationMs, canvasFile)
            canvasSource = FileDataSourceImpl(canvasFile)
            val rawVideoTrack = MovieCreator.build(canvasSource!!)
                .tracks.singleOrNull { it.handler == "vide" }
                ?: throw IllegalStateException("Canvas file has no video track")
            val videoTimescale = rawVideoTrack.getTrackMetaData().timescale
            check(videoTimescale > 0) { "Canvas video track has no timescale" }
            val targetVideoTicks = (durationSeconds * videoTimescale).roundToLong()
            val videoTrack = CanvasDurationTrack(rawVideoTrack, targetVideoTicks).also {
                it.getTrackMetaData().apply {
                    width = VIDEO_WIDTH.toDouble()
                    height = VIDEO_HEIGHT.toDouble()
                    layer = 0
                    language = "und"
                }
            }
            val videoSampleCount = videoTrack.samples.size

            // 4. tx3g text track.
            val textTrack = buildTextTrack(timeline)

            // 5. Assemble and write. mp4parser copies the AAC samples and
            //    config verbatim; Movie.addTrack dedupes track IDs.
            val movie = Movie()
            movie.addTrack(videoTrack)
            movie.addTrack(audioTrack)
            movie.addTrack(textTrack)
            val container = DefaultMp4Builder().build(movie)
            FileOutputStream(outputMp4File).use { out ->
                container.writeContainer(out.channel)
                out.flush()
                out.fd.sync()
            }

            // 6. Reopen and validate before the caller publishes anything.
            verifySource = FileDataSourceImpl(outputMp4File)
            val verified = MovieCreator.build(verifySource)
            val handlers = verified.tracks.groupBy { it.handler }
            check(handlers.keys.count { it == "vide" || it == "soun" || it == "sbtl" } == 3 &&
                handlers["vide"]?.size == 1 && handlers["soun"]?.size == 1 && handlers["sbtl"]?.size == 1
            ) { "Written MP4 does not contain exactly one video, audio and text track" }
            val outVideo = handlers.getValue("vide")[0]
            val outAudio = handlers.getValue("soun")[0]
            val outText = handlers.getValue("sbtl")[0]
            val videoMs = outVideo.duration * 1000.0 / outVideo.getTrackMetaData().timescale
            check(kotlin.math.abs(videoMs - audioDurationMs) <= 1.0) {
                "Video track duration $videoMs ms differs from audio $audioDurationMs ms"
            }
            val textMs = outText.duration * 1000.0 / outText.getTrackMetaData().timescale
            check(kotlin.math.abs(textMs - audioDurationMs) <= 1.0) {
                "Subtitle track duration $textMs ms differs from audio $audioDurationMs ms"
            }
            check(outVideo.samples.size == videoSampleCount && videoSampleCount > 0) {
                "Video sample count mismatch after write"
            }
            check(outAudio.samples.size == audioSampleCount) { "Audio sample count mismatch after write" }
            val outTextCount = outText.samples.size
            check(outTextCount == timeline.size && outTextCount > 0) {
                "Subtitle sample count mismatch after write ($outTextCount != ${timeline.size})"
            }
            (outVideo.getSampleDescriptionBox().boxes.firstOrNull() as? VisualSampleEntry)?.let { entry ->
                check(entry.width == VIDEO_WIDTH && entry.height == VIDEO_HEIGHT) {
                    "Written video is ${entry.width}×${entry.height}, expected ${VIDEO_WIDTH}×$VIDEO_HEIGHT"
                }
            } ?: check(false) { "Written video track has no visual sample entry" }
        } finally {
            runCatching { audioSource.close() } // also closes m4aChannel (idempotent)
            runCatching { canvasSource?.close() }
            runCatching { verifySource?.close() }
            canvasFile.delete()
        }
    }

    // ── cue timeline (pure, JVM-testable) ────────────────────────────────

    /** One non-overlapping display interval; empty [text] clears subtitles. */
    internal data class Mp4TimedCue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Boundary-sweep overlap resolution over the normalized transcript cues,
     * clamped to `[0, audioDurationMs]`:
     *  - every interval shows all active cue texts joined by `\n` in stable
     *    start/order order;
     *  - at a shared boundary ending cues win over starting cues;
     *  - adjacent intervals with identical text merge;
     *  - gaps become empty cues and the timeline always covers the whole
     *    `[0, audioDurationMs]` span (a silent transcript yields a single
     *    full-duration empty cue).
     */
    internal fun buildSubtitleCueTimeline(
        segments: List<DiarizedTranscriptSegment>,
        audioDurationMs: Long,
    ): List<Mp4TimedCue> {
        require(audioDurationMs > 0) { "audioDurationMs must be positive" }
        data class ClampedCue(val startMs: Long, val endMs: Long, val text: String)
        val cues = MeetingTranscriptSrt.normalizedSegments(segments)
            .mapNotNull { seg ->
                val start = seg.startMs.coerceAtLeast(0L)
                val end = seg.endMs.coerceAtMost(audioDurationMs)
                val text = MeetingTranscriptSrt.cueText(seg)
                if (start < audioDurationMs && end > start && text.isNotEmpty()) {
                    ClampedCue(start, end, text)
                } else null
            }

        if (cues.isEmpty()) return listOf(Mp4TimedCue(0, audioDurationMs, ""))

        data class Event(val time: Long, val index: Int, val starts: Boolean)
        val events = cues.flatMapIndexed { index, cue ->
            listOf(Event(cue.startMs, index, true), Event(cue.endMs, index, false))
        }.sortedWith(compareBy<Event>({ it.time }, { it.starts }, { it.index }))
        val boundaries = (events.map { it.time } + listOf(0L, audioDurationMs)).distinct().sorted()
        val active = java.util.TreeSet<Int>()
        var eventIndex = 0
        val intervals = mutableListOf<Mp4TimedCue>()
        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            while (eventIndex < events.size && events[eventIndex].time == from) {
                val event = events[eventIndex++]
                if (event.starts) active.add(event.index) else active.remove(event.index)
            }
            val text = active.joinToString("\n") { cues[it].text }
            if (intervals.isNotEmpty() && intervals.last().text == text) {
                intervals[intervals.size - 1] = intervals.last().copy(endMs = to)
            } else {
                intervals.add(Mp4TimedCue(from, to, text))
            }
        }
        // Terminal blank so the last cue clears exactly at the audio end.
        val lastEnd = intervals.last().endMs
        if (lastEnd < audioDurationMs) intervals.add(Mp4TimedCue(lastEnd, audioDurationMs, ""))
        return intervals
    }

    /**
     * SHA-256 over a length-prefixed canonical form of the fingerprint
     * format version, session id, immutable private audio length/mtime, and
     * the normalized `(startMs, endMs, cueText)` tuples. Title, notes,
     * summary and non-rendered speaker metadata are deliberately excluded:
     * editing them must not re-run video encoding.
     */
    internal fun mp4TranscriptFingerprint(
        sessionId: String,
        audioLength: Long,
        audioLastModifiedMs: Long,
        segments: List<DiarizedTranscriptSegment>,
    ): String {
        fun java.io.DataOutputStream.lenUtf8(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
        val canonical = java.io.ByteArrayOutputStream().use { buffer ->
            java.io.DataOutputStream(buffer).use { out ->
                out.writeInt(1) // fingerprint format version
                out.lenUtf8(sessionId)
                out.writeLong(audioLength)
                out.writeLong(audioLastModifiedMs)
                val cues = MeetingTranscriptSrt.normalizedSegments(segments)
                out.writeInt(cues.size)
                for (cue in cues) {
                    out.writeLong(cue.startMs)
                    out.writeLong(cue.endMs)
                    out.lenUtf8(MeetingTranscriptSrt.cueText(cue))
                }
            }
            buffer.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        return digest.joinToString("") { "%02x".format(it) }
     }

    // ── tx3g text track ──────────────────────────────────────────────────

    /** TS 26.245 justification: 0 = left/top, 1 = centered, -1 = bottom/right. */
    private const val JUSTIFY_CENTER = 1
    private const val JUSTIFY_BOTTOM = -1
    private const val FONT_ID = 1
    private const val FONT_SIZE_PX = 24
    private const val FONT_NAME = "SansSerif"

    private fun buildTextTrack(timeline: List<Mp4TimedCue>): TextTrackImpl {
        val track = TextTrackImpl()
        track.getTrackMetaData().apply {
            language = "und"
            layer = -1 // in front of the canvas video (layer 0)
            width = VIDEO_WIDTH.toDouble()
            height = VIDEO_HEIGHT.toDouble()
        }
        val tx3g = track.getSampleDescriptionBox().boxes.firstOrNull() as? TextSampleEntry
            ?: throw IllegalStateException("TextTrackImpl sample entry missing")
        tx3g.horizontalJustification = JUSTIFY_CENTER
        tx3g.verticalJustification = JUSTIFY_BOTTOM
        tx3g.boxRecord = TextSampleEntry.BoxRecord(240, 24, 336, 616)
        tx3g.styleRecord = TextSampleEntry.StyleRecord(
            0, 0, FONT_ID, 0, FONT_SIZE_PX, intArrayOf(0xFF, 0xFF, 0xFF, 0xFF)
        )
        // The constructor already added one ftab; retarget it instead of a second box.
        tx3g.getBoxes(FontTableBox::class.java).firstOrNull()
            ?.setEntries(listOf(FontTableBox.FontRecord(FONT_ID, FONT_NAME)))
            ?: tx3g.addBox(FontTableBox().apply {
                setEntries(listOf(FontTableBox.FontRecord(FONT_ID, FONT_NAME)))
            })
        for (cue in timeline) {
            val textBytes = cue.text.toByteArray(Charsets.UTF_8).size
            check(textBytes <= MAX_TX3G_TEXT_BYTES) {
                "Subtitle cue at ${cue.startMs} ms is $textBytes UTF-8 bytes; " +
                    "tx3g supports at most $MAX_TX3G_TEXT_BYTES"
            }
            track.subs.add(TextTrackImpl.Line(cue.startMs, cue.endMs, cue.text))
        }
        return track
    }

    /** Video track whose last sample duration lands the track exactly on the
     *  audio timeline instead of the next whole second. */
    private class CanvasDurationTrack(
        private val source: Track,
        private val targetDurationTicks: Long,
    ) : Track by source {
        private val adjusted: LongArray by lazy {
            val durations = source.sampleDurations
            check(durations.isNotEmpty()) { "Canvas video track has no samples" }
            val head = durations.copyOf(durations.size)
            val headSum = head.copyOf(head.size - 1).sum()
            val last = targetDurationTicks - headSum
            check(last > 0) { "Audio duration shorter than canvas frame timestamps" }
            head[head.size - 1] = last
            head
        }

        override fun getSampleDurations(): LongArray = adjusted.copyOf()
        override fun getDuration(): Long = adjusted.sum()
    }

    // ── AVC canvas encoding ──────────────────────────────────────────────

    private fun canvasFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MIME_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

    /** Compatible AVC encoder names; hardware-accelerated first on API 29+,
     *  platform software fallback last (same pattern as the AAC encoder). */
    private fun compatibleEncoderNames(): List<String> {
        val format = canvasFormat()
        val hardware = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME_AVC, ignoreCase = true) }) continue
            val capabilities = try {
                info.getCapabilitiesForType(MIME_AVC)
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (!capabilities.isFormatSupported(format)) continue
            if (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated) hardware.add(info.name)
            else other.add(info.name)
        }
        return if (Build.VERSION.SDK_INT >= 29) hardware + other else other
    }

    /** One dark-slate frame per whole second strictly before [durationMs]. */
    private fun expectedFrameCount(durationMs: Long): Int = ((durationMs + 999) / 1000).toInt()

    private suspend fun encodeCanvasTrack(durationMs: Long, canvasFile: File) {
        check(!canvasFile.exists() || canvasFile.delete()) { "Stale canvas file present" }
        val format = canvasFormat()
        var lastError: Throwable? = null
        for (name in compatibleEncoderNames()) {
            try {
                encodeCanvasWith(name, format, durationMs, canvasFile)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "AVC encoder $name failed for the canvas; trying next", t)
                lastError = t
                canvasFile.delete()
            }
        }
        throw IllegalStateException(
            "No usable AVC encoder for a ${VIDEO_WIDTH}×$VIDEO_HEIGHT ${VIDEO_FPS} fps canvas",
            lastError
        )
    }

    private suspend fun encodeCanvasWith(
        encoderName: String,
        format: MediaFormat,
        durationMs: Long,
        canvasFile: File,
    ) {
        val totalFrames = expectedFrameCount(durationMs).coerceAtLeast(1)
        val codec = MediaCodec.createByCodecName(encoderName)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var primary: Throwable? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(canvasFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val rowBuffer = ByteArray(VIDEO_WIDTH * 2)
            var trackIndex = -1
            var framesWritten = 0
            var nextFrame = 0
            var eosSeen = false
            var lastProgressAt = SystemClock.elapsedRealtime()

            fun drain(endOfStream: Boolean): Boolean {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer!!.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            try {
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                                    val out = codec.getOutputBuffer(outIndex)
                                    if (out != null) {
                                        out.position(bufferInfo.offset)
                                        out.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer.writeSampleData(trackIndex, out, bufferInfo)
                                        framesWritten++
                                    }
                                }
                            } finally {
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                            lastProgressAt = SystemClock.elapsedRealtime()
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                        }
                    }
                }
            }

            // Feed one frame at 0 and every whole second strictly before the
            // audio end; cancel checks run between buffer handoffs.
            while (nextFrame < totalFrames) {
                currentCoroutineContext().ensureActive()
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    try {
                        val size = codec.getInputBuffer(inIndex)?.capacity()
                            ?: throw IllegalStateException("Encoder $encoderName exposes no input buffer")
                        val image = codec.getInputImage(inIndex)
                            ?: throw IllegalStateException("Encoder $encoderName exposes no writable input images")
                        fillDarkSlate(image, rowBuffer)
                        codec.queueInputBuffer(inIndex, 0, size, nextFrame * 1_000_000L, 0)
                        nextFrame++
                        lastProgressAt = SystemClock.elapsedRealtime()
                    } catch (t: Throwable) {
                        runCatching { codec.queueInputBuffer(inIndex, 0, 0, 0, 0) }
                        throw t
                    }
                }
                drain(endOfStream = false)
                if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                    throw IllegalStateException("AVC canvas encoder stalled for ${NO_PROGRESS_TIMEOUT_MS / 1000}s")
                }
            }

            // EOS marker + final drain.
            while (true) {
                currentCoroutineContext().ensureActive()
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(
                        inIndex, 0, 0, durationMs * 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    lastProgressAt = SystemClock.elapsedRealtime()
                    break
                }
                drain(endOfStream = false)
                if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                    throw IllegalStateException("AVC canvas encoder stalled while queueing end of stream")
                }
            }
            while (!eosSeen) {
                currentCoroutineContext().ensureActive()
                eosSeen = drain(endOfStream = true)
                if (SystemClock.elapsedRealtime() - lastProgressAt > NO_PROGRESS_TIMEOUT_MS) {
                    throw IllegalStateException("AVC canvas encoder did not finish within ${NO_PROGRESS_TIMEOUT_MS / 1000}s")
                }
            }

            check(muxerStarted) { "Encoder produced no output format" }
            check(framesWritten == totalFrames) {
                "AVC canvas encoded $framesWritten of $totalFrames expected frames"
            }
        } catch (t: Throwable) {
            primary = t
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (t: Throwable) {
                if (primary == null) primary = t
            } finally {
                runCatching { muxer?.release() }
            }
        }
        primary?.let { throw it }
    }

    /** Fills Y=24, U=V=128 (BT.601 near-black slate) using each plane's real
     *  row/pixel strides; chroma planes sharing one interleaved buffer stay
     *  valid because both carry the same 128 value. */
    private fun fillDarkSlate(image: android.media.Image, yRow: ByteArray) {
        val crop = image.cropRect
        for ((planeIndex, plane) in image.planes.withIndex()) {
            val planeWidth = if (planeIndex == 0) crop.width() else (crop.width() + 1) / 2
            val planeHeight = if (planeIndex == 0) crop.height() else (crop.height() + 1) / 2
            val value = if (planeIndex == 0) 24 else 128
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val span = planeWidth * pixelStride
            val divisor = if (planeIndex == 0) 1 else 2
            val base = (crop.top / divisor) * rowStride + (crop.left / divisor) * pixelStride
            check(span <= yRow.size) { "Unexpected plane geometry" }
            Arrays.fill(yRow, 0, span, value.toByte())
            for (row in 0 until planeHeight) {
                val position = base + row * rowStride
                check(position >= 0 && position + (planeWidth - 1) * pixelStride < buffer.limit()) {
                    "Encoder image plane does not contain the full crop"
                }
                val writeSpan = minOf(span, buffer.limit() - position)
                buffer.position(position)
                buffer.put(yRow, 0, writeSpan)
            }
        }
    }
}
