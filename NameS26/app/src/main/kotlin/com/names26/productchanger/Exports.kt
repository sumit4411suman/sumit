package com.names26.productchanger

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Writes files into the public Downloads/NameS26 folder through MediaStore (no storage permission needed). */
object Exports {
    private const val SUBDIR = "NameS26"

    fun saveToDownloads(context: Context, source: File, displayName: String, mimeType: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            AppLog.e("export", "MediaStore insert failed for $displayName")
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val shown = "Download/$SUBDIR/$displayName"
            AppLog.i("export", "saved $shown (${source.length()} bytes)")
            shown
        } catch (e: Exception) {
            AppLog.e("export", "failed to save $displayName", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
