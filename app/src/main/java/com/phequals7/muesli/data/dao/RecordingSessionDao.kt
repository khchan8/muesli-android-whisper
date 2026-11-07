package com.phequals7.muesli.data.dao

import androidx.room.*
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingSessionDao {
    @Query("SELECT * FROM recording_sessions ORDER BY createdAt DESC")
    fun getAllSessionsFlow(): Flow<List<RecordingSession>>

    @Query("SELECT * FROM recording_sessions ORDER BY createdAt DESC")
    suspend fun getAllSessions(): List<RecordingSession>

    @Query("SELECT * FROM recording_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): RecordingSession?

    @Query("SELECT * FROM recording_sessions WHERE requestID = :requestId LIMIT 1")
    suspend fun getSessionByRequestId(requestId: String): RecordingSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: RecordingSession)

    @Update
    suspend fun updateSession(session: RecordingSession)

    /** Field-scoped updates: concurrent title/notes/summary writes must not
     *  clobber each other via whole-row copies. Each returns affected rows. */

    @Query("UPDATE recording_sessions SET manualNotes = :notes WHERE id = :sessionId")
    suspend fun updateMeetingNotes(sessionId: String, notes: String): Int

    /** NULL-safe: only moves the path when the row still holds [expectedOldPath]. */
    @Query("UPDATE recording_sessions SET audioFileName = :newPath WHERE id = :sessionId AND audioFileName IS :expectedOldPath")
    suspend fun updateAudioPath(sessionId: String, expectedOldPath: String?, newPath: String): Int

    @Query("UPDATE recording_sessions SET summaryState = :state WHERE id = :sessionId")
    suspend fun updateSummaryState(sessionId: String, state: String): Int

    @Query("UPDATE recording_sessions SET summaryText = :text, summaryState = :state, title = :title, " +
        "originalTitle = CASE WHEN :state = 'completed' THEN COALESCE(originalTitle, title) ELSE originalTitle END WHERE id = :sessionId")
    suspend fun updateSummary(sessionId: String, text: String, state: String, title: String): Int

    /** Export errors never overwrite an unrelated failure and can only be
     *  cleared by a successful retry (a null message must not clear an
     *  unrelated error either). */
    @Query(
        "UPDATE recording_sessions SET errorMessage = :message WHERE id = :sessionId " +
            "AND (errorMessage IS NULL OR errorMessage LIKE 'Recording export failed:%')"
    )
    suspend fun updateExportError(sessionId: String, message: String?): Int

    /** Audio-stage success clears only its own failure; a prior subtitle
     *  failure stays until the SRT stage succeeds. */
    @Query(
        "UPDATE recording_sessions SET errorMessage = NULL WHERE id = :sessionId " +
            "AND (errorMessage IS NULL OR errorMessage LIKE 'Recording export failed: Audio:%')"
    )
    suspend fun clearAudioExportError(sessionId: String): Int

    /** Two-stage finalization: audio is published while transcription runs
     *  (phase recording → transcribing), then the final transcript lands
     *  (phase transcribing → completed). Both are guarded phase transitions
     *  that touch only their own columns. */
    @Query(
        "UPDATE recording_sessions SET phase = 'transcribing', endedAt = :endedAt, " +
            "audioFileName = :audioFileName WHERE id = :sessionId AND phase = 'recording'"
    )
    suspend fun markRecordingStopped(sessionId: String, endedAt: Long, audioFileName: String?): Int

    @Query(
        "UPDATE recording_sessions SET phase = 'completed', transcriptID = :transcriptId " +
            "WHERE id = :sessionId AND phase = 'transcribing'"
    )
    suspend fun completeRecording(sessionId: String, transcriptId: String): Int

    /** Stage 1 commit: the exported audio pointer only, guarded by the row
     *  still holding [expectedAudioPath]. */
    @Query(
        "UPDATE recording_sessions SET exportedWavUri = :audioUri " +
            "WHERE id = :sessionId AND audioFileName IS :expectedAudioPath"
    )
    suspend fun updateExportedAudioUri(sessionId: String, expectedAudioPath: String?, audioUri: String): Int

    /** Stage 2 commit: the exported SRT pointer only, additionally guarded by
     *  the row still holding the committed Stage 1 audio URI. */
    @Query(
        "UPDATE recording_sessions SET exportedSrtUri = :srtUri " +
            "WHERE id = :sessionId AND audioFileName IS :expectedAudioPath AND exportedWavUri IS :expectedAudioUri"
    )
    suspend fun updateExportedSrtUri(
        sessionId: String,
        expectedAudioPath: String?,
        expectedAudioUri: String,
        srtUri: String
    ): Int

    /** MP4 stage commit (Room v8): guarded by completed phase, the expected
     *  audio path, the committed audio/SRT URIs, the title, and the previous
     *  MP4 pointer. A successful commit clears any MP4-only error. */
    @Query(
        "UPDATE recording_sessions SET exportedMp4Uri = :mp4Uri, " +
            "mp4TranscriptFingerprint = :fingerprint, mp4ExportError = NULL " +
            "WHERE id = :sessionId AND phase = 'completed' AND audioFileName IS :expectedAudioPath " +
            "AND exportedWavUri IS :expectedAudioUri AND exportedSrtUri IS :expectedSrtUri " +
            "AND title = :expectedTitle AND exportedMp4Uri IS :expectedOldMp4Uri"
    )
    suspend fun updateExportedMp4Uri(
        sessionId: String,
        expectedAudioPath: String?,
        expectedAudioUri: String,
        expectedSrtUri: String?,
        expectedTitle: String,
        expectedOldMp4Uri: String?,
        mp4Uri: String,
        fingerprint: String?
    ): Int

    /**
     * Rollback/recovery pointer update: when replacing an owned MP4 moved the
     * previous document to a new provider URI (or retained it under a backup
     * name after a failed rollback), updates `exportedMp4Uri` and preserves its
     * prior fingerprint as long as the row still holds [expectedOldMp4Uri],
     * without clearing `mp4ExportError` or failing if title/SRT/segments moved.
     */
    @Query(
        "UPDATE recording_sessions SET exportedMp4Uri = :mp4Uri, " +
            "mp4TranscriptFingerprint = :fingerprint " +
            "WHERE id = :sessionId AND exportedMp4Uri IS :expectedOldMp4Uri"
    )
    suspend fun restoreExportedMp4Uri(
        sessionId: String,
        expectedOldMp4Uri: String?,
        mp4Uri: String,
        fingerprint: String?
    ): Int
    /** MP4-only failure text; touches neither phase, summary state, nor the
     *  shared audio/SRT export error column. */
    @Query("UPDATE recording_sessions SET mp4ExportError = :message WHERE id = :sessionId")
    suspend fun updateMp4ExportError(sessionId: String, message: String?): Int

    /** Terminal capture/codec failure: records the error while preserving any
     *  already-saved audio path, export pointers, notes and a decoded
     *  transcript (when one exists). */
    @Query(
        "UPDATE recording_sessions SET phase = 'failed', endedAt = :endedAt, errorMessage = :message, " +
            "transcriptID = COALESCE(:transcriptId, transcriptID) WHERE id = :sessionId"
    )
    suspend fun failRecording(sessionId: String, endedAt: Long, message: String, transcriptId: String?): Int

    @Query("SELECT id, title FROM recording_sessions WHERE summaryState = 'generating'")
    suspend fun getSessionsWithGeneratingSummary(): List<SummaryGeneratingRow>

    @Delete
    suspend fun deleteSession(session: RecordingSession)
}

/** Minimal projection for stale-summary reconciliation. */
data class SummaryGeneratingRow(val id: String, val title: String)

/** Rendered-subtitle projection of a transcript segment for MP4 snapshot
 *  validation and fingerprinting (badge colors/embeddings excluded). */
data class Mp4SegmentProjection(
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,
    val text: String,
)
fun MeetingTranscriptSegmentEntity.toMp4Projection(): Mp4SegmentProjection = Mp4SegmentProjection(
    orderIndex = orderIndex,
    startMs = startMs,
    endMs = endMs,
    speakerLabel = speakerLabel,
    text = text,
)
