package com.phequals7.muesli.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.phequals7.muesli.data.entity.CustomWord
import com.phequals7.muesli.data.entity.SpeakerProfileEntity
import com.phequals7.muesli.data.entity.SpeakerProfileSampleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

data class ProfileWithSamples(
    val profile: SpeakerProfileEntity,
    val samples: List<SpeakerProfileSampleEntity>,
)

data class BackupSnapshot(
    val prefs: Map<String, Any>,
    val words: List<CustomWord>,
    val profiles: List<ProfileWithSamples>,
)

data class DecodedBackup(val snapshot: BackupSnapshot, val exportedAt: Long)

/** Pure (JVM-testable) encoding of the `muesli-backup.json` file. */
object AppBackupCodec {
    private const val VERSION = 1
    private const val NOT_A_BACKUP = "Not a Muesli backup"

    // Secrets (other apps can read the Recordings folder), device-local grants
    // and state that depends on files which are not part of the backup.
    private val EXCLUDED_KEYS = setOf(
        "openrouter_api_key",
        "custom_summary_api_key",
        "recordings_tree_uri",
        "onboarding_completed",
        "onboarding_use_case",
        "selected_model_id",
        "engine_type",
    )

    fun isExportablePref(key: String): Boolean =
        key !in EXCLUDED_KEYS && !key.startsWith("backup_")

    /** Auto-backup must never overwrite a newer backup this install has not restored. */
    fun shouldAutoWrite(foreignNewer: Boolean, newHash: String, lastHash: String): Boolean =
        !foreignNewer && newHash != lastHash

    fun encode(snapshot: BackupSnapshot, exportedAt: Long): String {
        val prefs = JSONObject()
        for (key in snapshot.prefs.keys.sorted()) {
            if (!isExportablePref(key)) continue
            val (tag, value) = when (val v = snapshot.prefs.getValue(key)) {
                is Boolean -> "b" to v
                is String -> "s" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                else -> continue
            }
            prefs.put(key, JSONObject().put("t", tag).put("v", value))
        }
        val words = JSONArray()
        for (w in snapshot.words) {
            words.put(
                JSONObject()
                    .put("id", w.id).put("word", w.word)
                    .put("replacement", w.replacement ?: JSONObject.NULL)
                    .put("matchingThreshold", w.matchingThreshold)
                    .put("createdAt", w.createdAt).put("isEnabled", w.isEnabled)
            )
        }
        val profiles = JSONArray()
        for ((p, samples) in snapshot.profiles) {
            val sampleArr = JSONArray()
            for (s in samples) {
                sampleArr.put(
                    JSONObject()
                        .put("sampleId", s.sampleId)
                        .put("sourceSessionId", s.sourceSessionId ?: JSONObject.NULL)
                        .put("durationMs", s.durationMs)
                        .put("embeddingHex", hex(s.embeddingBlob))
                        .put("createdAtMs", s.createdAtMs)
                )
            }
            profiles.put(
                JSONObject()
                    .put("profileId", p.profileId).put("displayName", p.displayName)
                    .put("badgeColorHex", p.badgeColorHex).put("sampleCount", p.sampleCount)
                    .put("centroidHex", hex(p.centroidEmbeddingBlob))
                    .put("createdAtMs", p.createdAtMs).put("updatedAtMs", p.updatedAtMs)
                    .put("samples", sampleArr)
            )
        }
        return JSONObject()
            .put("version", VERSION).put("exportedAt", exportedAt)
            .put("prefs", prefs).put("customWords", words).put("speakerProfiles", profiles)
            .toString()
    }

    /** @throws IllegalArgumentException when [json] is not a version-1 Muesli backup. */
    fun decode(json: String): DecodedBackup = try {
        val root = JSONObject(json)
        require(root.optInt("version", -1) == VERSION) { NOT_A_BACKUP }
        val prefs = HashMap<String, Any>()
        val prefsJson = root.getJSONObject("prefs")
        for (key in prefsJson.keys()) {
            if (!isExportablePref(key)) continue
            val o = prefsJson.getJSONObject(key)
            prefs[key] = when (o.getString("t")) {
                "b" -> o.getBoolean("v")
                "s" -> o.getString("v")
                "i" -> o.getInt("v")
                "l" -> o.getLong("v")
                "f" -> o.getDouble("v").toFloat()
                else -> throw IllegalArgumentException(NOT_A_BACKUP)
            }
        }
        val wordsJson = root.getJSONArray("customWords")
        val words = (0 until wordsJson.length()).map { i ->
            wordsJson.getJSONObject(i).let {
                CustomWord(
                    id = it.getString("id"), word = it.getString("word"),
                    replacement = if (it.isNull("replacement")) null else it.getString("replacement"),
                    matchingThreshold = it.getDouble("matchingThreshold"),
                    createdAt = it.getLong("createdAt"), isEnabled = it.getBoolean("isEnabled"),
                )
            }
        }
        val profilesJson = root.getJSONArray("speakerProfiles")
        val profiles = (0 until profilesJson.length()).map { i ->
            val p = profilesJson.getJSONObject(i)
            val id = p.getString("profileId")
            val samplesJson = p.getJSONArray("samples")
            ProfileWithSamples(
                SpeakerProfileEntity(
                    profileId = id, displayName = p.getString("displayName"),
                    badgeColorHex = p.getString("badgeColorHex"), sampleCount = p.getInt("sampleCount"),
                    centroidEmbeddingBlob = unhex(p.getString("centroidHex")),
                    createdAtMs = p.getLong("createdAtMs"), updatedAtMs = p.getLong("updatedAtMs"),
                ),
                (0 until samplesJson.length()).map { j ->
                    samplesJson.getJSONObject(j).let {
                        SpeakerProfileSampleEntity(
                            sampleId = it.getString("sampleId"), profileId = id,
                            sourceSessionId = if (it.isNull("sourceSessionId")) null else it.getString("sourceSessionId"),
                            durationMs = it.getLong("durationMs"),
                            embeddingBlob = unhex(it.getString("embeddingHex")),
                            createdAtMs = it.getLong("createdAtMs"),
                        )
                    }
                },
            )
        }
        DecodedBackup(BackupSnapshot(prefs, words, profiles), root.getLong("exportedAt"))
    } catch (e: JSONException) {
        throw IllegalArgumentException(NOT_A_BACKUP, e)
    }

    fun sha256Hex(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private const val HEX = "0123456789abcdef"

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { NOT_A_BACKUP }
        return ByteArray(s.length / 2) { i ->
            val hi = HEX.indexOf(s[2 * i])
            val lo = HEX.indexOf(s[2 * i + 1])
            require(hi >= 0 && lo >= 0) { NOT_A_BACKUP }
            ((hi shl 4) or lo).toByte()
        }
    }
}

/** Reads/writes `muesli-backup.json` in the user's Recordings folder (SAF tree). */
object AppBackup {
    const val FILE_NAME = "muesli-backup.json"

    suspend fun backup(context: Context, treeUri: Uri, force: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = SharedStore(context)
                val snapshot = snapshot(context, store)
                val hash = AppBackupCodec.sha256Hex(AppBackupCodec.encode(snapshot, 0L))
                val resolver = context.contentResolver
                val existing = findBackup(context, treeUri)
                if (!force) {
                    val existingAt = existing?.let {
                        runCatching { AppBackupCodec.decode(readText(context, it)).exportedAt }.getOrNull()
                    }
                    val foreignNewer = existingAt != null && existingAt > store.lastBackupAtMs
                    val lastHash = if (existing == null) "" else store.lastBackupHash
                    if (!AppBackupCodec.shouldAutoWrite(foreignNewer, hash, lastHash)) return@runCatching "Backup is up to date"
                }
                val target = existing ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                    "application/json", FILE_NAME,
                ) ?: throw IOException("The recordings folder refused to create $FILE_NAME")
                val now = System.currentTimeMillis()
                // shortcut: in-place truncate-then-write, tiny file; upgrade to temp+rename if corruption is ever observed
                (resolver.openOutputStream(target, "wt") ?: throw IOException("Could not write $FILE_NAME")).use {
                    it.write(AppBackupCodec.encode(snapshot, now).toByteArray())
                }
                store.lastBackupAtMs = now
                store.lastBackupHash = hash
                "Backed up ${snapshot.prefs.size} settings, ${snapshot.words.size} words, ${snapshot.profiles.size} voice profiles"
            }
        }

    suspend fun restore(context: Context, treeUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = findBackup(context, treeUri)
                    ?: throw IOException("No $FILE_NAME found in the Recordings folder")
                val decoded = AppBackupCodec.decode(readText(context, file))
                val store = SharedStore(context)
                val db = AppDatabase.getDatabase(context)
                db.withTransaction {
                    decoded.snapshot.words.forEach { db.customWordDao().insertCustomWord(it) }
                    for ((p, samples) in decoded.snapshot.profiles) {
                        db.speakerProfileDao().upsertProfile(p)
                        samples.forEach { db.speakerProfileDao().insertProfileSample(it) }
                    }
                }
                store.applyPrefs(decoded.snapshot.prefs)
                store.lastBackupAtMs = decoded.exportedAt
                store.lastBackupHash = AppBackupCodec.sha256Hex(AppBackupCodec.encode(decoded.snapshot, 0L))
                "Restored ${decoded.snapshot.prefs.size} settings, ${decoded.snapshot.words.size} words, " +
                    "${decoded.snapshot.profiles.size} voice profiles. Restart the app to apply theme changes."
            }
        }

    private suspend fun snapshot(context: Context, store: SharedStore): BackupSnapshot {
        val db = AppDatabase.getDatabase(context)
        val prefs = store.allPrefs().filter { (k, v) -> v != null && AppBackupCodec.isExportablePref(k) }
        @Suppress("UNCHECKED_CAST")
        return BackupSnapshot(
            prefs as Map<String, Any>,
            db.customWordDao().getAllCustomWords(),
            db.speakerProfileDao().getAllProfiles().map {
                ProfileWithSamples(it, db.speakerProfileDao().getSamplesForProfile(it.profileId))
            },
        )
    }

    private fun readText(context: Context, uri: Uri): String =
        (context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read $FILE_NAME"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun findBackup(context: Context, treeUri: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        return null
    }
}
