package com.vanskis.liquidpocket.ui.components

/**
 * When each piece of a streaming reply arrived, so it can fade in instead of appearing at once.
 *
 * A reply is laid out as blocks (paragraphs), each known by a stable [key]; [saw] records each
 * block's text length as it grows, and [fading] lists the pieces still fading in at a given time,
 * with how opaque each is (0 when it just arrived, 1 after [durationNanos]). A block whose text
 * got shorter (Markdown that changed meaning as more arrived) drops the pieces past its end.
 */
class FadeTimeline(private val durationNanos: Long = DEFAULT_DURATION_NANOS) {
    data class Piece(val start: Int, val end: Int, val alpha: Float)

    private class Arrival(val start: Int, val end: Int, val at: Long)

    private val lengths = HashMap<Int, Int>()
    private val arrivals = HashMap<Int, ArrayDeque<Arrival>>()

    fun saw(key: Int, length: Int, now: Long) {
        val last = lengths[key] ?: 0
        val pending = arrivals.getOrPut(key) { ArrayDeque() }
        when {
            length > last -> pending.addLast(Arrival(last, length, now))
            length < last -> {
                pending.removeAll { it.start >= length }
                if (pending.isNotEmpty() && pending.last().end > length) {
                    val cut = pending.removeLast()
                    pending.addLast(Arrival(cut.start, length, cut.at))
                }
            }
        }
        lengths[key] = length
    }

    fun fading(key: Int, now: Long): List<Piece> {
        val pending = arrivals[key] ?: return emptyList()
        while (pending.isNotEmpty() && now - pending.first().at >= durationNanos) pending.removeFirst()
        return pending.map { Piece(it.start, it.end, ((now - it.at).toFloat() / durationNanos).coerceIn(0f, 1f)) }
    }

    companion object {
        const val DEFAULT_DURATION_NANOS = 300_000_000L
    }
}
