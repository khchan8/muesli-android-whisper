SpeakerDiarizer.kt: Implements the 5-stage 3D-Speaker CAM++ and pyannote-segmentation-3-0 diarization pipeline with >=1.2s anchor-turn filtering, agglomerative centroid consolidation (cosine >= 0.58f), persistent SpeakerEmbeddingManager profile matching (threshold = 0.60f), and incremental running-mean voiceprint enrollment.

MeetingRecorderService.kt: Streams 16 kHz 16-bit mono PCM audio directly to /sdcard/Recordings/Muesli/ (Environment.DIRECTORY_RECORDINGS) with in-place 44-byte RIFF/WAVE header updates, MediaScannerConnection indexing, legacy /data/data/com.phequals7.muesli/files/meetings/ migration, live Silero VAD segmentation, and post-recording CAM++ diarization.

MeetingDetailScreen.kt: Provides the Jetpack Compose playback UI with an 80ms MediaPlayer position ticker, binary-search active block highlighting, auto-scrolling LazyColumn (with a 4-second manual scroll cooldown), tap-block-to-seek, speaker count constraint chips (Auto, 2, 3, 4, 5+), and the one-tap ModalBottomSheet for enrolling or linking speaker voiceprint profiles.

To wire these into your Muesli for Android build, place 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx in app/src/main/assets/ (or files/models/) and connect MeetingRecordingController.chunkTranscriber and speakerProfileStore to SherpaOnnxEngine.kt and AppDatabase.kt (MIGRATION_4_5). 

AppDatabase.kt: Defines the Room v5 entities (MeetingTranscriptSegmentEntity, SpeakerProfileEntity, SpeakerProfileSampleEntity), DAOs (MeetingDao, SpeakerProfileDao), explicit MIGRATION_4_5 SQLite migration, and RoomSpeakerProfileStore (SpeakerProfileStore implementation), while automatically wiring MeetingRecordingController callbacks and legacy /data/data/com.phequals7.muesli/files/meetings/ WAV migration to /sdcard/Recordings/Muesli/.

SherpaOnnxEngine.kt: Configures SherpaRecognizerHolder and SherpaOnnxEngine using the sherpa-onnx Kotlin API (OfflineQwen3AsrModelConfig, OfflineWhisperModelConfig, OfflineSenseVoiceModelConfig, and OfflineTransducerModelConfig) for Qwen3-ASR-0.6B INT8, Qwen3-ASR-1.7B INT8, Whisper-Large-v3-Turbo, and the SenseVoiceSmall + Zipformer-Thai hybrid router, with voice-note WAV capture directly to /sdcard/Recordings/Muesli/.

Note that OfflineQwen3AsrModelConfig requires updating your vendored app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt and app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so from v1.13.4 to a 2026 sherpa-onnx release.

SpeechModels.kt: Defines the complete catalog for Qwen3-ASR-0.6B INT8 (with both .tar.bz2 and direct HuggingFace file manifests), Qwen3-ASR-1.7B INT8, Whisper-Large-v3-Turbo, the SenseVoiceSmall (2024-07-17) + Zipformer-Thai (2024-06-20) composite bundle, and the 3D-Speaker CAM++ and pyannote-segmentation-3-0 speaker diarization models.

ModelManager.kt: Handles resumable HTTP Range (206 Partial Content) downloads, automatic huggingface.co to hf-mirror.com DNS fallback, streaming .tar.bz2 extraction with zip-slip protection, composite bundle installation, and automatic provisioning of the 28.3 MB CAM++ speaker embedding model whenever a multilingual ASR model is installed.

Notice that SENSEVOICE_SMALL_COMPONENT in SpeechModels.kt defaults to the 2024-07-17 INT8 archive (with 2025-09-09 as fallback) because 2024-07-17 preserves full punctuation when useInverseTextNormalization = true (sherpa-onnx Issue #2742), and ModelManager.kt uses org.apache.commons:commons-compress for .tar.bz2 decompression. Ensure implementation("org.apache.commons:commons-compress:1.26.2") is present in app/build.gradle.kts

