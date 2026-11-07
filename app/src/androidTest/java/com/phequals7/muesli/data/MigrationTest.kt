package com.phequals7.muesli.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration scaffold: hand-builds a v1-shaped database (as Room v1 wrote
 * it), runs all migrations to the current version via the real
 * [AppDatabase] open path, and asserts data survives with new columns
 * defaulted. Extend with per-version fixtures as the schema evolves.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun createV1Database() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
            db.execSQL(
                """CREATE TABLE dictation_results (
                     id TEXT NOT NULL PRIMARY KEY,
                     requestID TEXT NOT NULL,
                     sessionID TEXT,
                     text TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     engineIdentifier TEXT NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE recording_sessions (
                     id TEXT NOT NULL PRIMARY KEY,
                     requestID TEXT,
                     kind TEXT NOT NULL,
                     title TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     startedAt INTEGER,
                     endedAt INTEGER,
                     phase TEXT NOT NULL,
                     audioFileName TEXT,
                     transcriptID TEXT,
                     engineIdentifier TEXT,
                     errorMessage TEXT
                   )"""
            )
            db.execSQL(
                """CREATE TABLE transcripts (
                     id TEXT NOT NULL PRIMARY KEY,
                     sessionID TEXT NOT NULL,
                     text TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     engineIdentifier TEXT NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE custom_words (
                     id TEXT NOT NULL PRIMARY KEY,
                     word TEXT NOT NULL,
                     replacement TEXT,
                     matchingThreshold REAL NOT NULL,
                     createdAt INTEGER NOT NULL,
                     isEnabled INTEGER NOT NULL
                   )"""
            )
            db.execSQL("INSERT INTO dictation_results VALUES ('d1','r1',NULL,'hello from v1',1700000000000,'parakeet')")
            db.execSQL("INSERT INTO recording_sessions VALUES ('s1',NULL,'meeting','v1 meeting',1700000000000,NULL,NULL,'completed',NULL,NULL,'parakeet',NULL)")
            // Without this Room sees user_version=0, treats the DB as fresh,
            // and fails schema validation instead of running migrations.
            db.version = 1
        }
    }

    @Test
    fun migrate1ToLatest_preservesDataAndAddsColumns() {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        // Opening the DB forces the full migration chain.
        val results = runBlocking { db.dictationResultDao().getAllResults() }
        assertEquals(1, results.size)
        assertEquals("hello from v1", results[0].text)
        assertEquals(0L, results[0].durationMs) // MIGRATION_1_2 default

        val session = runBlocking { db.recordingSessionDao().getSessionById("s1") }
        assertNotNull(session)
        assertEquals("v1 meeting", session!!.title)
        assertEquals("general", session.templateId) // MIGRATION_2_3 default
        assertEquals("", session.summaryText)
        assertEquals("notStarted", session.summaryState)
        assertNull("v6 export pointers default to null", session.exportedWavUri)
        assertNull(session.exportedSrtUri)
        assertNull(session.originalTitle)
        runBlocking {
            db.recordingSessionDao().updateSummary("s1", "summary", "completed", "v1 meeting - topic")
            db.recordingSessionDao().updateSummary("s1", "new summary", "completed", "v1 meeting - new topic")
            val updated = db.recordingSessionDao().getSessionById("s1")!!
            assertEquals("v1 meeting", updated.originalTitle)
            assertEquals("v1 meeting - new topic", updated.title)
        }
        // MIGRATION_4_5: v2 speaker-profile and transcript-segment tables exist and are writable.
        runBlocking {
            db.speakerProfileDao().upsertProfile(
                com.phequals7.muesli.data.entity.SpeakerProfileEntity(
                    profileId = "p1",
                    displayName = "Alice",
                    badgeColorHex = "#1D63B8",
                    sampleCount = 1,
                    centroidEmbeddingBlob = ByteArray(768),
                    createdAtMs = 1L,
                    updatedAtMs = 1L
                )
            )
            assertEquals("Alice", db.speakerProfileDao().getProfileById("p1")?.displayName)
            assertEquals(0, db.transcriptSegmentDao().getSegmentsForSession("s1").size)
        }

        db.close()
    }

    /** Hand-builds a v5-shaped database (v1 tables + v3/v4/v5 columns and tables). */
    private fun createV5Database() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
            db.execSQL(
                """CREATE TABLE dictation_results (
                     id TEXT NOT NULL PRIMARY KEY,
                     requestID TEXT NOT NULL,
                     sessionID TEXT,
                     text TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     engineIdentifier TEXT NOT NULL,
                     durationMs INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            db.execSQL(
                """CREATE TABLE recording_sessions (
                     id TEXT NOT NULL PRIMARY KEY,
                     requestID TEXT,
                     kind TEXT NOT NULL,
                     title TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     startedAt INTEGER,
                     endedAt INTEGER,
                     phase TEXT NOT NULL,
                     audioFileName TEXT,
                     transcriptID TEXT,
                     engineIdentifier TEXT,
                     errorMessage TEXT,
                     templateId TEXT NOT NULL DEFAULT 'general',
                     manualNotes TEXT NOT NULL DEFAULT '',
                     summaryText TEXT NOT NULL DEFAULT '',
                     summaryState TEXT NOT NULL DEFAULT 'notStarted'
                   )"""
            )
            db.execSQL(
                """CREATE TABLE transcripts (
                     id TEXT NOT NULL PRIMARY KEY,
                     sessionID TEXT NOT NULL,
                     text TEXT NOT NULL,
                     createdAt INTEGER NOT NULL,
                     engineIdentifier TEXT NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE custom_words (
                     id TEXT NOT NULL PRIMARY KEY,
                     word TEXT NOT NULL,
                     replacement TEXT,
                     matchingThreshold REAL NOT NULL,
                     createdAt INTEGER NOT NULL,
                     isEnabled INTEGER NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE meeting_transcript_segments (
                     segmentId TEXT NOT NULL PRIMARY KEY,
                     sessionId TEXT NOT NULL,
                     orderIndex INTEGER NOT NULL,
                     startMs INTEGER NOT NULL,
                     endMs INTEGER NOT NULL,
                     clusterId INTEGER NOT NULL,
                     speakerProfileId TEXT,
                     speakerLabel TEXT NOT NULL,
                     badgeColorHex TEXT NOT NULL DEFAULT '#1D63B8',
                     text TEXT NOT NULL,
                     languageCode TEXT,
                     clusterCentroidBlob BLOB,
                     FOREIGN KEY(sessionId) REFERENCES recording_sessions(id) ON DELETE CASCADE
                   )"""
            )
            db.execSQL(
                """CREATE TABLE speaker_profiles (
                     profileId TEXT NOT NULL PRIMARY KEY,
                     displayName TEXT NOT NULL,
                     badgeColorHex TEXT NOT NULL,
                     sampleCount INTEGER NOT NULL,
                     centroidEmbeddingBlob BLOB NOT NULL,
                     createdAtMs INTEGER NOT NULL,
                     updatedAtMs INTEGER NOT NULL
                   )"""
            )
            db.execSQL(
                """CREATE TABLE speaker_profile_samples (
                     sampleId TEXT NOT NULL PRIMARY KEY,
                     profileId TEXT NOT NULL,
                     sourceSessionId TEXT,
                     durationMs INTEGER NOT NULL,
                     embeddingBlob BLOB NOT NULL,
                     createdAtMs INTEGER NOT NULL,
                     FOREIGN KEY(profileId) REFERENCES speaker_profiles(profileId) ON DELETE CASCADE
                   )"""
            )
            db.execSQL("CREATE INDEX index_meeting_transcript_segments_sessionId_startMs ON meeting_transcript_segments(sessionId, startMs, endMs)")
            db.execSQL("CREATE INDEX index_speaker_profile_samples_profileId ON speaker_profile_samples(profileId)")
            db.execSQL(
                "INSERT INTO recording_sessions VALUES ('v5s','r','meeting','v5 title',1700000000000,1700000005000,1700000006000," +
                    "'completed','Meeting_2026.wav','t1','qwen',NULL,'general','kept notes','old summary','completed')"
            )
            db.execSQL("INSERT INTO transcripts VALUES ('t1','v5s','v5 transcript text',1700000000000,'qwen')")
            db.execSQL(
                "INSERT INTO meeting_transcript_segments VALUES ('g1','v5s',0,0,2000,0,NULL,'Alice','#1D63B8','hello',NULL,NULL)"
            )
            db.version = 5
        }
    }

    @Test
    fun migrate5To6_preservesSessionTranscriptSegmentsAndAddsExportPointers() {
        createV5Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        runBlocking {
            val session = db.recordingSessionDao().getSessionById("v5s")
            assertNotNull(session)
            assertEquals("v5 title", session!!.title)
            assertEquals("kept notes", session.manualNotes)
            assertEquals("old summary", session.summaryText)
            assertEquals("completed", session.summaryState)
            assertEquals("Meeting_2026.wav", session.audioFileName)
            assertNull("MIGRATION_5_6 leaves existing rows' pointers null", session.exportedWavUri)
            assertNull(session.exportedSrtUri)
            assertEquals("v5 transcript text", db.transcriptDao().getTranscriptForSession("v5s")?.text)
            assertEquals(1, db.transcriptSegmentDao().getSegmentsForSession("v5s").size)
        }

        db.close()
    }

    @Test
    fun exportUrisCommitTogetherAndExportErrorPredicateProtectsUnrelatedFailures() {
        createV5Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        runBlocking {
            val dao = db.recordingSessionDao()
            // Guarded commit: only while the row still holds the expected audio path.
            assertEquals(
                1,
                dao.updateRecordingExportUris("v5s", "Meeting_2026.wav", "tree://wav", "tree://srt")
            )
            assertEquals(
                0,
                dao.updateRecordingExportUris("v5s", "stale.wav", "tree://w2", "tree://s2")
            )
            assertEquals("tree://wav", dao.getSessionById("v5s")?.exportedWavUri)
            assertEquals("tree://srt", dao.getSessionById("v5s")?.exportedSrtUri)

            // An export error may be set (null current) and replaced (prefixed),
            // but never overwrites an unrelated failure and null never clears one.
            assertEquals(1, dao.updateExportError("v5s", "Recording export failed: no folder"))
            assertEquals(1, dao.updateExportError("v5s", "Recording export failed: retry"))
            assertEquals(1, dao.updateExportError("v5s", null)) // clearing an export error is allowed
            assertEquals(null, dao.getSessionById("v5s")?.errorMessage)

            db.openHelper.writableDatabase.execSQL("UPDATE recording_sessions SET errorMessage = 'Microphone failed' WHERE id = 'v5s'")
            assertEquals(0, dao.updateExportError("v5s", "Recording export failed: x"))
            assertEquals(0, dao.updateExportError("v5s", null))
            assertEquals("Microphone failed", dao.getSessionById("v5s")?.errorMessage)
        }

        db.close()
    }
}
