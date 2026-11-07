package com.phequals7.muesli.meetings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.phequals7.muesli.data.SharedStore

/**
 * One-time SAF folder grant for paired WAV/SRT exports. The user picks the
 * folder once (Recordings/Muesli suggested); the grant is persisted and every
 * later export writes directly there.
 */
object RecordingExportAccess {

    fun parseTreeUri(stored: String): Uri? =
        stored.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Human-readable location of the chosen folder, e.g. `Recordings/Muesli`. */
    fun describeFolder(context: Context, stored: String): String? {
        val treeUri = parseTreeUri(stored) ?: return null
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        val label = docId.substringAfter(':').ifBlank { docId }
        return label
    }

    /**
     * Verifies the tree actually accepts document creation + deletion before
     * the preference is replaced. Returns an error message or null.
     */
    fun verifyTreeWritable(context: Context, treeUri: Uri): String? {
        val gateway = try {
            ContentResolverTreeGateway(context.applicationContext, treeUri)
        } catch (e: Exception) {
            return "That folder could not be opened (${e.message ?: e.javaClass.simpleName})."
        }
        var probe: String? = null
        return try {
            probe = gateway.createDocument("application/octet-stream", ".muesli_probe")
            gateway.openOutput(probe).use { it.write(1) }
            if (!gateway.deleteDocument(probe)) {
                "That folder could not remove a test file."
            } else {
                probe = null
                null
            }
        } catch (e: Exception) {
            "That folder could not be written to (${e.message ?: e.javaClass.simpleName}). Pick a folder on this device's storage."
        } finally {
            probe?.let { runCatching { gateway.deleteDocument(it) } }
        }
    }

    /** Persists read/write access to [treeUri]; false when the grant cannot be persisted. */
    fun persistGrant(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    } catch (e: SecurityException) {
        false
    }

    fun releaseGrant(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}

/**
 * Launcher + handler that stores a verified recordings folder in
 * [SharedStore.recordingsTreeUri]. Cancelling the picker keeps the previous
 * setting; a failing folder is reported through [onError] and reverted.
 */
@Composable
fun rememberRecordingsFolderPicker(
    store: SharedStore,
    onSelected: () -> Unit = {},
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // cancel keeps the old setting
        val appContext = context.applicationContext
        if (!RecordingExportAccess.persistGrant(appContext, uri)) {
            onError("That folder's access grant could not be saved. Try choosing it again.")
            return@rememberLauncherForActivityResult
        }
        val error = RecordingExportAccess.verifyTreeWritable(appContext, uri)
        if (error != null) {
            if (store.recordingsTreeUri != uri.toString()) RecordingExportAccess.releaseGrant(appContext, uri)
            onError(error)
        } else {
            store.recordingsTreeUri = uri.toString()
            onSelected()
        }
    }
    return remember { { launcher.launch(suggestedInitialUri()) } }
}

/** Suggests Recordings/Muesli as the starting location; ignored by pickers that cannot resolve it. */
private fun suggestedInitialUri(): Uri = Uri.parse(
    "content://com.android.externalstorage.documents/tree/" +
        android.net.Uri.encode("primary:Recordings/Muesli")
)
