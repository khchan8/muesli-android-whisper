package com.phequals7.muesli.meetings

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Shared reactive controller bridging `MeetingRecorderService` and the Jetpack Compose UI.
 */
object MeetingRecordingController {
    enum class Status {
        IDLE,
        RECORDING,
        FINALIZING_DIARIZATION,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class MeetingRecordingState(
        val sessionId: String? = null,
        val title: String = "New Meeting",
        val status: Status = Status.IDLE,
        val elapsedMs: Long = 0L,
        val currentRmsLevel: Float = 0f,
        val audioFilePath: String? = null,
        val expectedSpeakerCount: Int = -1, // -1 = Auto, or 2, 3, 4, 5+
        val languageHint: String = "auto",  // "auto", "en", "th", "zh", "yue"
        val liveChunks: List<RawTranscriptChunk> = emptyList(),
        val finalizedSegments: List<DiarizedTranscriptSegment> = emptyList(),
        val diarizationProgress: Float = 0f,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(MeetingRecordingState())
    val state: StateFlow<MeetingRecordingState> = _state.asStateFlow()

    // Pluggable transcription callback hooked up to SherpaOnnxEngine (Qwen3-ASR / Whisper / SenseVoice+Zipformer-Thai)
    var chunkTranscriber: (suspend (FloatArray, String) -> Pair<String, String?>)? = null

    // Pluggable persistence callback invoked when recording + CAM++ diarization completes
    var sessionPersistenceHandler: (suspend (MeetingRecordingState, SpeakerDiarizer.DiarizationResult) -> Unit)? = null

    // Optional SpeakerProfileStore reference for profile matching during post-recording diarization
    var speakerProfileStore: SpeakerProfileStore? = null

    internal fun updateState(transform: (MeetingRecordingState) -> MeetingRecordingState) {
        _state.update(transform)
    }

    fun setExpectedSpeakerCount(count: Int) {
        _state.update { it.copy(expectedSpeakerCount = count) }
    }

    fun setLanguageHint(lang: String) {
        _state.update { it.copy(languageHint = lang) }
    }
}

/**
 * Manages public `/sdcard/Recordings` (`Environment.DIRECTORY_RECORDINGS`) WAV file creation,
 * in-place 44-byte RIFF/WAVE header updates, MediaScanner indexing, and legacy migration from
 * `/data/data/com.phequals7.muesli/files/meetings/<sessionId>.wav`.
 */
object MeetingStorageManager {
    private const val TAG = "MeetingStorageManager"
    private const val SUBFOLDER_NAME = "Muesli"

    /**
     * Resolves `/sdcard/Recordings/Muesli` (`/storage/emulated/0/Recordings/Muesli`).
     * Falls back to `/sdcard/Recordings` if subdirectory creation is restricted.
     */
    fun getPublicRecordingsDirectory(): File {
        val publicRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_RECORDINGS
        )
        val muesliDir = File(publicRoot, SUBFOLDER_NAME)
        if (!muesliDir.exists() && !muesliDir.mkdirs()) {
            if (!publicRoot.exists()) publicRoot.mkdirs()
            return publicRoot
        }
        return muesliDir
    }

    /**
     * Creates a descriptive `.wav` file inside `/sdcard/Recordings/Muesli/` preserving English,
     * Thai (`\u0E00-\u0E7F`), and Chinese (`\u4E00-\u9FFF`) characters in the meeting title.
     */
    fun createPublicSessionWavFile(sessionId: String, title: String?): File {
        val targetDir = getPublicRecordingsDirectory()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val sanitizedTitle = (title?.takeIf { it.isNotBlank() } ?: "Meeting")
            .replace(Regex("[^a-zA-Z0-9\\u0E00-\\u0E7F\\u4E00-\\u9FFF_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(32)
            .ifEmpty { "Meeting" }
        val shortId = sessionId.take(8)
        return File(targetDir, "${timestamp}_${sanitizedTitle}_${shortId}.wav")
    }

    /**
     * Creates a temporary WAV file when user has disabled audio retention (deleted after diarization).
     */
    fun createTempWavFile(context: Context, sessionId: String): File {
        val tempDir = File(context.cacheDir, "temp_meetings").apply { mkdirs() }
        return File(tempDir, "${sessionId}_temp.wav")
    }

    /**
     * Triggers Android's MediaScanner so the newly recorded WAV in `/sdcard/Recordings/Muesli`
     * immediately appears in Xiaomi File Manager, system Recorder apps, and USB/MTP transfers.
     */
    fun indexRecordingInMediaStore(context: Context, wavFile: File) {
        if (!wavFile.exists()) return
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(wavFile.absolutePath),
            arrayOf("audio/x-wav", "audio/wav"),
            null
        )
    }

    /**
     * One-time migration utility that moves legacy `/data/data/com.phequals7.muesli/files/meetings/<sessionId>.wav`
     * files into `/sdcard/Recordings/Muesli/` and returns a map of `oldPath -> newPath` for Room DB updates.
     */
    suspend fun migrateLegacyInternalRecordings(
        context: Context,
        onPathMigrated: (suspend (sessionId: String, oldPath: String, newPath: String) -> Unit)? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val legacyDir = File(context.filesDir, "meetings")
        if (!legacyDir.exists() || !legacyDir.isDirectory) return@withContext emptyMap()

        val migratedMap = mutableMapOf<String, String>()
        val publicDir = getPublicRecordingsDirectory()

        legacyDir.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            ?.forEach { legacyWav ->
                try {
                    val sessionId = legacyWav.nameWithoutExtension
                    val destFile = File(publicDir, legacyWav.name)
                    legacyWav.inputStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists() && destFile.length() == legacyWav.length()) {
                        legacyWav.delete()
                        indexRecordingInMediaStore(context, destFile)
                        migratedMap[legacyWav.absolutePath] = destFile.absolutePath
                        onPathMigrated?.invoke(sessionId, legacyWav.absolutePath, destFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate legacy recording ${legacyWav.absolutePath}", e)
                }
            }
        migratedMap
    }
}

/**
 * Foreground service responsible for:
 * 1. Capturing 16 kHz mono float audio from `AudioRecord` and streaming 16-bit PCM directly to
 *    `/sdcard/Recordings/Muesli/<timestamp>_<title>_<sessionId>.wav`.
 * 2. Segmenting live speech via bundled `silero_vad.onnx` (up to 30s per segment) and decoding chunks
 *    sequentially on-device (`Qwen3-ASR-0.6B/1.7B`, `Whisper-Large-v3-Turbo`, or `SenseVoiceSmall + Zipformer-Thai`).
 * 3. Executing post-recording CAM++ speaker diarization (`SpeakerDiarizer.kt`) with centroid consolidation
 *    and persistent speaker profile matching.
 */
class MeetingRecorderService : Service() {

    companion object {
        private const val TAG = "MeetingRecorderService"
        private const val NOTIFICATION_ID = 4102
        private const val CHANNEL_ID = "muesli_meeting_recording_channel"

        const val ACTION_START_RECORDING = "com.phequals7.muesli.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.phequals7.muesli.action.STOP_RECORDING"
        const val ACTION_DISCARD_RECORDING = "com.phequals7.muesli.action.DISCARD_RECORDING"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_MEETING_TITLE = "extra_meeting_title"
        const val EXTRA_RETAIN_AUDIO = "extra_retain_audio"
        const val EXTRA_EXPECTED_SPEAKERS = "extra_expected_speakers"
        const val EXTRA_LANGUAGE_HINT = "extra_language_hint"

        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW_SAMPLES = 512 // 32ms at 16 kHz for Silero VAD
        private const val MAX_SEGMENT_DURATION_SEC = 30.0f
        private const val MAX_SEGMENT_SAMPLES = (SAMPLE_RATE * MAX_SEGMENT_DURATION_SEC).toInt()

        fun startService(
            context: Context,
            sessionId: String = UUID.randomUUID().toString(),
            title: String = "Meeting",
            retainAudio: Boolean = true,
            expectedSpeakers: Int = -1,
            languageHint: String = "auto"
        ) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_MEETING_TITLE, title)
                putExtra(EXTRA_RETAIN_AUDIO, retainAudio)
                putExtra(EXTRA_EXPECTED_SPEAKERS, expectedSpeakers)
                putExtra(EXTRA_LANGUAGE_HINT, languageHint)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isRecording = AtomicBoolean(false)
    private val isDiscarded = AtomicBoolean(false)

    private var captureJob: Job? = null
    private var timerJob: Job? = null
    private var decodeWorkerJob: Job? = null

    private data class QueuedAudioChunk(
        val samples: FloatArray,
        val startMs: Long,
        val endMs: Long
    )

    private var chunkChannel = Channel<QueuedAudioChunk>(Channel.UNLIMITED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                val title = intent.getStringExtra(EXTRA_MEETING_TITLE) ?: "Meeting"
                val retainAudio = intent.getBooleanExtra(EXTRA_RETAIN_AUDIO, true)
                val expectedSpeakers = intent.getIntExtra(EXTRA_EXPECTED_SPEAKERS, -1)
                val languageHint = intent.getStringExtra(EXTRA_LANGUAGE_HINT) ?: "auto"

                startMeetingCapture(
                    sessionId = sessionId,
                    title = title,
                    retainAudio = retainAudio,
                    expectedSpeakers = expectedSpeakers,
                    languageHint = languageHint
                )
            }
            ACTION_STOP_RECORDING -> {
                isRecording.set(false)
            }
            ACTION_DISCARD_RECORDING -> {
                isDiscarded.set(true)
                isRecording.set(false)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMeetingCapture(
        sessionId: String,
        title: String,
        retainAudio: Boolean,
        expectedSpeakers: Int,
        languageHint: String
    ) {
        if (isRecording.getAndSet(true)) return
        isDiscarded.set(false)

        val notification = buildNotification(title, "Recording to /sdcard/Recordings/Muesli...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val targetWavFile = if (retainAudio) {
            MeetingStorageManager.createPublicSessionWavFile(sessionId, title)
        } else {
            MeetingStorageManager.createTempWavFile(this, sessionId)
        }

        MeetingRecordingController.updateState {
            MeetingRecordingController.MeetingRecordingState(
                sessionId = sessionId,
                title = title,
                status = MeetingRecordingController.Status.RECORDING,
                elapsedMs = 0L,
                currentRmsLevel = 0f,
                audioFilePath = if (retainAudio) targetWavFile.absolutePath else null,
                expectedSpeakerCount = expectedSpeakers,
                languageHint = languageHint,
                liveChunks = emptyList(),
                finalizedSegments = emptyList(),
                diarizationProgress = 0f,
                errorMessage = null
            )
        }

        chunkChannel = Channel(Channel.UNLIMITED)
        startDecodeWorker(languageHint)
        startElapsedTimer(title)

        captureJob = serviceScope.launch(Dispatchers.IO) {
            runAudioCaptureLoop(
                sessionId = sessionId,
                title = title,
                wavFile = targetWavFile,
                retainAudio = retainAudio
            )
        }
    }

    private fun startElapsedTimer(title: String) {
        val startWallClockMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive && isRecording.get()) {
                val elapsed = System.currentTimeMillis() - startWallClockMs
                MeetingRecordingController.updateState { it.copy(elapsedMs = elapsed) }
                delay(250L)
            }
        }
    }

    private fun startDecodeWorker(languageHint: String) {
        decodeWorkerJob?.cancel()
        decodeWorkerJob = serviceScope.launch(Dispatchers.Default) {
            for (chunk in chunkChannel) {
                if (isDiscarded.get()) continue
                try {
                    val transcriber = MeetingRecordingController.chunkTranscriber
                    val (decodedText, detectedLang) = if (transcriber != null) {
                        transcriber.invoke(chunk.samples, languageHint)
                    } else {
                        "" to languageHint
                    }
                    if (decodedText.isNotBlank()) {
                        val rawChunk = RawTranscriptChunk(
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                            text = decodedText.trim(),
                            languageCode = detectedLang
                        )
                        MeetingRecordingController.updateState { state ->
                            state.copy(liveChunks = state.liveChunks + rawChunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding live speech chunk [${chunk.startMs}..${chunk.endMs}]", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runAudioCaptureLoop(
        sessionId: String,
        title: String,
        wavFile: File,
        retainAudio: Boolean
    ) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 2
        )

        val vad = initSileroVad()
        var totalSamplesWritten = 0L
        var chunkStartSample = 0L
        val fallbackAccumulator = FloatArray(MAX_SEGMENT_SAMPLES)
        var fallbackAccSize = 0

        try {
            wavFile.parentFile?.mkdirs()
            RandomAccessFile(wavFile, "rw").use { raf ->
                raf.setLength(0L)
                // Write placeholder 44-byte RIFF/WAVE header (updated with exact payload size on stop)
                writeWavHeader(raf, totalDataBytes = 0L, sampleRate = SAMPLE_RATE, channels = 1)

                audioRecord.startRecording()
                val pcmShortBuf = ShortArray(VAD_WINDOW_SAMPLES)
                val pcmByteBuf = ByteBuffer.allocate(VAD_WINDOW_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                val floatWindow = FloatArray(VAD_WINDOW_SAMPLES)

                while (isRecording.get() && !isDiscarded.get()) {
                    val readCount = audioRecord.read(pcmShortBuf, 0, VAD_WINDOW_SAMPLES)
                    if (readCount <= 0) continue

                    // 1. Convert 16-bit PCM to little-endian bytes for WAV file & float[-1..1] for VAD/ASR
                    pcmByteBuf.clear()
                    var sumSq = 0f
                    for (i in 0 until readCount) {
                        val s = pcmShortBuf[i]
                        pcmByteBuf.putShort(s)
                        val f = (s / 32768.0f).coerceIn(-1.0f, 1.0f)
                        floatWindow[i] = f
                        sumSq += f * f
                    }
                    raf.write(pcmByteBuf.array(), 0, readCount * 2)

                    val rms = sqrt(sumSq / readCount.toFloat()).coerceIn(0f, 1f)
                    MeetingRecordingController.updateState { it.copy(currentRmsLevel = rms) }

                    // 2. Segment via Silero VAD (or fallback 30-second windowing if VAD unavailable)
                    if (vad != null) {
                        val windowSlice = if (readCount == VAD_WINDOW_SAMPLES) {
                            floatWindow
                        } else {
                            floatWindow.copyOfRange(0, readCount)
                        }
                        vad.acceptWaveform(windowSlice)
                        while (!vad.empty()) {
                            val segment = vad.front()
                            vad.pop()
                            val segSamples = segment.samples
                            val segStartMs = (( segment.start.toLong() ) * 1000L) / SAMPLE_RATE
                            val segEndMs = segStartMs + ((segSamples.size.toLong() * 1000L) / SAMPLE_RATE)
                            if (segSamples.isNotEmpty()) {
                                chunkChannel.trySend(
                                    QueuedAudioChunk(
                                        samples = segSamples,
                                        startMs = segStartMs,
                                        endMs = segEndMs
                                    )
                                )
                            }
                        }
                    } else {
                        val spaceLeft = MAX_SEGMENT_SAMPLES - fallbackAccSize
                        val toCopy = readCount.coerceAtMost(spaceLeft)
                        System.arraycopy(floatWindow, 0, fallbackAccumulator, fallbackAccSize, toCopy)
                        fallbackAccSize += toCopy
                        if (fallbackAccSize >= MAX_SEGMENT_SAMPLES) {
                            val startMs = (chunkStartSample * 1000L) / SAMPLE_RATE
                            val endMs = ((chunkStartSample + fallbackAccSize) * 1000L) / SAMPLE_RATE
                            chunkChannel.trySend(
                                QueuedAudioChunk(
                                    samples = fallbackAccumulator.copyOfRange(0, fallbackAccSize),
                                    startMs = startMs,
                                    endMs = endMs
                                )
                            )
                            chunkStartSample += fallbackAccSize
                            fallbackAccSize = 0
                        }
                    }

                    totalSamplesWritten += readCount
                }

                // Flush trailing audio on stop
                if (!isDiscarded.get()) {
                    if (vad != null) {
                        vad.flush()
                        while (!vad.empty()) {
                            val segment = vad.front()
                            vad.pop()
                            val segSamples = segment.samples
                            val segStartMs = (segment.start.toLong() * 1000L) / SAMPLE_RATE
                            val segEndMs = segStartMs + ((segSamples.size.toLong() * 1000L) / SAMPLE_RATE)
                            if (segSamples.isNotEmpty()) {
                                chunkChannel.trySend(
                                    QueuedAudioChunk(
                                        samples = segSamples,
                                        startMs = segStartMs,
                                        endMs = segEndMs
                                    )
                                )
                            }
                        }
                    } else if (fallbackAccSize > 0) {
                        val startMs = (chunkStartSample * 1000L) / SAMPLE_RATE
                        val endMs = ((chunkStartSample + fallbackAccSize) * 1000L) / SAMPLE_RATE
                        chunkChannel.trySend(
                            QueuedAudioChunk(
                                samples = fallbackAccumulator.copyOfRange(0, fallbackAccSize),
                                startMs = startMs,
                                endMs = endMs
                            )
                        )
                    }

                    // Update the 44-byte RIFF/WAVE header in-place at offset 0 with exact data length
                    val totalDataBytes = totalSamplesWritten * 2L
                    writeWavHeader(raf, totalDataBytes = totalDataBytes, sampleRate = SAMPLE_RATE, channels = 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop failed", e)
            MeetingRecordingController.updateState {
                it.copy(
                    status = MeetingRecordingController.Status.FAILED,
                    errorMessage = e.localizedMessage ?: "Audio capture error"
                )
            }
        } finally {
            timerJob?.cancel()
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            } catch (_: Exception) {}
            audioRecord.release()
            vad?.release()
            chunkChannel.close()
        }

        if (isDiscarded.get()) {
            wavFile.delete()
            MeetingRecordingController.updateState {
                it.copy(
                    status = MeetingRecordingController.Status.CANCELLED,
                    audioFilePath = null
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Wait for all live ASR chunks to finish decoding
        decodeWorkerJob?.join()

        // Index in MediaStore immediately so `/sdcard/Recordings/Muesli/<file>.wav` is visible externally
        if (retainAudio && wavFile.exists()) {
            MeetingStorageManager.indexRecordingInMediaStore(this@MeetingRecorderService, wavFile)
        }

        // Run post-recording CAM++ speaker diarization & profile identification
        updateNotification(title, "Identifying speakers (CAM++ Diarization)...")
        MeetingRecordingController.updateState {
            it.copy(
                status = MeetingRecordingController.Status.FINALIZING_DIARIZATION,
                currentRmsLevel = 0f,
                diarizationProgress = 0.05f
            )
        }

        try {
            val currentState = MeetingRecordingController.state.value
            val diarizer = SpeakerDiarizer(
                context = applicationContext,
                profileStore = MeetingRecordingController.speakerProfileStore
            )
            val diarizationResult = diarizer.diarizeWavAndAlign(
                sessionId = sessionId,
                wavFile = wavFile,
                rawChunks = currentState.liveChunks,
                expectedSpeakerCount = currentState.expectedSpeakerCount,
                onProgress = { progress ->
                    MeetingRecordingController.updateState {
                        it.copy(diarizationProgress = progress)
                    }
                }
            )

            if (!retainAudio) {
                wavFile.delete()
            }

            MeetingRecordingController.updateState { state ->
                state.copy(
                    status = MeetingRecordingController.Status.COMPLETED,
                    finalizedSegments = diarizationResult.segments,
                    diarizationProgress = 1.0f
                )
            }

            MeetingRecordingController.sessionPersistenceHandler?.invoke(
                MeetingRecordingController.state.value,
                diarizationResult
            )
        } catch (e: Exception) {
            Log.e(TAG, "Post-recording CAM++ speaker diarization failed", e)
            MeetingRecordingController.updateState { state ->
                state.copy(
                    status = MeetingRecordingController.Status.COMPLETED,
                    errorMessage = "Diarization fallback applied: ${e.localizedMessage}"
                )
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun initSileroVad(): Vad? {
        return try {
            val vadAssetFile = File(filesDir, "bundled_models/silero_vad.onnx")
            if (!vadAssetFile.exists()) {
                vadAssetFile.parentFile?.mkdirs()
                assets.open("silero_vad.onnx").use { input ->
                    FileOutputStream(vadAssetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadAssetFile.absolutePath,
                    threshold = 0.50f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SAMPLES,
                    maxSpeechDuration = MAX_SEGMENT_DURATION_SEC
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu"
            )
            Vad(vadConfig = vadConfig)
        } catch (e: Exception) {
            Log.w(TAG, "Silero VAD initialization failed, falling back to 30s chunks", e)
            null
        }
    }

    /**
     * Writes or updates a standard 44-byte RIFF/WAVE header at byte offset 0 of `raf`.
     */
    private fun writeWavHeader(
        raf: RandomAccessFile,
        totalDataBytes: Long,
        sampleRate: Int,
        channels: Int
    ) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val riffChunkSize = (36L + totalDataBytes).coerceAtMost(0xFFFFFFFFL).toInt()
        val dataChunkSize = totalDataBytes.coerceAtMost(0xFFFFFFFFL).toInt()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1.toShort()) // AudioFormat = 1 (Linear PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataChunkSize)
        }

        val savedPos = raf.filePointer
        raf.seek(0L)
        raf.write(header.array())
        if (savedPos > 44L) {
            raf.seek(savedPos)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Muesli Meeting Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "On-device multilingual meeting transcription and speaker diarization"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val stopIntent = Intent(this, MeetingRecorderService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val discardIntent = Intent(this, MeetingRecorderService::class.java).apply {
            action = ACTION_DISCARD_RECORDING
        }
        val discardPendingIntent = PendingIntent.getService(
            this,
            2,
            discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop & Diarize", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_delete, "Discard", discardPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, subtitle: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, subtitle))
    }

    override fun onDestroy() {
        isRecording.set(false)
        serviceScope.cancel()
        super.onDestroy()
    }
}