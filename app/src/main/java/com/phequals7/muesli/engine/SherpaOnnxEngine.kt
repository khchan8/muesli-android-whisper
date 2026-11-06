package com.phequals7.muesli.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.phequals7.muesli.audio.AudioInputRouteManager
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.model.SpeechModels
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Multilingual on-device ASR models for Muesli v2.0, covering English (`en`),
 * Thai (`th`), Mandarin (`zh`), and Cantonese (`yue`) on sherpa-onnx.
 *
 * Legacy Parakeet entries keep the v0.3.x `modelId`/`folderName` so existing
 * downloads and stored selections keep resolving without a re-download.
 */
enum class MuesliAsrModelType(
    val modelId: String,
    val displayName: String,
    val folderName: String,
    val supportedLanguages: List<String>,
    val approxSizeMb: Int,
    val description: String
) {
    QWEN3_ASR_0_6B_INT8(
        modelId = "qwen3-asr-0.6b-int8",
        displayName = "Qwen3-ASR 0.6B INT8 (EN + TH + ZH/YUE)",
        folderName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 879,
        description = "Recommended unified trilingual model for English, Thai, Mandarin, and Cantonese meetings."
    ),

    QWEN3_ASR_1_7B_INT8(
        modelId = "qwen3-asr-1.7b-int8",
        displayName = "Qwen3-ASR 1.7B INT8 (Pro High-Accuracy EN + TH + ZH/YUE)",
        folderName = "sherpa-onnx-qwen3-asr-1.7B-int8",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 2400,
        description = "Maximum accuracy trilingual model for complex multi-speaker meetings on 12GB RAM devices."
    ),

    WHISPER_LARGE_V3_TURBO_INT8(
        modelId = "whisper-large-v3-turbo-int8",
        displayName = "Whisper Large-v3-Turbo INT8 (EN + TH + ZH)",
        folderName = "sherpa-onnx-whisper-large-v3-turbo",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 870,
        description = "Multilingual Whisper Turbo with native segment timestamps and strong Thai/Chinese accuracy."
    ),

    SENSEVOICE_PLUS_ZIPFORMER_THAI(
        modelId = "sensevoice-zipformer-thai-hybrid",
        displayName = "SenseVoiceSmall (ZH/EN) + Zipformer (TH) Ultra-Fast Hybrid",
        folderName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        supportedLanguages = listOf("auto", "zh", "yue", "en", "th"),
        approxSizeMb = 298,
        description = "Ultra-low-latency (~70ms per 10s) hybrid router for instant voice notes and meetings."
    ),

    PARAKEET_TDT_0_6B_V3_INT8(
        modelId = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 (European Languages Only)",
        folderName = "parakeet-tdt-0.6b-v3-int8", // v0.3.x on-disk layout — keeps existing downloads valid
        supportedLanguages = listOf("en"),
        approxSizeMb = 670,
        description = "Legacy NeMo transducer model (25 European languages; does not support Thai or Chinese)."
    ),

    PARAKEET_TDT_CTC_110M_EN(
        modelId = "parakeet-tdt-ctc-110m-en-36000-int8", // v0.3.x id — keeps stored selections valid
        displayName = "Parakeet 110M (English Only)",
        folderName = "parakeet-tdt-ctc-110m-en-36000-int8",
        supportedLanguages = listOf("en"),
        approxSizeMb = 126,
        description = "Lightweight English-only model."
    );

    companion object {
        const val ZIPFORMER_THAI_FOLDER = "sherpa-onnx-zipformer-thai-2024-06-20"

        fun fromModelId(id: String?): MuesliAsrModelType {
            return entries.firstOrNull { it.modelId == id || it.folderName == id }
                ?: QWEN3_ASR_0_6B_INT8
        }
    }
}

/** Result of a transcription pass including decoded text, detected language code, and optional emotion metadata. */
data class TranscriptionResult(
    val text: String,
    val languageCode: String = "auto",
    val emotionTag: String? = null,
    val audioFilePath: String? = null,
    val durationMs: Long = 0L
)

/**
 * Process-wide singleton holder for [OfflineRecognizer] instances.
 *
 * Thread-safe `Mutex` ensures concurrent chunk requests from dictation, voice
 * notes, and `MeetingRecorderService` never collide or double-load ONNX
 * weights. Also keeps the launch warmup screen's `WarmState` contract.
 */
object SherpaRecognizerHolder {
    private const val TAG = "SherpaRecognizer"
    private val mutex = kotlinx.coroutines.sync.Mutex()

    enum class WarmState { IDLE, WARMING, READY, FAILED }

    @Volatile
    var warmState = WarmState.IDLE
        private set

    private val prewarmExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeModelType: MuesliAsrModelType? = null

    @Volatile
    private var activeLanguageHint: String = "auto"
    private var primaryRecognizer: OfflineRecognizer? = null
    private var companionThaiRecognizer: OfflineRecognizer? = null

    /** The catalog entry backing the currently selected model. */
    fun selectedModelType(context: Context): MuesliAsrModelType =
        SpeechModels.selected(context).asrModelType ?: MuesliAsrModelType.PARAKEET_TDT_CTC_110M_EN

    /**
     * Background model prewarm for the launch warmup screen. Idempotent.
     * Idle IME prewarming is separately gated by `SharedStore.keepMicReady`
     * (default off — meeting-first memory allocation).
     */
    fun prewarmAsync(context: Context) {
        if (warmState != WarmState.IDLE) return
        warmState = WarmState.WARMING
        prewarmExecutor.execute {
            try {
                runBlocking {
                    withRecognizer(context.applicationContext, selectedModelType(context)) { _, _ -> }
                }
                warmState = WarmState.READY
            } catch (t: Throwable) {
                Log.w(TAG, "Prewarm failed: ${t.message}")
                warmState = WarmState.FAILED
            }
        }
    }

    /** Drops the loaded recognizer(s) so the next use reloads the selected model. */
    suspend fun release() = mutex.withLock {
        releaseInternal()
        warmState = WarmState.IDLE
    }

    fun reset() {
        warmState = WarmState.IDLE
        primaryRecognizer?.release()
        primaryRecognizer = null
        companionThaiRecognizer?.release()
        companionThaiRecognizer = null
        activeModelType = null
    }

    /**
     * Acquires (or lazily loads) the requested recognizer and executes [block]
     * under mutex protection.
     */
    suspend fun <T> withRecognizer(
        context: Context,
        modelType: MuesliAsrModelType,
        languageHint: String = "auto",
        numThreads: Int = 4,
        block: (primary: OfflineRecognizer, thaiCompanion: OfflineRecognizer?) -> T
    ): T = mutex.withLock {
        val needsReload = primaryRecognizer == null ||
            activeModelType != modelType ||
            (modelType == MuesliAsrModelType.WHISPER_LARGE_V3_TURBO_INT8 && activeLanguageHint != languageHint)

        if (needsReload) {
            releaseInternal()
            val modelsRoot = File(context.applicationContext.filesDir, "models")
            val primaryConfig = buildRecognizerConfig(
                modelsRoot = modelsRoot,
                modelType = modelType,
                languageHint = languageHint,
                numThreads = numThreads
            )
            primaryRecognizer = OfflineRecognizer(assetManager = null, config = primaryConfig)

            if (modelType == MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI) {
                val thaiDir = File(modelsRoot, MuesliAsrModelType.ZIPFORMER_THAI_FOLDER)
                if (thaiDir.exists()) {
                    val thaiConfig = buildZipformerThaiConfig(thaiDir, numThreads = numThreads)
                    companionThaiRecognizer = OfflineRecognizer(assetManager = null, config = thaiConfig)
                }
            }

            activeModelType = modelType
            activeLanguageHint = languageHint
            Log.i(TAG, "Loaded ${modelType.displayName} (lang=$languageHint, threads=$numThreads)")
        }

        block(primaryRecognizer!!, companionThaiRecognizer)
    }

    /**
     * Decodes 16 kHz mono float chunks into text plus a detected language tag.
     * Routes explicit Thai audio to the Zipformer-Thai companion when the
     * SenseVoice hybrid is selected (SenseVoiceSmall has no Thai support).
     */
    suspend fun transcribe(
        context: Context,
        chunks: List<FloatArray>,
        languageHint: String = "auto"
    ): TranscriptionResult {
        val totalSamples = chunks.sumOf { it.size }
        if (totalSamples == 0) return TranscriptionResult(text = "", languageCode = languageHint)
        val durationMs = (totalSamples * 1000L) / 16000
        val modelType = selectedModelType(context)

        return withRecognizer(
            context = context.applicationContext,
            modelType = modelType,
            languageHint = languageHint
        ) { primary, thaiCompanion ->
            val targetRecognizer = if (
                modelType == MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI &&
                languageHint.equals("th", ignoreCase = true) &&
                thaiCompanion != null
            ) {
                thaiCompanion
            } else {
                primary
            }

            val stream = targetRecognizer.createStream()
            try {
                for (chunk in chunks) {
                    stream.acceptWaveform(chunk, 16000)
                }
                targetRecognizer.decode(stream)
                val raw = targetRecognizer.getResult(stream)
                val detectedLang = raw.lang.trim()
                    .removePrefix("<|").removeSuffix("|>")
                    .ifEmpty { if (targetRecognizer === thaiCompanion) "th" else languageHint }
                TranscriptionResult(
                    text = raw.text.trim(),
                    languageCode = detectedLang,
                    emotionTag = raw.emotion.takeIf { it.isNotBlank() },
                    durationMs = durationMs
                )
            } finally {
                stream.release()
            }
        }
    }

    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        languageHint: String = "auto"
    ): TranscriptionResult = transcribe(context, listOf(samples), languageHint)

    private fun releaseInternal() {
        try {
            companionThaiRecognizer?.release()
        } catch (_: Exception) {}
        companionThaiRecognizer = null

        try {
            primaryRecognizer?.release()
        } catch (_: Exception) {}
        primaryRecognizer = null
        activeModelType = null
    }

    private fun buildRecognizerConfig(
        modelsRoot: File,
        modelType: MuesliAsrModelType,
        languageHint: String,
        numThreads: Int
    ): OfflineRecognizerConfig {
        val modelDir = File(modelsRoot, modelType.folderName).absolutePath
        val normalizedLang = if (languageHint.equals("auto", ignoreCase = true)) "" else languageHint.lowercase()

        val modelConfig = when (modelType) {
            MuesliAsrModelType.QWEN3_ASR_0_6B_INT8,
            MuesliAsrModelType.QWEN3_ASR_1_7B_INT8 -> {
                val encoderFile = resolveInt8OrFp32(modelDir, "encoder.int8.onnx", "encoder.onnx")
                val decoderFile = resolveInt8OrFp32(modelDir, "decoder.int8.onnx", "decoder.onnx")
                OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = "$modelDir/conv_frontend.onnx",
                        encoder = encoderFile,
                        decoder = decoderFile,
                        tokenizer = "$modelDir/tokenizer",
                        maxTotalLen = 512,
                        maxNewTokens = 192,
                        temperature = 1e-6f
                    ),
                    tokens = "",
                    numThreads = numThreads,
                    provider = "cpu"
                )
            }

            MuesliAsrModelType.WHISPER_LARGE_V3_TURBO_INT8 -> {
                val encoderFile = findMatchingFile(
                    modelDir,
                    listOf("turbo-encoder.int8.onnx", "large-v3-turbo-encoder.int8.onnx", "encoder.int8.onnx", "encoder.onnx")
                )
                val decoderFile = findMatchingFile(
                    modelDir,
                    listOf("turbo-decoder.int8.onnx", "large-v3-turbo-decoder.int8.onnx", "decoder.int8.onnx", "decoder.onnx")
                )
                val tokensFile = findMatchingFile(
                    modelDir,
                    listOf("turbo-tokens.txt", "large-v3-turbo-tokens.txt", "tokens.txt")
                )
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoderFile,
                        decoder = decoderFile,
                        language = normalizedLang, // "" = automatic language detection across en/th/zh/yue
                        task = "transcribe",
                        tailPaddings = 1000,
                        enableTokenTimestamps = false,
                        enableSegmentTimestamps = true
                    ),
                    tokens = tokensFile,
                    modelType = "whisper",
                    numThreads = numThreads,
                    provider = "cpu"
                )
            }

            MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI -> {
                val senseModelFile = resolveInt8OrFp32(modelDir, "model.int8.onnx", "model.onnx")
                val senseLang = if (normalizedLang in listOf("zh", "en", "yue", "ja", "ko")) normalizedLang else "auto"
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = senseModelFile,
                        language = senseLang,
                        useInverseTextNormalization = true
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = numThreads,
                    provider = "cpu"
                )
            }

            MuesliAsrModelType.PARAKEET_TDT_0_6B_V3_INT8 -> {
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = resolveInt8OrFp32(modelDir, "encoder.int8.onnx", "encoder.onnx"),
                        decoder = resolveInt8OrFp32(modelDir, "decoder.int8.onnx", "decoder.onnx"),
                        joiner = resolveInt8OrFp32(modelDir, "joiner.int8.onnx", "joiner.onnx")
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "nemo_transducer",
                    numThreads = numThreads,
                    provider = "cpu"
                )
            }

            MuesliAsrModelType.PARAKEET_TDT_CTC_110M_EN -> {
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = resolveInt8OrFp32(modelDir, "model.int8.onnx", "model.onnx")
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = numThreads,
                    provider = "cpu"
                )
            }
        }

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
    }

    private fun buildZipformerThaiConfig(thaiModelDir: File, numThreads: Int): OfflineRecognizerConfig {
        val dir = thaiModelDir.absolutePath
        val encoder = findMatchingFile(dir, listOf("encoder-epoch-12-avg-5.int8.onnx", "encoder.int8.onnx", "encoder.onnx"))
        val decoder = findMatchingFile(dir, listOf("decoder-epoch-12-avg-5.onnx", "decoder.onnx", "decoder.int8.onnx"))
        val joiner = findMatchingFile(dir, listOf("joiner-epoch-12-avg-5.int8.onnx", "joiner.int8.onnx", "joiner.onnx"))

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder,
                    decoder = decoder,
                    joiner = joiner
                ),
                tokens = "$dir/tokens.txt",
                modelType = "transducer",
                numThreads = numThreads,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
    }

    private fun resolveInt8OrFp32(dirPath: String, int8Name: String, fp32Name: String): String {
        val int8File = File(dirPath, int8Name)
        return if (int8File.exists()) int8File.absolutePath else File(dirPath, fp32Name).absolutePath
    }

    private fun findMatchingFile(dirPath: String, candidates: List<String>): String {
        for (name in candidates) {
            val file = File(dirPath, name)
            if (file.exists()) return file.absolutePath
        }
        return File(dirPath, candidates.first()).absolutePath
    }
}

/**
 * On-device transcription engine backed by sherpa-onnx running the selected
 * catalog model (Qwen3-ASR / Whisper Turbo / SenseVoice+Zipformer-Thai / legacy
 * Parakeet).
 *
 * All models are non-streaming, so audio is captured via AudioRecord
 * and decoded on stop. While recording, the accumulated audio is re-decoded
 * every few seconds to produce pseudo-live partial results.
 */
class SherpaOnnxEngine(private val context: Context) : TranscriptionEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        private const val SAMPLE_RATE = 16000
        private const val PARTIAL_INTERVAL_MS = 4_000L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    /** Prevents partial re-decode backlog: on slow chips a full re-decode of
     * the accumulated audio takes longer than the 4s interval, so queued
     * partials would starve the final decode indefinitely. */
    private val partialBusy = AtomicBoolean(false)

    /** Engine callbacks always land on the main thread (callers may Toasts/UI). */
    private fun emitPartial(text: String) = mainHandler.post { onPartial?.invoke(text) }
    private fun emitFinal(text: String) = mainHandler.post { onFinal?.invoke(text) }
    private fun emitError(message: String) = mainHandler.post { onErr?.invoke(message) }

    private var audioRecord: AudioRecord? = null
    private val lock = Object()

    /**
     * Captured audio as an append-only list of 100 ms chunks. The previous
     * single-buffer + copyOf approach copied the entire recording every
     * 100 ms (quadratic churn) and OOMed on devices with small heap caps
     * (POCO C71, ~256 MB large-heap). sherpa's stream accepts multiple
     * acceptWaveform calls, so no large concatenation is ever needed.
     */
    private val chunks = mutableListOf<FloatArray>()
    private var activeMicPref = AudioInputRouteManager.MicPreference.AUTO
    private val routeManager by lazy { AudioInputRouteManager(context) }

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onErr: ((String) -> Unit)? = null
    private var amplitudeListener: ((Float) -> Unit)? = null

    override fun setAmplitudeListener(listener: ((Float) -> Unit)?) {
        amplitudeListener = listener
    }

    override fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onPartial = onPartialResult
        onFinal = onFinalResult
        onErr = onError

        if (!com.phequals7.muesli.model.ModelManager.getInstance(context).isDownloaded()) {
            onError("The selected speech model is not downloaded. Download it from Settings.")
            return
        }

        executor.execute {
            try {
                // Warm the recognizer before opening the mic so no audio is missed.
                runBlocking {
                    SherpaRecognizerHolder.withRecognizer(
                        context, SherpaRecognizerHolder.selectedModelType(context)
                    ) { _, _ -> }
                }
                startCapture()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start", t)
                if (!cancelled.get()) emitError("Could not start on-device engine: ${t.message}")
            }
        }
    }

    override fun stopListening() {
        if (!recording.compareAndSet(true, false)) return
        executor.execute {
            val audio = synchronized(lock) { chunks.toList() }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            routeManager.stopBluetoothSco(activeMicPref)

            if (cancelled.get()) return@execute
            if (audio.sumOf { it.size } < SAMPLE_RATE / 2) {
                emitFinal("")
                return@execute
            }
            try {
                val text = decode(audio)
                if (!cancelled.get()) emitFinal(text)
            } catch (t: Throwable) {
                Log.e(TAG, "Final decode failed", t)
                if (!cancelled.get()) emitError("Transcription failed: ${t.message}")
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        recording.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        audioRecord?.release()
        audioRecord = null
        routeManager.stopBluetoothSco(activeMicPref)
        synchronized(lock) { chunks.clear() }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before entry points reach here
    private fun startCapture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val pref = AudioInputRouteManager.MicPreference.fromId(SharedStore(context).micPreference)
        activeMicPref = pref
        // Best-effort BT SCO bring-up for the Bluetooth preference; SCO
        // support varies by OEM, so failures fall back to the default route.
        routeManager.startBluetoothSco(pref)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE)) // ~1s buffer
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        routeManager.resolveDevice(pref)?.let { record.setPreferredDevice(it) }

        audioRecord = record
        record.startRecording()
        recording.set(true)

        val chunk = FloatArray(SAMPLE_RATE / 10) // 100 ms reads
        var lastPartialAt = System.currentTimeMillis()

        while (recording.get()) {
            val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            synchronized(lock) {
                chunks.add(chunk.copyOf(read))
            }
            publishLevel(chunk, read)

            val now = System.currentTimeMillis()
            if (now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                lastPartialAt = now
                schedulePartial()
            }
        }
    }

    /** RMS of the latest capture chunk mapped to a 0..1 waveform level. */
    private fun publishLevel(chunk: FloatArray, read: Int) {
        val listener = amplitudeListener ?: return
        var sum = 0.0
        for (i in 0 until read) sum += chunk[i] * chunk[i]
        val rms = sqrt(sum / read).toFloat()
        // Map -45 dB .. -10 dB RMS to 0..1 (typical speech near the mic)
        val db = 20f * kotlin.math.log10(rms + 1e-8f)
        listener(((db + 45f) / 35f).coerceIn(0f, 1f))
    }

    /** Re-decodes the audio captured so far to emulate a live partial result. */
    private fun schedulePartial() {
        if (!partialBusy.compareAndSet(false, true)) return
        val snapshot = synchronized(lock) { chunks.toList() }
        if (snapshot.sumOf { it.size } < SAMPLE_RATE) {
            partialBusy.set(false)
            return
        }
        executor.execute {
            try {
                if (!recording.get() || cancelled.get()) return@execute
                try {
                    val text = decode(snapshot)
                    if (!cancelled.get() && text.isNotBlank()) emitPartial(text)
                } catch (t: Throwable) {
                    Log.w(TAG, "Partial decode failed: ${t.message}")
                }
            } finally {
                partialBusy.set(false)
            }
        }
    }

    /** Feeds chunks into the recognizer stream one by one (no concatenation)
     * and decodes. */
    private fun decode(audio: List<FloatArray>): String {
        val result = runBlocking {
            SherpaRecognizerHolder.transcribe(
                context, audio, SharedStore(context).languageHint
            )
        }
        return result.text
    }
}
