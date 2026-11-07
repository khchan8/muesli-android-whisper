package com.phequals7.muesli.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {

    @Query("SELECT * FROM meeting_transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC, orderIndex ASC")
    fun observeSegmentsForSession(sessionId: String): Flow<List<MeetingTranscriptSegmentEntity>>

    @Query("SELECT * FROM meeting_transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC, orderIndex ASC")
    suspend fun getSegmentsForSession(sessionId: String): List<MeetingTranscriptSegmentEntity>

    @Query("DELETE FROM meeting_transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<MeetingTranscriptSegmentEntity>)

    @Query("UPDATE transcripts SET text = :inlineText WHERE sessionID = :sessionId")
    suspend fun updateInlineTranscriptText(sessionId: String, inlineText: String)

    /** Atomically swaps the segment set for a session and refreshes the flat transcript text. */
    @Transaction
    suspend fun replaceSegmentsForSession(
        sessionId: String,
        segments: List<MeetingTranscriptSegmentEntity>,
        inlineText: String
    ) {
        deleteSegmentsForSession(sessionId)
        insertSegments(segments)
        updateInlineTranscriptText(sessionId, inlineText)
    }
}
