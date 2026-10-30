package com.vanskis.liquidpocket.engine

import java.io.File

/**
 * Remembers across processes that the NPU took the app down. ggml-hexagon aborts the process on a
 * compute error, so nothing can catch it; instead [marker] is written before the NPU is started and
 * deleted once it has proven itself. A marker still there at the next start means the process died
 * on the NPU, which [crashedLastRun] reports (and the marker is removed, so it reports it once).
 */
internal class NpuCrashGuard(private val marker: File) {
    val crashedLastRun: Boolean = marker.exists().also { if (it) marker.delete() }

    /** Before the NPU backend is started or a model is loaded on it. */
    fun arm() {
        marker.parentFile?.mkdirs()
        marker.createNewFile()
    }

    /** Once the NPU ran a reply to the end, or the load did not end up on it. */
    fun disarm() {
        marker.delete()
    }
}
