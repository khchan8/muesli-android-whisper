package com.phequals7.muesli.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phequals7.muesli.data.dao.CustomWordDao
import com.phequals7.muesli.data.dao.DictationResultDao
import com.phequals7.muesli.data.dao.RecordingSessionDao
import com.phequals7.muesli.data.dao.SpeakerProfileDao
import com.phequals7.muesli.data.dao.TranscriptDao
import com.phequals7.muesli.data.dao.TranscriptSegmentDao
import com.phequals7.muesli.data.entity.CustomWord
import com.phequals7.muesli.data.entity.DictationResult
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.data.entity.SpeakerProfileEntity
import com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity
import com.phequals7.muesli.data.entity.Transcript

@Database(
    entities = [
        CustomWord::class,
        DictationResult::class,
        RecordingSession::class,
        Transcript::class,
        MeetingTranscriptSegmentEntity::class,
        SpeakerProfileEntity::class,
        SpeakerProfileSampleEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customWordDao(): CustomWordDao
    abstract fun dictationResultDao(): DictationResultDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun speakerProfileDao(): SpeakerProfileDao
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Adds DictationResult.durationMs (keeps existing rows). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictation_results ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds RecordingSession.templateId + manualNotes for meeting notes. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN templateId TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN manualNotes TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds RecordingSession.summaryText + summaryState for AI summaries. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN summaryText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN summaryState TEXT NOT NULL DEFAULT 'notStarted'")
            }
        }

        /**
         * Room v4 → v5: adds `meeting_transcript_segments` (block-level synced
         * playback + speaker attribution) and `speaker_profiles` /
         * `speaker_profile_samples` (persistent CAM++ voiceprints).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meeting_transcript_segments` (
                        `segmentId` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `clusterId` INTEGER NOT NULL,
                        `speakerProfileId` TEXT,
                        `speakerLabel` TEXT NOT NULL,
                        `badgeColorHex` TEXT NOT NULL DEFAULT '#1D63B8',
                        `text` TEXT NOT NULL,
                        `languageCode` TEXT,
                        `clusterCentroidBlob` BLOB,
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_meeting_transcript_segments_sessionId_startMs` " +
                        "ON `meeting_transcript_segments` (`sessionId`, `startMs`, `endMs`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speaker_profiles` (
                        `profileId` TEXT NOT NULL PRIMARY KEY,
                        `displayName` TEXT NOT NULL,
                        `badgeColorHex` TEXT NOT NULL,
                        `sampleCount` INTEGER NOT NULL,
                        `centroidEmbeddingBlob` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speaker_profile_samples` (
                        `sampleId` TEXT NOT NULL PRIMARY KEY,
                        `profileId` TEXT NOT NULL,
                        `sourceSessionId` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `embeddingBlob` BLOB NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        FOREIGN KEY(`profileId`) REFERENCES `speaker_profiles`(`profileId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_speaker_profile_samples_profileId` " +
                        "ON `speaker_profile_samples` (`profileId`)"
                )
            }
        }

        /** Room v5 → v6: nullable SAF export pointers for the recordings-folder WAV/SRT pair. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN exportedWavUri TEXT")
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN exportedSrtUri TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN originalTitle TEXT")
            }
        }

        /** Exposed for migration tests. */
        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "muesli_database"
                )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration() // Useful during scaffolding iteration
                .build()
                INSTANCE = instance
                instance
            }
        }

        @Volatile
        private var PROFILE_STORE_INSTANCE: RoomSpeakerProfileStore? = null

        /** Shared [RoomSpeakerProfileStore] bridging Room v5 and the diarizer. */
        fun getSpeakerProfileStore(context: Context): RoomSpeakerProfileStore {
            val db = getDatabase(context)
            return PROFILE_STORE_INSTANCE ?: synchronized(this) {
                PROFILE_STORE_INSTANCE ?: RoomSpeakerProfileStore(
                    speakerProfileDao = db.speakerProfileDao()
                ).also { PROFILE_STORE_INSTANCE = it }
            }
        }
    }

}
