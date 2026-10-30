package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.docs.ExtractedDocument

/**
 * Reads back an attached document's extracted text by its id ([Attachment.path] of a DOCUMENT).
 * The app reads [com.vanskis.liquidpocket.docs.DocumentStore]; tests fake it. Calls block;
 * [ChatController] makes them off the main thread.
 */
fun interface DocumentLoader {
    /** The document saved under [id], or null when it is gone. */
    fun document(id: String): ExtractedDocument?

    companion object {
        /** For controllers that never get documents. */
        val NONE = DocumentLoader { null }
    }
}
