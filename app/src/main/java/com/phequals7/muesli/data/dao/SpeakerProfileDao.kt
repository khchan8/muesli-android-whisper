package com.phequals7.muesli.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.phequals7.muesli.data.entity.SpeakerProfileEntity
import com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerProfileDao {

    @Query("SELECT * FROM speaker_profiles ORDER BY updatedAtMs DESC")
    fun observeAllProfiles(): Flow<List<SpeakerProfileEntity>>

    @Query("SELECT * FROM speaker_profiles ORDER BY updatedAtMs DESC")
    suspend fun getAllProfiles(): List<SpeakerProfileEntity>

    @Query("SELECT * FROM speaker_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): SpeakerProfileEntity?

    // @Insert(REPLACE) here would DELETE the parent row first and cascade-delete
    // every speaker_profile_samples row on each profile update; @Upsert UPDATEs in place.
    @Upsert
    suspend fun upsertProfile(profile: SpeakerProfileEntity)

    /** Atomic profile write + optional new enrollment sample in one transaction. */
    @Transaction
    suspend fun upsertProfileWithSample(
        profile: SpeakerProfileEntity,
        sample: SpeakerProfileSampleEntity?
    ) {
        upsertProfile(profile)
        if (sample != null) insertProfileSample(sample)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfileSample(sample: SpeakerProfileSampleEntity)

    @Query("SELECT * FROM speaker_profile_samples WHERE profileId = :profileId ORDER BY createdAtMs DESC")
    suspend fun getSamplesForProfile(profileId: String): List<SpeakerProfileSampleEntity>

    @Query("DELETE FROM speaker_profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
}
