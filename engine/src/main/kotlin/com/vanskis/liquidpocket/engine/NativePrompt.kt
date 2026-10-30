package com.vanskis.liquidpocket.engine

/**
 * A conversation flattened into the parallel arrays the `<prompt>` functions of [LlamaNative]
 * take. Text is UTF-8; per message, its images come before its audio clips (the order the chat
 * template shows them in, before the message text).
 */
internal class NativePrompt private constructor(
    val roles: IntArray,
    val contents: Array<ByteArray>,
    /** Per message; empty unless a tool turn. */
    val toolNames: Array<ByteArray>,
    /** Per message: name, arguments, name, arguments, ... */
    val toolCalls: Array<Array<ByteArray>>,
    /** Per media item: the index of its message. */
    val mediaMsg: IntArray,
    /** Per media item: [LlamaNative.MEDIA_IMAGE] or [LlamaNative.MEDIA_AUDIO]. */
    val mediaKind: IntArray,
    /** Per media item: width, height (image) or sample count, 0 (audio). */
    val mediaDims: IntArray,
    /** Per media item: RGB [ByteArray] or PCM [FloatArray], or null for a placeholder. */
    val mediaData: Array<Any?>,
    /** Name, description, parameters JSON per tool. */
    val tools: Array<ByteArray>,
) {
    companion object {
        /** Opens a thinking block at the start of an assistant message's content; [THINKING_END] ends it. */
        const val THINKING_START = '\u0001'
        const val THINKING_END = '\u0002'

        /** [message]'s content, led by its thinking between [THINKING_START] and [THINKING_END] for the native side to split off. */
        fun packed(message: ChatMessage): String {
            val thinking = message.thinking
            if (message.role != Role.ASSISTANT || thinking.isNullOrEmpty()) return message.content
            return "$THINKING_START${thinking.replace(THINKING_END.toString(), "")}$THINKING_END${message.content}"
        }

        /**
         * Flattens [messages] and [tools]. With [placeholders] the media data is left out: the
         * prompt then still counts the right number of tokens but cannot be decoded.
         */
        fun of(messages: List<ChatMessage>, tools: List<ToolSpec>, placeholders: Boolean = false): NativePrompt {
            val mediaMsg = ArrayList<Int>()
            val mediaKind = ArrayList<Int>()
            val mediaDims = ArrayList<Int>()
            val mediaData = ArrayList<Any?>()
            messages.forEachIndexed { i, m ->
                for (image in m.images) {
                    mediaMsg += i
                    mediaKind += LlamaNative.MEDIA_IMAGE
                    mediaDims += image.width
                    mediaDims += image.height
                    mediaData += if (placeholders) null else image.rgb
                }
                for (clip in m.audio) {
                    mediaMsg += i
                    mediaKind += LlamaNative.MEDIA_AUDIO
                    mediaDims += clip.samples.size
                    mediaDims += 0
                    mediaData += if (placeholders) null else clip.samples
                }
            }
            return NativePrompt(
                roles = IntArray(messages.size) { LlamaNative.roleCode(messages[it].role) },
                contents = Array(messages.size) { utf8(packed(messages[it])) },
                toolNames = Array(messages.size) { utf8(messages[it].toolName.orEmpty()) },
                toolCalls = Array(messages.size) { i ->
                    messages[i].toolCalls.flatMap { listOf(utf8(it.name), utf8(it.argumentsJson)) }.toTypedArray()
                },
                mediaMsg = mediaMsg.toIntArray(),
                mediaKind = mediaKind.toIntArray(),
                mediaDims = mediaDims.toIntArray(),
                mediaData = mediaData.toTypedArray(),
                tools = tools.flatMap { listOf(utf8(it.name), utf8(it.description), utf8(it.parametersJson)) }
                    .toTypedArray(),
            )
        }

        /**
         * Throws [EngineException] if a message carries media on a non-user turn or of a kind
         * [capabilities] does not accept.
         */
        fun checkMedia(messages: List<ChatMessage>, capabilities: Capabilities) {
            for (m in messages) {
                if (m.images.isEmpty() && m.audio.isEmpty()) continue
                if (m.role != Role.USER) throw EngineException("Only user messages can carry images or audio")
                if (m.images.isNotEmpty() && !capabilities.vision) {
                    throw EngineException("The loaded model cannot see images (no vision projector)")
                }
                if (m.audio.isNotEmpty() && !capabilities.audioIn) {
                    throw EngineException("The loaded model cannot hear audio (no audio projector)")
                }
            }
        }

        private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
    }
}
