package com.vanskis.liquidpocket.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A document shared into the app, copied to [path] in app storage (`filesDir/shared`). */
data class SharedDocument(val name: String, val mime: String, val path: String)

/** Documents shared from other apps, waiting to be used in a chat. The copies stay on disk until the user of the inbox deletes them. */
object SharedInbox {
    private val pending = MutableStateFlow<List<SharedDocument>>(emptyList())

    val pendingDocuments: StateFlow<List<SharedDocument>> = pending.asStateFlow()

    fun add(document: SharedDocument) = pending.update { it + document }

    fun remove(document: SharedDocument) = pending.update { it - document }

    /** [displayName] reduced to a file name that stays inside the shared folder. */
    fun safeFileName(displayName: String?, fallback: String = "document.pdf"): String {
        val base = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.').take(MAX_NAME)
        return cleaned.ifBlank { fallback }
    }

    /** The folder under filesDir that [SharedDocuments] copies into. */
    const val SHARED_DIR = "shared"

    private const val MAX_NAME = 80
}
