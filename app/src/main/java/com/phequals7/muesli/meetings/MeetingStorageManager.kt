package com.phequals7.muesli.meetings

import android.content.Context
import android.net.Uri
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
 * Private `/data/data/…/files/meetings` WAV + SRT artifact pairs with
 * title-driven naming, plus paired export into the user-granted recordings
 * folder (SAF tree). Legacy public `/sdcard/Recordings/Muesli` files stay
 * readable where they are — nothing is migrated or deleted for migration's
 * sake.
 *
 * Retained recordings are stored as `<safe-title>_<startedAt timestamp>.wav`
 * with an identically named `.srt` beside them.
 */
object MeetingStorageManager {
    private const val TAG = "MeetingStorageManager"
    const val EXPORT_ERROR_PREFIX = "Recording export failed:"
    const val CHOOSE_FOLDER_ERROR = "$EXPORT_ERROR_PREFIX Choose a recordings folder to save WAV and SRT."
    private const val MIME_WAV = "audio/wav"
    private const val MIME_SRT = "application/x-subrip"

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
     * Reserves a collision-free `.wav` (checking both `.wav` and `.srt`
     * candidates) named after the recording start time, and returns it.
     */
    fun createSessionWavFile(directory: File, title: String?, startedAtMs: Long): File {
        directory.mkdirs()
        val base = recordingBaseName(title, startedAtMs)
        var attempt = 1
        while (true) {
            val stem = if (attempt == 1) base else "${base}_$attempt"
            val wav = File(directory, "$stem.wav")
            // Both extensions must be free so the SRT sidecar can share the stem.
            if (!wav.exists() && !File(directory, "$stem.srt").exists() && wav.createNewFile()) return wav
            attempt++
        }
    }

    /** Resolves a stored [com.phequals7.muesli.data.entity.RecordingSession.audioFileName] to a file. */
    fun resolveRecordingWav(filesDir: File, audioFileName: String?): File? {
        val name = audioFileName?.takeIf { it.isNotBlank() } ?: return null
        return if (name.startsWith("/")) File(name) else File(filesDir, "meetings/$name")
    }

    /** Result of one private artifact synchronization pass. */
    internal data class SyncOutcome(
        /** Absolute path of the (possibly renamed) WAV after the pass. */
        val wavPath: String?,
        /** Export failure text starting with [EXPORT_ERROR_PREFIX], or null. */
        val exportError: String?
    )

    /**
     * Ensures a completed session's retained WAV has a matching SRT sidecar and
     * a title-driven `<title>_<startedAt>` basename, then updates Room's
     * `audioFileName` only after the new pair is durable. The DB commit AND
     * the source cleanup both run in [NonCancellable] so a cancellation
     * delivered right after the commit can never strand the new pair or
     * delete it after the DB already points at it.
     */
    internal suspend fun syncSessionArtifacts(
        sessionId: String,
        title: String,
        startedAtMs: Long,
        sourceWav: File,
        srtContent: String?,
        targetDirectory: File,
        storedAudioPath: String?,
        commitPath: suspend (expectedOldPath: String?, newPath: String) -> Boolean
    ): SyncOutcome {
        val desiredStem = recordingBaseName(title, startedAtMs)
        val sourceSrt = File(sourceWav.parentFile, sourceWav.nameWithoutExtension + ".srt")

        val currentStem = sourceWav.nameWithoutExtension
        val stableStem = currentStem == desiredStem ||
            currentStem.removePrefix("${desiredStem}_").let { it != currentStem && it.toIntOrNull()?.let { n -> n >= 2 } == true }
        val needsRename = !stableStem || sourceWav.parentFile != targetDirectory
        if (srtContent == null) {
            Log.i(TAG, "No timed segments for $sessionId; keeping audio without an SRT sidecar")
        }

        // Free stem in the target directory for both .wav and .srt; collision
        // suffixes _2, _3, ... never overwrite an existing file. The source's
        // own stem is not a conflict (we own it), so repeated syncs of an
        // already-suffixed pair stay stable instead of drifting to _3, _4…
        var stem = desiredStem
        var attempt = 1
        if (needsRename) {
            while (stem != sourceWav.nameWithoutExtension &&
                (File(targetDirectory, "$stem.wav").exists() || File(targetDirectory, "$stem.srt").exists())
            ) {
                attempt++
                stem = "${desiredStem}_$attempt"
            }
        }

        val newWav = if (needsRename) File(targetDirectory, "$stem.wav") else sourceWav
        val newSrt = if (needsRename) File(targetDirectory, "$stem.srt") else sourceSrt

        val created = mutableListOf<File>()
        var committed = false
        try {
            if (needsRename) {
                targetDirectory.mkdirs()
                copyAndSync(sourceWav, newWav)
                created.add(newWav)
                check(newWav.length() == sourceWav.length()) { "Incomplete recording copy" }
            }
            if (srtContent != null) {
                writeSrtAtomically(newSrt, srtContent)
                created.add(newSrt)
            }

            // Commit phase: the guarded DB update and the source cleanup form
            // one non-cancellable unit — after the commit succeeds the new
            // pair can no longer be deleted by cancellation or a later error.
            val expectedOldPath = if (needsRename) storedAudioPath else null
            withContext(NonCancellable) {
                committed = commitPath(expectedOldPath, newWav.absolutePath)
                if (committed && needsRename) {
                    if (!sourceWav.delete()) Log.w(TAG, "Could not remove original ${sourceWav.absolutePath}")
                    if (sourceSrt.exists() && !sourceSrt.delete()) {
                        Log.w(TAG, "Could not remove original ${sourceSrt.absolutePath}")
                    }
                }
                committed
            }.let { committed ->
                if (!committed) {
                    // Room row vanished or its path changed underneath us: keep
                    // the source pair, remove only what this pass created.
                    created.forEach { if (it != sourceWav && it != sourceSrt) it.delete() }
                    return SyncOutcome(sourceWav.absolutePath, null)
                }
            }
            return SyncOutcome(newWav.absolutePath, null)
        } catch (e: CancellationException) {
            if (!committed) created.forEach { if (it != sourceWav && it != sourceSrt) it.delete() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recording export failed for $sessionId", e)
            if (!committed) created.forEach { if (it != sourceWav && it != sourceSrt) it.delete() }
            return SyncOutcome(sourceWav.absolutePath, "$EXPORT_ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Reserves [dest] exclusively (atomic create), then copies + fsyncs so
     *  the WAV is durable before commit. */
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
    private fun writeSrtAtomically(target: File, content: String) {
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

    /**
     * Lazily synchronizes one session's artifacts: normalizes the private
     * WAV/SRT pair (title-driven basename) and exports the pair into the
     * granted recordings folder. Returns an export error message (for
     * `RecordingSession.errorMessage`) or null on success.
     */
    suspend fun syncRecordingArtifacts(context: Context, store: com.phequals7.muesli.data.SharedStore, sessionId: String): Unit =
        artifactMutex.withLock {
            withContext(Dispatchers.IO) {
                val session = store.getRecordingSessionById(sessionId) ?: return@withContext
                if (session.phase != "completed" || session.audioFileName.isNullOrBlank()) return@withContext
                val sourceWav = resolveRecordingWav(context.filesDir, session.audioFileName)
                    ?.takeIf { it.exists() && it.length() > 44L }
                if (sourceWav == null) {
                    store.updateRecordingExportError(sessionId, "$EXPORT_ERROR_PREFIX Recording audio is missing.")
                    return@withContext
                }

                val segments = store.getTranscriptSegments(sessionId)
                val transcriptText = store.getTranscriptForSession(sessionId)?.text.orEmpty()
                // Legacy flat transcript without timed cues: preserve audio and report
                // that an accurate SRT cannot be created; never fabricate timestamps.
                val noTimedCuesError = if (segments.isEmpty() && transcriptText.isNotBlank()) {
                    "$EXPORT_ERROR_PREFIX Accurate SRT subtitles cannot be created because this legacy recording has no timed cues."
                } else {
                    null
                }
                val durationMs = runCatching {
                    android.media.MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(sourceWav.absolutePath)
                            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    }
                }.getOrDefault(0L)
                val srtContent = if (noTimedCuesError != null) null else MeetingTranscriptSrt.serialize(
                    segments.map { it.toDomainModel() },
                    manualNotes = session.manualNotes,
                    summary = session.summaryText.takeUnless { it.startsWith("## Summary failed\n") }.orEmpty(),
                    audioDurationMs = maxOf(durationMs, (session.endedAt ?: 0L) - (session.startedAt ?: 0L)),
                )

                // 1. Private pair: normalize the basename in the WAV's own
                //    directory (files/meetings for new recordings; legacy
                //    public files stay in place, readable, never migrated).
                val targetDir = sourceWav.parentFile ?: internalRecordingsDirectory(context.filesDir)
                val privateOutcome = syncSessionArtifacts(
                    sessionId = sessionId,
                    title = session.title,
                    startedAtMs = session.startedAt ?: session.createdAt,
                    sourceWav = sourceWav,
                    srtContent = srtContent,
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

                var error: String? = privateOutcome.exportError

                // 2. Public export through the granted folder.
                if (error == null) {
                    val refreshed = store.getRecordingSessionById(sessionId) ?: return@withContext
                    val refreshedWav = resolveRecordingWav(context.filesDir, refreshed.audioFileName)
                    val treeUriStr = store.recordingsTreeUri
                    if (treeUriStr.isBlank()) {
                        error = noTimedCuesError ?: CHOOSE_FOLDER_ERROR
                    } else if (refreshedWav != null && refreshedWav.exists() && refreshedWav.length() > 44L) {
                        try {
                            val treeUri = Uri.parse(treeUriStr)
                            val gateway = ContentResolverTreeGateway(context.applicationContext, treeUri)
                            val export = exportPairToTree(
                                gateway = gateway,
                                sessionId = sessionId,
                                desiredStem = recordingBaseName(refreshed.title, refreshed.startedAt ?: refreshed.createdAt),
                                sourceWav = refreshedWav,
                                srtContent = srtContent,
                                ownedWavUri = refreshed.exportedWavUri,
                                ownedSrtUri = refreshed.exportedSrtUri,
                                commitUris = { wavUri, srtUri ->
                                    store.updateRecordingExportUris(sessionId, refreshed.audioFileName, wavUri, srtUri) == 1
                                }
                            )
                            error = export.exportError ?: export.cleanupError ?: noTimedCuesError
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "$EXPORT_ERROR_PREFIX ${e.message ?: "Unable to access the recordings folder."}"
                        }
                    }
                }

                if (error != null) {
                    store.updateRecordingExportError(sessionId, error)
                } else {
                    // A successful full export clears a previous export error only.
                    store.updateRecordingExportError(sessionId, null)
                }
            }
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

    internal data class TreeExportOutcome(
        val wavUri: String?,
        val srtUri: String?,
        val exportError: String?,
        val cleanupError: String?,
    )

    /**
     * Creates (or refreshes) the paired WAV/SRT documents named
     * `<desiredStem>.wav|.srt` in the tree, verifies display names and byte
     * lengths, commits both URIs together through the guarded [commitUris],
     * and only then deletes the superseded owned documents. Attempt-owned
     * documents are removed on any pre-commit failure; the committed pair is
     * never deleted afterwards.
     */
    internal suspend fun exportPairToTree(
        gateway: RecordingTreeGateway,
        sessionId: String,
        desiredStem: String,
        sourceWav: File,
        srtContent: String?,
        ownedWavUri: String?,
        ownedSrtUri: String?,
        commitUris: suspend (wavUri: String, srtUri: String) -> Boolean,
    ): TreeExportOutcome {
        val ownedWav = ownedWavUri?.takeIf { it.isNotBlank() }
        val ownedSrt = ownedSrtUri?.takeIf { it.isNotBlank() }
        val ownedWavName = ownedWav?.let { gateway.queryDisplayName(it) }.orEmpty()
        val ownedStem = ownedWavName.removeSuffix(".wav")
        val suffix = ownedStem.removePrefix("${desiredStem}_")
        val matchingStem = ownedStem == desiredStem ||
            (suffix != ownedStem && suffix.toIntOrNull()?.let { it >= 2 } == true)
        val reuseOwned = ownedWav != null && ownedSrt != null &&
            gateway.isInTree(ownedWav) && gateway.isInTree(ownedSrt) &&
            ownedWavName == "$ownedStem.wav" && matchingStem &&
            gateway.queryDisplayName(ownedSrt) == "$ownedStem.srt"

        val created = mutableListOf<String>()
        var committedPair = false
        try {
            val wavUri: String
            val srtUri: String?
            if (reuseOwned) {
                wavUri = ownedWav!!
                srtUri = ownedSrt.takeIf { srtContent != null }
            } else {
                val names = gateway.childDocumentNames()
                var stem = desiredStem
                var attempt = 1
                while ("$stem.wav" in names || "$stem.srt" in names) {
                    attempt++
                    stem = "${desiredStem}_$attempt"
                }
                wavUri = gateway.createDocument(MIME_WAV, "$stem.wav")
                    .also { created.add(it) }
                if (gateway.queryDisplayName(wavUri) != "$stem.wav") {
                    throw java.io.IOException("Provider created an unexpected WAV name")
                }
                srtUri = if (srtContent != null) {
                    gateway.createDocument(MIME_SRT, "$stem.srt")
                        .also { created.add(it) }
                        .also {
                            if (gateway.queryDisplayName(it) != "$stem.srt") {
                                throw java.io.IOException("Provider created an unexpected SRT name")
                            }
                        }
                } else {
                    null
                }
            }

            if (!reuseOwned || gateway.documentLength(wavUri) != sourceWav.length()) {
                writeDocument(gateway, wavUri) { out ->
                    sourceWav.inputStream().use { input -> copyActively(input, out) }
                }
                val wavLength = gateway.documentLength(wavUri)
                if (wavLength != sourceWav.length()) {
                    throw java.io.IOException("Exported WAV length mismatch ($wavLength != ${sourceWav.length()})")
                }
            }
            if (srtUri != null && srtContent != null) {
                writeDocument(gateway, srtUri) { out ->
                    out.write(srtContent.toByteArray(Charsets.UTF_8))
                }
                val srtLength = gateway.documentLength(srtUri)
                val expectedSrt = srtContent.toByteArray(Charsets.UTF_8).size.toLong()
                if (srtLength != expectedSrt) {
                    throw java.io.IOException("Exported SRT length mismatch ($srtLength != $expectedSrt)")
                }
            }

            // Commit both pointers in one guarded, non-cancellable write.
            // No SRT for this recording keeps any prior SRT pointer, else empty.
            val committed = withContext(NonCancellable) {
                commitUris(wavUri, srtUri ?: ownedSrt.orEmpty()).also { committedPair = it }
            }
            if (!committed) {
                // Session row vanished or changed: remove only what this
                // attempt created; reused owned documents keep their content.
                created.forEach { gateway.deleteDocument(it) }
                return TreeExportOutcome(null, null, null, null)
            }

            var cleanupError: String? = null
            if (!reuseOwned) {
                val oldWav = ownedWav?.takeIf { it != wavUri }
                val oldSrt = ownedSrt?.takeIf { srtUri == null || it != srtUri }
                if (oldWav != null && !gateway.deleteDocument(oldWav)) {
                    cleanupError = "$EXPORT_ERROR_PREFIX Previous export files could not be cleaned up."
                }
                if (oldSrt != null && !gateway.deleteDocument(oldSrt)) {
                    cleanupError = "$EXPORT_ERROR_PREFIX Previous export files could not be cleaned up."
                }
            }
            return TreeExportOutcome(wavUri, srtUri, null, cleanupError)
        } catch (e: CancellationException) {
            if (!committedPair) created.forEach { runCatching { gateway.deleteDocument(it) } }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tree export failed for $sessionId", e)
            if (!committedPair) created.forEach { runCatching { gateway.deleteDocument(it) } }
            return TreeExportOutcome(null, null, "$EXPORT_ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}", null)
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
