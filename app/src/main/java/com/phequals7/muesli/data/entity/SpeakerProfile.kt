package com.phequals7.muesli.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phequals7.muesli.meetings.SpeakerEmbeddingSerializer
import com.phequals7.muesli.meetings.SpeakerProfileRecord

/**
 * Persistent speaker voiceprint profile: a 192-dim L2-normalized CAM++
 * centroid that survives across meetings so enrolled speakers are
 * automatically recognized (`SpeakerEmbeddingManager.search` at 0.60).
 */
@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey val profileId: String,
    val displayName: String,
    val badgeColorHex: String,
    val sampleCount: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val centroidEmbeddingBlob: ByteArray,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    fun toDomainRecord(): SpeakerProfileRecord = SpeakerProfileRecord(
        profileId = profileId,
        displayName = displayName,
        badgeColorHex = badgeColorHex,
        sampleCount = sampleCount,
        centroidVector = SpeakerEmbeddingSerializer.blobToFloatArray(centroidEmbeddingBlob),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )

    companion object {
        fun fromDomainRecord(record: SpeakerProfileRecord): SpeakerProfileEntity = SpeakerProfileEntity(
            profileId = record.profileId,
            displayName = record.displayName,
            badgeColorHex = record.badgeColorHex,
            sampleCount = record.sampleCount,
            centroidEmbeddingBlob = SpeakerEmbeddingSerializer.floatArrayToBlob(record.centroidVector),
            createdAtMs = record.createdAtMs,
            updatedAtMs = record.updatedAtMs
        )
    }
}

/** Individual enrollment sample per profile for incremental centroid refinement. */
@Entity(
    tableName = "speaker_profile_samples",
    foreignKeys = [
        ForeignKey(
            entity = SpeakerProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            name = "index_speaker_profile_samples_profileId",
            value = ["profileId"]
        )
    ]
)
data class SpeakerProfileSampleEntity(
    @PrimaryKey val sampleId: String = java.util.UUID.randomUUID().toString(),
    val profileId: String,
    val sourceSessionId: String?,
    val durationMs: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,
    val createdAtMs: Long
)
