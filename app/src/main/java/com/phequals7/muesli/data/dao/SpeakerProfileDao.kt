package com.phequals7.muesli.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: SpeakerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfileSample(sample: SpeakerProfileSampleEntity)

    @Query("SELECT * FROM speaker_profile_samples WHERE profileId = :profileId ORDER BY createdAtMs DESC")
    suspend fun getSamplesForProfile(profileId: String): List<SpeakerProfileSampleEntity>

    @Query("DELETE FROM speaker_profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
}
