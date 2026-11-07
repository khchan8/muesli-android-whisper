package com.phequals7.muesli.data

import com.phequals7.muesli.data.dao.SpeakerProfileDao
import com.phequals7.muesli.meetings.SpeakerEmbeddingSerializer
import com.phequals7.muesli.meetings.SpeakerProfileRecord
import com.phequals7.muesli.meetings.SpeakerProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room v5 implementation of [SpeakerProfileStore]: persists speaker
 * voiceprints. Transcript-segment relabeling was removed with the
 * post-meeting enrollment sheet; microphone enrollment stays.
 */
class RoomSpeakerProfileStore(
    private val speakerProfileDao: SpeakerProfileDao
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
        // Single @Transaction: the profile row is UPDATEd in place (never REPLACEd),
        // so previously enrolled samples survive every refresh of the centroid.
        val sample = if (newSampleEmbedding.isNotEmpty()) {
            com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity(
                profileId = profile.profileId,
                sourceSessionId = sourceSessionId,
                durationMs = durationMs,
                embeddingBlob = SpeakerEmbeddingSerializer.floatArrayToBlob(newSampleEmbedding),
                createdAtMs = System.currentTimeMillis()
            )
        } else {
            null
        }
        speakerProfileDao.upsertProfileWithSample(com.phequals7.muesli.data.entity.SpeakerProfileEntity.fromDomainRecord(profile), sample)
    }

    /** Deletes a profile and its samples; labels already written into transcripts stay intact. */
    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        speakerProfileDao.deleteProfile(profileId)
    }

    /** Renames in place; false when the profile no longer exists. */
    suspend fun renameProfile(profileId: String, newDisplayName: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newDisplayName.trim()
        val existing = speakerProfileDao.getProfileById(profileId)
        if (existing == null || trimmed.isEmpty()) {
            false
        } else {
            speakerProfileDao.upsertProfile(
                existing.copy(displayName = trimmed, updatedAtMs = System.currentTimeMillis())
            )
            true
        }
    }
}
