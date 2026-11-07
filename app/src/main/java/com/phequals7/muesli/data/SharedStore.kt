package com.phequals7.muesli.data

import android.content.Context
import android.content.SharedPreferences
import com.phequals7.muesli.data.entity.CustomWord
import com.phequals7.muesli.data.entity.DictationResult
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.data.entity.Transcript
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import androidx.room.withTransaction
import com.phequals7.muesli.data.dao.Mp4SegmentProjection
import com.phequals7.muesli.data.dao.toMp4Projection
import kotlinx.coroutines.flow.Flow

class SharedStore(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val customWordDao = database.customWordDao()
    private val dictationResultDao = database.dictationResultDao()
    private val recordingSessionDao = database.recordingSessionDao()
    private val transcriptDao = database.transcriptDao()
    private val transcriptSegmentDao = database.transcriptSegmentDao()

    private val prefs: SharedPreferences = context.getSharedPreferences("muesli_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_ONBOARDING_USE_CASE = "onboarding_use_case"
        private const val KEY_ENGINE_TYPE = "engine_type" // legacy; always "sherpa" now
        private const val KEY_FILLER_WORD_REMOVAL = "filler_word_removal"
        private const val KEY_CUSTOM_DICTIONARY = "custom_dictionary"
        private const val KEY_USER_PROFILE_NAME = "user_profile_name"
        private const val KEY_KEEP_MIC_READY = "keep_mic_ready"
        private const val KEY_APPEARANCE_MODE = "appearance_mode" // "system", "light", "dark"
        private const val KEY_ACCENT_THEME = "accent_theme" // "blue", "green", "slate"
        private const val KEY_DEFAULT_MEETING_TEMPLATE = "default_meeting_template"
        private const val KEY_RETAIN_MEETING_AUDIO = "retain_meeting_audio"
        private const val KEY_SUMMARIES_ENABLED = "summaries_enabled"
        private const val KEY_SUMMARY_BACKEND = "summary_backend" // "chatgpt", "openrouter", or "local_qwen"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_LOCAL_SUMMARY_MODEL_ID = "local_summary_model_id"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val KEY_SPEAKER_LABELS = "speaker_labels"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_MIC_PREFERENCE = "mic_preference" // "auto", "phone", "bluetooth", "usb"
        private const val KEY_CUSTOM_SUMMARY_URL = "custom_summary_url"
        private const val KEY_CUSTOM_SUMMARY_API_KEY = "custom_summary_api_key"
        private const val KEY_CUSTOM_SUMMARY_MODEL = "custom_summary_model"
        private const val KEY_RECORDINGS_TREE_URI = "recordings_tree_uri"
        private const val KEY_LANGUAGE_HINT = "language_hint" // "auto", "en", "th", "zh", "yue"
        private const val KEY_EXPORT_EMBEDDED_MP4 = "export_embedded_mp4_enabled"
        private const val KEY_EXPORT_MP4_SUBFOLDER = "export_mp4_subfolder_enabled"
        private const val KEY_BACKUP_AUTO = "backup_auto_enabled"
        private const val KEY_BACKUP_LAST_AT = "backup_last_at"
        private const val KEY_BACKUP_LAST_HASH = "backup_last_hash"

        /**
         * Pure snapshot-validation predicate shared by pre-publish validation
         * and the transactional MP4 commit: compares the persisted session
         * fields and rendered segment projection `{orderIndex,startMs,endMs,speakerLabel,text}`
         * while ignoring notes, summary text, profile embeddings and badge colors.
         */
        internal fun matchesMp4Snapshot(
            session: RecordingSession?,
            currentSegments: List<Mp4SegmentProjection>,
            expectedAudioPath: String?,
            expectedAudioUri: String,
            expectedSrtUri: String?,
            expectedTitle: String,
            expectedOldMp4Uri: String?,
            expectedSegments: List<Mp4SegmentProjection>
        ): Boolean {
            if (session == null) return false
            if (session.phase != "completed" ||
                session.audioFileName != expectedAudioPath ||
                session.exportedAudioUri != expectedAudioUri ||
                session.exportedSrtUri != expectedSrtUri ||
                session.title != expectedTitle ||
                session.exportedMp4Uri != expectedOldMp4Uri
            ) return false
            return currentSegments == expectedSegments
        }
    }

    // Onboarding settings
    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    /** Use case picked in onboarding: "voice_notes", "copy", "keyboard",
     * "meetings", or "everything" (drives which steps are shown). */
    var onboardingUseCase: String
        get() = prefs.getString(KEY_ONBOARDING_USE_CASE, "everything") ?: "everything"
        set(value) = prefs.edit().putString(KEY_ONBOARDING_USE_CASE, value).apply()

    var engineType: String
        // Only the on-device sherpa engine exists now (the "system" and
        // "mock" engines were removed); legacy stored values are ignored.
        get() = "sherpa"
        set(value) = prefs.edit().putString(KEY_ENGINE_TYPE, value).apply()

    var isFillerWordRemovalEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILLER_WORD_REMOVAL, true)
        set(value) = prefs.edit().putBoolean(KEY_FILLER_WORD_REMOVAL, value).apply()

    var isCustomDictionaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_DICTIONARY, true)
        set(value) = prefs.edit().putBoolean(KEY_CUSTOM_DICTIONARY, value).apply()

    /**
     * When enabled, the Muesli keyboard starts dictation as soon as it is
     * shown — the Android counterpart of the iOS "Keep mic ready" session mode.
     */
    var keepMicReady: Boolean
        get() = prefs.getBoolean(KEY_KEEP_MIC_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_MIC_READY, value).apply()

    /** Appearance: "system" (default), "light", or "dark". */
    var appearanceMode: String
        get() = prefs.getString(KEY_APPEARANCE_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_APPEARANCE_MODE, value).apply()

    /** Accent theme: "blue" (default), "green", or "slate" (MuesliAccentTheme). */
    var accentTheme: String
        get() = prefs.getString(KEY_ACCENT_THEME, "blue") ?: "blue"
        set(value) = prefs.edit().putString(KEY_ACCENT_THEME, value).apply()

    /** Default meeting template preselected on the Meetings tab. */
    var defaultMeetingTemplate: String
        get() = prefs.getString(KEY_DEFAULT_MEETING_TEMPLATE, "general") ?: "general"
        set(value) = prefs.edit().putString(KEY_DEFAULT_MEETING_TEMPLATE, value).apply()

    /** When false, meeting audio is not retained on disk (text only). */
    var retainMeetingAudio: Boolean
        get() = prefs.getBoolean(KEY_RETAIN_MEETING_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_RETAIN_MEETING_AUDIO, value).apply()

    /** AI meeting summaries master toggle (default off, like iOS). */
    var summariesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUMMARIES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SUMMARIES_ENABLED, value).apply()

    /** Summary backend: "chatgpt" (primary, WHAM), "openrouter" (BYOK), or "local_qwen" (on-device GGUF). */
    var summaryBackend: String
        get() = prefs.getString(KEY_SUMMARY_BACKEND, "chatgpt") ?: "chatgpt"
        set(value) = prefs.edit().putString(KEY_SUMMARY_BACKEND, value).apply()

    var chatGptModel: String
        get() = prefs.getString(KEY_CHATGPT_MODEL, "gpt-5.5") ?: "gpt-5.5"
        set(value) = prefs.edit().putString(KEY_CHATGPT_MODEL, value).apply()

    var openRouterModel: String
        get() = prefs.getString(KEY_OPENROUTER_MODEL, "stepfun/step-3.5-flash:free") ?: "stepfun/step-3.5-flash:free"
        set(value) = prefs.edit().putString(KEY_OPENROUTER_MODEL, value).apply()

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENROUTER_API_KEY, value).apply()

    /** Selected on-device Qwen3 GGUF summary model (defaults to 1.7B Q4_K_M). */
    var localSummaryModelId: String
        get() = prefs.getString(KEY_LOCAL_SUMMARY_MODEL_ID, "qwen3-summary-1.7b-q4-k-m") ?: "qwen3-summary-1.7b-q4-k-m"
        set(value) = prefs.edit().putString(KEY_LOCAL_SUMMARY_MODEL_ID, value).apply()
    /** Custom OpenAI-compatible backend: full HTTPS chat-completions endpoint. */
    var customSummaryUrl: String
        get() = prefs.getString(KEY_CUSTOM_SUMMARY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SUMMARY_URL, value).apply()

    /** Bearer key for the custom endpoint; blank = unauthenticated endpoint. */
    var customSummaryApiKey: String
        get() = prefs.getString(KEY_CUSTOM_SUMMARY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SUMMARY_API_KEY, value).apply()

    /** Model id sent to the custom endpoint (required). */
    var customSummaryModel: String
        get() = prefs.getString(KEY_CUSTOM_SUMMARY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SUMMARY_MODEL, value).apply()

    /** SAF tree URI of the user-chosen recordings export folder; blank = not chosen. */
    var recordingsTreeUri: String
        get() = prefs.getString(KEY_RECORDINGS_TREE_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RECORDINGS_TREE_URI, value).apply()

    /** Write `muesli-backup.json` into the Recordings folder when the app is left. */
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKUP_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKUP_AUTO, value).apply()

    /** `exportedAt` of the backup this install last wrote or restored (0 = never). */
    var lastBackupAtMs: Long
        get() = prefs.getLong(KEY_BACKUP_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_BACKUP_LAST_AT, value).apply()

    var lastBackupHash: String
        get() = prefs.getString(KEY_BACKUP_LAST_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKUP_LAST_HASH, value).apply()

    /** Raw snapshot of every stored preference (AppBackup filters out secrets). */
    fun allPrefs(): Map<String, Any?> = prefs.all

    /** Writes already-filtered backup values; unsupported value types are skipped. */
    fun applyPrefs(values: Map<String, Any>) {
        val e = prefs.edit()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
        }
        e.apply()
    }

    /** Also export a same-basename `.mp4` (canvas video + copied audio +
     *  embedded tx3g transcript subtitles) after the SRT stage; default off. */
    var exportEmbeddedMp4Enabled: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_EMBEDDED_MP4, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPORT_EMBEDDED_MP4, value).apply()

    /** New MP4 exports go into an "mp4" subfolder of the recordings folder. */
    var mp4SubfolderEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_MP4_SUBFOLDER, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPORT_MP4_SUBFOLDER, value).apply()
    var userProfileName: String
        get() = prefs.getString(KEY_USER_PROFILE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_PROFILE_NAME, value).apply()

    /** Selected on-device ASR model (id from the SpeechModels catalog). */
    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL_ID, value).apply()

    /** When on, meetings are diarized after stop and transcript chunks are
     * prefixed with speaker labels (adds post-processing time). */
    var speakerLabelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER_LABELS, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKER_LABELS, value).apply()

    /** ASR language hint for multilingual models ("auto", "en", "th", "zh", "yue"). */
    var languageHint: String
        get() = prefs.getString(KEY_LANGUAGE_HINT, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_LANGUAGE_HINT, value).apply()

    /** Floating quick-capture bubble over other apps (Android-exclusive). */
    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, value).apply()

    /** Recording microphone route: "auto" (default), "phone", "bluetooth", or "usb"
     * (AudioInputRouteManager.MicPreference ids, iOS RecordingMicrophonePreference parity). */
    var micPreference: String
        get() = prefs.getString(KEY_MIC_PREFERENCE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_MIC_PREFERENCE, value).apply()

    // Custom Words dictionary
    fun getCustomWordsFlow(): Flow<List<CustomWord>> = customWordDao.getAllCustomWordsFlow()
    suspend fun getCustomWords(): List<CustomWord> = customWordDao.getAllCustomWords()
    suspend fun insertCustomWord(word: CustomWord) = customWordDao.insertCustomWord(word)
    suspend fun updateCustomWord(word: CustomWord) = customWordDao.updateCustomWord(word)
    suspend fun deleteCustomWordById(id: String) = customWordDao.deleteCustomWordById(id)

    // Dictation results
    fun getDictationResultsFlow(): Flow<List<DictationResult>> = dictationResultDao.getAllResultsFlow()
    suspend fun getDictationResults(): List<DictationResult> = dictationResultDao.getAllResults()
    suspend fun getDictationResultForRequest(requestId: String): DictationResult? = dictationResultDao.getResultForRequest(requestId)
    suspend fun insertDictationResult(result: DictationResult) = dictationResultDao.insertResult(result)
    suspend fun deleteDictationResult(id: String) = dictationResultDao.deleteResult(id)

    // Recording Sessions
    fun getRecordingSessionsFlow(): Flow<List<RecordingSession>> = recordingSessionDao.getAllSessionsFlow()
    suspend fun getRecordingSessions(): List<RecordingSession> = recordingSessionDao.getAllSessions()
    suspend fun getRecordingSessionById(id: String): RecordingSession? = recordingSessionDao.getSessionById(id)
    suspend fun getRecordingSessionByRequestId(requestId: String): RecordingSession? = recordingSessionDao.getSessionByRequestId(requestId)
    suspend fun insertRecordingSession(session: RecordingSession) = recordingSessionDao.insertSession(session)
    suspend fun updateRecordingSession(session: RecordingSession) = recordingSessionDao.updateSession(session)
    suspend fun deleteRecordingSession(session: RecordingSession) = recordingSessionDao.deleteSession(session)

    // Field-scoped meeting updates (see RecordingSessionDao for rationale)
    suspend fun updateMeetingNotes(sessionId: String, notes: String): Int =
        recordingSessionDao.updateMeetingNotes(sessionId, notes)

    suspend fun updateRecordingAudioPath(sessionId: String, expectedOldPath: String?, newPath: String): Int =
        recordingSessionDao.updateAudioPath(sessionId, expectedOldPath, newPath)

    suspend fun updateMeetingSummaryState(sessionId: String, state: String): Int =
        recordingSessionDao.updateSummaryState(sessionId, state)

    suspend fun updateMeetingSummary(sessionId: String, text: String, state: String, title: String): Int =
        recordingSessionDao.updateSummary(sessionId, text, state, title)

    suspend fun updateRecordingExportError(sessionId: String, message: String?): Int =
        recordingSessionDao.updateExportError(sessionId, message)

    suspend fun clearAudioExportError(sessionId: String): Int =
        recordingSessionDao.clearAudioExportError(sessionId)

    // Transcripts
    fun getTranscriptForSessionFlow(sessionId: String): Flow<Transcript?> = transcriptDao.getTranscriptForSessionFlow(sessionId)
    suspend fun getTranscriptForSession(sessionId: String): Transcript? = transcriptDao.getTranscriptForSession(sessionId)
    suspend fun insertTranscript(transcript: Transcript) = transcriptDao.insertTranscript(transcript)
    suspend fun deleteTranscript(transcript: Transcript) = transcriptDao.deleteTranscript(transcript)

    // Structured transcript segments (Room v5)
    fun getTranscriptSegmentsFlow(sessionId: String): Flow<List<MeetingTranscriptSegmentEntity>> =
        transcriptSegmentDao.observeSegmentsForSession(sessionId)

    suspend fun getTranscriptSegments(sessionId: String): List<MeetingTranscriptSegmentEntity> =
        transcriptSegmentDao.getSegmentsForSession(sessionId)

    suspend fun replaceTranscriptSegments(sessionId: String, segments: List<MeetingTranscriptSegmentEntity>, inlineText: String) =
        transcriptSegmentDao.replaceSegmentsForSession(sessionId, segments, inlineText)

    /** Relabels one speaker's turns and refreshes the flat transcript text to match. */
    suspend fun relabelSpeakerCluster(sessionId: String, clusterId: Int, profileId: String, label: String, badgeColorHex: String) {
        transcriptSegmentDao.updateClusterSpeakerAttribution(sessionId, clusterId, profileId, label, badgeColorHex)
        val segments = transcriptSegmentDao.getSegmentsForSession(sessionId)
        if (segments.isNotEmpty()) {
            transcriptSegmentDao.updateInlineTranscriptText(
                sessionId,
                com.phequals7.muesli.meetings.SpeakerDiarizer(null).formatInlineTranscript(segments.map { it.toDomainModel() })
            )
        }
    }

    suspend fun markRecordingStopped(sessionId: String, endedAt: Long, audioFileName: String?): Int =
        recordingSessionDao.markRecordingStopped(sessionId, endedAt, audioFileName)

    suspend fun completeRecording(sessionId: String, transcriptId: String): Int =
        recordingSessionDao.completeRecording(sessionId, transcriptId)

    suspend fun updateExportedAudioUri(sessionId: String, expectedAudioPath: String?, audioUri: String): Int =
        recordingSessionDao.updateExportedAudioUri(sessionId, expectedAudioPath, audioUri)

    suspend fun updateExportedSrtUri(
        sessionId: String,
        expectedAudioPath: String?,
        expectedAudioUri: String,
        srtUri: String
    ): Int = recordingSessionDao.updateExportedSrtUri(sessionId, expectedAudioPath, expectedAudioUri, srtUri)

    suspend fun failRecording(sessionId: String, endedAt: Long, message: String, transcriptId: String?): Int =
        recordingSessionDao.failRecording(sessionId, endedAt, message, transcriptId)

    suspend fun getSessionsWithGeneratingSummary(): List<com.phequals7.muesli.data.dao.SummaryGeneratingRow> =
        recordingSessionDao.getSessionsWithGeneratingSummary()
    // MP4 embedded-subtitle export (Room v8)

    suspend fun updateExportedMp4Uri(
        sessionId: String,
        expectedAudioPath: String?,
        expectedAudioUri: String,
        expectedSrtUri: String?,
        expectedTitle: String,
        expectedOldMp4Uri: String?,
        mp4Uri: String,
        fingerprint: String?
    ): Int = recordingSessionDao.updateExportedMp4Uri(
        sessionId, expectedAudioPath, expectedAudioUri, expectedSrtUri,
        expectedTitle, expectedOldMp4Uri, mp4Uri, fingerprint
    )
    suspend fun restoreExportedMp4Uri(
        sessionId: String,
        expectedOldMp4Uri: String?,
        mp4Uri: String,
        fingerprint: String?
    ): Int = recordingSessionDao.restoreExportedMp4Uri(
        sessionId, expectedOldMp4Uri, mp4Uri, fingerprint
    )

    suspend fun updateMp4ExportError(sessionId: String, message: String?): Int =
        recordingSessionDao.updateMp4ExportError(sessionId, message)

    /**
     * One Room transaction: re-reads the session and its segment projection
     * and reports whether they still match the captured MP4 snapshot inputs
     * (notes, summary text, profile embeddings and badge colors ignored).
     */
    suspend fun validateMp4Snapshot(
        sessionId: String,
        expectedAudioPath: String,
        expectedAudioUri: String,
        expectedSrtUri: String?,
        expectedTitle: String,
        expectedOldMp4Uri: String?,
        expectedSegments: List<Mp4SegmentProjection>
    ): Boolean = database.withTransaction {
        val session = recordingSessionDao.getSessionById(sessionId)
        val current = transcriptSegmentDao.getSegmentsForSession(sessionId).map { it.toMp4Projection() }
        matchesMp4Snapshot(
            session = session,
            currentSegments = current,
            expectedAudioPath = expectedAudioPath,
            expectedAudioUri = expectedAudioUri,
            expectedSrtUri = expectedSrtUri,
            expectedTitle = expectedTitle,
            expectedOldMp4Uri = expectedOldMp4Uri,
            expectedSegments = expectedSegments
        )
    }

    /**
     * Single Room transaction: validates the persisted session fields and
     * rendered segment projection `{orderIndex,startMs,endMs,speakerLabel,text}`
     * against the captured snapshot (ignoring notes, summary text, profile
     * embeddings and badge colors) and, only when they match, executes the
     * guarded MP4 URI + fingerprint commit.
     */
    suspend fun commitMp4Export(
        sessionId: String,
        expectedAudioPath: String,
        expectedAudioUri: String,
        expectedSrtUri: String?,
        expectedTitle: String,
        expectedOldMp4Uri: String?,
        expectedSegments: List<Mp4SegmentProjection>,
        mp4Uri: String,
        fingerprint: String?
    ): Boolean = database.withTransaction {
        val session = recordingSessionDao.getSessionById(sessionId)
        val current = transcriptSegmentDao.getSegmentsForSession(sessionId).map { it.toMp4Projection() }
        if (!matchesMp4Snapshot(
                session = session,
                currentSegments = current,
                expectedAudioPath = expectedAudioPath,
                expectedAudioUri = expectedAudioUri,
                expectedSrtUri = expectedSrtUri,
                expectedTitle = expectedTitle,
                expectedOldMp4Uri = expectedOldMp4Uri,
                expectedSegments = expectedSegments
            )
        ) {
            return@withTransaction false
        }
        recordingSessionDao.updateExportedMp4Uri(
            sessionId = sessionId,
            expectedAudioPath = expectedAudioPath,
            expectedAudioUri = expectedAudioUri,
            expectedSrtUri = expectedSrtUri,
            expectedTitle = expectedTitle,
            expectedOldMp4Uri = expectedOldMp4Uri,
            mp4Uri = mp4Uri,
            fingerprint = fingerprint
        ) == 1
    }
}
