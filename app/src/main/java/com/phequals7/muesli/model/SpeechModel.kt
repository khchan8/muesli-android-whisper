package com.phequals7.muesli.model

import com.phequals7.muesli.engine.MuesliAsrModelType
import java.io.File

/**
 * Defines how a model artifact is downloaded and unpacked into `files/models/`.
 */
sealed class ModelDeliverySpec {
    /**
     * A compressed `.tar.bz2` archive (standard for `k2-fsa/sherpa-onnx` GitHub releases)
     * that extracts into `files/models/<targetFolderName>/`.
     */
    data class TarBz2Archive(
        val primaryUrl: String,
        val fallbackUrls: List<String> = emptyList(),
        val archiveRootFolderOverride: String? = null
    ) : ModelDeliverySpec()

    /**
     * A set of individual files downloaded directly into `files/models/<targetFolderName>/<relativePath>`
     * (used for HuggingFace repositories such as `Qwen3-ASR-1.7B` or standalone `.onnx` speaker models).
     */
    data class DirectFileManifest(
        val files: List<RemoteModelFile>
    ) : ModelDeliverySpec()

    /**
     * A composite bundle that requires installing two or more sub-models together
     * (e.g., `SenseVoiceSmall` for ZH/YUE/EN + `Zipformer-Thai` for TH).
     */
    data class CompositeBundle(
        val componentModelIds: List<String>
    ) : ModelDeliverySpec()
}

data class RemoteModelFile(
    val relativeDestinationPath: String,
    val primaryUrl: String,
    val fallbackUrls: List<String> = emptyList(),
    val expectedMinBytes: Long = 128L
)

enum class ModelCategory {
    ASR_MULTILINGUAL,
    ASR_LEGACY,
    SPEAKER_DIARIZATION
}

/**
 * Metadata and verification contract for each downloadable ASR or Speaker Diarization model.
 */
data class SpeechModelSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: ModelCategory,
    val asrModelType: MuesliAsrModelType? = null,
    val targetFolderName: String,
    val supportedLanguages: List<String>,
    val approxDownloadSizeMb: Int,
    val approxRamFootprintMb: Int,
    val recommendedForMixFold3: Boolean = false,
    val requiredRelativeFiles: List<String>,
    val delivery: ModelDeliverySpec
) {
    /** Compact name for chips and status lines ("Qwen3-ASR 0.6B", "Parakeet 110M"). */
    val shortName: String get() = title.substringBefore(" (").substringBefore(" +").trim()

    /** One-line language summary for list rows ("EN + TH + ZH + YUE"). */
    val capabilityLabel: String get() = supportedLanguages.joinToString(" + ") { it.uppercase() }

    val totalSizeBytes: Long get() = approxDownloadSizeMb * 1024L * 1024L

    /**
     * Checks whether all required model files exist and are non-empty inside `modelsRootDir`.
     */
    fun isInstalled(modelsRootDir: File): Boolean {
        if (delivery is ModelDeliverySpec.CompositeBundle) {
            return delivery.componentModelIds.all { compId ->
                SpeechModels.findById(compId)?.isInstalled(modelsRootDir) == true
            }
        }
        val baseDir = if (targetFolderName.isEmpty()) modelsRootDir else File(modelsRootDir, targetFolderName)
        if (!baseDir.exists()) return false
        return requiredRelativeFiles.all { relPath ->
            val file = File(baseDir, relPath)
            file.exists() && file.isFile && file.length() > 64L
        }
    }
}

/**
 * Central catalog of ASR (English, Thai, Mandarin, Cantonese) and Speaker Diarization (CAM++, Pyannote)
 * models for Muesli for Android v2.0.
 */
object SpeechModels {

    // Base URLs for official k2-fsa/sherpa-onnx releases and HuggingFace repositories
    private const val SHERPA_ASR_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private const val SHERPA_SPEAKER_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"
    private const val SHERPA_SEGMENTATION_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models"

    private const val HF_QWEN3_0_6B_PRIMARY =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"
    private const val HF_QWEN3_0_6B_MIRROR =
        "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-0.6B-int8/resolve/main"
    private const val HF_QWEN3_1_7B_PRIMARY =
        "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8/resolve/main"
    private const val HF_PARAKEET_V3_PRIMARY =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

    // ========================================================================
    // 1. MULTILINGUAL ASR MODELS (EN + TH + ZH + YUE)
    // ========================================================================

    val QWEN3_ASR_0_6B_INT8 = SpeechModelSpec(
        id = MuesliAsrModelType.QWEN3_ASR_0_6B_INT8.modelId,
        title = "Qwen3-ASR 0.6B (INT8)",
        subtitle = "Default Unified EN + Thai + Mandarin + Cantonese (Code-Switching, ~0.21x RTF)",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = MuesliAsrModelType.QWEN3_ASR_0_6B_INT8,
        targetFolderName = MuesliAsrModelType.QWEN3_ASR_0_6B_INT8.folderName,
        supportedLanguages = listOf("en", "th", "zh", "yue"),
        approxDownloadSizeMb = 879,
        approxRamFootprintMb = 2000,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json"
        ),
        delivery = ModelDeliverySpec.TarBz2Archive(
            primaryUrl = "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            fallbackUrls = listOf(
                "$HF_QWEN3_0_6B_PRIMARY/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
            )
        )
    )

    val QWEN3_ASR_0_6B_DIRECT_MANIFEST = ModelDeliverySpec.DirectFileManifest(
        files = listOf(
            RemoteModelFile(
                relativeDestinationPath = "conv_frontend.onnx",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/conv_frontend.onnx",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/conv_frontend.onnx")
            ),
            RemoteModelFile(
                relativeDestinationPath = "encoder.int8.onnx",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/encoder.int8.onnx",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/encoder.int8.onnx")
            ),
            RemoteModelFile(
                relativeDestinationPath = "decoder.int8.onnx",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/decoder.int8.onnx",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/decoder.int8.onnx")
            ),
            RemoteModelFile(
                relativeDestinationPath = "tokenizer/vocab.json",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/tokenizer/vocab.json",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/tokenizer/vocab.json")
            ),
            RemoteModelFile(
                relativeDestinationPath = "tokenizer/merges.txt",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/tokenizer/merges.txt",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/tokenizer/merges.txt")
            ),
            RemoteModelFile(
                relativeDestinationPath = "tokenizer/tokenizer_config.json",
                primaryUrl = "$HF_QWEN3_0_6B_PRIMARY/tokenizer/tokenizer_config.json",
                fallbackUrls = listOf("$HF_QWEN3_0_6B_MIRROR/tokenizer/tokenizer_config.json")
            )
        )
    )

    val QWEN3_ASR_1_7B_INT8 = SpeechModelSpec(
        id = MuesliAsrModelType.QWEN3_ASR_1_7B_INT8.modelId,
        title = "Qwen3-ASR 1.7B Pro (INT8)",
        subtitle = "Maximum Accuracy EN + Thai + Mandarin + Cantonese for 12GB RAM (~0.55x RTF)",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = MuesliAsrModelType.QWEN3_ASR_1_7B_INT8,
        targetFolderName = MuesliAsrModelType.QWEN3_ASR_1_7B_INT8.folderName,
        supportedLanguages = listOf("en", "th", "zh", "yue"),
        approxDownloadSizeMb = 2400,
        approxRamFootprintMb = 3500,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json"
        ),
        delivery = ModelDeliverySpec.DirectFileManifest(
            files = listOf(
                RemoteModelFile(
                    relativeDestinationPath = "conv_frontend.onnx",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/conv_frontend.onnx"
                ),
                RemoteModelFile(
                    relativeDestinationPath = "encoder.int8.onnx",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/encoder.int8.onnx"
                ),
                RemoteModelFile(
                    relativeDestinationPath = "decoder.int8.onnx",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/decoder.int8.onnx"
                ),
                RemoteModelFile(
                    relativeDestinationPath = "tokenizer/vocab.json",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/tokenizer/vocab.json"
                ),
                RemoteModelFile(
                    relativeDestinationPath = "tokenizer/merges.txt",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/tokenizer/merges.txt"
                ),
                RemoteModelFile(
                    relativeDestinationPath = "tokenizer/tokenizer_config.json",
                    primaryUrl = "$HF_QWEN3_1_7B_PRIMARY/tokenizer/tokenizer_config.json"
                )
            )
        )
    )

    val WHISPER_LARGE_V3_TURBO_INT8 = SpeechModelSpec(
        id = MuesliAsrModelType.WHISPER_LARGE_V3_TURBO_INT8.modelId,
        title = "Whisper Large-v3-Turbo (INT8)",
        subtitle = "OpenAI 99-Language Pruned 4-Layer Decoder (EN + TH + ZH + YUE, ~0.30x RTF)",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = MuesliAsrModelType.WHISPER_LARGE_V3_TURBO_INT8,
        targetFolderName = MuesliAsrModelType.WHISPER_LARGE_V3_TURBO_INT8.folderName,
        supportedLanguages = listOf("en", "th", "zh", "yue"),
        approxDownloadSizeMb = 545,
        approxRamFootprintMb = 1300,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf(
            "turbo-encoder.int8.onnx",
            "turbo-decoder.int8.onnx",
            "turbo-tokens.txt"
        ),
        delivery = ModelDeliverySpec.TarBz2Archive(
            primaryUrl = "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-whisper-turbo.tar.bz2",
            archiveRootFolderOverride = "sherpa-onnx-whisper-turbo"
        )
    )

    /**
     * Component A of the Ultra-Fast Hybrid Router: SenseVoiceSmall INT8 (`zh`, `yue`, `en`, `ja`, `ko`).
     * Uses the `2024-07-17` checkpoint by default because it preserves full punctuation when `use_itn=true`
     * (see `k2-fsa/sherpa-onnx` Issue #2742), while placing it in the canonical SenseVoice folder.
     */
    val SENSEVOICE_SMALL_COMPONENT = SpeechModelSpec(
        id = "component-sensevoice-small-int8",
        title = "SenseVoiceSmall INT8 (ZH + YUE + EN Component)",
        subtitle = "Non-autoregressive ultra-fast model for Mandarin, Cantonese, and English",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = null,
        targetFolderName = MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI.folderName,
        supportedLanguages = listOf("zh", "yue", "en"),
        approxDownloadSizeMb = 230,
        approxRamFootprintMb = 380,
        requiredRelativeFiles = listOf(
            "model.int8.onnx",
            "tokens.txt"
        ),
        delivery = ModelDeliverySpec.TarBz2Archive(
            primaryUrl = "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            fallbackUrls = listOf(
                "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
            )
        )
    )

    /**
     * Component B of the Ultra-Fast Hybrid Router: Zipformer-Thai INT8 (`th`).
     */
    val ZIPFORMER_THAI_COMPONENT = SpeechModelSpec(
        id = "component-zipformer-thai-int8",
        title = "Zipformer-Thai INT8 (Thai Component)",
        subtitle = "Dedicated fast transducer for Thai speech recognition",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = null,
        targetFolderName = MuesliAsrModelType.ZIPFORMER_THAI_FOLDER,
        supportedLanguages = listOf("th"),
        approxDownloadSizeMb = 68,
        approxRamFootprintMb = 160,
        requiredRelativeFiles = listOf(
            "encoder-epoch-12-avg-5.int8.onnx",
            "decoder-epoch-12-avg-5.onnx",
            "joiner-epoch-12-avg-5.int8.onnx",
            "tokens.txt"
        ),
        delivery = ModelDeliverySpec.TarBz2Archive(
            primaryUrl = "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-zipformer-thai-2024-06-20.tar.bz2"
        )
    )

    val SENSEVOICE_PLUS_ZIPFORMER_THAI_HYBRID = SpeechModelSpec(
        id = MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI.modelId,
        title = "SenseVoiceSmall (ZH/EN) + Zipformer (TH) Hybrid",
        subtitle = "Ultra-Fast Voice Note & Single-Language Mode (<0.05x RTF, ~70ms per 10s)",
        category = ModelCategory.ASR_MULTILINGUAL,
        asrModelType = MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI,
        targetFolderName = MuesliAsrModelType.SENSEVOICE_PLUS_ZIPFORMER_THAI.folderName,
        supportedLanguages = listOf("zh", "yue", "en", "th"),
        approxDownloadSizeMb = 298,
        approxRamFootprintMb = 540,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf("model.int8.onnx", "tokens.txt"),
        delivery = ModelDeliverySpec.CompositeBundle(
            componentModelIds = listOf(SENSEVOICE_SMALL_COMPONENT.id, ZIPFORMER_THAI_COMPONENT.id)
        )
    )

    // ========================================================================
    // 2. SPEAKER DIARIZATION & VOICEPRINT PROFILE MODELS (CAM++ & PYANNOTE)
    // ========================================================================

    val CAMPLUS_ZH_EN_SPEAKER_EMBEDDING = SpeechModelSpec(
        id = "speaker-embedding-campplus-zh-en",
        title = "3D-Speaker CAM++ Bilingual (ZH + EN/TH, 192-dim)",
        subtitle = "Required for accurate 3-speaker diarization and cross-session Speaker Profiles",
        category = ModelCategory.SPEAKER_DIARIZATION,
        asrModelType = null,
        targetFolderName = "", // Placed directly in files/models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx
        supportedLanguages = listOf("en", "th", "zh", "yue"),
        approxDownloadSizeMb = 28,
        approxRamFootprintMb = 90,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf(
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        ),
        delivery = ModelDeliverySpec.DirectFileManifest(
            files = listOf(
                RemoteModelFile(
                    relativeDestinationPath = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
                    primaryUrl = "$SHERPA_SPEAKER_RELEASE_BASE/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
                    fallbackUrls = listOf(
                        "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
                    ),
                    expectedMinBytes = 25_000_000L
                )
            )
        )
    )

    val PYANNOTE_SEGMENTATION_3_0 = SpeechModelSpec(
        id = "speaker-segmentation-pyannote-3-0",
        title = "Pyannote Segmentation 3.0 (INT8)",
        subtitle = "Overlap-aware speaker turn segmentation for meeting recordings",
        category = ModelCategory.SPEAKER_DIARIZATION,
        asrModelType = null,
        targetFolderName = "sherpa-onnx-pyannote-segmentation-3-0",
        supportedLanguages = listOf("en", "th", "zh", "yue"),
        approxDownloadSizeMb = 2,
        approxRamFootprintMb = 45,
        recommendedForMixFold3 = true,
        requiredRelativeFiles = listOf("model.int8.onnx"),
        delivery = ModelDeliverySpec.DirectFileManifest(
            files = listOf(
                RemoteModelFile(
                    relativeDestinationPath = "model.int8.onnx",
                    primaryUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx",
                    fallbackUrls = listOf(
                        "$SHERPA_SEGMENTATION_RELEASE_BASE/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
                    ),
                    expectedMinBytes = 1_000_000L
                )
            )
        )
    )

    // ========================================================================
    // 3. LEGACY EUROPEAN / ENGLISH MODELS (v0.3.0 Backward Compatibility)
    // ========================================================================

    val PARAKEET_TDT_0_6B_V3_INT8 = SpeechModelSpec(
        id = MuesliAsrModelType.PARAKEET_TDT_0_6B_V3_INT8.modelId,
        title = "Parakeet TDT 0.6B v3 (Legacy European)",
        subtitle = "Supports 25 European languages only (No Thai or Chinese support)",
        category = ModelCategory.ASR_LEGACY,
        asrModelType = MuesliAsrModelType.PARAKEET_TDT_0_6B_V3_INT8,
        targetFolderName = MuesliAsrModelType.PARAKEET_TDT_0_6B_V3_INT8.folderName,
        supportedLanguages = listOf("en"),
        approxDownloadSizeMb = 670,
        approxRamFootprintMb = 950,
        requiredRelativeFiles = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        ),
        delivery = ModelDeliverySpec.DirectFileManifest(
            files = listOf(
                RemoteModelFile("encoder.int8.onnx", "$HF_PARAKEET_V3_PRIMARY/encoder.int8.onnx"),
                RemoteModelFile("decoder.int8.onnx", "$HF_PARAKEET_V3_PRIMARY/decoder.int8.onnx"),
                RemoteModelFile("joiner.int8.onnx", "$HF_PARAKEET_V3_PRIMARY/joiner.int8.onnx"),
                RemoteModelFile("tokens.txt", "$HF_PARAKEET_V3_PRIMARY/tokens.txt")
            )
        )
    )

    val PARAKEET_110M_EN_INT8 = SpeechModelSpec(
        id = MuesliAsrModelType.PARAKEET_TDT_CTC_110M_EN.modelId,
        title = "Parakeet 110M (Legacy English Only)",
        subtitle = "Compact English-only model (No Thai or Chinese support)",
        category = ModelCategory.ASR_LEGACY,
        asrModelType = MuesliAsrModelType.PARAKEET_TDT_CTC_110M_EN,
        targetFolderName = MuesliAsrModelType.PARAKEET_TDT_CTC_110M_EN.folderName,
        supportedLanguages = listOf("en"),
        approxDownloadSizeMb = 126,
        approxRamFootprintMb = 320,
        requiredRelativeFiles = listOf("model.int8.onnx", "tokens.txt"),
        delivery = ModelDeliverySpec.TarBz2Archive(
            primaryUrl = "$SHERPA_ASR_RELEASE_BASE/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2"
        )
    )

    /**
     * User-visible ASR models in Settings -> Models (ordered by recommendation for EN/TH/ZH).
     */
    val selectableAsrModels: List<SpeechModelSpec> = listOf(
        QWEN3_ASR_0_6B_INT8,
        QWEN3_ASR_1_7B_INT8,
        WHISPER_LARGE_V3_TURBO_INT8,
        SENSEVOICE_PLUS_ZIPFORMER_THAI_HYBRID,
        PARAKEET_TDT_0_6B_V3_INT8,
        PARAKEET_110M_EN_INT8
    )

    /**
     * Required speaker diarization models installed automatically or via Settings -> Models.
     */
    val diarizationModels: List<SpeechModelSpec> = listOf(
        CAMPLUS_ZH_EN_SPEAKER_EMBEDDING,
        PYANNOTE_SEGMENTATION_3_0
    )

    /**
     * Complete registry including internal composite components.
     */
    val allModels: List<SpeechModelSpec> = selectableAsrModels + diarizationModels + listOf(
        SENSEVOICE_SMALL_COMPONENT,
        ZIPFORMER_THAI_COMPONENT
    )

    fun findById(id: String): SpeechModelSpec? = allModels.firstOrNull { it.id == id }

    const val DEFAULT_ID = "qwen3-asr-0.6b-int8"

    /** Resolves a stored selection; unknown/blank ids fall back to the v2 default. */
    fun byId(id: String?): SpeechModelSpec =
        selectableAsrModels.firstOrNull { it.id == id } ?: QWEN3_ASR_0_6B_INT8

    fun selected(context: android.content.Context): SpeechModelSpec =
        byId(com.phequals7.muesli.data.SharedStore(context.applicationContext).selectedModelId)
}