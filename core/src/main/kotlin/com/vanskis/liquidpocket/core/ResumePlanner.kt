package com.vanskis.liquidpocket.core

/** How to start (or skip) a download given what is already on disk. */
sealed interface StartPlan {
    data object Fresh : StartPlan
    data class Resume(val offset: Long) : StartPlan
    data object AlreadyComplete : StartPlan
}

/** What to do with an HTTP response to a (possibly ranged) download request. */
sealed interface ResponseAction {
    /** Write the body after the existing [offset] bytes. */
    data class Append(val offset: Long) : ResponseAction
    /** The server sent the whole file: truncate the partial file and write from byte 0. */
    data object WriteFromStart : ResponseAction
    /** Nothing left to fetch. */
    data object Complete : ResponseAction
    /** The partial file cannot be continued: delete it and start over with a plain request. */
    data object Restart : ResponseAction
    data class Fail(val code: Int) : ResponseAction
}

object ResumePlanner {
    /**
     * @param partialBytes size of the `.part` file (0 if absent)
     * @param partialTarget the sha256 the `.part` file was started for (null if unknown)
     * @param target the sha256 of the file we want now (null if the repo listing gave none)
     */
    fun plan(partialBytes: Long, expectedSize: Long, partialTarget: String?, target: String?): StartPlan = when {
        partialBytes <= 0L -> StartPlan.Fresh
        !partialTarget.equals(target, ignoreCase = true) -> StartPlan.Fresh
        partialBytes > expectedSize -> StartPlan.Fresh
        partialBytes == expectedSize -> StartPlan.AlreadyComplete
        else -> StartPlan.Resume(partialBytes)
    }

    fun rangeHeader(offset: Long): String = "bytes=$offset-"

    fun onResponse(offset: Long, code: Int, contentRange: String?, expectedSize: Long): ResponseAction = when (code) {
        206 -> {
            val range = parseContentRange(contentRange)
            if (range != null && range.start == offset && (range.total == null || range.total == expectedSize)) {
                ResponseAction.Append(offset)
            } else {
                ResponseAction.Restart
            }
        }
        200 -> ResponseAction.WriteFromStart
        416 -> if (offset == expectedSize) ResponseAction.Complete else ResponseAction.Restart
        else -> ResponseAction.Fail(code)
    }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    /** Parses `bytes <start>-<end>/<total|*>`. */
    fun parseContentRange(header: String?): ContentRange? {
        val m = header?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
        val (start, end, total) = m.destructured
        return ContentRange(start.toLong(), end.toLong(), total.takeIf { it != "*" }?.toLong())
    }

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
}
