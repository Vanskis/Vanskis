package com.vanskis.liquidpocket.access

/**
 * What an incoming intent asks the app to do: a share from another app, the home-screen widget or
 * the Quick Settings tile. [U] is the URI type ([android.net.Uri] in the app); the routing here is
 * plain Kotlin, the Android parsing is in [LaunchIntents].
 */
sealed interface LaunchRequest<out U> {
    /** Opens a new chat, with [prefill] in the message field (not sent) and the files waiting to go. */
    data class NewChat<out U>(
        val prefill: String? = null,
        val images: List<U> = emptyList(),
        val documents: List<U> = emptyList(),
        val startVoice: Boolean = false,
        val focusInput: Boolean = false,
    ) : LaunchRequest<U>

    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

        /** The extra the widget and tile set: [START_TEXT], [START_VOICE] or [START_NEW]. */
        const val EXTRA_START = "com.vanskis.liquidpocket.extra.START"
        const val START_TEXT = "text"
        const val START_VOICE = "voice"
        const val START_NEW = "new"

        const val MIME_PDF = "application/pdf"

        /** The request [intent] makes, or null when it asks for nothing beyond opening the app. */
        fun <U> from(intent: IncomingIntent<U>): NewChat<U>? = if (intent.fromHistory) null else when (intent.action) {
            ACTION_SEND, ACTION_SEND_MULTIPLE -> share(intent)
            // Only the app's own widget and tile reach the non-exported entry that may start the mic.
            else -> if (!intent.fromOwnEntry) null else when (intent.start) {
                START_TEXT -> NewChat(focusInput = true)
                START_VOICE -> NewChat(startVoice = true)
                START_NEW -> NewChat()
                else -> null
            }
        }

        private fun <U> share(intent: IncomingIntent<U>): NewChat<U>? {
            val images = mutableListOf<U>()
            val documents = mutableListOf<U>()
            for (stream in intent.streams) {
                val mime = (stream.mime ?: intent.type)?.lowercase() ?: continue
                when {
                    mime.startsWith("image/") -> images += stream.uri
                    mime == MIME_PDF -> documents += stream.uri
                }
            }
            val text = intent.text?.trim()?.takeIf { it.isNotEmpty() }
            if (text == null && images.isEmpty() && documents.isEmpty()) return null
            return NewChat(prefill = text, images = images, documents = documents)
        }
    }
}

/** The parts of an Android intent the routing reads. */
data class IncomingIntent<out U>(
    val action: String?,
    /** The intent's MIME type; for a multiple share often a wildcard like `image/` + `*`. */
    val type: String? = null,
    /** EXTRA_TEXT: shared text or a link. */
    val text: String? = null,
    /** EXTRA_STREAM items, each with its own MIME type when the content resolver knows it. */
    val streams: List<SharedStream<U>> = emptyList(),
    /** [LaunchRequest.EXTRA_START]. */
    val start: String? = null,
    /** True when the intent came through the app's non-exported widget and tile entry. */
    val fromOwnEntry: Boolean = false,
    /** True when Android relaunched the task from Recents: the original request was handled already. */
    val fromHistory: Boolean = false,
)

data class SharedStream<out U>(val uri: U, val mime: String?)
