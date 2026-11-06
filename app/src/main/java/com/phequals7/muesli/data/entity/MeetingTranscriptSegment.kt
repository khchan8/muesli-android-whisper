package com.phequals7.muesli.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.SpeakerEmbeddingSerializer

/**
 * Structured transcript block introduced in Room v5 (`MIGRATION_4_5`) for
 * block-level synchronized audio-text playback (`startMs <= positionMs <= endMs`)
 * and persistent speaker attribution.
 */
@Entity(
    tableName = "meeting_transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            name = "index_meeting_transcript_segments_sessionId_startMs",
            value = ["sessionId", "startMs", "endMs"]
        )
    ]
)
data class MeetingTranscriptSegmentEntity(
    @PrimaryKey val segmentId: String,
    val sessionId: String,
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val clusterId: Int,
    val speakerProfileId: String?,
    val speakerLabel: String,
    val badgeColorHex: String = "#1D63B8",
    val text: String,
    val languageCode: String? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val clusterCentroidBlob: ByteArray? = null
) {
    fun toDomainModel(): DiarizedTranscriptSegment = DiarizedTranscriptSegment(
        segmentId = segmentId,
        sessionId = sessionId,
        orderIndex = orderIndex,
        startMs = startMs,
        endMs = endMs,
        clusterId = clusterId,
        speakerProfileId = speakerProfileId,
        speakerLabel = speakerLabel,
        badgeColorHex = badgeColorHex,
        text = text,
        languageCode = languageCode,
        clusterCentroid = clusterCentroidBlob?.let { SpeakerEmbeddingSerializer.blobToFloatArray(it) }
            ?: FloatArray(0)
    )

    companion object {
        fun fromDomainModel(segment: DiarizedTranscriptSegment): MeetingTranscriptSegmentEntity =
            MeetingTranscriptSegmentEntity(
                segmentId = segment.segmentId,
                sessionId = segment.sessionId,
                orderIndex = segment.orderIndex,
                startMs = segment.startMs,
                endMs = segment.endMs,
                clusterId = segment.clusterId,
                speakerProfileId = segment.speakerProfileId,
                speakerLabel = segment.speakerLabel,
                badgeColorHex = segment.badgeColorHex,
                text = segment.text,
                languageCode = segment.languageCode,
                clusterCentroidBlob = if (segment.clusterCentroid.isNotEmpty()) {
                    segment.clusterCentroidBlob
                } else {
                    null
                }
            )
    }
}
