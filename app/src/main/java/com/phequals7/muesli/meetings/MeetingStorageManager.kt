package com.phequals7.muesli.meetings

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Private `/data/data/…/files/meetings` audio + SRT artifacts with
 * title-driven naming, plus two-stage export into the user-granted
 * recordings folder (SAF tree): Stage 1 publishes the retained audio
 * (`.m4a`, or legacy `.wav`) as soon as capture stops; Stage 2 publishes
 * the `.srt` after the final transcript/segments are persisted. Legacy
 * public `/sdcard/Recordings/Muesli` files stay readable where they are —
 * nothing is migrated or transcoded for migration's sake.
 */
object MeetingStorageManager {
    private const val TAG = "MeetingStorageManager"
    const val EXPORT_ERROR_PREFIX = "Recording export failed:"
    const val AUDIO_STAGE_ERROR_PREFIX = "$EXPORT_ERROR_PREFIX Audio:"
    const val SRT_STAGE_ERROR_PREFIX = "$EXPORT_ERROR_PREFIX SRT:"
    private const val MIME_WAV = "audio/wav"
    private const val MIME_M4A = "audio/mp4"
    private const val MIME_SRT = "application/x-subrip"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Serializes every artifact write/rename in the process. */
    private val artifactMutex = Mutex()

    /** Private meetings dir: capture target and canonical storage location. */
    fun internalRecordingsDirectory(context: File): File =
        File(context, "meetings").apply { mkdirs() }

    /**
     * `<safe-title>_yyyy-MM-dd_HH-mm-ss-SSS` in the device's local timezone.
     * Keeps Unicode letters, combining marks, and digits; other characters
     * become `_`; the title is capped at 160 UTF-8 bytes (no split code
     * points) so the full filename stays below filesystem limits.
     */
    fun recordingBaseName(title: String?, startedAtMs: Long): String {
        val sanitizedTitle = sanitizeTitleForFilename(title)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date(startedAtMs))
        return "${sanitizedTitle}_$timestamp"
    }

    internal fun sanitizeTitleForFilename(title: String?): String {
        var cleaned = (title ?: "")
            .replace(Regex("[\\p{Z}\\t\\r\\n\\u0000-\\u001f\\/\\\\:\"*?<>|]"), "_")
            .replace(Regex("[^\\p{L}\\p{M}\\p{Nd}_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ')
        // Cap at 160 UTF-8 bytes without splitting a code point.
        var bytes = 0
        var end = 0
        for ((index, ch) in cleaned.withIndex()) {
            val chBytes = when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                ch.code <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + chBytes > 160) break
            bytes += chBytes
            end = index + 1
        }
        cleaned = cleaned.take(end).trim('_', '.', ' ')
        return cleaned.ifEmpty { "Meeting" }
    }

    /**
     * Reserves a collision-free `.m4a` (checking `.m4a`, `.wav`, and `.srt`
     * candidates) named after the recording start time, and returns it.
     */
    fun createSessionAudioFile(directory: File, title: String?, startedAtMs: Long): File {
        directory.mkdirs()
        val base = recordingBaseName(title, startedAtMs)
        var attempt = 1
        while (true) {
            val stem = if (attempt == 1) base else "${base}_$attempt"
            val audio = File(directory, "$stem.m4a")
            // All three extensions must be free so the SRT sidecar can share the stem.
            if (!audio.exists() && !File(directory, "$stem.wav").exists() &&
                !File(directory, "$stem.srt").exists() && audio.createNewFile()
            ) return audio
            attempt++
        }
    }

    /** Resolves a stored [com.phequals7.muesli.data.entity.RecordingSession.audioFileName] to a file. */
    fun resolveRecordingAudio(filesDir: File, audioFileName: String?): File? {
        val name = audioFileName?.takeIf { it.isNotBlank() } ?: return null
        return if (name.startsWith("/")) File(name) else File(filesDir, "meetings/$name")
    }

    /** A usable retained audio file: a nonempty regular file; legacy WAVs
     *  additionally need a data payload beyond the 44-byte header. */
    internal fun isUsableAudioFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (!file.name.endsWith(".wav", ignoreCase = true)) return true
        return file.length() > 44L
    }

    /** A finalized M4A is exportable only when it exposes a readable AAC track. */
    internal fun hasReadableAacTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
        } catch (t: Throwable) {
            Log.w(TAG, "M4A validation failed for ${file.name}: ${t.message}")
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Result of one private artifact normalization pass. */
    internal data class SyncOutcome(
        /** Absolute path of the (possibly renamed) audio file after the pass. */
        val audioPath: String?,
        /** Export failure text starting with [EXPORT_ERROR_PREFIX], or null. */
        val exportError: String?
    )

    /**
     * Normalizes the retained audio file to a title-driven
     * `<title>_<startedAt>` basename in [targetDirectory] and updates Room's
     * `audioFileName` only after the new file is durable. An existing private
     * SRT sidecar is copied to the new stem (never created when absent — new
     * subtitle content is Stage 2's job). The DB commit AND the source
     * cleanup both run in [NonCancellable] so a cancellation delivered right
     * after the commit can never strand the new pair or delete it after the
     * DB already points at it.
     */
    internal suspend fun syncSessionArtifacts(
        sessionId: String,
        title: String,
        startedAtMs: Long,
        sourceAudio: File,
        targetDirectory: File,
        storedAudioPath: String?,
        commitPath: suspend (expectedOldPath: String?, newPath: String) -> Boolean
    ): SyncOutcome {
        val desiredStem = recordingBaseName(title, startedAtMs)
        val extension = sourceAudio.name.substringAfterLast('.', "").lowercase(Locale.US)
        val sourceSrt = File(sourceAudio.parentFile, sourceAudio.nameWithoutExtension + ".srt")

        val currentStem = sourceAudio.nameWithoutExtension
        val stableStem = currentStem == desiredStem ||
            currentStem.removePrefix("${desiredStem}_").let { it != currentStem && it.toIntOrNull()?.let { n -> n >= 2 } == true }
        val needsRename = !stableStem || sourceAudio.parentFile != targetDirectory

        // Free stem in the target directory for the audio and its sidecar;
        // collision suffixes _2, _3, ... never overwrite an existing file.
        // The source's own stem is not a conflict (we own it), so repeated
        // syncs of an already-suffixed file stay stable instead of drifting.
        var stem = desiredStem
        var attempt = 1
        if (needsRename) {
            while (stem != sourceAudio.nameWithoutExtension &&
                (targetDirectory.listFiles()?.map { it.name } ?: emptyList()).let { names ->
                    names.any { it == "$stem.$extension" || it == "$stem.srt" ||
                        it == "$stem.m4a" || it == "$stem.wav" }
                }
            ) {
                attempt++
                stem = "${desiredStem}_$attempt"
            }
        }

        val newName = if (extension.isEmpty()) stem else "$stem.$extension"
        val newAudio = if (needsRename) File(targetDirectory, newName) else sourceAudio
        val newSrt = if (needsRename) File(targetDirectory, "$stem.srt") else sourceSrt

        val created = mutableListOf<File>()
        var committed = false
        try {
            if (needsRename) {
                targetDirectory.mkdirs()
                copyAndSync(sourceAudio, newAudio)
                created.add(newAudio)
                check(newAudio.length() == sourceAudio.length()) { "Incomplete recording copy" }
                if (sourceSrt.exists()) {
                    copyAndSync(sourceSrt, newSrt)
                    created.add(newSrt)
                }
            }

            // Commit phase: the guarded DB update and the source cleanup form
            // one non-cancellable unit — after the commit succeeds the new
            // files can no longer be deleted by cancellation or a later error.
            val expectedOldPath = if (needsRename) storedAudioPath else null
            withContext(NonCancellable) {
                committed = commitPath(expectedOldPath, newAudio.absolutePath)
                if (committed && needsRename) {
                    if (!sourceAudio.delete()) Log.w(TAG, "Could not remove original ${sourceAudio.absolutePath}")
                    if (sourceSrt.exists() && !sourceSrt.delete()) {
                        Log.w(TAG, "Could not remove original ${sourceSrt.absolutePath}")
                    }
                }
                committed
            }.let { committed ->
                if (!committed) {
                    // Room row vanished or its path changed underneath us: keep
                    // the source pair, remove only what this pass created.
                    created.forEach { if (it != sourceAudio && it != sourceSrt) it.delete() }
                    return SyncOutcome(sourceAudio.absolutePath, null)
                }
            }
            return SyncOutcome(newAudio.absolutePath, null)
        } catch (e: CancellationException) {
            if (!committed) created.forEach { if (it != sourceAudio && it != sourceSrt) it.delete() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recording normalization failed for $sessionId", e)
            if (!committed) created.forEach { if (it != sourceAudio && it != sourceSrt) it.delete() }
            return SyncOutcome(sourceAudio.absolutePath, "$EXPORT_ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Reserves [dest] exclusively (atomic create), then copies + fsyncs so
     *  the file is durable before commit. */
    private fun copyAndSync(source: File, dest: File) {
        check(dest.createNewFile()) { "Destination ${dest.name} already exists" }
        try {
            dest.outputStream().use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                (out as? FileOutputStream)?.fd?.sync()
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    /**
     * Writes the SRT through a unique synced temp file + same-directory
     * atomic rename. The old target is never deleted first and there is no
     * truncating fallback: a failed rename keeps the old sidecar intact and
     * surfaces an error instead.
     */
    internal fun writeSrtAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("Could not replace ${target.name} atomically")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ── staged export entry points ───────────────────────────────────────

    /**
     * Full retry: normalizes private artifacts, runs the audio stage, then —
     * only for completed sessions — the SRT stage. Holds [artifactMutex]
     * once so the whole pass is atomic against stage entry points.
     */
    suspend fun syncRecordingArtifacts(context: Context, store: com.phequals7.muesli.data.SharedStore, sessionId: String): Unit =
        artifactMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!runAudioStage(context, store, sessionId)) return@withContext
                val session = store.getRecordingSessionById(sessionId) ?: return@withContext
                if (session.phase == "completed") {
                    runSrtStage(context, store, sessionId)
                }
            }
        }

    /**
     * Stage 1: publish the retained audio (private normalization + public
     * export into the granted folder) while transcription is still running.
     * Accepts `transcribing` and `completed` sessions. No retained audio is
     * a successful no-op; a deleted row or stale guard returns false without
     * creating anything. Never reads segments or creates an SRT.
     */
    suspend fun exportAudioArtifactImmediately(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        sessionId: String
    ): Boolean = artifactMutex.withLock {
        withContext(Dispatchers.IO) { runAudioStage(context, store, sessionId) }
    }

    /**
     * Stage 2: (re)write the private SRT sidecar and publish it beside the
     * committed public audio. Runs only for `completed` sessions after the
     * final segments/notes are persisted; never copies, renames, or exports
     * audio.
     */
    suspend fun exportSrtArtifactOnly(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        sessionId: String
    ): Boolean = artifactMutex.withLock {
        withContext(Dispatchers.IO) { runSrtStage(context, store, sessionId) }
    }

    private fun audioStageError(detail: String): String = "$AUDIO_STAGE_ERROR_PREFIX $detail"
    private fun srtStageError(detail: String): String = "$SRT_STAGE_ERROR_PREFIX $detail"

    /** Audio stage; caller holds [artifactMutex] and is on Dispatchers.IO. */
    private suspend fun runAudioStage(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        sessionId: String
    ): Boolean {
        val session = store.getRecordingSessionById(sessionId)
            ?: return false
        if (session.phase != "transcribing" && session.phase != "completed") return false
        if (session.audioFileName.isNullOrBlank()) return true // no retention: successful no-op

        val sourceAudio = resolveRecordingAudio(context.filesDir, session.audioFileName)
            ?.takeIf { isUsableAudioFile(it) }
        if (sourceAudio == null) {
            store.updateRecordingExportError(sessionId, audioStageError("Recording audio is missing."))
            return false
        }
        if (sourceAudio.name.endsWith(".m4a", ignoreCase = true) && !hasReadableAacTrack(sourceAudio)) {
            store.updateRecordingExportError(sessionId, audioStageError("The recording's M4A audio could not be verified."))
            return false
        }

        // 1. Private normalization: title-driven basename in the audio's own
        //    directory (files/meetings for new recordings; legacy public files
        //    stay in place, readable, never migrated).
        val targetDir = sourceAudio.parentFile ?: internalRecordingsDirectory(context.filesDir)
        val outcome = syncSessionArtifacts(
            sessionId = sessionId,
            title = session.title,
            startedAtMs = session.startedAt ?: session.createdAt,
            sourceAudio = sourceAudio,
            targetDirectory = targetDir,
            storedAudioPath = session.audioFileName,
            commitPath = { expectedOld, newPath ->
                if (expectedOld != null) {
                    store.updateRecordingAudioPath(sessionId, expectedOld, newPath) == 1
                } else {
                    true
                }
            }
        )
        if (outcome.exportError != null) {
            store.updateRecordingExportError(sessionId, withAudioStagePrefix(outcome.exportError))
            return false
        }

        // 2. Public export through the granted folder.
        val refreshed = store.getRecordingSessionById(sessionId) ?: return false
        val audio = resolveRecordingAudio(context.filesDir, refreshed.audioFileName)
            ?.takeIf { isUsableAudioFile(it) }
        if (audio == null) {
            store.updateRecordingExportError(sessionId, audioStageError("Recording audio is missing."))
            return false
        }
        val treeUriStr = store.recordingsTreeUri
        if (treeUriStr.isBlank()) {
            store.updateRecordingExportError(sessionId, CHOOSE_FOLDER_AUDIO_ERROR)
            return false
        }
        return try {
            val gateway = ContentResolverTreeGateway(context.applicationContext, Uri.parse(treeUriStr))
            val export = exportAudioToTree(
                gateway = gateway,
                sessionId = sessionId,
                desiredStem = recordingBaseName(refreshed.title, refreshed.startedAt ?: refreshed.createdAt),
                sourceAudio = audio,
                ownedAudioUri = refreshed.exportedAudioUri,
                ownedSrtUri = refreshed.exportedSrtUri,
                commitUri = { audioUri ->
                    store.updateExportedAudioUri(sessionId, refreshed.audioFileName, audioUri) == 1
                }
            )
            val error = export.exportError ?: export.cleanupError
            if (error != null) {
                store.updateRecordingExportError(sessionId, withAudioStagePrefix(error))
                false
            } else if (export.uri == null) {
                // Stale guard or a row deleted mid-flight: nothing committed,
                // and nothing this stage owns was left behind.
                false
            } else {
                requestMediaScan(context, export.uri, publicAudioMime(audio))
                store.clearAudioExportError(sessionId)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio export failed for $sessionId", e)
            store.updateRecordingExportError(
                sessionId,
                audioStageError(e.message ?: "Unable to access the recordings folder.")
            )
            false
        }
    }

    /** SRT stage; caller holds [artifactMutex] and is on Dispatchers.IO. */
    private suspend fun runSrtStage(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        sessionId: String
    ): Boolean {
        val session = store.getRecordingSessionById(sessionId) ?: return false
        if (session.phase != "completed") return false
        if (session.audioFileName.isNullOrBlank()) return true // nothing retained: no-op

        // Unverified/missing audio keeps a prior Audio: stage error untouched
        // (full retry owns fixing it) and only reports when no audio error exists.
        suspend fun retainAudioErrorAndFail(detail: String): Boolean {
            val current = session.errorMessage
            if (current == null || !current.startsWith(AUDIO_STAGE_ERROR_PREFIX)) {
                store.updateRecordingExportError(sessionId, srtStageError(detail))
            }
            return false
        }

        val segments = store.getTranscriptSegments(sessionId)
        val transcriptText = store.getTranscriptForSession(sessionId)?.text.orEmpty()
        // Legacy flat transcript without timed cues: preserve audio and report
        // that an accurate SRT cannot be created; never fabricate timestamps.
        if (segments.isEmpty() && transcriptText.isNotBlank()) {
            store.updateRecordingExportError(
                sessionId,
                srtStageError("Accurate SRT subtitles cannot be created because this legacy recording has no timed cues.")
            )
            return false
        }

        val sourceAudio = resolveRecordingAudio(context.filesDir, session.audioFileName)
            ?.takeIf { isUsableAudioFile(it) }
        if (sourceAudio == null) {
            return retainAudioErrorAndFail("Recording audio is missing. Retry the full export.")
        }

        val durationMs = readAudioDurationMs(sourceAudio)
        val srtContent = MeetingTranscriptSrt.serialize(
            segments.map { it.toDomainModel() },
            manualNotes = session.manualNotes,
            summary = session.summaryText.takeUnless { it.startsWith("## Summary failed\n") }.orEmpty(),
            audioDurationMs = maxOf(durationMs, (session.endedAt ?: 0L) - (session.startedAt ?: 0L)),
        )

        // 1. Private sidecar is always refreshed, even without a public folder.
        try {
            writeSrtAtomically(
                File(sourceAudio.parentFile, sourceAudio.nameWithoutExtension + ".srt"),
                srtContent
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Private SRT write failed for $sessionId", e)
            store.updateRecordingExportError(sessionId, srtStageError(e.message ?: e.javaClass.simpleName))
            return false
        }

        // 2. Public SRT beside the committed audio document.
        val treeUriStr = store.recordingsTreeUri
        if (treeUriStr.isBlank()) {
            store.updateRecordingExportError(sessionId, CHOOSE_FOLDER_SRT_ERROR)
            return false
        }
        val ownedAudioUri = session.exportedAudioUri?.takeIf { it.isNotBlank() }
        if (ownedAudioUri == null) {
            return retainAudioErrorAndFail("The audio file must be exported first. Retry the full export.")
        }
        return try {
            val gateway = ContentResolverTreeGateway(context.applicationContext, Uri.parse(treeUriStr))
            if (!gateway.isInTree(ownedAudioUri)) {
                return retainAudioErrorAndFail("The exported audio file is no longer in the recordings folder. Retry the full export.")
            }
            val audioName = gateway.queryDisplayName(ownedAudioUri)
                ?: return retainAudioErrorAndFail("The exported audio file is no longer readable. Retry the full export.")
            val desiredStem = recordingBaseName(session.title, session.startedAt ?: session.createdAt)
            val ownedStem = audioName.substringBeforeLast('.')
            val suffix = ownedStem.removePrefix("${desiredStem}_")
            val stemMatches = ownedStem == desiredStem ||
                (suffix != ownedStem && suffix.toIntOrNull()?.let { it >= 2 } == true)
            if (!stemMatches) {
                return retainAudioErrorAndFail("The exported audio name no longer matches this meeting. Retry the full export.")
            }

            val export = exportSrtToTree(
                gateway = gateway,
                sessionId = sessionId,
                audioUri = ownedAudioUri,
                srtContent = srtContent,
                ownedSrtUri = session.exportedSrtUri,
                commitUri = { srtUri ->
                    store.updateExportedSrtUri(sessionId, session.audioFileName, ownedAudioUri, srtUri) == 1
                }
            )
            val error = export.exportError ?: export.cleanupError
            if (error != null) {
                store.updateRecordingExportError(sessionId, withSrtStagePrefix(error))
                false
            } else if (export.uri == null) {
                false
            } else {
                requestMediaScan(context, export.uri, MIME_SRT)
                // Both stages verified: a successful subtitle commit may clear
                // any prior export error.
                store.updateRecordingExportError(sessionId, null)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SRT export failed for $sessionId", e)
            store.updateRecordingExportError(
                sessionId,
                srtStageError(e.message ?: "Unable to access the recordings folder.")
            )
            false
        }
    }

    private fun withAudioStagePrefix(error: String): String =
        "$AUDIO_STAGE_ERROR_PREFIX ${error.removePrefix(EXPORT_ERROR_PREFIX).trim()}"

    private fun withSrtStagePrefix(error: String): String =
        "$SRT_STAGE_ERROR_PREFIX ${error.removePrefix(EXPORT_ERROR_PREFIX).trim()}"

    /** Chooses a recordings-folder guidance message for each stage. */
    const val CHOOSE_FOLDER_AUDIO_ERROR = "$AUDIO_STAGE_ERROR_PREFIX Choose a recordings folder to save the audio."
    const val CHOOSE_FOLDER_SRT_ERROR = "$SRT_STAGE_ERROR_PREFIX Choose a recordings folder to save audio and SRT."

    private fun publicAudioMime(audio: File): String =
        if (audio.name.endsWith(".wav", ignoreCase = true)) MIME_WAV else MIME_M4A

    private fun readAudioDurationMs(audio: File): Long = runCatching {
        android.media.MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(audio.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }
    }.getOrDefault(0L)

    /**
     * Requests a MediaStore scan using the document's actual public path,
     * for local ExternalStorageProvider documents only. SAF creation and
     * verified bytes already establish export success, so a null scanner
     * callback can never fail or roll back the export.
     */
    private fun requestMediaScan(context: Context, documentUri: String, mime: String) {
        val path = localDocumentPath(documentUri) ?: return
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, _ -> }
    }

    /** Actual public filesystem path for `primary:` ExternalStorageProvider
     *  documents, with canonical containment checks; null otherwise. */
    internal fun localDocumentPath(documentUri: String): String? = try {
        val uri = Uri.parse(documentUri)
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = DocumentsContract.getDocumentId(uri)
        val separator = docId.indexOf(':')
        if (separator <= 0) return null
        if (docId.substring(0, separator) != "primary") return null
        val relative = docId.substring(separator + 1)
        if (relative.isBlank() || relative.contains("..")) return null
        val root = Environment.getExternalStorageDirectory()
        val canonical = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        val contained = canonical == canonicalRoot ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
        if (contained) canonical.path else null
    } catch (t: Throwable) {
        null
    }

    // ── SAF tree export ──────────────────────────────────────────────────

    /**
     * Pure seam over DocumentsContract/ContentResolver so export logic is
     * JVM-testable; document references are plain URI strings.
     */
    interface RecordingTreeGateway {
        /** True when [documentUri] points inside this gateway's tree. */
        fun isInTree(documentUri: String): Boolean

        /** Display names of the tree root's direct children. */
        fun childDocumentNames(): Set<String>

        /** Creates a new document; must fail instead of overwriting an existing name. */
        fun createDocument(mime: String, displayName: String): String

        fun queryDisplayName(documentUri: String): String?

        /** Replaces the document's content (owned documents only). */
        fun openOutput(documentUri: String): OutputStream

        fun documentLength(documentUri: String): Long

        fun deleteDocument(documentUri: String): Boolean
    }

    internal data class ArtifactExportOutcome(
        val uri: String?,
        val exportError: String?,
        val cleanupError: String?,
    )

    /**
     * Stage 1 pure SAF export: creates (or reuses the owned) audio document
     * named `<desiredStem>.m4a|.wav` in the tree — a stem whose `.m4a`,
     * `.wav`, or `.srt` name is free — verifies the byte length, and commits
     * only the audio URI through the guarded [commitUri]. A matching owned
     * document is reused without rewriting (byte-length comparison) and does
     * not depend on owning an SRT. Any superseded audio document is deleted
     * only after the replacement URI is committed; the old SRT is never
     * touched here — Stage 2 replaces it. Attempt-owned documents are
     * removed on any pre-commit failure; the committed document is never
     * deleted afterwards.
     */
    internal suspend fun exportAudioToTree(
        gateway: RecordingTreeGateway,
        sessionId: String,
        desiredStem: String,
        sourceAudio: File,
        ownedAudioUri: String?,
        ownedSrtUri: String?,
        commitUri: suspend (audioUri: String) -> Boolean,
    ): ArtifactExportOutcome {
        val ext = if (sourceAudio.name.endsWith(".wav", ignoreCase = true)) "wav" else "m4a"
        val mime = if (ext == "wav") MIME_WAV else MIME_M4A
        val ownedAudio = ownedAudioUri?.takeIf { it.isNotBlank() }
        val ownedAudioName = ownedAudio?.let { gateway.queryDisplayName(it) }.orEmpty()
        val ownedStem = ownedAudioName.removeSuffix(".$ext")
        val suffix = ownedStem.removePrefix("${desiredStem}_")
        val matchingStem = ownedStem == desiredStem ||
            (suffix != ownedStem && suffix.toIntOrNull()?.let { it >= 2 } == true)
        val reuseOwned = ownedAudio != null && gateway.isInTree(ownedAudio) &&
            ownedAudioName == "$ownedStem.$ext" && matchingStem

        val created = mutableListOf<String>()
        var committed = false
        try {
            val audioUri: String
            if (reuseOwned) {
                audioUri = ownedAudio!!
            } else {
                val names = gateway.childDocumentNames()
                var stem = desiredStem
                var attempt = 1
                while ("$stem.m4a" in names || "$stem.wav" in names || "$stem.srt" in names) {
                    attempt++
                    stem = "${desiredStem}_$attempt"
                }
                audioUri = gateway.createDocument(mime, "$stem.$ext")
                    .also { created.add(it) }
                if (gateway.queryDisplayName(audioUri) != "$stem.$ext") {
                    throw java.io.IOException("Provider created an unexpected audio name")
                }
            }

            if (!reuseOwned || gateway.documentLength(audioUri) != sourceAudio.length()) {
                writeDocument(gateway, audioUri) { out ->
                    sourceAudio.inputStream().use { input -> copyActively(input, out) }
                }
                val audioLength = gateway.documentLength(audioUri)
                if (audioLength != sourceAudio.length()) {
                    throw java.io.IOException("Exported audio length mismatch ($audioLength != ${sourceAudio.length()})")
                }
            }

            // Commit the audio pointer in one guarded, non-cancellable write.
            val committedNow = withContext(NonCancellable) {
                commitUri(audioUri).also { committed = it }
            }
            if (!committedNow) {
                // Session row vanished or changed: remove only what this
                // attempt created; a reused owned document keeps its content.
                created.forEach { gateway.deleteDocument(it) }
                return ArtifactExportOutcome(null, null, null)
            }

            var cleanupError: String? = null
            if (!reuseOwned) {
                val oldAudio = ownedAudio?.takeIf { it != audioUri }
                // Stage 2 replaces (and deletes) the superseded SRT after its
                // own commit; audio cleanup never removes it.
                if (oldAudio != null && !gateway.deleteDocument(oldAudio)) {
                    cleanupError = "$EXPORT_ERROR_PREFIX Previous export files could not be cleaned up."
                }
            }
            return ArtifactExportOutcome(audioUri, null, cleanupError)
        } catch (e: CancellationException) {
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio tree export failed for $sessionId", e)
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            return ArtifactExportOutcome(null, "$EXPORT_ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    /**
     * Stage 2 pure SAF export: writes the subtitle beside the committed
     * audio document (`<audio display name>.srt`), reusing a matching owned
     * SRT; a name taken by an unrelated file is never overwritten. Commits
     * the SRT URI through the guarded [commitUri] before deleting any
     * superseded owned subtitle.
     */
    internal suspend fun exportSrtToTree(
        gateway: RecordingTreeGateway,
        sessionId: String,
        audioUri: String,
        srtContent: String,
        ownedSrtUri: String?,
        commitUri: suspend (srtUri: String) -> Boolean,
    ): ArtifactExportOutcome {
        val audioName = gateway.queryDisplayName(audioUri)
            ?: throw java.io.IOException("The exported audio file is no longer readable")
        val desiredSrtName = audioName.substringBeforeLast('.') + ".srt"
        val ownedSrt = ownedSrtUri?.takeIf { it.isNotBlank() }
        val ownedSrtName = ownedSrt?.let { gateway.queryDisplayName(it) }
        val reuse = ownedSrt != null && gateway.isInTree(ownedSrt) && ownedSrtName == desiredSrtName

        val created = mutableListOf<String>()
        var committed = false
        try {
            val srtUri: String
            if (reuse) {
                srtUri = ownedSrt!!
            } else {
                val names = gateway.childDocumentNames()
                if (desiredSrtName in names) {
                    // An unrelated file took the exact sidecar name between
                    // stages: refuse the overwrite, audio stays unchanged.
                    throw java.io.IOException("\"$desiredSrtName\" already exists in the recordings folder")
                }
                srtUri = gateway.createDocument(MIME_SRT, desiredSrtName)
                    .also { created.add(it) }
                if (gateway.queryDisplayName(srtUri) != desiredSrtName) {
                    throw java.io.IOException("Provider created an unexpected SRT name")
                }
            }

            writeDocument(gateway, srtUri) { out ->
                out.write(srtContent.toByteArray(Charsets.UTF_8))
            }
            val srtLength = gateway.documentLength(srtUri)
            val expectedSrt = srtContent.toByteArray(Charsets.UTF_8).size.toLong()
            if (srtLength != expectedSrt) {
                throw java.io.IOException("Exported SRT length mismatch ($srtLength != $expectedSrt)")
            }

            val committedNow = withContext(NonCancellable) {
                commitUri(srtUri).also { committed = it }
            }
            if (!committedNow) {
                created.forEach { gateway.deleteDocument(it) }
                return ArtifactExportOutcome(null, null, null)
            }

            var cleanupError: String? = null
            val oldSrt = ownedSrt?.takeIf { it != srtUri }
            if (oldSrt != null && !gateway.deleteDocument(oldSrt)) {
                cleanupError = "$EXPORT_ERROR_PREFIX Previous export files could not be cleaned up."
            }
            return ArtifactExportOutcome(srtUri, null, cleanupError)
        } catch (e: CancellationException) {
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SRT tree export failed for $sessionId", e)
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            return ArtifactExportOutcome(null, "$EXPORT_ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    /** Reopens the document output stream so the write is verifiable. */
    private suspend fun writeDocument(
        gateway: RecordingTreeGateway,
        uri: String,
        writer: suspend (OutputStream) -> Unit
    ) {
        gateway.openOutput(uri).use { out ->
            writer(out)
            out.flush()
        }
    }

    /** Copies in chunks, observing job cancellation between chunks. */
    private suspend fun copyActively(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buf)
            if (read < 0) break
            out.write(buf, 0, read)
        }
    }
}

/** DocumentsContract/ContentResolver-backed [MeetingStorageManager.RecordingTreeGateway]. */
class ContentResolverTreeGateway(
    private val context: Context,
    private val treeUri: Uri,
) : MeetingStorageManager.RecordingTreeGateway {

    private val contentResolver = context.contentResolver
    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

    private fun query(uri: Uri, column: String): String? = try {
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun isInTree(documentUri: String): Boolean = try {
        val docId = DocumentsContract.getDocumentId(Uri.parse(documentUri))
        docId == treeDocId || docId.startsWith("$treeDocId/")
    } catch (_: Exception) {
        false
    }

    override fun childDocumentNames(): Set<String> {
        val names = mutableSetOf<String>()
        val cursor = contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        ) ?: throw java.io.IOException("Could not enumerate the recordings folder")
        cursor.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
        }
        return names
    }

    override fun createDocument(mime: String, displayName: String): String =
        DocumentsContract.createDocument(contentResolver, rootDocUri, mime, displayName)?.toString()
            ?: throw java.io.IOException("The recordings folder refused to create $displayName")

    override fun queryDisplayName(documentUri: String): String? =
        query(Uri.parse(documentUri), DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    override fun openOutput(documentUri: String): OutputStream =
        contentResolver.openOutputStream(Uri.parse(documentUri), "wt")
            ?: throw java.io.IOException("Could not open the exported file for writing")

    override fun documentLength(documentUri: String): Long {
        val input = contentResolver.openInputStream(Uri.parse(documentUri))
            ?: throw java.io.IOException("Could not reopen the exported file")
        return input.use {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
    }

    override fun deleteDocument(documentUri: String): Boolean = try {
        DocumentsContract.deleteDocument(contentResolver, Uri.parse(documentUri))
    } catch (e: Exception) {
        Log.w("MeetingStorageManager", "Could not delete exported document: ${e.message}")
        false
    }
}
