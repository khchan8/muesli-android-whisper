package com.phequals7.muesli.meetings

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Represents a raw ASR chunk captured during live recording before or during diarization alignment.
 */
data class RawTranscriptChunk(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val languageCode: String? = null
)

/**
 * Represents a structured, speaker-attributed transcript block for Room v5 (`meeting_transcript_segments`)
 * and block-level synchronized audio playback in `MeetingDetailScreen.kt`.
 */
data class DiarizedTranscriptSegment(
    val segmentId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val clusterId: Int,
    val speakerProfileId: String?,
    val speakerLabel: String,
    val badgeColorHex: String,
    val text: String,
    val languageCode: String? = null,
    val clusterCentroid: FloatArray = FloatArray(0)
) {
    val clusterCentroidBlob: ByteArray
        get() = SpeakerEmbeddingSerializer.floatArrayToBlob(clusterCentroid)
}

/**
 * Represents a persistent speaker voiceprint profile stored in Room v5 (`speaker_profiles`).
 */
data class SpeakerProfileRecord(
    val profileId: String,
    val displayName: String,
    val badgeColorHex: String,
    val sampleCount: Int,
    val centroidVector: FloatArray,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * A scored identification result: cosine similarity in [-1, 1] between the
 * L2-normalized query embedding and an enrolled profile centroid.
 */
data class SpeakerProfileMatch(
    val profile: SpeakerProfileRecord,
    val similarity: Float
)

/**
 * Storage contract bridging `SpeakerDiarizer` with Room v5 `speaker_profiles` & `speaker_profile_samples`.
 */
interface SpeakerProfileStore {
    suspend fun getAllProfiles(): List<SpeakerProfileRecord>
    suspend fun getProfileById(profileId: String): SpeakerProfileRecord?
    suspend fun upsertProfile(profile: SpeakerProfileRecord, newSampleEmbedding: FloatArray, sourceSessionId: String?, durationMs: Long)
}

/**
 * Utility for converting 192-dim Float32 CAM++ speaker embeddings to/from SQLite BLOBs (768 bytes).
 */
object SpeakerEmbeddingSerializer {
    fun floatArrayToBlob(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buffer.putFloat(v)
        return buffer.array()
    }

    fun blobToFloatArray(blob: ByteArray): FloatArray {
        if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.float }
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        if (vector.isEmpty()) return vector
        var sumSq = 0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}

/**
 * Upgraded on-device Speaker Diarization & Voiceprint Profile Identification engine.
 *
 * Fixes the >100 speakers over-segmentation issue on 3-speaker meetings by:
 * 1. Replacing English-only `nemo_en_titanet_small.onnx` with bilingual `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` (192-dim CAM++).
 * 2. Filtering out sub-second backchannel utterances (< 1.20s) from cluster centroid formation.
 * 3. Tuning `FastClusteringConfig` (`threshold = 0.68f`) and supporting user speaker count hints (`Auto`, `2`, `3`, `4`, `5+`).
 * 4. Running an iterative Post-Clustering Centroid Consolidation pass (`mergeCosineThreshold = 0.58f`, `maxAutoSpeakers = 8`).
 * 5. Matching consolidated cluster centroids against enrolled voiceprint profiles via `SpeakerEmbeddingManager`.
 */
class SpeakerDiarizer(
    // Nullable: alignment/enrollment helpers are context-free (unit-testable);
    // model resolution paths require a real Context.
    private val context: Context?,
    private val profileStore: SpeakerProfileStore? = null
) {
    companion object {
        private const val TAG = "SpeakerDiarizer"
        private const val SAMPLE_RATE = 16000

        // Downloaded-model paths (files/models/...) take priority; the two
        // bundled APK assets (models/...) are the offline fallback.
        private const val PYANNOTE_INT8_ASSET = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"
        private const val BUNDLED_PYANNOTE_ASSET = "models/pyannote_segmentation_3_0.int8.onnx"
        private const val CAMPLUS_ZH_EN_ASSET = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        private const val PYANNOTE_FP32_ASSET = "sherpa-onnx-pyannote-segmentation-3-0/model.onnx"
        private const val MIN_ENROLLMENT_SECONDS = 6

        // Diarization hyper-parameters tuned for CAM++ (EN + TH + ZH)
        private const val DEFAULT_CLUSTER_DISTANCE_THRESHOLD = 0.68f
        private const val DEFAULT_CENTROID_MERGE_COSINE_THRESHOLD = 0.58f
        private const val DEFAULT_PROFILE_MATCH_COSINE_THRESHOLD = 0.60f
        private const val MIN_ANCHOR_TURN_DURATION_SEC = 1.20f
        private const val MAX_MICRO_GAP_MERGE_SEC = 0.40f
        private const val DEFAULT_MAX_AUTO_SPEAKERS = 8

        val DEFAULT_BADGE_COLORS = listOf(
            "#1D63B8", "#0D9488", "#D97706", "#7C3AED",
            "#DC2626", "#059669", "#2563EB", "#DB2777"
        )
    }

    data class DiarizationTurn(
        val startMs: Long,
        val endMs: Long,
        val clusterId: Int,
        val speakerProfileId: String?,
        val speakerLabel: String,
        val badgeColorHex: String,
        val clusterCentroid: FloatArray
    )

    data class DiarizationResult(
        val turns: List<DiarizationTurn>,
        val segments: List<DiarizedTranscriptSegment>,
        val formattedInlineTranscript: String,
        val detectedSpeakerCount: Int
    )

    /**
     * Runs the complete 5-stage diarization, consolidation, profile matching, and ASR chunk alignment
     * pipeline against a recorded 16 kHz mono WAV file.
     */
    suspend fun diarizeWavAndAlign(
        sessionId: String,
        wavFile: File,
        rawChunks: List<RawTranscriptChunk>,
        expectedSpeakerCount: Int = -1,
        maxAutoSpeakers: Int = DEFAULT_MAX_AUTO_SPEAKERS,
        onProgress: ((Float) -> Unit)? = null
    ): DiarizationResult = withContext(Dispatchers.Default) {
        val samples = readWavFileToMonoFloat16k(wavFile)
        if (samples.isEmpty()) {
            Log.w(TAG, "WAV file ${wavFile.absolutePath} contained no readable PCM samples.")
            val fallbackSegments = buildFallbackSegments(sessionId, rawChunks)
            return@withContext DiarizationResult(
                turns = emptyList(),
                segments = fallbackSegments,
                formattedInlineTranscript = formatInlineTranscript(fallbackSegments),
                detectedSpeakerCount = if (fallbackSegments.isNotEmpty()) 1 else 0
            )
        }

        val turns = diarizeSamples(
            samples16kMono = samples,
            expectedSpeakerCount = expectedSpeakerCount,
            maxAutoSpeakers = maxAutoSpeakers,
            onProgress = onProgress
        )

        val alignedSegments = alignTurnsWithTranscriptChunks(
            sessionId = sessionId,
            turns = turns,
            rawChunks = rawChunks
        )

        val uniqueSpeakers = alignedSegments.map { it.clusterId }.distinct().size
        DiarizationResult(
            turns = turns,
            segments = alignedSegments,
            formattedInlineTranscript = formatInlineTranscript(alignedSegments),
            detectedSpeakerCount = uniqueSpeakers
        )
    }

    /**
     * Executes the 5-stage constrained diarization and profile recognition pipeline on raw 16 kHz mono float samples.
     */
    suspend fun diarizeSamples(
        samples16kMono: FloatArray,
        expectedSpeakerCount: Int = -1,
        clusterDistanceThreshold: Float = DEFAULT_CLUSTER_DISTANCE_THRESHOLD,
        mergeCosineThreshold: Float = DEFAULT_CENTROID_MERGE_COSINE_THRESHOLD,
        profileMatchThreshold: Float = DEFAULT_PROFILE_MATCH_COSINE_THRESHOLD,
        maxAutoSpeakers: Int = DEFAULT_MAX_AUTO_SPEAKERS,
        onProgress: ((Float) -> Unit)? = null
    ): List<DiarizationTurn> = withContext(Dispatchers.Default) {
        // v2.1 Root Cause C: an unverified model path aborts in native
        // `require(ptr != 0L)`, so resolve via recursive files/+assets/ scan
        // and degrade to the timestamped single-speaker fallback on absence.
        val segmentationPath = resolveVerifiedModelFile(PYANNOTE_INT8_ASSET, PYANNOTE_FP32_ASSET, BUNDLED_PYANNOTE_ASSET)
        val embeddingPath = resolveVerifiedModelFile(CAMPLUS_ZH_EN_ASSET)
        if (segmentationPath == null || embeddingPath == null) {
            warn("Diarization models unavailable (segmentation=${segmentationPath != null}, embedding=${embeddingPath != null}); falling back to timestamped single-speaker segments")
            return@withContext emptyList<DiarizationTurn>()
        }
        onProgress?.invoke(0.05f)

        val extractorConfig = SpeakerEmbeddingExtractorConfig(
            model = embeddingPath,
            numThreads = 4,
            debug = false,
            provider = "cpu"
        )

        // v2.1 Root Cause B: OfflineSpeakerDiarization loads its own internal
        // CAM++ copy; run it to completion and release it BEFORE the standalone
        // SpeakerEmbeddingExtractor (a second CAM++ copy) is instantiated.
        val mergedSegments: List<OfflineSpeakerDiarizationSegment>
        try {
            val diarizer = OfflineSpeakerDiarization(
                config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationPath),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu"
                    ),
                    embedding = extractorConfig,
                    clustering = FastClusteringConfig(
                        numClusters = if (expectedSpeakerCount > 0) expectedSpeakerCount else -1,
                        threshold = clusterDistanceThreshold
                    ),
                    minDurationOn = 0.45f,
                    minDurationOff = 0.45f
                )
            )
            try {
                // v2.1 Root Cause A: synchronous process() only — the JNI
                // callback path runs on an unattached native worker thread
                // (SIGABRT) or throws UnsatisfiedLinkError (an Error).
                val rawSegments = diarizer.process(samples16kMono)
                mergedSegments = mergeMicroGaps(
                    rawSegments.toList().sortedBy { it.start },
                    MAX_MICRO_GAP_MERGE_SEC
                )
            } finally {
                try {
                    diarizer.release()
                } catch (t: Throwable) {
                    warn("Diarizer release failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            warn("Diarization failed (${t.javaClass.simpleName}: ${t.message}); falling back to timestamped single-speaker segments")
            return@withContext emptyList()
        }

        if (mergedSegments.isEmpty()) return@withContext emptyList()
        onProgress?.invoke(0.6f)

        var extractor: SpeakerEmbeddingExtractor? = null
        var embeddingManager: SpeakerEmbeddingManager? = null
        try {
            extractor = SpeakerEmbeddingExtractor(config = extractorConfig)
            val embeddingDim = extractor.dim()
            embeddingManager = SpeakerEmbeddingManager(embeddingDim)

            // Load enrolled speaker profiles into SpeakerEmbeddingManager
            val enrolledProfiles = profileStore?.getAllProfiles().orEmpty()
            warnOnModelFamilyMismatch(enrolledProfiles, embeddingDim)
            val profileMapById = enrolledProfiles.associateBy { it.profileId }
            for (profile in enrolledProfiles) {
                if (profile.centroidVector.size == embeddingDim) {
                    embeddingManager.add(profile.profileId, profile.centroidVector)
                }
            }


            // Stage 2 & 3: Separate Anchor Turns (>= 1.2s) from Short Turns (< 1.2s) and extract cluster centroids
            val groupedByRawSpeaker = mergedSegments.groupBy { it.speaker }
            val rawCentroids = mutableMapOf<Int, FloatArray>()
            val totalDurationByCluster = mutableMapOf<Int, Float>()

            for ((rawClusterId, turns) in groupedByRawSpeaker) {
                val totalDur = turns.sumOf { (it.end - it.start).toDouble() }.toFloat()
                totalDurationByCluster[rawClusterId] = totalDur

                val anchorTurns = turns
                    .filter { (it.end - it.start) >= MIN_ANCHOR_TURN_DURATION_SEC }
                    .sortedByDescending { it.end - it.start }
                    .take(5)

                val selectedTurns = anchorTurns.ifEmpty {
                    turns.sortedByDescending { it.end - it.start }.take(2)
                }

                val weightedSum = FloatArray(embeddingDim)
                var hasValidEmbedding = false

                for (turn in selectedTurns) {
                    val startSample = (turn.start * SAMPLE_RATE).toInt().coerceIn(0, samples16kMono.size)
                    val endSample = (turn.end * SAMPLE_RATE).toInt().coerceIn(startSample, samples16kMono.size)
                    if (endSample - startSample < SAMPLE_RATE / 4) continue // Require at least 250ms

                    val slice = samples16kMono.copyOfRange(startSample, endSample)
                    val vec = extractNormalizedEmbedding(extractor, slice) ?: continue
                    val weight = (turn.end - turn.start).coerceAtLeast(0.25f)
                    for (i in 0 until embeddingDim) {
                        weightedSum[i] += vec[i] * weight
                    }
                    hasValidEmbedding = true
                }

                if (hasValidEmbedding) {
                    rawCentroids[rawClusterId] = SpeakerEmbeddingSerializer.l2Normalize(weightedSum)
                }
            }

            // Stage 4: Post-Clustering Centroid Consolidation (eliminating the >100 speaker fragmentation)
            val targetMaxClusters = if (expectedSpeakerCount > 0) expectedSpeakerCount else maxAutoSpeakers
            val consolidated = consolidateClusterCentroids(
                rawCentroids = rawCentroids,
                totalDurationByCluster = totalDurationByCluster,
                mergeCosineThreshold = mergeCosineThreshold,
                maxClusters = targetMaxClusters
            )

            // Reassign any raw cluster that had only ultra-short turns and no valid centroid
            val fullClusterRemap = consolidated.clusterRemap.toMutableMap()
            for (rawClusterId in groupedByRawSpeaker.keys) {
                if (!fullClusterRemap.containsKey(rawClusterId)) {
                    val fallbackTarget = consolidated.canonicalCentroids.keys.firstOrNull() ?: 0
                    fullClusterRemap[rawClusterId] = fallbackTarget
                }
            }

            // Stage 5: Match consolidated centroids against enrolled Speaker Profiles
            data class ResolvedSpeakerMeta(
                val canonicalClusterId: Int,
                val profileId: String?,
                val displayName: String,
                val badgeColorHex: String,
                val centroid: FloatArray
            )

            val resolvedMetaByCluster = mutableMapOf<Int, ResolvedSpeakerMeta>()
            val matchedClusterByProfileId = mutableMapOf<String, Int>()
            var anonymousSpeakerIndex = 1

            // Sort canonical clusters by first appearance in the meeting for natural Speaker 1, Speaker 2 numbering
            val firstSeenOrder = mergedSegments
                .map { fullClusterRemap[it.speaker] ?: it.speaker }
                .distinct()

            for (canonicalId in firstSeenOrder) {
                val centroid = consolidated.canonicalCentroids[canonicalId] ?: FloatArray(embeddingDim)
                val matchedProfileId = if (centroid.isNotEmpty() && enrolledProfiles.isNotEmpty()) {
                    embeddingManager.search(centroid, profileMatchThreshold)
                } else {
                    ""
                }

                val matchedProfile = if (matchedProfileId.isNotEmpty()) profileMapById[matchedProfileId] else null
                if (matchedProfile != null) {
                    val existingCanonical = matchedClusterByProfileId[matchedProfile.profileId]
                    if (existingCanonical != null) {
                        // Two clusters matched the same enrolled person -> unify them
                        fullClusterRemap.entries.forEach { entry ->
                            if (entry.value == canonicalId) fullClusterRemap[entry.key] = existingCanonical
                        }
                        continue
                    }
                    matchedClusterByProfileId[matchedProfile.profileId] = canonicalId
                    resolvedMetaByCluster[canonicalId] = ResolvedSpeakerMeta(
                        canonicalClusterId = canonicalId,
                        profileId = matchedProfile.profileId,
                        displayName = matchedProfile.displayName,
                        badgeColorHex = matchedProfile.badgeColorHex,
                        centroid = centroid
                    )
                } else {
                    val colorHex = DEFAULT_BADGE_COLORS[(anonymousSpeakerIndex - 1) % DEFAULT_BADGE_COLORS.size]
                    resolvedMetaByCluster[canonicalId] = ResolvedSpeakerMeta(
                        canonicalClusterId = canonicalId,
                        profileId = null,
                        displayName = "Speaker $anonymousSpeakerIndex",
                        badgeColorHex = colorHex,
                        centroid = centroid
                    )
                    anonymousSpeakerIndex++
                }
            }

            // Convert segments and merge adjacent turns that now share the same consolidated canonical speaker
            val preliminaryTurns = mergedSegments.map { seg ->
                val canonicalId = fullClusterRemap[seg.speaker] ?: seg.speaker
                val meta = resolvedMetaByCluster[canonicalId] ?: ResolvedSpeakerMeta(
                    canonicalClusterId = canonicalId,
                    profileId = null,
                    displayName = "Speaker 1",
                    badgeColorHex = DEFAULT_BADGE_COLORS[0],
                    centroid = FloatArray(embeddingDim)
                )
                DiarizationTurn(
                    startMs = (seg.start * 1000f).toLong(),
                    endMs = (seg.end * 1000f).toLong(),
                    clusterId = meta.canonicalClusterId,
                    speakerProfileId = meta.profileId,
                    speakerLabel = meta.displayName,
                    badgeColorHex = meta.badgeColorHex,
                    clusterCentroid = meta.centroid
                )
            }

            onProgress?.invoke(1f)
            return@withContext mergeAdjacentConsolidatedTurns(preliminaryTurns, maxGapMs = 600L)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            warn("Speaker centroid extraction failed (${t.javaClass.simpleName}: ${t.message}); keeping anonymous speaker turns")
            return@withContext anonymousTurnsFromSegments(mergedSegments)
        } finally {
            try {
                embeddingManager?.release()
            } catch (t: Throwable) {
                warn("Embedding manager release failed: ${t.message}")
            }
            try {
                extractor?.release()
            } catch (t: Throwable) {
                warn("Extractor release failed: ${t.message}")
            }
        }
    }

    /**
     * Enrolls a new speaker profile or extends an existing one from a raw
     * >= 6 s 16 kHz mono enrollment clip (SpeakerProfilesScreen live path).
     *
     * Fails fast — never a silent no-op: [IllegalArgumentException] for clips
     * shorter than [MIN_ENROLLMENT_SECONDS], [IllegalStateException] when the
     * CAM++ model is unavailable, the extracted vector fails validation, or
     * the existing profile belongs to a different speaker model family.
     */
    suspend fun enrollSpeakerFromAudioSamples(
        samples16kMono: FloatArray,
        displayName: String,
        existingProfileId: String? = null
    ): SpeakerProfileRecord {
        require(samples16kMono.size >= MIN_ENROLLMENT_SECONDS * SAMPLE_RATE) {
            "Voice enrollment needs at least $MIN_ENROLLMENT_SECONDS seconds of audio"
        }
        return withContext(Dispatchers.IO) {
            val embedding = extractValidatedEmbedding(samples16kMono)
            persistEnrollment(
                normalizedSample = embedding,
                displayName = displayName,
                existingProfileId = existingProfileId,
                badgeColorHex = null,
                sourceSessionId = null,
                durationMs = samples16kMono.size * 1000L / SAMPLE_RATE
            )
        }
    }

    /**
     * Identifies the speakers of a raw 16 kHz mono clip against every enrolled
     * profile, best match first. [SpeakerProfileMatch.similarity] is the cosine
     * between the L2-normalized query embedding and the profile centroid
     * (-1..1). Returns an empty list when no embedding can be extracted or no
     * compatible profile exists; throws [IllegalStateException] only when the
     * embedding model itself is unavailable.
     */
    suspend fun identifySpeakerFromAudioSamples(samples16kMono: FloatArray): List<SpeakerProfileMatch> =
        withContext(Dispatchers.IO) {
            val profiles = profileStore?.getAllProfiles().orEmpty()
            if (profiles.isEmpty()) return@withContext emptyList<SpeakerProfileMatch>()

            val embeddingPath = resolveVerifiedEmbeddingModelPath()
            val query = withExtractor(embeddingPath) { extractor ->
                extractNormalizedEmbedding(extractor, samples16kMono)
            } ?: return@withContext emptyList()

            warnOnModelFamilyMismatch(profiles, query.size)
            profiles
                .filter { it.centroidVector.size == query.size }
                .map { profile ->
                    SpeakerProfileMatch(
                        profile = profile,
                        similarity = SpeakerEmbeddingSerializer.cosineSimilarity(query, profile.centroidVector)
                    )
                }
                .sortedByDescending { it.similarity }
        }

    /** Extracts a CAM++ embedding for enrollment audio, rejecting unusable vectors. */
    private fun extractValidatedEmbedding(samples16kMono: FloatArray): FloatArray {
        val embedding = withExtractor(resolveVerifiedEmbeddingModelPath()) { extractor ->
            extractNormalizedEmbedding(extractor, samples16kMono)
        } ?: throw IllegalStateException("Could not extract a speaker embedding from this audio")
        if (embedding.isEmpty() || embedding.any { !it.isFinite() }) {
            throw IllegalStateException("Extracted speaker embedding failed validation")
        }
        return embedding
    }

    private fun resolveVerifiedEmbeddingModelPath(): String =
        resolveVerifiedModelFile(CAMPLUS_ZH_EN_ASSET)
            ?: throw IllegalStateException("CAM++ speaker model is not available; download it in Settings → Models")

    /** Runs [block] with a standalone CAM++ extractor, releasing it even on Error. */
    private inline fun <T> withExtractor(modelPath: String, block: (SpeakerEmbeddingExtractor) -> T): T {
        val extractor = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = 4, debug = false, provider = "cpu")
        )
        return try {
            block(extractor)
        } finally {
            try {
                extractor.release()
            } catch (t: Throwable) {
                warn("Extractor release failed: ${t.message}")
            }
        }
    }

    /**
     * Saves (or extends) a profile from a meeting speaker's stored CAM++ cluster
     * centroid, so no audio is re-processed. Fails like [persistEnrollment] on
     * invalid vectors or a mismatched model family.
     */
    suspend fun enrollSpeakerFromCentroid(
        sessionId: String,
        clusterCentroid: FloatArray,
        displayName: String,
        existingProfileId: String?,
        badgeColorHex: String?,
        durationMs: Long
    ): SpeakerProfileRecord = withContext(Dispatchers.IO) {
        persistEnrollment(
            normalizedSample = SpeakerEmbeddingSerializer.l2Normalize(clusterCentroid),
            displayName = displayName,
            existingProfileId = existingProfileId,
            badgeColorHex = badgeColorHex,
            sourceSessionId = sessionId,
            durationMs = durationMs
        )
    }

    /**
     * Validates the new embedding and persists the profile (running
     * L2-normalized mean across historical samples) plus the raw sample row.
     */
    private suspend fun persistEnrollment(
        normalizedSample: FloatArray,
        displayName: String,
        existingProfileId: String?,
        badgeColorHex: String?,
        sourceSessionId: String?,
        durationMs: Long
    ): SpeakerProfileRecord {
        if (normalizedSample.isEmpty() || normalizedSample.any { !it.isFinite() }) {
            throw IllegalStateException("Speaker embedding failed validation")
        }
        val store = profileStore
            ?: throw IllegalStateException("SpeakerDiarizer needs a SpeakerProfileStore to enroll speakers")
        val now = System.currentTimeMillis()

        val existing = existingProfileId?.let { store.getProfileById(it) }
        if (existing != null && existing.centroidVector.size != normalizedSample.size) {
            // Vectors from different speaker model families must never blend.
            throw IllegalStateException(
                "Profile '${existing.displayName}' was enrolled with a different speaker model family; enroll it as a new profile"
            )
        }

        val updatedProfile = if (existing != null) {
            // Running L2-normalized mean across historical samples + new sample
            val n = existing.sampleCount.coerceAtLeast(1)
            val combined = FloatArray(normalizedSample.size) { i ->
                (existing.centroidVector[i] * n + normalizedSample[i]) / (n + 1).toFloat()
            }
            existing.copy(
                displayName = displayName.trim().ifEmpty { existing.displayName },
                badgeColorHex = badgeColorHex ?: existing.badgeColorHex,
                sampleCount = n + 1,
                centroidVector = SpeakerEmbeddingSerializer.l2Normalize(combined),
                updatedAtMs = now
            )
        } else {
            SpeakerProfileRecord(
                profileId = existingProfileId ?: UUID.randomUUID().toString(),
                displayName = displayName.trim().ifEmpty { "Speaker" },
                badgeColorHex = badgeColorHex
                    ?: DEFAULT_BADGE_COLORS[abs(displayName.hashCode()) % DEFAULT_BADGE_COLORS.size],
                sampleCount = 1,
                centroidVector = normalizedSample,
                createdAtMs = now,
                updatedAtMs = now
            )
        }

        store.upsertProfile(
            profile = updatedProfile,
            newSampleEmbedding = normalizedSample,
            sourceSessionId = sourceSessionId,
            durationMs = durationMs
        )
        return updatedProfile
    }

    /** Profiles whose vector dimension differs from the active model's can
     *  never match; surface that loudly instead of silently skipping. */
    private fun warnOnModelFamilyMismatch(profiles: List<SpeakerProfileRecord>, embeddingDim: Int) {
        val mismatched = profiles.filter { it.centroidVector.isNotEmpty() && it.centroidVector.size != embeddingDim }
        if (mismatched.isNotEmpty()) {
            warn(
                "${mismatched.size} enrolled profile(s) use a different speaker model family " +
                    "(${mismatched.first().centroidVector.size}-dim vs $embeddingDim-dim) and are excluded from matching; re-enroll those speakers"
            )
        }
    }

    /** Last-resort turns when the standalone extractor layer fails: keep
     *  pyannote's speaker separation with anonymous labels instead of
     *  discarding diarization entirely. */
    private fun anonymousTurnsFromSegments(segments: List<OfflineSpeakerDiarizationSegment>): List<DiarizationTurn> {
        val orderIndexBySpeaker = segments.map { it.speaker }.distinct().withIndex().associate { (i, s) -> s to i }
        return segments.map { seg ->
            val idx = orderIndexBySpeaker[seg.speaker] ?: 0
            DiarizationTurn(
                startMs = (seg.start * 1000f).toLong(),
                endMs = (seg.end * 1000f).toLong(),
                clusterId = seg.speaker,
                speakerProfileId = null,
                speakerLabel = "Speaker ${idx + 1}",
                badgeColorHex = DEFAULT_BADGE_COLORS[idx % DEFAULT_BADGE_COLORS.size],
                clusterCentroid = FloatArray(0)
            )
        }
    }

    /**
     * Aligns timestamped ASR transcript chunks with consolidated speaker diarization turns by temporal overlap,
     * merging consecutive chunks spoken by the same speaker within 1.5s into readable paragraph blocks.
     */
    fun alignTurnsWithTranscriptChunks(
        sessionId: String,
        turns: List<DiarizationTurn>,
        rawChunks: List<RawTranscriptChunk>
    ): List<DiarizedTranscriptSegment> {
        val validChunks = rawChunks.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (validChunks.isEmpty()) return emptyList()
        if (turns.isEmpty()) return buildFallbackSegments(sessionId, validChunks)

        val attributedBlocks = mutableListOf<DiarizedTranscriptSegment>()

        for (chunk in validChunks) {
            // Find the diarization turn with maximum overlap, or nearest temporal boundary
            var bestTurn: DiarizationTurn? = null
            var maxOverlapMs = 0L

            for (turn in turns) {
                val overlapStart = max(chunk.startMs, turn.startMs)
                val overlapEnd = min(chunk.endMs, turn.endMs)
                val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
                if (overlap > maxOverlapMs) {
                    maxOverlapMs = overlap
                    bestTurn = turn
                }
            }

            val chosenTurn = bestTurn ?: turns.minByOrNull { turn ->
                val chunkMid = (chunk.startMs + chunk.endMs) / 2
                val turnMid = (turn.startMs + turn.endMs) / 2
                abs(chunkMid - turnMid)
            } ?: turns.first()

            val previous = attributedBlocks.lastOrNull()
            // Group consecutive chunks from the same speaker separated by <= 1.5s into one cohesive block
            // (capped at 25 seconds per block so playback highlighting stays granular and easy to follow)
            val canMergeWithPrevious = previous != null &&
                previous.clusterId == chosenTurn.clusterId &&
                (chunk.startMs - previous.endMs) <= 1500L &&
                (chunk.endMs - previous.startMs) <= 25_000L

            if (canMergeWithPrevious && previous != null) {
                val separator = if (needsSpaceBetween(previous.text, chunk.text)) " " else ""
                attributedBlocks[attributedBlocks.lastIndex] = previous.copy(
                    endMs = max(previous.endMs, chunk.endMs),
                    text = previous.text + separator + chunk.text.trim()
                )
            } else {
                attributedBlocks.add(
                    DiarizedTranscriptSegment(
                        sessionId = sessionId,
                        orderIndex = attributedBlocks.size,
                        startMs = chunk.startMs,
                        endMs = max(chunk.endMs, chunk.startMs + 500L),
                        clusterId = chosenTurn.clusterId,
                        speakerProfileId = chosenTurn.speakerProfileId,
                        speakerLabel = chosenTurn.speakerLabel,
                        badgeColorHex = chosenTurn.badgeColorHex,
                        text = chunk.text.trim(),
                        languageCode = chunk.languageCode,
                        clusterCentroid = chosenTurn.clusterCentroid
                    )
                )
            }
        }

        return attributedBlocks.mapIndexed { idx, seg -> seg.copy(orderIndex = idx) }
    }

    fun formatInlineTranscript(segments: List<DiarizedTranscriptSegment>): String {
        return segments.joinToString(separator = "\n\n") { seg ->
            val mm = (seg.startMs / 1000) / 60
            val ss = (seg.startMs / 1000) % 60
            String.format("[%02d:%02d] %s: %s", mm, ss, seg.speakerLabel, seg.text)
        }
    }

    private fun buildFallbackSegments(
        sessionId: String,
        rawChunks: List<RawTranscriptChunk>
    ): List<DiarizedTranscriptSegment> {
        return rawChunks.filter { it.text.isNotBlank() }.mapIndexed { index, chunk ->
            DiarizedTranscriptSegment(
                sessionId = sessionId,
                orderIndex = index,
                startMs = chunk.startMs,
                endMs = max(chunk.endMs, chunk.startMs + 500L),
                clusterId = 0,
                speakerProfileId = null,
                speakerLabel = "Speaker 1",
                badgeColorHex = DEFAULT_BADGE_COLORS[0],
                text = chunk.text.trim(),
                languageCode = chunk.languageCode
            )
        }
    }

    private fun extractNormalizedEmbedding(
        extractor: SpeakerEmbeddingExtractor,
        samples16kMono: FloatArray
    ): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples16kMono, SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) {
                val raw = extractor.compute(stream)
                SpeakerEmbeddingSerializer.l2Normalize(raw)
            } else {
                null
            }
        } finally {
            stream.release()
        }
    }

    private data class ConsolidationResult(
        val clusterRemap: Map<Int, Int>,
        val canonicalCentroids: Map<Int, FloatArray>
    )

    /**
     * Agglomeratively merges over-segmented clusters whose duration-weighted centroids exceed
     * `mergeCosineThreshold` or when the total cluster count exceeds `maxClusters`.
     */
    private fun consolidateClusterCentroids(
        rawCentroids: Map<Int, FloatArray>,
        totalDurationByCluster: Map<Int, Float>,
        mergeCosineThreshold: Float,
        maxClusters: Int
    ): ConsolidationResult {
        if (rawCentroids.isEmpty()) {
            return ConsolidationResult(emptyMap(), emptyMap())
        }

        val activeCentroids = rawCentroids.toMutableMap()
        val activeWeights = totalDurationByCluster.toMutableMap()
        val parentMap = rawCentroids.keys.associateWith { it }.toMutableMap()

        fun findRoot(id: Int): Int {
            var curr = id
            while (parentMap[curr] != null && parentMap[curr] != curr) {
                curr = parentMap[curr]!!
            }
            return curr
        }

        while (activeCentroids.size > 1) {
            val keys = activeCentroids.keys.toList()
            var bestPair: Pair<Int, Int>? = null
            var bestSim = -1f

            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    val idA = keys[i]
                    val idB = keys[j]
                    val sim = SpeakerEmbeddingSerializer.cosineSimilarity(
                        activeCentroids[idA]!!,
                        activeCentroids[idB]!!
                    )
                    if (sim > bestSim) {
                        bestSim = sim
                        bestPair = idA to idB
                    }
                }
            }

            val shouldMerge = bestPair != null && (
                bestSim >= mergeCosineThreshold || activeCentroids.size > maxClusters
            )
            if (!shouldMerge || bestPair == null) break

            val (firstId, secondId) = bestPair
            val weightA = activeWeights[firstId] ?: 1f
            val weightB = activeWeights[secondId] ?: 1f

            // Keep the dominant speaker cluster ID as the canonical cluster root
            val (keepId, absorbId) = if (weightA >= weightB) firstId to secondId else secondId to firstId
            val vecKeep = activeCentroids[keepId]!!
            val vecAbsorb = activeCentroids[absorbId]!!
            val wKeep = activeWeights[keepId] ?: 1f
            val wAbsorb = activeWeights[absorbId] ?: 1f

            val mergedVec = FloatArray(vecKeep.size) { idx ->
                vecKeep[idx] * wKeep + vecAbsorb[idx] * wAbsorb
            }
            activeCentroids[keepId] = SpeakerEmbeddingSerializer.l2Normalize(mergedVec)
            activeWeights[keepId] = wKeep + wAbsorb

            activeCentroids.remove(absorbId)
            activeWeights.remove(absorbId)
            parentMap[absorbId] = keepId
        }

        val finalRemap = rawCentroids.keys.associateWith { findRoot(it) }
        return ConsolidationResult(
            clusterRemap = finalRemap,
            canonicalCentroids = activeCentroids
        )
    }

    private fun mergeMicroGaps(
        segments: List<OfflineSpeakerDiarizationSegment>,
        maxGapSec: Float
    ): List<OfflineSpeakerDiarizationSegment> {
        if (segments.isEmpty()) return emptyList()
        val merged = mutableListOf<OfflineSpeakerDiarizationSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.speaker == current.speaker && (next.start - current.end) <= maxGapSec) {
                current = OfflineSpeakerDiarizationSegment(
                    start = current.start,
                    end = max(current.end, next.end),
                    speaker = current.speaker
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun mergeAdjacentConsolidatedTurns(
        turns: List<DiarizationTurn>,
        maxGapMs: Long
    ): List<DiarizationTurn> {
        if (turns.isEmpty()) return emptyList()
        val merged = mutableListOf<DiarizationTurn>()
        var current = turns.first()

        for (i in 1 until turns.size) {
            val next = turns[i]
            if (next.clusterId == current.clusterId && (next.startMs - current.endMs) <= maxGapMs) {
                current = current.copy(endMs = max(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Resolves a verified diarization ONNX model path (v2.1 Root Cause C):
     * handing JNI an unverified path aborts natively (`ptr == 0L`).
     *
     * Checks each candidate in order — the exact path under `files/models`,
     * then a recursive scan of `files/` by file name (downloaded models may
     * live in differently named folders), then the APK asset at the exact
     * path, then a recursive asset scan by file name (asset hits are
     * extracted to `files/bundled_models`). Generic names such as
     * `model.int8.onnx` are only accepted under the candidate's expected
     * folder so the scan can never grab another model's ONNX. Returns null
     * when no real, non-empty copy exists so callers fall back instead of
     * crashing.
     */
    private fun resolveVerifiedModelFile(vararg candidatePaths: String): String? {
        val appContext = context ?: return null
        val modelsRoot = File(appContext.filesDir, "models")

        for (candidate in candidatePaths) {
            val fileName = candidate.substringAfterLast('/')
            val dirHint = candidate.substringBeforeLast('/', missingDelimiterValue = "")
                .ifEmpty { null }

            File(modelsRoot, candidate)
                .takeIf { it.isFile && it.length() > 0L }
                ?.let { return it.absolutePath }
            findFileByNameRecursive(appContext.filesDir, fileName, dirHint)?.let { return it.absolutePath }

            extractAssetIfPresent(candidate)?.let { return it.absolutePath }
            findAssetFileNameRecursive(appContext, "", fileName, dirHint)?.let { assetRel ->
                extractAssetIfPresent(assetRel)?.let { return it.absolutePath }
            }
        }
        return null
    }

    private fun findFileByNameRecursive(dir: File, fileName: String, dirHint: String?): File? {
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                findFileByNameRecursive(child, fileName, dirHint)?.let { return it }
            } else if (child.length() > 0L && child.name == fileName && matchesDirHint(child.parent, dirHint)) {
                return child
            }
        }
        return null
    }

    private fun matchesDirHint(parentPath: String?, dirHint: String?): Boolean =
        dirHint == null || parentPath?.contains(dirHint) == true

    private fun findAssetFileNameRecursive(context: Context, dir: String, fileName: String, dirHint: String?): String? {
        val children = try {
            context.assets.list(dir) ?: return null
        } catch (t: Throwable) {
            return null
        }
        for (child in children) {
            val rel = if (dir.isEmpty()) child else "$dir/$child"
            if (child == fileName && matchesDirHint(rel.substringBeforeLast('/'), dirHint)) return rel
            findAssetFileNameRecursive(context, rel, fileName, dirHint)?.let { return it }
        }
        return null
    }

    private fun extractAssetIfPresent(assetPath: String): File? {
        val appContext = context ?: return null
        return try {
            val outFile = File(appContext.filesDir, "bundled_models/$assetPath")
            if (outFile.exists() && outFile.length() > 0L) return outFile
            outFile.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                val temp = File.createTempFile("model-", ".tmp", outFile.parentFile)
                try {
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                    check(temp.length() > 0L && temp.renameTo(outFile)) { "Could not install bundled model" }
                } finally {
                    temp.delete()
                }
            }
            outFile
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Reads a 16 kHz 16-bit mono PCM WAV file into a normalized FloatArray in [-1.0f, 1.0f].
     */
    private fun readWavFileToMonoFloat16k(wavFile: File): FloatArray {
        if (!wavFile.exists() || wavFile.length() <= 44L) return FloatArray(0)
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                // Locate the "data" subchunk dynamically in case extra RIFF metadata chunks exist
                var dataOffset = 44L
                var dataSize = (raf.length() - 44L).toInt()
                raf.seek(12L)
                val headerBuf = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(headerBuf)
                    val chunkId = String(headerBuf, 0, 4, Charsets.US_ASCII)
                    val chunkLen = ByteBuffer.wrap(headerBuf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (chunkId == "data") {
                        dataOffset = raf.filePointer
                        dataSize = min(chunkLen.toLong(), raf.length() - dataOffset).toInt()
                        break
                    } else {
                        val skipBytes = if (chunkLen % 2 == 1) chunkLen + 1L else chunkLen.toLong()
                        raf.seek(raf.filePointer + skipBytes)
                    }
                }

                raf.seek(dataOffset)
                val numSamples = dataSize / 2
                val floatSamples = FloatArray(numSamples)
                val byteChunk = ByteArray(32768)
                var sampleIndex = 0
                var bytesRemaining = dataSize

                while (bytesRemaining > 0 && sampleIndex < numSamples) {
                    val toRead = min(byteChunk.size, bytesRemaining)
                    val read = raf.read(byteChunk, 0, toRead)
                    if (read <= 0) break
                    val shortCount = read / 2
                    val shortBuf = ByteBuffer.wrap(byteChunk, 0, shortCount * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                    for (i in 0 until shortCount) {
                        if (sampleIndex < numSamples) {
                            floatSamples[sampleIndex++] = (shortBuf.get(i) / 32768.0f).coerceIn(-1.0f, 1.0f)
                        }
                    }
                    bytesRemaining -= read
                }
                floatSamples
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read WAV file ${wavFile.absolutePath}", t)
            FloatArray(0)
        }
    }

    /** Logging must never crash the pipeline: JNI failure paths surface as
     *  Errors, and android.util.Log throws in JVM unit tests. */
    private fun warn(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun needsSpaceBetween(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val lastChar = left.last()
        val firstChar = right.first()
        // Thai (\u0E00-\u0E7F) and CJK (\u4E00-\u9FFF) characters do not require spaces between contiguous phrases
        val isLeftCjkOrThai = lastChar in '\u0E00'..'\u0E7F' || lastChar in '\u4E00'..'\u9FFF'
        val isRightCjkOrThai = firstChar in '\u0E00'..'\u0E7F' || firstChar in '\u4E00'..'\u9FFF'
        return !(isLeftCjkOrThai && isRightCjkOrThai)
    }
}