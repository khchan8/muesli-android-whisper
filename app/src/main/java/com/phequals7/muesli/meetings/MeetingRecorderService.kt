package com.phequals7.muesli.meetings

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.phequals7.muesli.MainActivity
import com.phequals7.muesli.R
import com.phequals7.muesli.audio.AudioInputRouteManager
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.data.entity.Transcript
import com.phequals7.muesli.engine.SherpaRecognizerHolder
import com.phequals7.muesli.model.ModelManager
import com.phequals7.muesli.model.SpeechModels
import com.phequals7.muesli.data.AppDatabase
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that records a meeting and transcribes it on-device.
 *
 * Pipeline (Android counterpart of the iOS StreamingMeetingRecorder):
 *   AudioRecord 32 kHz PCM16 → M4A retention (AAC-LC 64 kbps via
 *   [M4aAudioEncoder]) + pairwise 2:1 downsample to 16 kHz → Silero VAD
 *   speech segmentation (bundled model, iOS 3–60 s FluidAudio-VAD parity)
 *   → sequential Parakeet decode per segment → merged live transcript in
 *   [MeetingRecordingController]. The 16 kHz stream also lands in a private
 *   staging WAV that feeds speaker diarization (bundled pyannote + TitaNet
 *   models) and is deleted after it; chunks are prefixed with
 *   "Speaker N · m:ss".
 *
 * On stop the finalized M4A is published to the recordings folder
 * immediately (Stage 1) while final ASR + diarization run; the SRT follows
 * once the final transcript is persisted (Stage 2).
 *
 * If the VAD cannot be created, chunking falls back to fixed 30 s rotation.
 */
class MeetingRecorderService : Service() {

    companion object {
        private const val TAG = "MeetingRecorder"
        const val ACTION_START = "com.phequals7.muesli.meetings.START"
        const val ACTION_STOP = "com.phequals7.muesli.meetings.STOP"
        const val ACTION_DISCARD = "com.phequals7.muesli.meetings.DISCARD"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_EXPECTED_SPEAKERS = "expected_speakers"

        private const val CAPTURE_SAMPLE_RATE = 32000
        private const val MODEL_SAMPLE_RATE = 16000
        private const val CAPTURE_JOIN_TIMEOUT_MS = 5_000L
        private const val CHUNK_SECONDS = 30 // fallback when VAD is unavailable
        private const val PARTIAL_INTERVAL_MS = 5_000L
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 42
    }

    /** A finished audio chunk ready for decode, with its position in the recording. */
    private data class TimedChunk(val pcm: FloatArray, val startSec: Float, val endSec: Float)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)
    private val finalizing = AtomicBoolean(false)
    private var finalizationJob: Job? = null
    private lateinit var store: SharedStore

    private var sessionId: String? = null
    private var sessionTitle: String = ""
    private var startedAtMs: Long = 0
    private var retainAudioAtStart = true
    private var sessionInsert = CompletableDeferred<Unit>()

    private var audioRecord: AudioRecord? = null
    private var activeMicPref = AudioInputRouteManager.MicPreference.AUTO
    private var captureThread: Thread? = null

    /** Retained M4A + encoder: written by the capture thread, finalized by
     *  the finalizer only after that thread has exited. */
    private var audioFile: File? = null
    private var encoder: M4aAudioEncoder? = null

    /** Private 16 kHz staging WAV fed to diarization; never exported. */
    private var stagingDir: File? = null
    private var stagingWavFile: File? = null
    private var stagingWriter: RandomAccessFile? = null
    private var stagingBytesWritten = 0L
    private val stagingByteBuffer = ByteArray((CAPTURE_SAMPLE_RATE / 10 / 2 + 1) * Short.SIZE_BYTES)

    /** Permanent capture/codec error recorded by the capture loop. */
    @Volatile
    private var captureError: String? = null

    private var expectedSpeakers = -1
    private var sessionTemplateId: String = "general"

    private val lock = Object()
    // Fixed-chunk fallback accumulation as append-only parts (concatenated
    // once at rotation — the old per-100ms copyOf churned quadratically and
    // contributed to OOM on low-heap devices). VAD mode leaves this empty.
    private val currentChunkParts = mutableListOf<FloatArray>()
    private var currentChunkSize = 0
    private var currentChunkStartSec = 0f
    private var samplesCaptured = 0L
    private var vad: Vad? = null
    private val finishedChunks = LinkedBlockingQueue<TimedChunk>()
    private val transcriptParts = mutableListOf<RawTranscriptChunk>()
    private val decodeBusy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SharedStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID) ?: MeetingTemplate.GENERAL.id
                expectedSpeakers = intent.getIntExtra(EXTRA_EXPECTED_SPEAKERS, -1)
                startRecording(id, title, templateId)
            }
            ACTION_STOP -> stopRecording(discard = false)
            ACTION_DISCARD -> stopRecording(discard = true)
        }
        return START_NOT_STICKY
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    private fun startRecording(id: String, title: String, templateId: String) {
        if (capturing.get() || finalizing.get()) return
        // A live summary owns native model memory; refuse until it finishes
        // or is cancelled (the UI shows MeetingSummaryService.BUSY_MESSAGE).
        if (MeetingSummaryService.hasActiveJob) return
        val selectedModel = SpeechModels.selected(this)
        if (!ModelManager.getInstance(this).isDownloaded(selectedModel)) {
            failSession(id, title, templateId, "${selectedModel.shortName} model is not downloaded")
            return
        }

        sessionId = id
        sessionTitle = title
        startedAtMs = System.currentTimeMillis()
        sessionTemplateId = templateId
        // Snapshot retention at start: toggling the setting mid-recording
        // applies to the next meeting, never to mismatched in-flight output.
        retainAudioAtStart = store.retainMeetingAudio
        captureError = null
        sessionInsert = CompletableDeferred()
        transcriptParts.clear()
        synchronized(lock) {
            currentChunkParts.clear()
            currentChunkSize = 0
            currentChunkStartSec = 0f
            samplesCaptured = 0L
        }
        finishedChunks.clear()
        vad = try {
            Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "models/silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.5f,
                        windowSize = 512,
                        // iOS rotates VAD chunks at 3–60 s; 30 s caps decode latency per chunk.
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = MODEL_SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VAD unavailable, falling back to fixed ${CHUNK_SECONDS}s chunks: ${t.message}")
            null
        }

        val insert = sessionInsert
        serviceScope.launch {
            try {
                store.insertRecordingSession(
                    RecordingSession(
                        id = id,
                        kind = "meeting",
                        title = title,
                        startedAt = startedAtMs,
                        phase = "recording",
                        engineIdentifier = selectedModel.id,
                        templateId = templateId,
                    )
                )
                insert.complete(Unit)
            } catch (t: Throwable) {
                insert.completeExceptionally(t)
            }
        }

        startForegroundWithNotification(title)
        MeetingRecordingController.onCaptureStarted(id, title)

        try {
            // Warm the recognizer before opening the mic.
            decodeExecutor.execute {
                try {
                    SherpaRecognizerHolder.prewarmAsync(applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recognizer warm-up failed", t)
                }
            }
            capturing.set(true)
            startCaptureThread()
        } catch (t: Throwable) {
            capturing.set(false)
            Log.e(TAG, "Failed to start capture", t)
            failSession(id, title, templateId, "Could not start microphone: ${t.message}")
            teardown()
        }
    }

    private fun stopRecording(discard: Boolean) {
        if (!capturing.compareAndSet(true, false)) return
        finalizing.set(true)
        val endedAt = System.currentTimeMillis()
        MeetingRecordingController.onFinalizing()

        // Assigned immediately (main) so onTimeout/onDestroy can cancel the
        // whole finalization, not just the tail after diarization.
        finalizationJob = serviceScope.launch {
            try {
                var failure: String? = null
                var discardOutputs = discard
                var finalText = synchronized(transcriptParts) {
                    transcriptParts.joinToString("\n\n") { it.text }.trim()
                }
                var diarizationResult: SpeakerDiarizer.DiarizationResult? = null
                val id = sessionId

                try {
                    // 1. Stop the mic to unblock READ_BLOCKING, then wait
                    //    (bounded) on IO for the capture thread before touching
                    //    its encoder/writer. Codec/encoder calls carry their
                    //    own 10 s no-progress bound.
                    val joined = withContext(Dispatchers.IO) {
                        try {
                            audioRecord?.stop()
                        } catch (_: Throwable) {
                        }
                        val thread = captureThread
                        if (thread != null) {
                            try {
                                thread.join(CAPTURE_JOIN_TIMEOUT_MS)
                                !thread.isAlive
                            } catch (_: InterruptedException) {
                                false
                            }
                        } else {
                            true
                        }
                    }
                    releaseAudioRecord()
                    AudioInputRouteManager(applicationContext).stopBluetoothSco(activeMicPref)
                    startFinalizingForegroundNotification(sessionTitle)

                    if (!joined) {
                        // The capture thread is stuck; do not close objects it
                        // may still be using. Its own finally path releases them.
                        failure = "Recording capture did not shut down within " +
                            "${CAPTURE_JOIN_TIMEOUT_MS / 1000}s; audio was not finalized."
                    } else if (captureError != null) {
                        failure = captureError
                        // The capture thread has exited; release its codec
                        // quietly so a vendor encoder cannot leak.
                        runCatching { encoder?.stopAndRelease() }
                        encoder = null
                    } else if (discardOutputs) {
                        discardPrivateOutputs()
                    } else {
                        // 2. Finalize encoder EOS/muxer and the staging header
                        //    on IO, then publish the audio before any final ASR
                        //    or diarization work — a slow ASR tail must not hold
                        //    the finalized M4A behind the decode backlog.
                        val m4aName = withContext(Dispatchers.IO) {
                            encoder?.let { it.stopAndRelease() }
                            encoder = null
                            finalizeStaging()
                            audioFile?.name
                        }
                        MeetingRecordingController.onFinalizationStatus("Saving audio")

                        sessionInsert.await()
                        if (id != null) {
                            val marked = store.markRecordingStopped(id, endedAt, m4aName)
                            if (marked == 1) {
                                // Public export errors are saved on the row but
                                // do not stop transcription/diarization and never
                                // delete the private M4A.
                                MeetingStorageManager.exportAudioArtifactImmediately(applicationContext, store, id)
                            }
                            // marked == 0: the row vanished or changed; never
                            // recreate it or export on its behalf.
                        }

                        // 3. Final VAD/fallback flush + chunk decode on the
                        //    single decodeExecutor, behind any in-flight
                        //    decodes; bridge the one result back and await it.
                        finalText = drainFinalSpeech(finalText)

                        // 4. Free the ASR weights before pyannote + CAM++ load
                        //    (v2.1 Root Cause B: three models exhaust the native
                        //    heap), then diarize against the staging WAV.
                        val staging = stagingWavFile
                        if (store.speakerLabelsEnabled && staging != null && finalText.isNotBlank()) {
                            SherpaRecognizerHolder.release()
                            try {
                                MeetingRecordingController.onFinalizationStatus("Identifying speakers")
                                MeetingRecordingController.onDiarizationProgress(0.02f)
                                diarizationResult = SpeakerDiarizer(
                                    applicationContext,
                                    AppDatabase.getSpeakerProfileStore(applicationContext)
                                ).diarizeWavAndAlign(
                                    sessionId = id ?: "",
                                    wavFile = staging,
                                    rawChunks = synchronized(transcriptParts) { transcriptParts.toList() },
                                    expectedSpeakerCount = expectedSpeakers
                                ) { progress ->
                                    MeetingRecordingController.onDiarizationProgress(progress)
                                }
                                diarizationResult?.let { finalText = it.formattedInlineTranscript }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Diarization failed, keeping unlabeled transcript: ${t.message}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    failure = failure ?: "Recording finalization was cancelled."
                    discardOutputs = true // cancelled finalization publishes nothing
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording finalization failed before persist", t)
                    failure = failure ?: (t.message ?: t.javaClass.simpleName)
                } finally {
                    try {
                        // The staging WAV is obsolete the moment its consumer
                        // returns (or immediately when diarization never ran),
                        // including failures, cancellation and discard.
                        withContext(NonCancellable) { deleteStaging() }
                    } catch (_: Throwable) {
                    }
                    releaseVad()
                }

                // Persist phase — always runs unless cancelled, so the
                // controller + foreground state can never wedge (the old
                // whole-path guard).
                runCatching { sessionInsert.await() }
                try {
                    val existing = id?.let { store.getRecordingSessionById(it) }
                    if (failure != null) {
                        if (existing != null) {
                            persistRecordingFailure(id!!, endedAt, failure, finalText, existing.engineIdentifier)
                        } else {
                            failSession(id ?: "", sessionTitle, sessionTemplateId, failure)
                        }
                    } else if (discardOutputs) {
                        if (existing != null) {
                            store.updateRecordingSession(
                                existing.copy(phase = "cancelled", endedAt = endedAt)
                            )
                        }
                    } else if (id != null && existing != null) {
                        MeetingRecordingController.onFinalizationStatus("Saving recording")
                        val transcript = Transcript(
                            sessionID = id,
                            text = finalText,
                            engineIdentifier = existing.engineIdentifier
                                ?: SpeechModels.selected(applicationContext).id
                        )
                        store.insertTranscript(transcript)

                        // Structured segments: diarized speaker blocks when
                        // diarization ran; otherwise one block per decoded chunk
                        // (single speaker) so synchronized playback still works.
                        val rawChunks = synchronized(transcriptParts) { transcriptParts.toList() }
                        val segments = diarizationResult?.segments
                            ?: SpeakerDiarizer(applicationContext)
                                .alignTurnsWithTranscriptChunks(id, emptyList(), rawChunks)
                        store.replaceTranscriptSegments(
                            id,
                            segments.map { MeetingTranscriptSegmentEntity.fromDomainModel(it) },
                            finalText
                        )

                        // Field-scoped completion keeps the already-committed
                        // audio path/URI and any export error; only then does
                        // Stage 2 run.
                        if (store.completeRecording(id, transcript.id) == 1) {
                            MeetingStorageManager.exportSrtArtifactOnly(applicationContext, store, id)
                        }

                        // Hand the automatic summary off while still foreground;
                        // ownership registers synchronously so a second recording
                        // cannot race in before teardown.
                        if (store.summariesEnabled) {
                            MeetingSummaryService.generate(applicationContext, id, fromRecorderHandoff = true)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording persist failed", t)
                    val failedId = id
                    if (failedId != null && failure == null) {
                        // Never flip an already-completed session to failed
                        // because a post-completion step (e.g. summary handoff)
                        // threw.
                        val phase = runCatching {
                            store.getRecordingSessionById(failedId)?.phase
                        }.getOrNull()
                        if (phase != "completed") {
                            runCatching {
                                persistRecordingFailure(
                                    failedId, endedAt,
                                    t.message ?: t.javaClass.simpleName, finalText, null
                                )
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    finalizationJob = null
                    finalizing.set(false)
                    MeetingRecordingController.onFinished()
                    teardown()
                }
            }
        }
    }

    /** Failure persistence: visible `failed` phase, saved transcript when one
     *  was decoded, preserved private files, no public artifacts claimed. */
    private suspend fun persistRecordingFailure(
        id: String,
        endedAt: Long,
        message: String,
        finalText: String,
        engineIdentifier: String?
    ) {
        val transcriptId: String? = if (finalText.isNotBlank()) {
            val transcript = Transcript(
                sessionID = id,
                text = finalText,
                engineIdentifier = engineIdentifier ?: SpeechModels.selected(applicationContext).id
            )
            store.insertTranscript(transcript)
            transcript.id
        } else {
            null
        }
        store.failRecording(id, endedAt, message, transcriptId)
    }

    /**
     * Enqueues the final VAD flush + fallback rotation + chunk decode on the
     * single decodeExecutor (behind in-flight decodes) and awaits the result.
     * Cancellation still waits for the running executor task to finish before
     * unwinding, so the recognizer is never released underneath native code.
     */
    private suspend fun drainFinalSpeech(currentText: String): String {
        val drained = CompletableDeferred<Unit>()
        val submitted = runCatching {
            decodeExecutor.execute {
                try {
                    try {
                        vad?.let { v ->
                            v.flush()
                            drainVadSegments(v)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "VAD flush failed: ${t.message}")
                    }
                    synchronized(lock) {
                        if (currentChunkSize > 0) {
                            val endSec = samplesCaptured.toFloat() / MODEL_SAMPLE_RATE
                            finishedChunks.offer(TimedChunk(concatParts(), currentChunkStartSec, endSec))
                            currentChunkParts.clear()
                            currentChunkSize = 0
                        }
                    }
                    drainChunkQueue()
                    drained.complete(Unit)
                } catch (t: Throwable) {
                    drained.completeExceptionally(t)
                }
            }
        }
        if (submitted.isFailure) return currentText // executor already gone
        try {
            drained.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { drained.await() } }
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Final decode failed", t)
        }
        return synchronized(transcriptParts) {
            transcriptParts.joinToString("\n\n") { it.text }.trim()
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
    }

    private fun releaseVad() {
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        vad = null
    }

    private fun failSession(id: String, title: String, templateId: String, message: String) {
        serviceScope.launch {
            store.insertRecordingSession(
                RecordingSession(
                    id = id,
                    kind = "meeting",
                    title = title.ifEmpty { "Untitled Meeting" },
                    phase = "failed",
                    errorMessage = message,
                    engineIdentifier = SpeechModels.selected(this@MeetingRecorderService).id,
                    templateId = templateId,
                )
            )
            MeetingRecordingController.onFinished()
        }
    }

    private fun teardown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached (fgsType=$fgsType); cancelling finalization")
        if (capturing.get()) {
            stopRecording(discard = false)
        }
        finalizationJob?.cancel() ?: run {
            finalizing.set(false)
            MeetingRecordingController.onFinished()
            teardown()
        }
    }

    override fun onDestroy() {
        capturing.set(false)
        finalizationJob?.cancel()
        decodeExecutor.shutdown()
        serviceScope.cancel()
        // The finalizer owns encoder/writer/VAD while active; only clean them
        // up here when no finalization ever ran (crash/startup-failure path).
        if (finalizing.compareAndSet(true, false)) {
            releaseAudioRecord()
            AudioInputRouteManager(applicationContext).stopBluetoothSco(activeMicPref)
            runCatching { encoder?.stopAndRelease() }
            encoder = null
            closeStagingQuietly()
            releaseVad()
        }
        super.onDestroy()
    }

    // ── capture ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // RECORD_AUDIO granted before reaching here
    private fun startCaptureThread() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "32 kHz mono microphone input is unavailable on this device" }

        val pref = AudioInputRouteManager.MicPreference.fromId(store.micPreference)
        activeMicPref = pref
        val routeManager = AudioInputRouteManager(applicationContext)
        // Best-effort BT SCO bring-up for the Bluetooth preference; SCO
        // support varies by OEM, so failures fall back to the default route.
        routeManager.startBluetoothSco(pref)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(CAPTURE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, CAPTURE_SAMPLE_RATE * Short.SIZE_BYTES / 10))
            .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            check(record.sampleRate == CAPTURE_SAMPLE_RATE) {
                "Microphone does not support 32 kHz capture (reported ${record.sampleRate} Hz)"
            }
            routeManager.resolveDevice(pref)?.let { record.setPreferredDevice(it) }
            audioRecord = record

            // The 16 kHz staging WAV always exists: it feeds diarization at
            // stop and is deleted right after. It must never be exported or
            // become the session's audioFileName.
            val dir = File(cacheDir, "temp_meetings/$sessionId")
            check(dir.mkdirs() || dir.isDirectory) { "Could not create the staging directory" }
            val staging = File(dir, "staging_16k.wav")
            val writer = RandomAccessFile(staging, "rw")
            writer.setLength(0)
            writer.write(wavHeaderBytes(0))
            stagingDir = dir
            stagingWavFile = staging
            stagingWriter = writer
            stagingBytesWritten = 0L

            // Retained captures reserve an .m4a in the private meetings dir;
            // the public copy is exported through the granted recordings
            // folder as soon as capture stops. No encoder → no retained audio.
            if (retainAudioAtStart) {
                val target = MeetingStorageManager.createSessionAudioFile(
                    MeetingStorageManager.internalRecordingsDirectory(filesDir),
                    sessionTitle,
                    startedAtMs
                )
                audioFile = target
                encoder = M4aAudioEncoder(target)
            } else {
                audioFile = null
                encoder = null
            }

            // Start the microphone only after the writer/encoder init succeeded.
            record.startRecording()
            captureThread = Thread { captureLoop(record) }.also { it.start() }
        } catch (t: Throwable) {
            releaseAudioRecord()
            runCatching { encoder?.stopAndRelease() }
            encoder = null
            closeStagingQuietly()
            // Remove only this attempt's empty/invalid files.
            stagingWavFile?.delete()
            stagingDir?.deleteRecursively()
            audioFile?.delete()
            audioFile = null
            stagingDir = null
            stagingWavFile = null
            throw t
        }
    }

    private fun captureLoop(record: AudioRecord) {
        val captureBuf = ShortArray(CAPTURE_SAMPLE_RATE / 10) // 100 ms @ 32 kHz
        val downBuf = ShortArray(captureBuf.size / 2 + 1) // 100 ms @ 16 kHz (+ carry)
        val downsampler = Pcm32kTo16kDownsampler()
        var lastPartialAt = System.currentTimeMillis()

        while (capturing.get()) {
            val read = try {
                record.read(captureBuf, 0, captureBuf.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                captureError = "Microphone read failed: ${t.message}"
                break
            }
            if (read < 0) {
                captureError = "Microphone read failed (error $read)"
                break
            }
            if (read == 0) continue

            val downCount = downsampler.process(captureBuf, read, downBuf)
            try {
                appendStagingPcm16(downBuf, downCount)
            } catch (t: Throwable) {
                captureError = "Staging audio write failed: ${t.message}"
                break
            }
            try {
                encoder?.encodePcmShorts(captureBuf, read)
            } catch (t: Throwable) {
                captureError = "M4A encoding failed: ${t.message}"
                break
            }

            // An odd short read can leave zero downsampled samples (the odd
            // one waits in the carry): staging/encoding consumed it, but the
            // 16 kHz model frame is empty this round.
            val frame = FloatArray(downCount) { downBuf[it] / 32768f }
            if (downCount > 0) publishLevel(downBuf, downCount)

            val frameStartSec: Float
            synchronized(lock) {
                frameStartSec = samplesCaptured.toFloat() / MODEL_SAMPLE_RATE
                samplesCaptured += downCount
            }

            val v = vad
            if (v != null) {
                try {
                    v.acceptWaveform(frame)
                    drainVadSegments(v)
                } catch (t: Throwable) {
                    Log.w(TAG, "VAD failed mid-capture, using fixed chunks: ${t.message}")
                    try { v.release() } catch (_: Throwable) {}
                    vad = null
                }
            } else {
                synchronized(lock) {
                    if (currentChunkSize == 0) currentChunkStartSec = frameStartSec
                    currentChunkParts.add(frame)
                    currentChunkSize += downCount
                    if (currentChunkSize >= MODEL_SAMPLE_RATE * CHUNK_SECONDS) {
                        finishedChunks.offer(
                            TimedChunk(
                                concatParts(),
                                currentChunkStartSec,
                                currentChunkStartSec + currentChunkSize.toFloat() / MODEL_SAMPLE_RATE
                            )
                        )
                        currentChunkParts.clear()
                        currentChunkSize = 0
                    }
                }
            }
            scheduleChunkDecode()

            val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
            val now = System.currentTimeMillis()
            if (now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                lastPartialAt = now
                schedulePartial()
            }
            MeetingRecordingController.onProgress(
                elapsedSec,
                lastLevel,
                lastPartial,
                synchronized(transcriptParts) { transcriptParts.joinToString("\n\n") { it.text } }
            )
        }

        if (captureError != null && capturing.get()) {
            // Permanent capture failure: request the normal finalization path
            // (which marks the session failed) instead of dropping audio
            // silently.
            stopRecording(discard = false)
        }
    }

    /** Concatenates the fallback chunk parts once (call with [lock] held). */
    private fun concatParts(): FloatArray {
        val out = FloatArray(currentChunkSize)
        var offset = 0
        for (part in currentChunkParts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    /** Pops every completed speech segment out of the VAD into the decode
     * queue. Must be called after acceptWaveform/flush. */
    private fun drainVadSegments(v: Vad) {
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            if (segment.samples.size < MODEL_SAMPLE_RATE / 2) continue
            val startSec = segment.start.toFloat() / MODEL_SAMPLE_RATE
            val endSec = startSec + segment.samples.size.toFloat() / MODEL_SAMPLE_RATE
            finishedChunks.offer(TimedChunk(segment.samples, startSec, endSec))
        }
        scheduleChunkDecode()
    }

    @Volatile
    private var lastLevel = 0f

    @Volatile
    private var lastPartial = ""

    private fun publishLevel(samples: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768f
            sum += (s * s).toDouble()
        }
        val rms = kotlin.math.sqrt(sum / count).toFloat()
        val db = 20f * kotlin.math.log10(rms + 1e-8f)
        lastLevel = ((db + 45f) / 35f).coerceIn(0f, 1f)
    }

    // ── transcription ────────────────────────────────────────────────────

    private fun scheduleChunkDecode() {
        if (!decodeBusy.compareAndSet(false, true)) return
        decodeExecutor.execute {
            try {
                drainChunkQueue()
            } finally {
                decodeBusy.set(false)
            }
        }
    }

    /** Decodes every finished chunk currently queued. Must run on decodeExecutor. */
    private fun drainChunkQueue() {
        while (true) {
            val chunk = finishedChunks.poll() ?: break
            if (chunk.pcm.size < MODEL_SAMPLE_RATE / 2) continue
            try {
                val result = decode(chunk.pcm)
                if (result.text.isNotBlank()) {
                    synchronized(transcriptParts) {
                        transcriptParts.add(
                            RawTranscriptChunk(
                                startMs = (chunk.startSec * 1000).toLong(),
                                endMs = (chunk.endSec * 1000).toLong(),
                                text = result.text,
                                languageCode = result.languageCode.takeUnless { it.isBlank() || it == "auto" }
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Chunk decode failed", t)
            }
        }
    }

    /** Re-decodes the in-progress chunk for live feedback. Disabled: the
     * re-decode doubles CPU use and the flickering preview tested poorly. */
    private fun schedulePartial() = Unit

    private fun decode(pcm: FloatArray): com.phequals7.muesli.engine.TranscriptionResult {
        val result = runBlocking {
            SherpaRecognizerHolder.transcribe(applicationContext, pcm, store.languageHint)
        }
        val processed = postProcess(result.text)
        return if (processed.isBlank()) result else result.copy(text = processed)
    }

    /** Filler-word removal + custom dictionary, same pipeline as dictation
     * (iOS applies TranscriptPostProcessor to meeting chunks too). */
    private fun postProcess(text: String): String {
        if (text.isBlank()) return text
        var processed = text
        if (store.isFillerWordRemovalEnabled) {
            processed = com.phequals7.muesli.utils.FillerWordFilter.apply(processed)
        }
        if (store.isCustomDictionaryEnabled) {
            processed = kotlinx.coroutines.runBlocking {
                com.phequals7.muesli.utils.CustomWordMatcher.apply(processed, store.getCustomWords())
            }
        }
        return processed.trim()
    }

    // ── staging WAV + retained M4A ───────────────────────────────────────

    /** Appends already-downsampled 16 kHz shorts to the open staging writer. */
    private fun appendStagingPcm16(samples: ShortArray, count: Int) {
        val writer = stagingWriter ?: return
        for (i in 0 until count) {
            val s = samples[i].toInt()
            stagingByteBuffer[i * 2] = (s and 0xFF).toByte()
            stagingByteBuffer[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        writer.write(stagingByteBuffer, 0, count * Short.SIZE_BYTES)
        stagingBytesWritten += count * Short.SIZE_BYTES.toLong()
    }

    /** Patches the final data length into the staging header and closes it. */
    private fun finalizeStaging() {
        val writer = stagingWriter ?: return
        try {
            writer.seek(0)
            writer.write(wavHeaderBytes(stagingBytesWritten))
        } finally {
            writer.close()
            stagingWriter = null
        }
    }

    private fun closeStagingQuietly() {
        try {
            stagingWriter?.close()
        } catch (_: Throwable) {
        }
        stagingWriter = null
    }

    /** Deletes the staging WAV and its session directory (NonCancellable). */
    private suspend fun deleteStaging() {
        withContext(NonCancellable) {
            closeStagingQuietly()
            stagingWavFile?.delete()
            stagingDir?.deleteRecursively()
            stagingWavFile = null
            stagingDir = null
        }
    }

    /** Discard keeps nothing: release the encoder quietly and remove this
     *  session's private outputs. */
    private fun discardPrivateOutputs() {
        runCatching { encoder?.stopAndRelease() }
        encoder = null
        audioFile?.delete()
        audioFile = null
        closeStagingQuietly()
    }

    /** Minimal 44-byte mono 16-bit PCM WAV header at the model sample rate. */
    private fun wavHeaderBytes(dataBytes: Long): ByteArray {
        val byteRate = MODEL_SAMPLE_RATE * Short.SIZE_BYTES
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) header[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Long) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = (v shr 8 and 0xFF).toByte()
            header[off + 2] = (v shr 16 and 0xFF).toByte()
            header[off + 3] = (v shr 24 and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = (v shr 8 and 0xFF).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + dataBytes); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, MODEL_SAMPLE_RATE.toLong()); putInt(28, byteRate.toLong())
        putShort(32, Short.SIZE_BYTES); putShort(34, 16)
        putStr(36, "data"); putInt(40, dataBytes)
        return header
    }

    // ── notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meeting Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while Muesli records a meeting" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(title: String) {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeetingRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val discardIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MeetingRecorderService::class.java).setAction(ACTION_DISCARD),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording meeting")
            .setContentText(title.ifEmpty { "Muesli" })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setWhen(startedAtMs)
            .setUsesChronometer(true)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Discard", discardIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Switches the foreground notification to dataSync (no mic, no chronometer, no Stop/Discard actions). */
    private fun startFinalizingForegroundNotification(title: String) {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Processing meeting")
            .setContentText(title.ifEmpty { "Muesli" })
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
