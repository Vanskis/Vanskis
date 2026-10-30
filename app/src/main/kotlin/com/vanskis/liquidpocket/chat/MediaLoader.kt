package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.ImageInput

/**
 * Turns attachment files into what the engine takes. The app decodes with Android
 * ([com.vanskis.liquidpocket.media.AndroidMediaLoader]); tests fake it. Calls block and throw when
 * a file cannot be read; [ChatController] makes them off the main thread.
 */
interface MediaLoader {
    /** The image at [path], upright and downscaled for the model. */
    fun image(path: String): ImageInput

    /** The clip at [path] as mono samples at [sampleRate]. */
    fun audio(path: String, sampleRate: Int): AudioInput

    companion object {
        /** For controllers that never get attachments. */
        val NONE: MediaLoader = object : MediaLoader {
            override fun image(path: String): ImageInput = throw UnsupportedOperationException("No media loader")
            override fun audio(path: String, sampleRate: Int): AudioInput = throw UnsupportedOperationException("No media loader")
        }
    }
}

/**
 * Keeps recently decoded media in memory, up to [maxBytes], least recently used out first. Every
 * reply resends the whole history, so without it each turn would decode every image again.
 */
class CachingMediaLoader(private val source: MediaLoader, private val maxBytes: Long = 48L shl 20) : MediaLoader {
    private val entries = LinkedHashMap<String, Any>(16, 0.75f, true)
    private var bytes = 0L

    override fun image(path: String): ImageInput = cached("image:$path") { source.image(path) }

    override fun audio(path: String, sampleRate: Int): AudioInput = cached("audio:$sampleRate:$path") { source.audio(path, sampleRate) }

    private inline fun <reified T : Any> cached(key: String, load: () -> T): T {
        synchronized(entries) { (entries[key] as? T)?.let { return it } }
        val value = load()
        synchronized(entries) {
            if (entries.put(key, value) == null) bytes += size(value)
            val it = entries.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == key) continue
                bytes -= size(eldest.value)
                it.remove()
            }
        }
        return value
    }

    private fun size(value: Any): Long = when (value) {
        is ImageInput -> value.rgb.size.toLong()
        is AudioInput -> value.samples.size * 4L
        else -> 0L
    }
}

/** What the model is told in place of media it cannot take or that is gone. */
object MediaNotes {
    const val IMAGE_UNSUPPORTED = "[The user attached an image, but the current model cannot see images.]"
    const val AUDIO_UNSUPPORTED = "[The user attached a voice message, but the current model cannot hear audio.]"
    const val IMAGE_MISSING = "[The user attached an image that is no longer available.]"
    const val AUDIO_MISSING = "[The user attached a voice message that is no longer available.]"
    const val DOCUMENT_MISSING = "[The user attached a document that is no longer available.]"
}
