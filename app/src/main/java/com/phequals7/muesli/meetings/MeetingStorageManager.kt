package com.phequals7.muesli.meetings

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.phequals7.muesli.data.entity.RecordingSession
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
            // All four extensions must be free so SRT/MP4 sidecars can share the stem.
            if (!audio.exists() && !File(directory, "$stem.wav").exists() &&
                !File(directory, "$stem.srt").exists() && !File(directory, "$stem.mp4").exists() &&
                audio.createNewFile()
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
                        it == "$stem.m4a" || it == "$stem.wav" || it == "$stem.mp4" }
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
                ownedMp4Uri = refreshed.exportedMp4Uri,
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
        val domainSegments = segments.map { it.toDomainModel() }
        val srtContent = MeetingTranscriptSrt.serialize(
            domainSegments,
            manualNotes = session.manualNotes,
            summary = usableSummaryText(session),
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
                // Single automatic-enqueue policy: recorder handoff, summary
                // refresh, transcript edits and manual retries all land here.
                // Enqueue only — video work must never block Stage 1/2.
                if (store.exportEmbeddedMp4Enabled && sourceAudio.name.endsWith(".m4a", ignoreCase = true)) {
                    val latestSession = store.getRecordingSessionById(sessionId) ?: session
                    val currentFingerprint = Mp4SubtitleMuxer.mp4TranscriptFingerprint(
                        sessionId = sessionId,
                        audioLength = sourceAudio.length(),
                        audioLastModifiedMs = sourceAudio.lastModified(),
                        segments = domainSegments,
                        summary = usableSummaryText(latestSession),
                    )
                    val alreadyUpToDate = canSkipAutomaticMp4Export(
                        gateway = gateway,
                        force = false,
                        audioUri = ownedAudioUri,
                        ownedMp4Uri = latestSession.exportedMp4Uri,
                        ownedMp4Fingerprint = latestSession.mp4TranscriptFingerprint,
                        currentFingerprint = currentFingerprint,
                        mp4Subfolder = if (store.mp4SubfolderEnabled) MP4_SUBFOLDER else null,
                    )
                    if (alreadyUpToDate) {
                        if (latestSession.mp4ExportError != null) {
                            store.updateMp4ExportError(sessionId, null)
                        }
                    } else if (latestSession.exportedMp4Uri.isNullOrBlank()) {
                        // Automatic exports only ever create the FIRST MP4;
                        // refreshing an existing one is manual (Regenerate MP4).
                        MeetingMp4ExportService.requestExport(context.applicationContext, sessionId, force = false)
                    }
                }
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

        /** Cheap provider-reported size in bytes; null when unknown or missing. */
        fun documentSize(documentUri: String): Long?

        /** Renames a document and returns its URI afterwards (same URI when
         *  the provider renamed in place). Throws when the provider refuses. */
        fun renameDocument(documentUri: String, newDisplayName: String): String

        /** Gateway rooted at the direct child directory [name] — created
         *  first when [create]; null when absent and [create] is false.
         *  Only documents directly inside that directory are in the child
         *  gateway's tree. */
        fun childDirectoryGateway(name: String, create: Boolean): RecordingTreeGateway?
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
        ownedMp4Uri: String? = null,
        commitUri: suspend (audioUri: String) -> Boolean,
    ): ArtifactExportOutcome {
        val ext = if (sourceAudio.name.endsWith(".wav", ignoreCase = true)) "wav" else "m4a"
        val mime = if (ext == "wav") MIME_WAV else MIME_M4A
        val ownedAudio = ownedAudioUri?.takeIf { it.isNotBlank() }
        val ownedAudioInTree = ownedAudio != null && gateway.isInTree(ownedAudio)
        val ownedAudioName = if (ownedAudioInTree) gateway.queryDisplayName(ownedAudio!!).orEmpty() else ""
        val ownedStem = ownedAudioName.removeSuffix(".$ext")
        val suffix = ownedStem.removePrefix("${desiredStem}_")
        val matchingStem = ownedStem == desiredStem ||
            (suffix != ownedStem && suffix.toIntOrNull()?.let { it >= 2 } == true)
        // Reusing the owned audio name is safe only when every existing
        // sidecar of that stem is also owned in this tree with the exact
        // name; an unowned blocker forces a fresh common stem for all three
        // artifacts instead of suffixing just one of them.
        var reuseOwned = ownedAudioInTree &&
            ownedAudioName == "$ownedStem.$ext" && matchingStem
        if (reuseOwned) {
            val names = gateway.childDocumentNames()
            val otherAudioExt = if (ext == "wav") "m4a" else "wav"
            fun sidecarIsFreeOrOwned(name: String, ownedUri: String?): Boolean =
                name !in names || (ownedUri?.takeIf { it.isNotBlank() }
                    ?.let { uri -> gateway.isInTree(uri) && gateway.queryDisplayName(uri) == name } == true)
            reuseOwned = "$ownedStem.$otherAudioExt" !in names &&
                sidecarIsFreeOrOwned("$ownedStem.srt", ownedSrtUri) &&
                sidecarIsFreeOrOwned("$ownedStem.mp4", ownedMp4Uri)
        }

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
                while ("$stem.m4a" in names || "$stem.wav" in names ||
                    "$stem.srt" in names || "$stem.mp4" in names
                ) {
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
                if (oldAudio != null && gateway.isInTree(oldAudio) && !gateway.deleteDocument(oldAudio)) {
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
        val ownedSrtInTree = ownedSrt != null && gateway.isInTree(ownedSrt)
        val ownedSrtName = if (ownedSrtInTree) gateway.queryDisplayName(ownedSrt!!) else null
        val reuse = ownedSrtInTree && ownedSrtName == desiredSrtName
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
            val oldSrt = ownedSrt?.takeIf { it != srtUri && ownedSrtInTree }
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

    // ── MP4 embedded-subtitle export (v2.3) ──────────────────────────────

    private const val MIME_MP4 = "video/mp4"
    internal const val MP4_EXPORT_CACHE_DIR_PREFIX = "mp4_export_"
    /** Recordings-folder subfolder for MP4 exports when the user opts in. */
    internal const val MP4_SUBFOLDER = "mp4"
    /** Prefix of the informational "MP4 saved as <fallback name>" export note. */
    internal const val MP4_SAVED_NOTE_PREFIX = "MP4 saved as"

    /** Everything a muxing run captured under [artifactMutex]; publication
     *  re-validates all of it before anything becomes public. */
    internal class Mp4ExportSnapshot(
        val sessionId: String,
        val sessionTitle: String,
        val audioFile: File,
        /** Read-only channel on the private M4A; the caller closes it on
         *  every path (it survives a concurrent title rename/unlink). */
        val audioChannel: java.nio.channels.FileChannel,
        val expectedAudioPath: String,
        val ownedAudioUri: String,
        val ownedSrtUri: String?,
        val ownedMp4Uri: String?,
        /** Fingerprint the owned MP4 was built from; restored on rollback. */
        val ownedMp4Fingerprint: String?,
        val treeUri: String,
        val segments: List<DiarizedTranscriptSegment>,
        val segmentProjection: List<com.phequals7.muesli.data.dao.Mp4SegmentProjection>,
        val audioLength: Long,
        val audioLastModifiedMs: Long,
        val fingerprint: String,
        val summaryText: String,
        val desiredStem: String? = null,
    )

    /** Terminal outcome of publishing a generated MP4. */
    internal data class Mp4PublishOutcome(
        /** Committed public document URI on success; null otherwise. */
        val publishedUri: String?,
        /** Snapshot went stale: the caller should coalesce a fresh request. */
        val stale: Boolean,
        /** User-presentable failure for the MP4-only error column. */
        val error: String?,
        /** Surviving backup document retained when rollback itself failed. */
        val recoveryUri: String?,
    )

    /**
     * Pure automatic-skip check before entering codec work: skips only when
     * not forced, the fingerprint matches, both audio and owned MP4 are in
     * [gateway]'s selected tree with matching stems, and provider metadata
     * reports a positive document size. Missing/unknown size rebuilds.
     * With [mp4Subfolder] set, the owned MP4 must live in that subfolder
     * gateway instead (a missing subfolder rebuilds).
     */
    internal fun canSkipAutomaticMp4Export(
        gateway: RecordingTreeGateway,
        force: Boolean,
        audioUri: String,
        ownedMp4Uri: String?,
        ownedMp4Fingerprint: String?,
        currentFingerprint: String,
        mp4Subfolder: String? = null,
    ): Boolean {
        if (force || ownedMp4Uri.isNullOrBlank() || ownedMp4Fingerprint.isNullOrBlank() ||
            ownedMp4Fingerprint != currentFingerprint
        ) return false
        val mp4Scope = if (mp4Subfolder == null) gateway
        else gateway.childDirectoryGateway(mp4Subfolder, create = false) ?: return false
        if (!gateway.isInTree(audioUri) || !mp4Scope.isInTree(ownedMp4Uri)) return false
        val audioName = gateway.queryDisplayName(audioUri) ?: return false
        val audioStem = audioName.substringBeforeLast('.', "")
        if (audioStem.isBlank() || audioStem == audioName) return false
        if (!isOwnedMp4Name(audioStem, mp4Scope.queryDisplayName(ownedMp4Uri) ?: return false)) return false
        return (mp4Scope.documentSize(ownedMp4Uri) ?: -1L) > 0L
    }

    /** Owned MP4 export names: `<stem>.mp4`, or the dated fallback
     *  `<stem>-regenerate-<yyyy-MM-dd_HH-mm-ss>.mp4` written when an
     *  unowned file already occupies the stem name. */
    internal fun isOwnedMp4Name(audioStem: String, name: String): Boolean =
        name == "$audioStem.mp4" ||
            (name.startsWith("$audioStem-regenerate-") && name.endsWith(".mp4"))

    /** Summary text rendered into artifacts; failed-summary notes are excluded. */
    internal fun usableSummaryText(session: RecordingSession): String =
        session.summaryText.takeUnless { it.startsWith("## Summary failed\n") }.orEmpty()

    /**
     * Opens the completed meeting's private M4A read-only under
     * [artifactMutex] and captures everything the muxer and the later
     * publication need. Returns null (touching nothing) when the session
     * cannot produce an MP4: not completed, no retained audio, legacy WAV
     * audio, no committed public audio document, or no chosen folder.
     */
    internal suspend fun prepareMp4ExportSnapshot(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        sessionId: String,
    ): Mp4ExportSnapshot? = artifactMutex.withLock {
        withContext(Dispatchers.IO) {
            val session = store.getRecordingSessionById(sessionId) ?: return@withContext null
            if (session.phase != "completed") return@withContext null
            val audioPath = session.audioFileName?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val audio = resolveRecordingAudio(context.filesDir, audioPath)
                ?.takeIf { it.name.endsWith(".m4a", ignoreCase = true) && isUsableAudioFile(it) }
                ?: return@withContext null
            if (!hasReadableAacTrack(audio)) return@withContext null
            val ownedAudioUri = session.exportedAudioUri?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val treeUri = store.recordingsTreeUri.takeIf { it.isNotBlank() }
                ?: return@withContext null

            val channel = java.nio.channels.FileChannel.open(
                audio.toPath(), java.nio.file.StandardOpenOption.READ
            )
            try {
                val entities = store.getTranscriptSegments(sessionId)
                val domainSegments = entities.map { it.toDomainModel() }
                Mp4ExportSnapshot(
                    sessionId = sessionId,
                    sessionTitle = session.title,
                    audioFile = audio,
                    audioChannel = channel,
                    expectedAudioPath = audioPath,
                    ownedAudioUri = ownedAudioUri,
                    ownedSrtUri = session.exportedSrtUri,
                    ownedMp4Uri = session.exportedMp4Uri,
                    ownedMp4Fingerprint = session.mp4TranscriptFingerprint,
                    treeUri = treeUri,
                    segments = domainSegments,
                    segmentProjection = entities.map {
                        com.phequals7.muesli.data.dao.Mp4SegmentProjection(
                            orderIndex = it.orderIndex, startMs = it.startMs, endMs = it.endMs,
                            speakerLabel = it.speakerLabel, text = it.text
                        )
                    },
                    audioLength = audio.length(),
                    audioLastModifiedMs = audio.lastModified(),
                    fingerprint = Mp4SubtitleMuxer.mp4TranscriptFingerprint(
                        sessionId, audio.length(), audio.lastModified(), domainSegments,
                        summary = usableSummaryText(session),
                    ),
                    summaryText = usableSummaryText(session),
                    desiredStem = recordingBaseName(session.title, session.startedAt ?: session.createdAt),
                )
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }
    }

    /**
     * Publishes a freshly generated (and internally validated) cache MP4
     * after re-validating the whole snapshot under [artifactMutex]: the row
     * must still exist and hold the captured audio/title/SRT/old-MP4 fields
     * and the same rendered segment projection, the tree must be unchanged,
     * and the private audio identity must not have moved.
     */
    internal suspend fun publishMp4Export(
        context: Context,
        store: com.phequals7.muesli.data.SharedStore,
        snapshot: Mp4ExportSnapshot,
        generatedMp4: File,
    ): Mp4PublishOutcome = artifactMutex.withLock {
        withContext(Dispatchers.IO) {
            if (store.getRecordingSessionById(snapshot.sessionId) == null) {
                return@withContext Mp4PublishOutcome(null, stale = false, error = null, recoveryUri = null)
            }
            val audio = resolveRecordingAudio(context.filesDir, snapshot.expectedAudioPath)
            if (audio == null || !audio.isFile || audio.length() != snapshot.audioLength ||
                audio.lastModified() != snapshot.audioLastModifiedMs
            ) {
                return@withContext Mp4PublishOutcome(null, stale = true, error = null, recoveryUri = null)
            }
            if (store.recordingsTreeUri != snapshot.treeUri ||
                !store.validateMp4Snapshot(
                    sessionId = snapshot.sessionId,
                    expectedAudioPath = snapshot.expectedAudioPath,
                    expectedAudioUri = snapshot.ownedAudioUri,
                    expectedSrtUri = snapshot.ownedSrtUri,
                    expectedTitle = snapshot.sessionTitle,
                    expectedOldMp4Uri = snapshot.ownedMp4Uri,
                    expectedSegments = snapshot.segmentProjection,
                )
            ) {
                return@withContext Mp4PublishOutcome(null, stale = true, error = null, recoveryUri = null)
            }

            val gateway = ContentResolverTreeGateway(context.applicationContext, Uri.parse(snapshot.treeUri))
            val mp4Gateway = runCatching {
                if (store.mp4SubfolderEnabled) {
                    gateway.childDirectoryGateway(MP4_SUBFOLDER, create = true)
                        ?: error("The recordings folder refused to create the \"$MP4_SUBFOLDER\" subfolder")
                } else gateway
            }.getOrElse { e -> return@withContext failure(e.message ?: e.javaClass.simpleName) }
            val outcome = exportMp4ToTree(
                gateway = gateway,
                mp4Gateway = mp4Gateway,
                sessionId = snapshot.sessionId,
                audioUri = snapshot.ownedAudioUri,
                ownedMp4Uri = snapshot.ownedMp4Uri,
                generatedMp4 = generatedMp4,
                commitUri = { mp4Uri ->
                    store.commitMp4Export(
                        sessionId = snapshot.sessionId,
                        expectedAudioPath = snapshot.expectedAudioPath,
                        expectedAudioUri = snapshot.ownedAudioUri,
                        expectedSrtUri = snapshot.ownedSrtUri,
                        expectedTitle = snapshot.sessionTitle,
                        expectedOldMp4Uri = snapshot.ownedMp4Uri,
                        expectedSegments = snapshot.segmentProjection,
                        mp4Uri = mp4Uri,
                        fingerprint = snapshot.fingerprint
                    )
                },
                ownedSrtUri = snapshot.ownedSrtUri,
                verifySrtStem = true,
                desiredStem = snapshot.desiredStem,
            )
            withContext(NonCancellable) {
                when {
                    outcome.publishedUri != null && outcome.error == null ->
                        store.updateMp4ExportError(snapshot.sessionId, null)
                    outcome.publishedUri != null ->
                        store.updateMp4ExportError(snapshot.sessionId, outcome.error)
                    !outcome.stale && (outcome.error != null || outcome.recoveryUri != null) -> {
                        val recovery = outcome.recoveryUri?.let { " (backup kept at: $it)" } ?: ""
                        store.updateMp4ExportError(
                            snapshot.sessionId,
                            (outcome.error ?: "MP4 export failed.") + recovery
                        )
                    }
                }
            }
            if (outcome.publishedUri != null) {
                requestMediaScan(context, outcome.publishedUri, MIME_MP4)
            }
            outcome
        }
    }

    /**
     * Pure SAF publication of a verified cache MP4 beside the committed
     * audio document. The first export creates the exact stem name; every
     * export of a meeting that already owns an MP4 (and any export whose
     * stem name is taken by an unowned file) instead writes a new dated
     * `<stem>-regenerate-<timestamp>.mp4` — existing files are never
     * overwritten or deleted. A failed or rejected export removes only the
     * new document.
     */
    internal suspend fun exportMp4ToTree(
        gateway: RecordingTreeGateway,
        sessionId: String,
        audioUri: String,
        ownedMp4Uri: String?,
        generatedMp4: File,
        commitUri: suspend (mp4Uri: String) -> Boolean,
        ownedSrtUri: String? = null,
        verifySrtStem: Boolean = ownedSrtUri != null,
        desiredStem: String? = null,
        /** MP4 documents live in this gateway (the tree root or its "mp4" subfolder); audio/SRT use [gateway]. */
        mp4Gateway: RecordingTreeGateway = gateway,
    ): Mp4PublishOutcome {
        if (!generatedMp4.isFile || generatedMp4.length() <= 0L) {
            return failure("Generated MP4 file is missing or empty.")
        }
        val created = mutableListOf<String>()
        var committed = false
        try {
            if (!gateway.isInTree(audioUri)) {
                return failure("The exported audio file is no longer in the recordings folder. Retry the full export.")
            }
            val audioName = gateway.queryDisplayName(audioUri)
                ?: return failure("The exported audio file is no longer readable. Retry the full export.")
            val audioStem = audioName.substringBeforeLast('.', "")
            if (audioStem.isBlank() || audioStem == audioName) {
                return failure("The exported audio file has an invalid name. Retry the full export.")
            }
            if (desiredStem != null) {
                val suffix = audioStem.removePrefix("${desiredStem}_")
                val stemMatches = audioStem == desiredStem ||
                    (suffix != audioStem && suffix.toIntOrNull()?.let { it >= 2 } == true)
                if (!stemMatches) {
                    return failure("The exported audio name no longer matches this meeting. Retry the full export.")
                }
            }
            if (verifySrtStem) {
                val srtUri = ownedSrtUri?.takeIf { it.isNotBlank() }
                    ?: return failure("The SRT subtitle file must be exported first. Retry the full export.")
                if (!gateway.isInTree(srtUri)) {
                    return failure("The exported SRT file is no longer in the recordings folder. Retry the full export.")
                }
                val srtName = gateway.queryDisplayName(srtUri)
                    ?: return failure("The exported SRT file is no longer readable. Retry the full export.")
                if (srtName != "$audioStem.srt") {
                    return failure("The exported SRT name no longer matches the audio file. Retry the full export.")
                }
            }

            val desiredName = "$audioStem.mp4"
            val ownedInTree = ownedMp4Uri?.takeIf { it.isNotBlank() }
                ?.let { mp4Gateway.isInTree(it) } == true
            val names = mp4Gateway.childDocumentNames().toMutableSet()
            var targetName = desiredName
            if (ownedInTree || desiredName in names) {
                // Versioned export: regenerating never overwrites — each run
                // writes a new dated file; so does an unowned squatter on
                // the stem name.
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                var attempt = 1
                do {
                    targetName = "$audioStem-regenerate-$stamp" +
                        (if (attempt > 1) "-$attempt" else "") + ".mp4"
                    attempt++
                } while (targetName in names)
            }
            val uri = mp4Gateway.createDocument(MIME_MP4, targetName)
                .also { created.add(it) }
            if (!mp4Gateway.isInTree(uri) || mp4Gateway.queryDisplayName(uri) != targetName) {
                throw java.io.IOException("Provider created an unexpected MP4 name")
            }
            writeDocument(mp4Gateway, uri) { out ->
                generatedMp4.inputStream().use { input -> copyActively(input, out) }
            }
            verifyDocumentLength(mp4Gateway, uri, generatedMp4.length())
            val note = if (targetName != desiredName) {
                "$MP4_SAVED_NOTE_PREFIX \"$targetName\"."
            } else null
            val committedNow = withContext(NonCancellable) {
                val ok = commitUri(uri).also { committed = it }
                if (ok) created.clear()
                else {
                    created.forEach { runCatching { mp4Gateway.deleteDocument(it) } }
                    created.clear()
                }
                ok
            }
            if (!committedNow) {
                return Mp4PublishOutcome(null, stale = true, error = null, recoveryUri = null)
            }
            return Mp4PublishOutcome(uri, stale = false, error = note, recoveryUri = null)
        } catch (e: CancellationException) {
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MP4 tree export failed for $sessionId", e)
            if (!committed) created.forEach { runCatching { gateway.deleteDocument(it) } }
            return failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failure(detail: String) =
        Mp4PublishOutcome(null, stale = false, error = detail, recoveryUri = null)

    private fun verifyDocumentLength(gateway: RecordingTreeGateway, uri: String, expected: Long) {
        val length = gateway.documentLength(uri)
        if (length != expected) {
            throw java.io.IOException("Exported MP4 length mismatch ($length != $expected)")
        }
    }
}

/** DocumentsContract/ContentResolver-backed [MeetingStorageManager.RecordingTreeGateway]. */
class ContentResolverTreeGateway(
    private val context: Context,
    private val treeUri: Uri,
    private val baseDocId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : MeetingStorageManager.RecordingTreeGateway {

    private val contentResolver = context.contentResolver
    private val baseDocUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, baseDocId)

    private fun query(uri: Uri, column: String): String? = try {
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun isInTree(documentUri: String): Boolean = try {
        val uri = Uri.parse(documentUri)
        val docId = DocumentsContract.getDocumentId(uri)
        val sameTree = !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(treeUri)
        val directChild = docId == baseDocId ||
            (docId.startsWith("$baseDocId/") && !docId.removePrefix("$baseDocId/").contains('/'))
        uri.authority == treeUri.authority && sameTree && directChild
    } catch (_: Exception) {
        false
    }

    override fun childDocumentNames(): Set<String> {
        val names = mutableSetOf<String>()
        val cursor = contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, baseDocId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        ) ?: throw java.io.IOException("Could not enumerate the recordings folder")
        cursor.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
        }
        return names
    }

    override fun createDocument(mime: String, displayName: String): String =
        DocumentsContract.createDocument(contentResolver, baseDocUri, mime, displayName)?.toString()
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

    override fun documentSize(documentUri: String): Long? = try {
        contentResolver.query(
            Uri.parse(documentUri),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }

    override fun childDirectoryGateway(
        name: String,
        create: Boolean,
    ): MeetingStorageManager.RecordingTreeGateway? {
        childDirDocId(name)?.let { return ContentResolverTreeGateway(context, treeUri, it) }
        if (!create) return null
        val created = DocumentsContract.createDocument(
            contentResolver, baseDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw java.io.IOException("The recordings folder refused to create the $name subfolder")
        return ContentResolverTreeGateway(context, treeUri, DocumentsContract.getDocumentId(created))
    }

    private fun childDirDocId(name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, baseDocId)
        contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name &&
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                ) return c.getString(0)
            }
        }
        return null
    }

    override fun renameDocument(documentUri: String, newDisplayName: String): String =
        DocumentsContract.renameDocument(contentResolver, Uri.parse(documentUri), newDisplayName)
            ?.toString()
            ?: documentUri // provider renamed the document in place
}
