package com.phequals7.muesli.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phequals7.muesli.meetings.ContentResolverTreeGateway
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingExportTruncationTest {
    @Test
    fun replacingAFileWithShorterContentTruncatesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "muesli-export-test-${UUID.randomUUID()}.srt")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download")
            val gateway = ContentResolverTreeGateway(context, tree)
            gateway.openOutput(uri.toString()).use { it.write("longer original notes".toByteArray()) }
            gateway.openOutput(uri.toString()).use { it.write("short".toByteArray()) }
            val actual = requireNotNull(resolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            assertEquals("short", actual)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
