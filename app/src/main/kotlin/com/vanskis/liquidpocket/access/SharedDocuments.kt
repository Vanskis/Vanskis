package com.vanskis.liquidpocket.access

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/** Copies shared documents into `filesDir/shared`, so they outlive the sender's URI grant. */
object SharedDocuments {
    /** Copies [uri] and adds it to [SharedInbox]. Blocks. */
    fun import(context: Context, uri: Uri): SharedDocument {
        val resolver = context.contentResolver
        val name = SharedInbox.safeFileName(displayName(context, uri))
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: LaunchRequest.MIME_PDF
        val dir = File(context.filesDir, SharedInbox.SHARED_DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}-$name")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open $name")
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return SharedDocument(name, mime, target.path).also(SharedInbox::add)
    }

    /** Deletes what a previous process left in the shared folder; call at app start, when the inbox is empty. */
    fun clearStored(context: Context) {
        File(context.filesDir, SharedInbox.SHARED_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
