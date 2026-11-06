package com.phequals7.muesli.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.MeetingRecordingController
import com.phequals7.muesli.meetings.MeetingStorageManager
import com.phequals7.muesli.meetings.SpeakerDiarizer
import com.phequals7.muesli.meetings.SpeakerEmbeddingSerializer
import com.phequals7.muesli.meetings.SpeakerProfileRecord
import com.phequals7.muesli.meetings.SpeakerProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================
// 1. ROOM ENTITIES (v5 Schema)
// ============================================================================

@Entity(tableName = "dictations")
data class DictationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val wordCount: Int,
    val modelId: String? = null,
    val languageCode: String? = null
)

@Entity(tableName = "meeting_sessions")
data class MeetingSessionEntity(
    @PrimaryKey val sessionId: String,
    val title: String,
    val createdAtMs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val durationMs: Long,
    val status: String, // "recording", "completed", "failed", "cancelled"
    val templateId: String = "General",
    val manualNotes: String = "",
    val aiSummary: String? = null,
    val audioFilePath: String? = null, // Points to /sdcard/Recordings/Muesli/<file>.wav
    val detectedSpeakerCount: Int = 0,
    val languageHint: String = "auto"
)

@Entity(
    tableName = "meeting_transcripts",
    foreignKeys = [
        ForeignKey(
            entity = MeetingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"], unique = true)]
)
data class MeetingTranscriptEntity(
    @PrimaryKey val sessionId: String,
    val fullTranscriptText: String,
    val updatedAtMs: Long
)

/**
 * Structured segment table introduced in Room v5 (`MIGRATION_4_5`) for block-level synchronized
 * audio-text highlighting (`startMs <= positionMs <= endMs`) and persistent speaker attribution.
 */
@Entity(
    tableName = "meeting_transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = MeetingSessionEntity::class,
            parentColumns = ["sessionId"],
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

/**
 * Persistent Speaker Profile entity storing the 192-dim L2-normalized CAM++ voiceprint centroid.
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

/**
 * Stores individual voiceprint samples enrolled for a speaker profile across meetings.
 */
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
    @PrimaryKey val sampleId: String = UUID.randomUUID().toString(),
    val profileId: String,
    val sourceSessionId: String?,
    val durationMs: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,
    val createdAtMs: Long
)

@Entity(tableName = "custom_words")
data class CustomWordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val targetPhrase: String,
    val phoneticHint: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

// ============================================================================
// 2. DATA ACCESS OBJECTS (DAOs)
// ============================================================================

@Dao
interface DictationDao {
    @Query("SELECT * FROM dictations ORDER BY createdAtMs DESC")
    fun observeAllDictations(): Flow<List<DictationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDictation(dictation: DictationEntity)

    @Query("DELETE FROM dictations WHERE id = :id")
    suspend fun deleteDictation(id: String)
}

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meeting_sessions ORDER BY createdAtMs DESC")
    fun observeAllSessions(): Flow<List<MeetingSessionEntity>>

    @Query("SELECT * FROM meeting_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<MeetingSessionEntity?>

    @Query("SELECT * FROM meeting_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): MeetingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: MeetingSessionEntity)

    @Query("UPDATE meeting_sessions SET audioFilePath = :newPath WHERE sessionId = :sessionId")
    suspend fun updateSessionAudioPath(sessionId: String, newPath: String)

    @Query("UPDATE meeting_sessions SET manualNotes = :notes WHERE sessionId = :sessionId")
    suspend fun updateManualNotes(sessionId: String, notes: String)

    @Query("UPDATE meeting_sessions SET aiSummary = :summary WHERE sessionId = :sessionId")
    suspend fun updateAiSummary(sessionId: String, summary: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInlineTranscript(transcript: MeetingTranscriptEntity)

    @Query("SELECT * FROM meeting_transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC, orderIndex ASC")
    fun observeSegmentsForSession(sessionId: String): Flow<List<MeetingTranscriptSegmentEntity>>

    @Query("SELECT * FROM meeting_transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC, orderIndex ASC")
    suspend fun getSegmentsForSession(sessionId: String): List<MeetingTranscriptSegmentEntity>

    @Query("DELETE FROM meeting_transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<MeetingTranscriptSegmentEntity>)

    @Transaction
    suspend fun replaceSegmentsForSession(
        sessionId: String,
        segments: List<MeetingTranscriptSegmentEntity>,
        formattedInlineText: String
    ) {
        deleteSegmentsForSession(sessionId)
        insertSegments(segments)
        upsertInlineTranscript(
            MeetingTranscriptEntity(
                sessionId = sessionId,
                fullTranscriptText = formattedInlineText,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    @Query(
        """
        UPDATE meeting_transcript_segments
        SET speakerProfileId = :speakerProfileId,
            speakerLabel = :speakerLabel,
            badgeColorHex = :badgeColorHex
        WHERE sessionId = :sessionId AND clusterId = :clusterId
        """
    )
    suspend fun updateClusterSpeakerAttribution(
        sessionId: String,
        clusterId: Int,
        speakerProfileId: String,
        speakerLabel: String,
        badgeColorHex: String
    )
}

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

@Dao
interface CustomWordDao {
    @Query("SELECT * FROM custom_words ORDER BY targetPhrase ASC")
    fun observeAllCustomWords(): Flow<List<CustomWordEntity>>

    @Query("SELECT * FROM custom_words ORDER BY targetPhrase ASC")
    suspend fun getAllCustomWords(): List<CustomWordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomWord(word: CustomWordEntity)

    @Query("DELETE FROM custom_words WHERE id = :id")
    suspend fun deleteCustomWord(id: String)
}

// ============================================================================
// 3. SPEAKER PROFILE STORE IMPLEMENTATION (Bridging Room v5 <-> SpeakerDiarizer)
// ============================================================================

class RoomSpeakerProfileStore(
    private val speakerProfileDao: SpeakerProfileDao,
    private val meetingDao: MeetingDao
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
        speakerProfileDao.upsertProfile(SpeakerProfileEntity.fromDomainRecord(profile))
        if (newSampleEmbedding.isNotEmpty()) {
            speakerProfileDao.insertProfileSample(
                SpeakerProfileSampleEntity(
                    profileId = profile.profileId,
                    sourceSessionId = sourceSessionId,
                    durationMs = durationMs,
                    embeddingBlob = SpeakerEmbeddingSerializer.floatArrayToBlob(newSampleEmbedding),
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun updateSessionClusterProfile(
        sessionId: String,
        clusterId: Int,
        speakerProfileId: String,
        speakerLabel: String,
        badgeColorHex: String
    ) = withContext(Dispatchers.IO) {
        meetingDao.updateClusterSpeakerAttribution(
            sessionId = sessionId,
            clusterId = clusterId,
            speakerProfileId = speakerProfileId,
            speakerLabel = speakerLabel,
            badgeColorHex = badgeColorHex
        )

        // Also refresh the fallback inline transcript string so text exports reflect the new speaker name
        val updatedSegments = meetingDao.getSegmentsForSession(sessionId).map { it.toDomainModel() }
        if (updatedSegments.isNotEmpty()) {
            val inlineFormatted = updatedSegments.joinToString("\n\n") { seg ->
                val mm = (seg.startMs / 1000) / 60
                val ss = (seg.startMs / 1000) % 60
                String.format("[%02d:%02d] %s: %s", mm, ss, seg.speakerLabel, seg.text)
            }
            meetingDao.upsertInlineTranscript(
                MeetingTranscriptEntity(
                    sessionId = sessionId,
                    fullTranscriptText = inlineFormatted,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }
}

// ============================================================================
// 4. ROOM DATABASE (v5) & EXPLICIT MIGRATIONS (v1 -> v5)
// ============================================================================

@Database(
    entities = [
        DictationEntity::class,
        MeetingSessionEntity::class,
        MeetingTranscriptEntity::class,
        MeetingTranscriptSegmentEntity::class,
        SpeakerProfileEntity::class,
        SpeakerProfileSampleEntity::class,
        CustomWordEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictationDao(): DictationDao
    abstract fun meetingDao(): MeetingDao
    abstract fun speakerProfileDao(): SpeakerProfileDao
    abstract fun customWordDao(): CustomWordDao

    companion object {
        private const val DATABASE_NAME = "muesli_app.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var PROFILE_STORE_INSTANCE: RoomSpeakerProfileStore? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meeting_sessions` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `startedAtMs` INTEGER NOT NULL,
                        `endedAtMs` INTEGER,
                        `durationMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL DEFAULT 'General',
                        `manualNotes` TEXT NOT NULL DEFAULT '',
                        `aiSummary` TEXT,
                        `audioFilePath` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meeting_transcripts` (
                        `sessionId` TEXT NOT NULL PRIMARY KEY,
                        `fullTranscriptText` TEXT NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `meeting_sessions`(`sessionId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_words` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `targetPhrase` TEXT NOT NULL,
                        `phoneticHint` TEXT,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure templateId, manualNotes, aiSummary, and audioFilePath columns exist on meeting_sessions
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `templateId` TEXT NOT NULL DEFAULT 'General'")
                }
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `manualNotes` TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `aiSummary` TEXT")
                }
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `audioFilePath` TEXT")
                }
            }
        }

        /**
         * Room v4 -> v5 Migration:
         * 1. Adds `detectedSpeakerCount` and `languageHint` to `meeting_sessions`.
         * 2. Creates `meeting_transcript_segments` for block-level synchronized playback and speaker attribution.
         * 3. Creates `speaker_profiles` and `speaker_profile_samples` for 192-dim CAM++ voiceprint persistence.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `detectedSpeakerCount` INTEGER NOT NULL DEFAULT 0")
                }
                runCatching {
                    db.execSQL("ALTER TABLE `meeting_sessions` ADD COLUMN `languageHint` TEXT NOT NULL DEFAULT 'auto'")
                }

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
                        FOREIGN KEY(`sessionId`) REFERENCES `meeting_sessions`(`sessionId`) ON DELETE CASCADE
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { db ->
                        INSTANCE = db
                        wireMeetingControllerCallbacks(context.applicationContext, db)
                    }
            }
        }

        fun getSpeakerProfileStore(context: Context): RoomSpeakerProfileStore {
            val db = getInstance(context)
            return PROFILE_STORE_INSTANCE ?: synchronized(this) {
                PROFILE_STORE_INSTANCE ?: RoomSpeakerProfileStore(
                    speakerProfileDao = db.speakerProfileDao(),
                    meetingDao = db.meetingDao()
                ).also { PROFILE_STORE_INSTANCE = it }
            }
        }

        /**
         * Automatically wires `MeetingRecordingController` to persist completed sessions, structured
         * transcript blocks, and speaker profiles into Room v5, and migrates any legacy internal
         * `/data/data/com.phequals7.muesli/files/meetings/*.wav` files to `/sdcard/Recordings/Muesli/`.
         */
        private fun wireMeetingControllerCallbacks(appContext: Context, db: AppDatabase) {
            val store = RoomSpeakerProfileStore(db.speakerProfileDao(), db.meetingDao())
            PROFILE_STORE_INSTANCE = store
            MeetingRecordingController.speakerProfileStore = store

            MeetingRecordingController.sessionPersistenceHandler = { recordingState, diarizationResult ->
                val sessionId = recordingState.sessionId ?: UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val existing = db.meetingDao().getSessionById(sessionId)

                val sessionEntity = MeetingSessionEntity(
                    sessionId = sessionId,
                    title = recordingState.title,
                    createdAtMs = existing?.createdAtMs ?: (now - recordingState.elapsedMs),
                    startedAtMs = existing?.startedAtMs ?: (now - recordingState.elapsedMs),
                    endedAtMs = now,
                    durationMs = recordingState.elapsedMs,
                    status = "completed",
                    templateId = existing?.templateId ?: "General",
                    manualNotes = existing?.manualNotes ?: "",
                    aiSummary = existing?.aiSummary,
                    audioFilePath = recordingState.audioFilePath,
                    detectedSpeakerCount = diarizationResult.detectedSpeakerCount,
                    languageHint = recordingState.languageHint
                )

                db.meetingDao().upsertSession(sessionEntity)
                val segmentEntities = diarizationResult.segments.map {
                    MeetingTranscriptSegmentEntity.fromDomainModel(it)
                }
                db.meetingDao().replaceSegmentsForSession(
                    sessionId = sessionId,
                    segments = segmentEntities,
                    formattedInlineText = diarizationResult.formattedInlineTranscript
                )

                // Also migrate any legacy internal WAV files if still present
                MeetingStorageManager.migrateLegacyInternalRecordings(appContext) { legacySessionId, _, newPath ->
                    db.meetingDao().updateSessionAudioPath(legacySessionId, newPath)
                }
            }
        }
    }
}