package com.phequals7.muesli.data.dao

import androidx.room.*
import com.phequals7.muesli.data.entity.RecordingSession
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

    /** Commits the paired SAF export pointers in one write, guarded by the
     *  session still holding [expectedAudioPath] (and existing). */
    @Query(
        "UPDATE recording_sessions SET exportedWavUri = :wavUri, exportedSrtUri = :srtUri " +
            "WHERE id = :sessionId AND audioFileName IS :expectedAudioPath"
    )
    suspend fun updateRecordingExportUris(sessionId: String, expectedAudioPath: String?, wavUri: String, srtUri: String): Int

    @Query("SELECT id, title FROM recording_sessions WHERE summaryState = 'generating'")
    suspend fun getSessionsWithGeneratingSummary(): List<SummaryGeneratingRow>

    @Delete
    suspend fun deleteSession(session: RecordingSession)
}

/** Minimal projection for stale-summary reconciliation. */
data class SummaryGeneratingRow(val id: String, val title: String)
