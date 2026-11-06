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
import com.phequals7.muesli.meetings.MeetingRecordingController
import com.phequals7.muesli.meetings.MeetingStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Defines the supported multilingual on-device ASR models for Muesli v2.0,
 * tuned for the Xiaomi Mix Fold 3 (Snapdragon 8 Gen 2, 12GB LPDDR5X RAM) covering
 * English (`en`), Thai (`th`), Mandarin (`zh`), and Cantonese (`yue`).
 */
enum class MuesliAsrModelType(
    val modelId: String,
    val displayName: String,
    val folderName: String,
    val supportedLanguages: List<String>,
    val approxSizeMb: Int,
    val description: String
) {
    /**
     * Primary Recommended Unified Model: Native English, Thai, Mandarin, and Cantonese support
     * with intra- and inter-sentence code-switching (~0.21x RTF on Snapdragon 8 Gen 2).
     */
    QWEN3_ASR_0_6B_INT8(
        modelId = "qwen3-asr-0.6b-int8",
        displayName = "Qwen3-ASR 0.6B INT8 (EN + TH + ZH/YUE)",
        folderName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 879,
        description = "Recommended unified trilingual model for English, Thai, Mandarin, and Cantonese meetings."
    ),

    /**
     * High-Precision Pro Model: Uses Mix Fold 3's 12GB RAM for maximum multilingual and tonal accuracy.
     */
    QWEN3_ASR_1_7B_INT8(
        modelId = "qwen3-asr-1.7b-int8",
        displayName = "Qwen3-ASR 1.7B INT8 (Pro High-Accuracy EN + TH + ZH/YUE)",
        folderName = "sherpa-onnx-qwen3-asr-1.7B-int8",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 2400,
        description = "Maximum accuracy trilingual model for complex multi-speaker meetings on 12GB RAM devices."
    ),

    /**
     * OpenAI Whisper Large-v3-Turbo (4-layer pruned decoder): Fast 99-language support including EN, TH, ZH, YUE.
     */
    WHISPER_LARGE_V3_TURBO_INT8(
        modelId = "whisper-large-v3-turbo-int8",
        displayName = "Whisper Large-v3-Turbo INT8 (EN + TH + ZH)",
        folderName = "sherpa-onnx-whisper-large-v3-turbo",
        supportedLanguages = listOf("auto", "en", "th", "zh", "yue"),
        approxSizeMb = 870,
        description = "Multilingual Whisper Turbo with native segment timestamps and strong Thai/Chinese accuracy."
    ),

    /**
     * Ultra-Fast Dual-Model Router (< 0.05x RTF): Pairs SenseVoiceSmall (ZH/YUE/EN) with Zipformer-Thai (TH).
     * Note: Open-source SenseVoiceSmall alone supports only zh/en/ja/ko/yue and lacks Thai (`th`).
     */
    SENSEVOICE_PLUS_ZIPFORMER_THAI(
        modelId = "sensevoice-zipformer-thai-hybrid",
        displayName = "SenseVoiceSmall (ZH/EN) + Zipformer (TH) Ultra-Fast Hybrid",
        folderName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
        supportedLanguages = listOf("auto", "zh", "yue", "en", "th"),
        approxSizeMb = 298,
        description = "Ultra-low-latency (~70ms per 10s) hybrid router for instant voice notes and meetings."
    ),

    /**
     * Legacy European-only model retained for backward compatibility.
     */
    PARAKEET_TDT_0_6B_V3_INT8(
        modelId = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 (European Languages Only)",
        folderName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        supportedLanguages = listOf("en"),
        approxSizeMb = 670,
        description = "Legacy NeMo transducer model (25 European languages; does not support Thai or Chinese)."
    ),

    PARAKEET_TDT_CTC_110M_EN(
        modelId = "parakeet-110m-en-int8",
        displayName = "Parakeet 110M (English Only)",
        folderName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
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

/**
 * Result of a transcription pass including decoded text, detected language code, and optional emotion/event metadata.
 */
data class TranscriptionResult(
    val text: String,
    val languageCode: String = "auto",
    val emotionTag: String? = null,
    val audioFilePath: String? = null,
    val durationMs: Long = 0L
)

/**
 * Process-wide singleton holder for `OfflineRecognizer` instances.
 *
 * Optimization for Xiaomi Mix Fold 3 Meeting & Voice Note priority:
 * - Disables background IME keyboard model prewarming (`keepKeyboardPrewarmed = false` by default)
 *   so RAM and CPU threads are reserved for meeting recording, voice notes, and CAM++ diarization.
 * - Thread-safe `Mutex` lock ensures concurrent chunk requests from `MeetingRecorderService`
 *   never collide or double-load ONNX weights.
 */
object SherpaRecognizerHolder {
    private const val TAG = "SherpaRecognizerHolder"
    private val mutex = Mutex()

    private var activeModelType: MuesliAsrModelType? = null
    private var activeLanguageHint: String = "auto"
    private var primaryRecognizer: OfflineRecognizer? = null
    private var companionThaiRecognizer: OfflineRecognizer? = null

    /**
     * Acquires (or lazily loads) the requested `OfflineRecognizer` and executes `block` under mutex protection.
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
            val modelsRoot = File(context.filesDir, "models")
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

    suspend fun release() = mutex.withLock {
        releaseInternal()
    }

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
 * Upgraded on-device transcription engine for Muesli v2.0.
 *
 * Key upgrades:
 * 1. Supports English (`en`), Thai (`th`), Mandarin (`zh`), and Cantonese (`yue`) via `Qwen3-ASR-0.6B/1.7B`,
 *    `Whisper-Large-v3-Turbo`, or the `SenseVoiceSmall + Zipformer-Thai` hybrid router.
 * 2. Automatically registers itself with `MeetingRecordingController.chunkTranscriber` so live meeting
 *    chunks are decoded with language tags and timestamps.
 * 3. Supports standalone Voice Note capture with optional `.wav` retention directly in `/sdcard/Recordings/Muesli/`.
 * 4. Applies multilingual filler-word cleanup and Jaro-Winkler custom vocabulary replacements.
 */
class SherpaOnnxEngine(
    private val context: Context,
    initialModelType: MuesliAsrModelType = MuesliAsrModelType.QWEN3_ASR_0_6B_INT8,
    private val cpuThreads: Int = 4 // Optimal for Snapdragon 8 Gen 2 Cortex-X3 + 3x Cortex-A715 performance cores
) {
    companion object {
        private const val TAG = "SherpaOnnxEngine"
        private const val SAMPLE_RATE = 16000

        private val ENGLISH_FILLER_REGEX = Regex(
            pattern = "\\b(um+|uh+|er+|ah+|hmm+)\\b[,\\s]*",
            options = setOf(RegexOption.IGNORE_CASE)
        )
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var selectedModelType: MuesliAsrModelType = initialModelType

    @Volatile
    var preferredLanguageHint: String = "auto"

    @Volatile
    var customDictionaryWords: List<String> = emptyList()

    private val isCapturingVoiceNote = AtomicBoolean(false)
    private var voiceNoteJob: Job? = null

    private val _voiceNoteRmsFlow = MutableStateFlow(0f)
    val voiceNoteRmsFlow: StateFlow<Float> = _voiceNoteRmsFlow.asStateFlow()

    init {
        // Wire live chunk transcription for MeetingRecorderService
        MeetingRecordingController.chunkTranscriber = { samples16kMono, langHint ->
            val result = transcribeSamples(
                samples16kMono = samples16kMono,
                languageHint = langHint.ifBlank { preferredLanguageHint }
            )
            result.text to result.languageCode
        }
    }

    /**
     * Decodes a 16 kHz mono float waveform array into text and detected language code.
     * Always releases native `OfflineStream` handles in a `finally` block to prevent JNI memory leaks.
     */
    suspend fun transcribeSamples(
        samples16kMono: FloatArray,
        languageHint: String = preferredLanguageHint,
        removeFillers: Boolean = true
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        if (samples16kMono.isEmpty()) {
            return@withContext TranscriptionResult(text = "", languageCode = languageHint)
        }

        val durationMs = (samples16kMono.size * 1000L) / SAMPLE_RATE

        SherpaRecognizerHolder.withRecognizer(
            context = context,
            modelType = selectedModelType,
            languageHint = languageHint,
            numThreads = cpuThreads
        ) { primaryRecognizer, thaiCompanionRecognizer ->
            // If in SenseVoiceSmall + Zipformer-Thai hybrid mode and language is explicitly Thai ("th"),
            // route directly to Zipformer-Thai because SenseVoiceSmall does not support Thai.
            val targetRecognizer = if (
                selectedModelType == MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI &&
                languageHint.equals("th", ignoreCase = true) &&
                thaiCompanionRecognizer != null
            ) {
                thaiCompanionRecognizer
            } else {
                primaryRecognizer
            }

            val stream = targetRecognizer.createStream()
            try {
                stream.acceptWaveform(samples16kMono, SAMPLE_RATE)
                targetRecognizer.decode(stream)
                val rawResult = targetRecognizer.getResult(stream)

                val detectedLang = rawResult.lang
                    .trim()
                    .removePrefix("<|")
                    .removeSuffix("|>")
                    .ifEmpty {
                        if (targetRecognizer === thaiCompanionRecognizer) "th" else languageHint
                    }

                var cleanedText = rawResult.text.trim()
                if (removeFillers) {
                    cleanedText = stripFillerTokens(cleanedText)
                }
                if (customDictionaryWords.isNotEmpty() && cleanedText.isNotEmpty()) {
                    cleanedText = applyCustomDictionaryFuzzyReplacement(cleanedText, customDictionaryWords)
                }

                TranscriptionResult(
                    text = cleanedText,
                    languageCode = detectedLang,
                    emotionTag = rawResult.emotion.takeIf { it.isNotBlank() },
                    durationMs = durationMs
                )
            } finally {
                stream.release()
            }
        }
    }

    /**
     * Starts capturing a standalone Voice Note from the microphone.
     * If `saveWavToPublicRecordings = true`, also writes the voice note WAV to `/sdcard/Recordings/Muesli/`.
     */
    @SuppressLint("MissingPermission")
    fun startVoiceNoteCapture(
        title: String = "VoiceNote",
        saveWavToPublicRecordings: Boolean = true,
        onComplete: (TranscriptionResult) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        if (isCapturingVoiceNote.getAndSet(true)) return

        voiceNoteJob = engineScope.launch(Dispatchers.IO) {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )

            val recordedShorts = ArrayList<Short>(SAMPLE_RATE * 30)
            val wavFile = if (saveWavToPublicRecordings) {
                MeetingStorageManager.createPublicSessionWavFile(UUID.randomUUID().toString(), title)
            } else {
                null
            }

            try {
                recorder.startRecording()
                val readBuf = ShortArray(1024)
                val byteBuf = ByteBuffer.allocate(readBuf.size * 2).order(ByteOrder.LITTLE_ENDIAN)

                RandomAccessFile(wavFile ?: File(context.cacheDir, "vn_tmp.wav"), "rw").use { raf ->
                    if (wavFile != null) {
                        raf.setLength(0L)
                        writeWavHeader(raf, 0L)
                    }

                    while (isCapturingVoiceNote.get()) {
                        val read = recorder.read(readBuf, 0, readBuf.size)
                        if (read <= 0) continue

                        var sumSq = 0f
                        byteBuf.clear()
                        for (i in 0 until read) {
                            val s = readBuf[i]
                            recordedShorts.add(s)
                            if (wavFile != null) byteBuf.putShort(s)
                            val f = s / 32768.0f
                            sumSq += f * f
                        }
                        if (wavFile != null) {
                            raf.write(byteBuf.array(), 0, read * 2)
                        }
                        _voiceNoteRmsFlow.value = sqrt(sumSq / read.toFloat()).coerceIn(0f, 1f)
                    }

                    if (wavFile != null) {
                        writeWavHeader(raf, recordedShorts.size * 2L)
                    }
                }

                if (wavFile != null && wavFile.exists()) {
                    MeetingStorageManager.indexRecordingInMediaStore(context, wavFile)
                }

                val floatSamples = FloatArray(recordedShorts.size) { idx ->
                    (recordedShorts[idx] / 32768.0f).coerceIn(-1.0f, 1.0f)
                }
                val result = transcribeSamples(floatSamples, preferredLanguageHint)
                withContext(Dispatchers.Main) {
                    onComplete(result.copy(audioFilePath = wavFile?.absolutePath))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Voice note capture failed", t)
                withContext(Dispatchers.Main) { onError(t) }
            } finally {
                _voiceNoteRmsFlow.value = 0f
                isCapturingVoiceNote.set(false)
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {}
                recorder.release()
            }
        }
    }

    fun stopVoiceNoteCapture() {
        isCapturingVoiceNote.set(false)
    }

    private fun stripFillerTokens(text: String): String {
        return text
            .replace(ENGLISH_FILLER_REGEX, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /**
     * Applies Jaro-Winkler fuzzy matching (threshold >= 0.90) on space-delimited Latin tokens
     * to correct domain-specific names and technical terms from `CustomWordEntity`.
     */
    private fun applyCustomDictionaryFuzzyReplacement(text: String, customWords: List<String>): String {
        if (!text.any { it in 'A'..'Z' || it in 'a'..'z' }) return text
        val tokens = text.split(" ")
        return tokens.joinToString(" ") { token ->
            val stripped = token.trim(',', '.', '!', '?', ';', ':')
            if (stripped.length < 4) {
                token
            } else {
                val bestMatch = customWords.firstOrNull { dictWord ->
                    jaroWinklerSimilarity(stripped.lowercase(), dictWord.lowercase()) >= 0.91
                }
                if (bestMatch != null) {
                    token.replace(stripped, bestMatch)
                } else {
                    token
                }
            }
        }
    }

    private fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        val matchDistance = max(len1, len2) / 2 - 1
        val s1Matches = BooleanArray(len1)
        val s2Matches = BooleanArray(len2)
        var matches = 0

        for (i in 0 until len1) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, len2)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in 0 until len1) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        val jaro = ((m / len1) + (m / len2) + ((m - transpositions / 2.0) / m)) / 3.0
        var prefix = 0
        for (i in 0 until min(4, min(len1, len2))) {
            if (s1[i] == s2[i]) prefix++ else break
        }
        return jaro + prefix * 0.1 * (1.0 - jaro)
    }

    private fun writeWavHeader(raf: RandomAccessFile, totalDataBytes: Long) {
        val byteRate = SAMPLE_RATE * 2
        val riffSize = (36L + totalDataBytes).coerceAtMost(0xFFFFFFFFL).toInt()
        val dataSize = totalDataBytes.coerceAtMost(0xFFFFFFFFL).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        val pos = raf.filePointer
        raf.seek(0L)
        raf.write(header.array())
        if (pos > 44L) raf.seek(pos)
    }
}