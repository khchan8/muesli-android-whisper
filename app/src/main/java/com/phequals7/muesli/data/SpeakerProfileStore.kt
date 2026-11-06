package com.phequals7.muesli.data

import com.phequals7.muesli.data.dao.SpeakerProfileDao
import com.phequals7.muesli.data.dao.TranscriptSegmentDao
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import com.phequals7.muesli.data.entity.SpeakerProfileEntity
import com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity
import com.phequals7.muesli.meetings.SpeakerEmbeddingSerializer
import com.phequals7.muesli.meetings.SpeakerProfileRecord
import com.phequals7.muesli.meetings.SpeakerProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room v5 implementation of [SpeakerProfileStore]: persists speaker
 * voiceprints and re-labels transcript segments when a cluster is enrolled
 * into a named profile.
 */
class RoomSpeakerProfileStore(
    private val speakerProfileDao: SpeakerProfileDao,
    private val transcriptSegmentDao: TranscriptSegmentDao
) : SpeakerProfileStore {

    fun observeProfiles(): Flow<List<SpeakerProfileRecord>> =
        speakerProfileDao.observeAllProfiles().map { list -> list.map { it.toDomainRecord() } }

    override suspend fun getAllProfiles(): List<SpeakerProfileRecord> = withContext(Dispatchers.IO) {
        speakerProfileDao.getAllProfiles().map { it.toDomainRecord() }
    }

    override suspend fun getProfileById(profileId: String): SpeakerProfileRecord? = withContext(Dispatchers.IO) {
        speakerProfileDao.getProfileById(profileId)?.toDomainRecord()
    }

    override suspend fun upsertProfile(
        profile: SpeakerProfileRecord,
        newSampleEmbedding: FloatArray,
        sourceSessionId: String?,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        speakerProfileDao.upsertProfile(SpeakerProfileEntity.fromDomainRecord(profile))
        if (newSampleEmbedding.isNotEmpty()) {
            speakerProfileDao.insertProfileSample(
                SpeakerProfileSampleEntity(
                    profileId = profile.profileId,
                    sourceSessionId = sourceSessionId,
                    durationMs = durationMs,
                    embeddingBlob = SpeakerEmbeddingSerializer.floatArrayToBlob(newSampleEmbedding),
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun updateSessionClusterProfile(
        sessionId: String,
        clusterId: Int,
        speakerProfileId: String,
        speakerLabel: String,
        badgeColorHex: String
    ) = withContext(Dispatchers.IO) {
        transcriptSegmentDao.updateClusterSpeakerAttribution(
            sessionId = sessionId,
            clusterId = clusterId,
            speakerProfileId = speakerProfileId,
            speakerLabel = speakerLabel,
            badgeColorHex = badgeColorHex
        )

        // Refresh the flat inline transcript so text exports reflect the new speaker name.
        val segments = transcriptSegmentDao.getSegmentsForSession(sessionId)
        if (segments.isNotEmpty()) {
            transcriptSegmentDao.updateInlineTranscriptText(sessionId, formatInline(segments))
        }
    }

    private fun formatInline(segments: List<MeetingTranscriptSegmentEntity>): String =
        segments.joinToString("\n\n") { seg ->
            val mm = (seg.startMs / 1000) / 60
            val ss = (seg.startMs / 1000) % 60
            String.format("[%02d:%02d] %s: %s", mm, ss, seg.speakerLabel, seg.text)
        }
}
