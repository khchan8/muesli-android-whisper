package com.phequals7.muesli.meetings

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages public `/sdcard/Recordings/Muesli` (`Environment.DIRECTORY_RECORDINGS`)
 * WAV file creation, MediaScanner indexing, and one-time migration of legacy
 * internal `/data/data/com.phequals7.muesli/files/meetings/<id>.wav` recordings.
 *
 * On Android 10+ (scoped storage) apps may contribute new media files to
 * public media directories via direct file paths without any permission;
 * pre-Android-10 devices fall back to the internal meetings dir at the
 * call site when writes fail.
 */
object MeetingStorageManager {
    private const val TAG = "MeetingStorageManager"
    private const val SUBFOLDER_NAME = "Muesli"

    /** Resolves `/sdcard/Recordings/Muesli`, falling back to `/sdcard/Recordings`. */
    fun getPublicRecordingsDirectory(): File {
        val publicRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_RECORDINGS
        )
        val muesliDir = File(publicRoot, SUBFOLDER_NAME)
        if (!muesliDir.exists() && !muesliDir.mkdirs()) {
            if (!publicRoot.exists()) publicRoot.mkdirs()
            return publicRoot
        }
        return muesliDir
    }

    /** True when a probe write succeeds — gates the public-dir fallback on API < 29. */
    fun isPublicRecordingsWritable(): Boolean = try {
        val dir = getPublicRecordingsDirectory()
        val probe = File(dir, ".muesli_probe")
        probe.writeText("ok")
        probe.delete()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Creates a descriptive `.wav` file inside `/sdcard/Recordings/Muesli/`
     * preserving English, Thai, and Chinese characters in the meeting title.
     */
    fun createPublicSessionWavFile(sessionId: String, title: String?): File {
        val targetDir = getPublicRecordingsDirectory()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val sanitizedTitle = (title?.takeIf { it.isNotBlank() } ?: "Meeting")
            .replace(Regex("[^a-zA-Z0-9\\u0E00-\\u0E7F\\u4E00-\\u9FFF_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(32)
            .ifEmpty { "Meeting" }
        val shortId = sessionId.take(8)
        return File(targetDir, "${timestamp}_${sanitizedTitle}_${shortId}.wav")
    }

    /** Internal meetings dir (v0.3.x layout) used when public storage is unwritable. */
    fun internalRecordingsDirectory(context: Context): File =
        File(context.filesDir, "meetings").apply { mkdirs() }

    /** Makes a newly written public WAV visible to file managers and MTP. */
    fun indexRecordingInMediaStore(context: Context, wavFile: File) {
        if (!wavFile.exists()) return
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(wavFile.absolutePath),
            arrayOf("audio/x-wav", "audio/wav"),
            null
        )
    }

    /**
     * One-time migration: moves legacy internal `files/meetings/<sessionId>.wav`
     * files into `/sdcard/Recordings/Muesli/` and reports each `oldPath -> newPath`
     * via [onPathMigrated] so Room rows can be updated.
     */
    suspend fun migrateLegacyInternalRecordings(
        context: Context,
        onPathMigrated: (suspend (sessionId: String, oldPath: String, newPath: String) -> Unit)? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val legacyDir = File(context.filesDir, "meetings")
        if (!legacyDir.exists() || !legacyDir.isDirectory) return@withContext emptyMap()

        val migratedMap = mutableMapOf<String, String>()
        val publicDir = getPublicRecordingsDirectory()

        legacyDir.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            ?.forEach { legacyWav ->
                try {
                    val sessionId = legacyWav.nameWithoutExtension.removePrefix(".tmp-")
                    val destFile = File(publicDir, legacyWav.name.removePrefix(".tmp-"))
                    if (destFile.exists()) {
                        // Already migrated on a previous launch — drop the duplicate.
                        legacyWav.delete()
                        return@forEach
                    }
                    legacyWav.inputStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists() && destFile.length() == legacyWav.length()) {
                        legacyWav.delete()
                        indexRecordingInMediaStore(context, destFile)
                        migratedMap[legacyWav.absolutePath] = destFile.absolutePath
                        onPathMigrated?.invoke(sessionId, legacyWav.absolutePath, destFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to migrate legacy recording ${legacyWav.absolutePath}", e)
                }
            }
        migratedMap
    }
}
