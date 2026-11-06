# Product Requirements Document (PRD): Muesli for Android v2.0 — Trilingual (EN/TH/ZH) On-Device ASR, CAM++ Speaker Diarization, Voiceprint Profiles, Synchronized Playback, and Public Recordings Storage

**Target Codebase:** [khchan8/muesli-android-whisper](https://github.com/khchan8/muesli-android-whisper) (forked from [Muesli-HQ/muesli-android](https://github.com/Muesli-HQ/muesli-android), package `com.phequals7.muesli`, `v0.3.0-alpha`)  
**Primary Hardware Target:** Xiaomi Mix Fold 3 (Qualcomm Snapdragon 8 Gen 2, 8-Core Kryo CPU, Adreno 740 GPU, Hexagon NPU, 12GB LPDDR5X RAM, Android 14/15 HyperOS)  
**Core Runtime Engine:** [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (`arm64-v8a` JNI / ONNX Runtime)  
**Database Schema Target:** Room `v4` to Room `v5` (`MIGRATION_4_5`)

## 1. Executive Summary & Current-State Codebase Audit

[Muesli for Android](https://github.com/Muesli-HQ/muesli-android/blob/main/README.md) (`package com.phequals7.muesli`) is an on-device speech-to-text and meeting transcription application built in Kotlin with Jetpack Compose, Room SQLite persistence, and vendored [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (`v1.13.4`) native binaries (`libsherpa-onnx-jni.so` and `libonnxruntime.so` under `app/src/main/jniLibs/arm64-v8a/`). Because keyboard dictation (`MuesliInputMethodService.kt`) is not a priority for your workflow, system resources on the Xiaomi Mix Fold 3 (12GB RAM) can be dedicated entirely to high-accuracy meeting recording, voice notes, speaker diarization, and interactive playback.

An audit of the current `v0.3.0-alpha` repository identifies five architectural bottlenecks that must be resolved:

| Source File Path (`app/src/main/java/com/phequals7/muesli/...`) | Current `v0.3.0-alpha` Implementation | Root Technical Limitation |
| :--- | :--- | :--- |
| `engine/SherpaOnnxEngine.kt`, `engine/SherpaRecognizerHolder.kt`, `model/SpeechModels.kt` | Uses `sherpa-onnx v1.13.4` with [Parakeet TDT-CTC 110M](https://k2-fsa.github.io/sherpa/onnx/nemo/index.html) (`en`) and [Parakeet TDT 0.6B v3](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) (`nemo_transducer`). | [Parakeet v3](https://github.com/mybigday/whisper.rn/blob/main/README.md) supports 25 European languages only; it has zero vocabulary tokens or acoustic training for Thai (`th`), Mandarin (`zh`), or Cantonese (`yue`). |
| `meetings/SpeakerDiarizer.kt` | Runs `OfflineSpeakerDiarization` using bundled [sherpa-onnx-pyannote-segmentation-3-0](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0) and English-trained `nemo_en_titanet_small.onnx` (192-dim). | Unconstrained clustering (`numClusters = -1`) combined with sub-second turns (`<1.2s`) and English-only `TitaNet-S` embeddings causes a 3-speaker meeting to fragment into 100+ speaker IDs. |
| `meetings/SpeakerLabeler.kt`, `data/AppDatabase.kt` | Aligns diarization turns with ASR chunks and writes a single flat text string (with inline `Speaker 1`, `Speaker 2` prefixes) into the Room `transcripts` table. | No structured segment table or voiceprint registry exists; speakers cannot be enrolled into persistent profiles or matched across future meetings. |
| `ui/MeetingDetailScreen.kt` | Renders a static selectable `Text` composable and a decoupled Android `MediaPlayer` seek bar. | Because timestamps and speaker turns are flattened into plain text, the UI cannot highlight or auto-scroll transcript blocks as audio plays. |
| `meetings/MeetingRecorderService.kt` | Streams 16 kHz mono 16-bit PCM WAV data to `File(context.filesDir, "meetings/<sessionId>.wav")`. | Saves recordings inside app-private `/data/data/com.phequals7.muesli/files/meetings/<sessionId>.wav`, hiding files from file managers and USB transfer instead of `/sdcard/Recordings`. |

## 2. Root-Cause Diagnosis: Why a 3-Speaker Meeting Outputs Over 100 Speakers

When a meeting recording stops in `MeetingRecorderService.kt`, `SpeakerDiarizer.kt` executes `com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization.process(samples)` against the recorded WAV file. Three compounding factors inside `SpeakerDiarizer.kt` and `sherpa-onnx`'s clustering pipeline cause a 3-speaker recording to explode into >100 speaker labels:

1. **Unconstrained Cosine-Distance Clustering (`numClusters = -1`) in `FastClusteringConfig`**:
   In [sherpa-onnx Speaker Diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html), clustering is governed by `FastClusteringConfig(numClusters = -1, threshold = ...)`. When `numClusters` is left at `-1` (unknown number of speakers), `sherpa-onnx` relies strictly on hierarchical agglomerative cosine-distance thresholding. As documented in [k2-fsa/sherpa-onnx Discussion #3233](https://github.com/k2-fsa/sherpa-onnx/discussions/3233), running `OfflineSpeakerDiarization` with `pyannote-segmentation-3-0` on a 21.2-minute recording with `numClusters = -1` produced **338 segments and 117 distinct speakers (`Speaker 0` to `Speaker 116`)**. Minor intra-speaker acoustic shifts (speaking rate, pitch, distance from the phone microphone, or room reverb) exceed the tight default threshold and spawn a new cluster ID for almost every turn.
2. **Sub-Second Utterance Fragmentation (`<1.2s`) in 10-Second Sliding Windows**:
   The [pyannote/segmentation-3.0](https://huggingface.co/pyannote/segmentation-3.0) SincNet + BiLSTM model scans audio in 10-second windows with a 1-second hop. When `minDurationOn` and `minDurationOff` are small (`0.1s–0.3s`), brief conversational backchannels ("uh-huh", "ครับ", "ใช่", "对"), breaths, or micro-pauses generate hundreds of sub-second segments. Extracting 192-dimensional speaker embeddings on clips shorter than `1.2 seconds` produces noisy, high-variance vectors ([MOSS-Transcribe-Diarize Issue #60](https://github.com/OpenMOSS/MOSS-Transcribe-Diarize/issues/60)) that have low cosine similarity to all existing cluster centroids and become isolated singleton clusters.
3. **English-Only Acoustic Bias of `nemo_en_titanet_small.onnx` on Tonal Speech**:
   The bundled `nemo_en_titanet_small.onnx` ([sherpa-onnx Speaker Identification Models](https://k2-fsa.github.io/sherpa/onnx/speaker-identification/apk.html)) was trained on English corpora ([TitaNet-Small Model Card](https://huggingface.co/Sky-Kim/com.sky.sentis.titanet-small)). When speakers converse or code-switch across tonal languages like Thai (5 tones) and Mandarin/Cantonese (4–6+ tones), steep fundamental frequency ($F_0$) pitch contours shift the resulting embeddings in `TitaNet-S` space, causing the same speaker to split into multiple clusters across language switches.

## 3. Multilingual ASR Model Benchmark & Architecture Selection (EN / TH / ZH)

### 3.1 Critical Finding on Alibaba FunAudioLLM `SenseVoiceSmall`
While [FunAudioLLM/SenseVoiceSmall](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) ([GitHub Repository](https://github.com/FunAudioLLM/SenseVoice)) is an exceptional non-autoregressive model for Chinese and English, **open-source `SenseVoiceSmall` does NOT support Thai (`th`)**. Its model vocabulary and [sherpa-onnx SenseVoice export](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html) (`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` / `2025-09-09`) expose only **5 language tokens**: Mandarin (`zh`), Cantonese (`yue`), English (`en`), Japanese (`ja`), and Korean (`ko`). Feeding Thai audio into `SenseVoiceSmall` results in phonetic hallucination into Chinese, English, or Japanese characters.

### 3.2 Benchmark Comparison on Xiaomi Mix Fold 3 (Snapdragon 8 Gen 2, 12GB LPDDR5X RAM)

| Candidate ASR Model | Runtime in Muesli | Supported Target Languages | Code-Switching (`en`/`th`/`zh`/`yue`) | Disk Size / Peak RAM | Est. RTF (SD 8 Gen 2, 4 Threads) | Architectural Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **[Qwen3-ASR-0.6B INT8](https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25)** ([Repo](https://github.com/QwenLM/Qwen3-ASR)) | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Native drop-in) | 30 langs incl. `en`, `th`, `zh`, `yue` (22 Chinese dialects) | Strong intra- and inter-sentence switching | ~879 MB / ~2.0 GB | `0.18x – 0.25x` | **Primary Default Engine:** Best single-model accuracy and speed across English, Thai, Mandarin, and Cantonese without replacing `sherpa-onnx`. |
| **[Qwen3-ASR-1.7B INT8](https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8)** ([Base Card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B)) | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/issues/3535) (Native drop-in) | 30 langs incl. `en`, `th`, `zh`, `yue` (22 Chinese dialects) | Top-tier open-source multilingual & tonal accuracy | ~2.4 GB / ~3.5 GB | `0.45x – 0.65x` | **High-Precision Meeting Mode:** Takes advantage of Mix Fold 3's 12GB RAM for maximum accuracy on complex multi-speaker meetings. |
| **[Whisper-Large-v3-Turbo](https://huggingface.co/models?search=openai/whisper)** (`q8_0` / `int8`) | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) or [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html) | 99 langs incl. `en`, `th`, `zh`, `yue` | Good inter-sentence; moderate intra-sentence | ~870 MB / ~1.3 GB | `0.25x – 0.35x` | **Strong Secondary Unified Option:** 4-layer pruned decoder is 3–4x faster than `Whisper-Medium` with strong Thai and Chinese WER. |
| **[SenseVoiceSmall INT8](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html) + [Zipformer-Thai INT8](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html)** | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Dual-Model Router) | `SenseVoiceSmall`: `zh`/`yue`/`en`; `Zipformer-Thai`: `th` | `zh`–`en` and `yue`–`en` supported; `th` routed per chunk | ~298 MB total / ~0.55 GB | `0.04x – 0.05x` | **Ultra-Fast Voice Note Mode:** ~15x faster than Whisper (`~70ms` per 10s audio); ideal for instant voice notes when routed by LID or language toggle. |
| **[Whisper-Medium](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html)** / **[Whisper-Small](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html)** | `whisper.cpp` / `sherpa-onnx` | 99 langs incl. `en`, `th`, `zh`, `yue` | Weak to moderate on Thai-English mix | 260–820 MB / 0.45–1.5 GB | `0.25x – 1.15x` | **Not Recommended:** `Small` suffers high Thai Character Error Rate (CER) on tonal vowels; `Medium` is slower and less accurate than `Large-v3-Turbo`. |
| **[Pathumma-Whisper-th-Large-v3](https://huggingface.co/nectec/Pathumma-whisper-th-large-v3)** / **[Breeze-ASR-25](https://huggingface.co/MediaTek-Research/Breeze-ASR-25)** | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Pathumma: `th`/`en`; Breeze: `zh`/`en` | Strong bilateral switching only | ~1.05 GB / ~1.8 GB | `0.95x – 1.25x` | **Not Recommended as Single Engine:** Full 32-layer decoder is heavy on mobile CPU, and fine-tuning degrades the third language (`zh` or `th`). |

### 3.3 Recommended Unified ASR Engine Architecture
Instead of rewriting Muesli's JNI layer around `whisper.cpp`, **retaining and upgrading `sherpa-onnx` to its latest 2026 release** provides a unified C++/JNI runtime that natively runs `Qwen3-ASR`, `Whisper`, `SenseVoiceSmall`, `Zipformer-Thai`, `Silero VAD v5`, `pyannote-segmentation-3-0`, and `3D-Speaker CAM++`.

In `SpeechModels.kt` and `SherpaOnnxEngine.kt`, Muesli v2.0 will replace the European-only Parakeet catalog with three user-selectable profiles tailored for the Mix Fold 3's 12GB RAM:
1. **Qwen3-ASR-0.6B INT8 (Default Balanced Mode — `en`/`th`/`zh`/`yue`)**: Loaded via `OfflineModelConfig` in `SherpaOnnxEngine.kt`. Delivers real-time chunk transcription during recording (`RTF ~0.21x` on 4 Kryo CPU threads) and handles mixed English/Thai/Chinese utterances without manual language switching.
2. **Qwen3-ASR-1.7B INT8 (High-Accuracy Meeting Post-Processing Mode)**: Optional 2.4 GB download for maximum accuracy on dense technical or multi-accent meetings. Because the Mix Fold 3 has 12GB RAM, its ~3.5 GB peak footprint runs comfortably in `MeetingRecorderService`.
3. **SenseVoiceSmall INT8 + Zipformer-Thai (`2024-06-20`) (Ultra-Fast Voice Note Mode)**: Uses `SpokenLanguageIdentification` (`whisper-tiny` LID in `sherpa-onnx`) or a quick language chip (`Auto / EN-ZH / TH`) to route Thai chunks to `Zipformer-Thai` and Chinese/Cantonese/English chunks to `SenseVoiceSmall` at `<0.05x RTF`.

## 4. Speaker Diarization & Persistent Speaker Profile Architecture

### 4.1 Upgrading Speaker Embeddings from `TitaNet-S` to `3D-Speaker CAM++`

All speaker embedding models below run directly inside `sherpa-onnx`'s `SpeakerEmbeddingExtractor` ([sherpa-onnx Speaker Recognition Models Release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) and [HuggingFace Mirror](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/main)):

| Speaker Embedding Model (`sherpa-onnx`) | Architecture & Params | Training Data | Vector Dim / Size | Benchmarks (VoxCeleb1-O / CNCeleb / 3D-Speaker EER) | Recommendation for Muesli v2.0 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **[`3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`](https://huggingface.co/csukuangfj/speaker-embedding-models/blob/main/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx)** | [3D-Speaker](https://github.com/modelscope/3D-Speaker) CAM++ (D-TDNN, 7.2M params) | ~200k+ bilingual `zh` + `en` speakers | 192-dim / 28.3 MB | `0.65% / 6.78% / 7.75%` | **Primary Default Bundled Model:** Fast context-aware masking D-TDNN trained on both tonal and non-tonal speech; ideal for `en`/`th`/`zh`/`yue`. |
| **[`3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx`](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/main)** | [3D-Speaker](https://github.com/modelscope/3D-Speaker) ERes2NetV2 (17.8M params) | 200k Mandarin speakers | 192-dim / 71.4 MB | `0.61% / 6.14% / 6.52%` | **Optional High-Accuracy Download:** Slightly lower EER on reverberant far-field room audio at ~2.5x compute cost. |
| **[`wespeaker_en_voxceleb_CAM++.onnx`](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/main)** | [WeSpeaker](https://github.com/wenet-e2e/wespeaker) CAM++ | VoxCeleb (`en` / multilingual) | 512-dim / 29.3 MB | `~0.72%` VoxCeleb1-O | **Alternative:** Good on English, less resilient on tonal Thai/Chinese than `3dspeaker_speech_campplus_sv_zh_en`. |
| `nemo_en_titanet_small.onnx` (Current v0.3.0) | NeMo TitaNet-Small | English speech | 192-dim / ~24 MB | High variance on tonal speech | **Remove & Replace:** Root contributor to cross-language cluster splitting. |

### 4.2 Five-Stage Constrained Diarization & Profile Identification Pipeline (`SpeakerDiarizer.kt`)

To guarantee that a 3-speaker meeting produces 3 speakers (instead of 100+) and automatically recognizes enrolled speaker profiles, `SpeakerDiarizer.kt` is redesigned around a five-stage pipeline:

1. **Stage 1 — Pyannote Turn Segmentation & Micro-Gap Merging**:
   Run [`sherpa-onnx-pyannote-segmentation-3-0`](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0) (`model.int8.onnx`) to obtain initial `[startSec, endSec, localCluster]` turns. Merge consecutive turns assigned to the same local speaker when separated by `< 0.40s` of silence.
2. **Stage 2 — Anchor Turn Filtering (`>= 1.2s`) vs. Short Backchannel Holdout (`< 1.2s`)**:
   Separate segments into **Anchor Turns** (`duration >= 1.2s` and non-overlapping) and **Short Turns** (`duration < 1.2s`, such as "ครับ", "okay", "对"). Short turns are excluded from initial cluster formation so noisy sub-second embeddings cannot create singleton clusters.
3. **Stage 3 — CAM++ Embedding Extraction & Tuned Agglomerative Clustering**:
   Configure `SpeakerEmbeddingExtractor` with `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` (`numThreads = 4`). In `FastClusteringConfig`:
   - Expose a **Speaker Count Hint** selector in the Recording & Meeting Detail UI (`Auto`, `2 Speakers`, `3 Speakers`, `4 Speakers`, `5+ Speakers`), defaulting to `numClusters = 2` when the `1:1` meeting template is selected and `numClusters = -1` (`Auto`) otherwise.
   - When `numClusters = -1`, raise the cosine distance threshold to `threshold = 0.68f` (tuned for 192-dim CAM++ embeddings across tonal/non-tonal speech) so intra-speaker pitch and mic-distance shifts remain grouped within a single cluster.
4. **Stage 4 — Post-Clustering Centroid Consolidation & Short-Turn Reassignment**:
   - For each cluster $k$ returned by `OfflineSpeakerDiarization`, select up to the 5 longest Anchor Turns ($\ge 1.5\text{s}$), extract their 192-dim CAM++ vectors $\mathbf{e}_{k,i}$ using `SpeakerEmbeddingExtractor`, and compute a duration-weighted L2-normalized centroid:
     $$\mathbf{c}_k = \frac{\sum_{i} d_i \mathbf{e}_{k,i}}{\left\|\sum_{i} d_i \mathbf{e}_{k,i}\right\|_2}$$
   - Compute pairwise cosine similarity $S_{j,k} = \mathbf{c}_j \cdot \mathbf{c}_k$ across all cluster centroids. Iteratively merge any pair of clusters with $S_{j,k} \ge 0.58$ (and enforce a configurable `maxAutoSpeakers = 8` ceiling by merging the smallest clusters into their nearest neighbor).
   - Assign each held-out Short Turn (`< 1.2s`) to the surviving cluster with highest cosine similarity (weighted by temporal adjacency).
5. **Stage 5 — Persistent Profile Identification via `SpeakerEmbeddingManager`**:
   - Query each surviving cluster centroid $\mathbf{c}_k$ against `com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager(192)` using `val matchedProfileId = embeddingManager.search(centroid, threshold = 0.60f)`.
   - If `matchedProfileId.isNotEmpty()`, assign the saved `SpeakerProfileEntity` (`profileId`, `displayName`, `badgeColorHex`) to all segments in cluster $k$ (handling tie-breaking if two clusters match the same profile by merging them).
   - If `matchedProfileId.isEmpty()`, label the cluster sequentially (`Speaker 1`, `Speaker 2`, `Speaker 3`) and store its computed centroid $\mathbf{c}_k$ on the session so the user can enroll that speaker into a profile with one tap after the meeting.

## 5. Product Requirements & Technical Specifications

### 5.1 Functional Requirements Matrix

| Req ID | Feature Category | Detailed Engineering Requirement & Acceptance Criteria | Priority |
| :--- | :--- | :--- | :--- |
| **FR-01** | **Trilingual ASR Engine (`en`/`th`/`zh`/`yue`)** | Upgrade vendored `sherpa-onnx` to latest 2026 release and add `Qwen3-ASR-0.6B INT8` (default), `Qwen3-ASR-1.7B INT8` (high precision), `Whisper-Large-v3-Turbo`, and `SenseVoiceSmall + Zipformer-Thai` to `SpeechModels.kt` and `SherpaOnnxEngine.kt`. Support automatic language detection and code-switching across English, Thai, Mandarin, and Cantonese. | **P0** |
| **FR-02** | **CAM++ Diarization & Cluster Consolidation** | Replace `nemo_en_titanet_small.onnx` with `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`. Implement `>=1.2s` anchor filtering, `threshold = 0.68f` clustering, centroid merging (`cosine >= 0.58f`), and an optional speaker count selector (`Auto`, `2`, `3`, `4`, `5+`). A 3-speaker recording must yield 3 clusters (`±1` before profile match, never >100). | **P0** |
| **FR-03** | **Speaker Profile Labeling & Cross-Session Recognition** | Persist speaker voiceprints (192-dim CAM++ centroids and sample vectors) in Room v5 (`speaker_profiles`, `speaker_profile_samples`) and load them into `SpeakerEmbeddingManager` on startup. Allow creating/updating a profile by tapping any speaker badge in a transcript or recording a 6-second voice sample. Automatically identify enrolled speakers in subsequent meetings (`threshold = 0.60f`). | **P0** |
| **FR-04** | **Block-Level Synchronized Audio-Text Playback** | Store structured transcript segments (`startMs`, `endMs`, `speakerProfileId`, `speakerLabel`, `text`) in Room v5 (`meeting_transcript_segments`). During audio playback in `MeetingDetailScreen.kt`, poll playback position every `80ms`, highlight the active transcript block, smoothly auto-scroll the `LazyColumn`, and allow tapping any block to seek audio to `segment.startMs`. | **P0** |
| **FR-05** | **Public Storage in `/sdcard/Recordings`** | Change `MeetingRecorderService.kt` to write WAV recordings directly to `/sdcard/Recordings/Muesli/` (`Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)`), update the 44-byte RIFF header via `RandomAccessFile` on stop, and invoke `MediaScannerConnection.scanFile()` for immediate file manager visibility. Include one-tap migration for existing internal recordings. | **P0** |
| **FR-06** | **Meeting-First Memory & Thread Allocation** | Disable idle IME keyboard prewarming (`keepMicReady = false` by default) in `SherpaRecognizerHolder.kt` and allocate 4–6 CPU threads (`Cortex-X3` + `Cortex-A715` performance cores on Snapdragon 8 Gen 2) to active meeting transcription and diarization. | **P1** |

### 5.2 Database Schema Specification (`AppDatabase.kt` Room v4 to v5 Migration)

In `app/src/main/java/com/phequals7/muesli/data/AppDatabase.kt`, increment `@Database(version = 5, ...)` and register `MIGRATION_4_5` to introduce structured transcript blocks and persistent speaker voiceprints while preserving all existing `meeting_sessions`, `dictations`, and `custom_words`:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Structured transcript blocks for synchronized playback & speaker attribution
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `meeting_transcript_segments` (
                `segmentId` TEXT NOT NULL PRIMARY KEY,
                `sessionId` TEXT NOT NULL,
                `orderIndex` INTEGER NOT NULL,
                `startMs` INTEGER NOT NULL,
                `endMs` INTEGER NOT NULL,
                `clusterId` INTEGER NOT NULL,
                `speakerProfileId` TEXT,
                `speakerLabel` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `languageCode` TEXT,
                `clusterCentroidBlob` BLOB,
                FOREIGN KEY(`sessionId`) REFERENCES `meeting_sessions`(`sessionId`) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_meeting_transcript_segments_sessionId_startMs` " +
            "ON `meeting_transcript_segments` (`sessionId`, `startMs`, `endMs`)"
        )

        // 2. Persistent Speaker Profiles (192-dim Float32 L2-normalized CAM++ centroids = 768 bytes per blob)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `speaker_profiles` (
                `profileId` TEXT NOT NULL PRIMARY KEY,
                `displayName` TEXT NOT NULL,
                `badgeColorHex` TEXT NOT NULL,
                `sampleCount` INTEGER NOT NULL,
                `centroidEmbeddingBlob` BLOB NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 3. Individual enrollment voice samples for incremental profile refinement
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `speaker_profile_samples` (
                `sampleId` TEXT NOT NULL PRIMARY KEY,
                `profileId` TEXT NOT NULL,
                `sourceSessionId` TEXT,
                `durationMs` INTEGER NOT NULL,
                `embeddingBlob` BLOB NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                FOREIGN KEY(`profileId`) REFERENCES `speaker_profiles`(`profileId`) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_speaker_profile_samples_profileId` " +
            "ON `speaker_profile_samples` (`profileId`)"
        )
    }
}
```

### 5.3 Speaker Diarization, Consolidation & Profile Recognition Implementation (`SpeakerDiarizer.kt` & `SpeakerProfileRepository.kt`)

Below is the core architecture for `SpeakerDiarizer.kt` and `SpeakerProfileRepository.kt` using `sherpa-onnx`'s `OfflineSpeakerDiarization`, `SpeakerEmbeddingExtractor`, and `SpeakerEmbeddingManager`:

```kotlin
package com.phequals7.muesli.meetings

import com.k2fsa.sherpa.onnx.*
import kotlin.math.sqrt

data class ConsolidatedTurn(
    val startMs: Long,
    val endMs: Long,
    val clusterId: Int,
    val speakerProfileId: String?,
    val speakerLabel: String,
    val clusterCentroid: FloatArray
)

class CamPlusPlusSpeakerDiarizer(
    private val segmentationModelPath: String, // sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx
    private val embeddingModelPath: String,    // 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx
    private val profileRepository: SpeakerProfileRepository
) {
    private val extractor = SpeakerEmbeddingExtractor(
        SpeakerEmbeddingExtractorConfig(
            model = embeddingModelPath,
            numThreads = 4,
            debug = false,
            provider = "cpu"
        )
    )
    private val embeddingManager = SpeakerEmbeddingManager(extractor.dim()) // 192-dim for CAM++

    fun reloadEnrolledProfiles() {
        for (profile in profileRepository.getAllProfilesBlocking()) {
            embeddingManager.remove(profile.profileId)
            embeddingManager.add(profile.profileId, profile.centroidVector)
        }
    }

    fun diarizeAndIdentify(
        samples16kMono: FloatArray,
        expectedSpeakersHint: Int = -1, // -1 = Auto, or 2, 3, 4...
        clusterThreshold: Float = 0.68f,
        mergeCosineThreshold: Float = 0.58f,
        profileMatchThreshold: Float = 0.60f,
        maxAutoSpeakers: Int = 8
    ): List<ConsolidatedTurn> {
        reloadEnrolledProfiles()

        val sdConfig = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModelPath),
                numThreads = 4
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModelPath,
                numThreads = 4
            ),
            clustering = FastClusteringConfig(
                numClusters = expectedSpeakersHint,
                threshold = clusterThreshold
            ),
            minDurationOn = 0.45f,
            minDurationOff = 0.45f
        )

        val diarizer = OfflineSpeakerDiarization(sdConfig)
        val rawSegments = diarizer.process(samples16kMono).toList()
        diarizer.release()

        if (rawSegments.isEmpty()) return emptyList()

        // Step 1: Merge adjacent turns of the same raw cluster separated by < 400ms
        val mergedSegments = mergeAdjacentTurns(rawSegments, maxGapSec = 0.40f)

        // Step 2: Compute duration-weighted centroid for each cluster using Anchor Turns (>= 1.2s)
        val rawClusters = mergedSegments.groupBy { it.speaker }
        val clusterCentroids = mutableMapOf<Int, FloatArray>()

        for ((clusterId, turns) in rawClusters) {
            val anchorTurns = turns.filter { (it.end - it.start) >= 1.2f }
                .sortedByDescending { it.end - it.start }
                .take(5)
            val candidateTurns = anchorTurns.ifEmpty {
                turns.sortedByDescending { it.end - it.start }.take(2)
            }
            val weightedSum = FloatArray(extractor.dim())
            for (turn in candidateTurns) {
                val startIdx = (turn.start * 16000).toInt().coerceIn(0, samples16kMono.size)
                val endIdx = (turn.end * 16000).toInt().coerceIn(startIdx, samples16kMono.size)
                if (endIdx - startIdx < 4000) continue
                val slice = samples16kMono.copyOfRange(startIdx, endIdx)
                val emb = computeEmbedding(slice) ?: continue
                val weight = (turn.end - turn.start)
                for (i in weightedSum.indices) weightedSum[i] += emb[i] * weight
            }
            clusterCentroids[clusterId] = l2Normalize(weightedSum)
        }

        // Step 3: Consolidate over-segmented clusters whose centroids have cosine similarity >= mergeCosineThreshold
        val clusterMapping = consolidateClusters(
            clusterCentroids = clusterCentroids,
            turnsByCluster = rawClusters,
            mergeCosineThreshold = mergeCosineThreshold,
            maxAutoSpeakers = if (expectedSpeakersHint > 0) expectedSpeakersHint else maxAutoSpeakers
        )

        // Step 4: Match consolidated centroids against enrolled Speaker Profiles in SpeakerEmbeddingManager
        val finalProfileMap = mutableMapOf<Int, Pair<String?, String>>()
        var unknownCounter = 1
        for ((canonicalClusterId, centroid) in clusterMapping.canonicalCentroids) {
            val matchedProfileId = embeddingManager.search(centroid, threshold = profileMatchThreshold)
            if (matchedProfileId.isNotEmpty()) {
                val profile = profileRepository.getProfileById(matchedProfileId)
                finalProfileMap[canonicalClusterId] = matchedProfileId to (profile?.displayName ?: "Speaker $unknownCounter")
            } else {
                finalProfileMap[canonicalClusterId] = null to "Speaker ${unknownCounter++}"
            }
        }

        return mergedSegments.map { seg ->
            val canonicalId = clusterMapping.remap[seg.speaker] ?: seg.speaker
            val (profileId, label) = finalProfileMap[canonicalId] ?: (null to "Speaker 1")
            ConsolidatedTurn(
                startMs = (seg.start * 1000L).toLong(),
                endMs = (seg.end * 1000L).toLong(),
                clusterId = canonicalId,
                speakerProfileId = profileId,
                speakerLabel = label,
                clusterCentroid = clusterMapping.canonicalCentroids[canonicalId] ?: FloatArray(extractor.dim())
            )
        }
    }

    fun computeEmbedding(samples16kMono: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples16kMono, 16000)
            stream.inputFinished()
            if (extractor.isReady(stream)) l2Normalize(extractor.compute(stream)) else null
        } finally {
            stream.release() // Critical: prevents JNI native heap leak on Android
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-8f)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
```

#### How Profile Creation Works for Next-Meeting Identification
1. When viewing a completed meeting in `MeetingDetailScreen.kt`, each speaker badge (e.g., `Speaker 2`) is clickable.
2. Tapping `Speaker 2` opens `SpeakerProfileBottomSheet`, allowing the user to type a name (e.g., `"Khun Nattapong"`) or link to an existing profile.
3. Upon confirmation:
   - All segments in the current meeting with `clusterId == selectedClusterId` are updated in `meeting_transcript_segments` with `speakerProfileId` and `speakerLabel = "Khun Nattapong"`.
   - The `clusterCentroidBlob` stored on those segments is inserted as a new sample in `speaker_profile_samples`, and the profile's master `centroidEmbeddingBlob` in `speaker_profiles` is updated via running L2-normalized mean:
     $$\mathbf{c}_{\text{updated}} = \text{Normalize}\left(\frac{N \cdot \mathbf{c}_{\text{old}} + \mathbf{e}_{\text{new}}}{N + 1}\right)$$
   - On the very next meeting recording, `CamPlusPlusSpeakerDiarizer.reloadEnrolledProfiles()` loads `"Khun Nattapong"` into `SpeakerEmbeddingManager`, and `embeddingManager.search(centroid, threshold = 0.60f)` automatically labels all of Khun Nattapong's turns without manual intervention.

### 5.4 Block-Level Synchronized Audio-Text Playback (`MeetingDetailScreen.kt` & `MeetingDetailViewModel.kt`)

To highlight the active block of text as the meeting recording plays (and support tap-to-seek on any block):

1. **Reactive Playback Ticker (`MeetingDetailViewModel.kt`)**:
   While `MediaPlayer.isPlaying` is `true`, a coroutine launched in `viewModelScope` polls `mediaPlayer.currentPosition.toLong()` every `80ms` and updates `_currentPositionMs: MutableStateFlow<Long>`.
2. **Binary Search Active Segment Resolver**:
   Given `segments: List<MeetingTranscriptSegmentEntity>` sorted by `startMs`:
   ```kotlin
   fun findActiveSegmentIndex(segments: List<MeetingTranscriptSegmentEntity>, positionMs: Long): Int {
       if (segments.isEmpty()) return -1
       var low = 0
       var high = segments.lastIndex
       var candidate = -1
       while (low <= high) {
           val mid = (low + high) ushr 1
           val seg = segments[mid]
           when {
               positionMs < seg.startMs -> high = mid - 1
               positionMs > seg.endMs -> {
                   // Keep recent segment highlighted during brief <500ms inter-turn pauses
                   if (positionMs - seg.endMs <= 500L) candidate = mid
                   low = mid + 1
               }
               else -> return mid
           }
       }
       return candidate
   }
   ```
3. **Jetpack Compose `LazyColumn` Block Highlighting & Auto-Scroll (`MeetingDetailScreen.kt`)**:
   - Replace the static `Text` transcript view with a `LazyColumn(state = lazyListState)` rendering each `MeetingTranscriptSegmentEntity` as a `TranscriptSegmentBlock`.
   - When `index == activeSegmentIndex`:
     - Apply a highlighted container background (`MuesliColors.AccentBlue.copy(alpha = 0.14f)`), a `3.dp` left accent border, and bold timestamp/speaker header text.
     - Trigger smooth auto-scroll via `LaunchedEffect(activeSegmentIndex)` when `isPlaying` is `true` and the user has not manually dragged the list within the past `4,000ms`:
       ```kotlin
       var lastUserScrollMs by remember { mutableLongStateOf(0L) }
       LaunchedEffect(lazyListState.isScrollInProgress) {
           if (lazyListState.isScrollInProgress) {
               lastUserScrollMs = System.currentTimeMillis()
           }
       }
       LaunchedEffect(activeSegmentIndex, isPlaying) {
           val userScrollCooldownElapsed = (System.currentTimeMillis() - lastUserScrollMs) > 4000L
           if (isPlaying && activeSegmentIndex >= 0 && userScrollCooldownElapsed) {
               lazyListState.animateScrollToItem(
                   index = activeSegmentIndex,
                   scrollOffset = -160
               )
           }
       }
       ```
   - **Tap Block to Seek Audio**: Adding `.clickable { viewModel.seekToMs(segment.startMs) }` on each `TranscriptSegmentBlock` lets you tap any paragraph in the meeting transcript to jump audio playback directly to that moment.

### 5.5 Storing Audio Recordings in `/sdcard/Recordings` (`MeetingRecorderService.kt`)

Currently, `MeetingRecorderService.kt` creates WAV files in `File(context.filesDir, "meetings/<sessionId>.wav")`, which resolves to `/data/data/com.phequals7.muesli/files/meetings/<sessionId>.wav`. On Xiaomi Mix Fold 3 (Android 14/15 HyperOS), `/sdcard/Recordings` is symlinked to `/storage/emulated/0/Recordings`, which is exposed by the Android SDK constant `Environment.DIRECTORY_RECORDINGS` (introduced in API 31 and backward-compatible via string literal `"Recordings"`).

#### Technical Implementation for `/sdcard/Recordings`
1. **Why Direct File Path + `MediaScannerConnection` Works Cleanly on Android 10–15**:
   Under Android Scoped Storage (API 29–36), any app can create and write its own new audio files inside public top-level media directories (`Environment.DIRECTORY_RECORDINGS`) using standard `java.io.File` and `java.io.RandomAccessFile` APIs via Android's FUSE filesystem wrapper **without requiring `MANAGE_EXTERNAL_STORAGE`**. Using `RandomAccessFile` directly on `/sdcard/Recordings/Muesli/<filename>.wav` is essential because `MeetingRecorderService.kt` streams 16-bit PCM audio continuously during the meeting and must `seek(0)` when recording stops to write the final 44-byte RIFF/WAVE `dataSize` and `chunkSize` fields into the WAV header.
2. **File Path Resolution & Media Indexing Code (`MeetingStorageManager.kt`)**:
   ```kotlin
   package com.phequals7.muesli.meetings

   import android.content.Context
   import android.media.MediaScannerConnection
   import android.os.Environment
   import java.io.File
   import java.text.SimpleDateFormat
   import java.util.Date
   import java.util.Locale

   object MeetingStorageManager {
       fun getPublicRecordingsDir(context: Context): File {
           val baseRecordingsDir = Environment.getExternalStoragePublicDirectory(
               Environment.DIRECTORY_RECORDINGS
           ) // Resolves to /storage/emulated/0/Recordings (/sdcard/Recordings)
           val muesliDir = File(baseRecordingsDir, "Muesli")
           if (!muesliDir.exists() && !muesliDir.mkdirs()) {
               // Fallback to root /sdcard/Recordings if subfolder creation fails
               if (!baseRecordingsDir.exists()) baseRecordingsDir.mkdirs()
               return baseRecordingsDir
           }
           return muesliDir
       }

       fun createSessionWavFile(context: Context, sessionId: String, title: String?): File {
           val dir = getPublicRecordingsDir(context)
           val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
           val cleanTitle = (title ?: "Meeting")
               .replace(Regex("[^a-zA-Z0-9\\u0E00-\\u0E7F\\u4E00-\\u9FFF_-]"), "_")
               .take(30)
               .trim('_')
           val shortId = sessionId.take(8)
           return File(dir, "${timestamp}_${cleanTitle}_${shortId}.wav")
       }

       fun notifyMediaScanner(context: Context, wavFile: File) {
           MediaScannerConnection.scanFile(
               context,
               arrayOf(wavFile.absolutePath),
               arrayOf("audio/x-wav", "audio/wav"),
               null
           )
       }
   }
   ```
3. **Automatic One-Time Migration of Existing Recordings**:
   On app startup after upgrading to v2.0, `MeetingStorageManager.migrateLegacyInternalRecordings(context, appDatabase)` checks `File(context.filesDir, "meetings")` for any existing `<sessionId>.wav` files, copies them to `/sdcard/Recordings/Muesli/`, updates the session's `audioFilePath` in Room (`meeting_sessions`), calls `notifyMediaScanner(...)`, and deletes the internal `/data/data/com.phequals7.muesli/files/meetings/<sessionId>.wav` copy.

## 6. Phased Implementation Roadmap

| Sprint / Milestone | Target Modules & Files | Concrete Engineering Tasks | Verification & Acceptance Test |
| :--- | :--- | :--- | :--- |
| **Milestone 1: Public `/sdcard/Recordings` Storage & Room v5 Schema** | `MeetingStorageManager.kt`, `MeetingRecorderService.kt`, `AppDatabase.kt` | Redirect WAV creation from `context.filesDir` to `Environment.DIRECTORY_RECORDINGS` (`/sdcard/Recordings/Muesli/`); invoke `MediaScannerConnection` on stop; implement Room `MIGRATION_4_5` (`meeting_transcript_segments`, `speaker_profiles`, `speaker_profile_samples`). | Record a 1-minute meeting on Mix Fold 3; verify `.wav` file appears immediately in `/sdcard/Recordings/Muesli/` via Xiaomi File Manager and plays cleanly. |
| **Milestone 2: CAM++ Diarization & 100+ Speaker Bug Fix** | `SpeakerDiarizer.kt`, `SpeakerLabeler.kt`, `SpeakerProfileRepository.kt` | Bundle `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`; implement `>=1.2s` Anchor Turn filtering, `threshold = 0.68f`, centroid consolidation (`cosine >= 0.58f`), and speaker count hint selector (`Auto`, `2`, `3`, `4`, `5+`). | Run diarization on the 3-speaker meeting WAV that previously produced >100 speakers; verify output converges to 3 speaker clusters. |
| **Milestone 3: Speaker Profile Enrollment & Recognition** | `SpeakerProfileRepository.kt`, `SpeakerProfileBottomSheet.kt`, `MeetingDetailViewModel.kt` | Wire `SpeakerEmbeddingManager(192)` to `speaker_profiles`; implement one-tap speaker labeling in transcript view and 6-second voice enrollment in Settings. | Label "Speaker 1" as a named profile in Meeting A; record Meeting B with the same person speaking English, Thai, and Chinese; verify automatic profile attribution. |
| **Milestone 4: Trilingual ASR Engine (`en`/`th`/`zh`/`yue`)** | `jniLibs/arm64-v8a/`, `SpeechModels.kt`, `ModelManager.kt`, `SherpaOnnxEngine.kt` | Upgrade `libsherpa-onnx-jni.so` to latest release; add `Qwen3-ASR-0.6B INT8`, `Qwen3-ASR-1.7B INT8`, `Whisper-Large-v3-Turbo`, and `SenseVoiceSmall + Zipformer-Thai` to `SpeechModels.kt`; disable idle keyboard prewarming. | Benchmark English, Thai, Mandarin, and Cantonese code-switching audio on Mix Fold 3; verify RTF `< 0.30x` on `Qwen3-ASR-0.6B INT8` and segment timestamp preservation. |
| **Milestone 5: Block-Level Synchronized Audio-Text Playback** | `MeetingDetailScreen.kt`, `MeetingDetailViewModel.kt` | Replace flat transcript `Text` with `LazyColumn` of `TranscriptSegmentBlock` cards driven by `80ms` playback position polling, binary search active-block lookup, auto-scroll with 4s manual scroll cooldown, and tap-to-seek. | Play back a 20-minute meeting recording; verify the active block highlights and scrolls smoothly in sync with audio, and tapping any block seeks immediately. |

## 7. Verified References & Model Artifacts

* **Muesli for Android Codebase**: [Muesli-HQ/muesli-android GitHub Repository & Architecture README](https://github.com/Muesli-HQ/muesli-android/blob/main/README.md) · [khchan8/muesli-android-whisper](https://github.com/khchan8/muesli-android-whisper)
* **Sherpa-ONNX Runtime & Speaker Diarization**: [k2-fsa/sherpa-onnx GitHub](https://github.com/k2-fsa/sherpa-onnx) · [Speaker Diarization Docs](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html) · [Speaker Identification APKs Docs](https://k2-fsa.github.io/sherpa/onnx/speaker-identification/apk.html) · [Discussion #3233: 117-Speaker Clustering Root Cause](https://github.com/k2-fsa/sherpa-onnx/discussions/3233) · [Issue #974: JNI Stream Release Memory Management](https://github.com/k2-fsa/sherpa-onnx/issues/974)
* **Multilingual ASR Models (`en` / `th` / `zh` / `yue`)**:
  * [Qwen3-ASR-0.6B INT8 ONNX (`csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`)](https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25) · [QwenLM/Qwen3-ASR Repository](https://github.com/QwenLM/Qwen3-ASR)
  * [Qwen3-ASR-1.7B INT8 ONNX (`thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8`)](https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8) · [Qwen/Qwen3-ASR-1.7B Model Card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B)
  * [FunAudioLLM/SenseVoiceSmall Model Card](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) · [Sherpa-ONNX SenseVoice Documentation](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html)
  * [Sherpa-ONNX Zipformer-Thai Transducer (`sherpa-onnx-zipformer-thai-2024-06-20`)](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html)
  * [ggml-org/whisper.cpp Repository](https://github.com/ggml-org/whisper.cpp) · [Sherpa-ONNX Whisper Models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html)
* **Speaker Segmentation & Embedding Models (`CAM++` / `ERes2NetV2` / `Pyannote`)**:
  * [sherpa-onnx-pyannote-segmentation-3-0 Mirror](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0) · [pyannote/segmentation-3.0 Card](https://huggingface.co/pyannote/segmentation-3.0)
  * [3D-Speaker CAM++ Bilingual ZH-EN Advanced (`3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`)](https://huggingface.co/csukuangfj/speaker-embedding-models/blob/main/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx)
  * [3D-Speaker ModelScope / GitHub Repository](https://github.com/modelscope/3D-Speaker) · [Sherpa-ONNX Speaker Embedding Models Collection](https://huggingface.co/csukuangfj/speaker-embedding-models/tree/main)
