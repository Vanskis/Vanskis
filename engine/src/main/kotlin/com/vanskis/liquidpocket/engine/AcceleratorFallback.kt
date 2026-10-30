package com.vanskis.liquidpocket.engine

/**
 * Loads on the requested [Accelerator], falling back to the CPU when the NPU cannot be used: the
 * build does not ship it, its backend does not start ([initNpu] returns a reason), or loading the
 * model on it fails (native failures are [IllegalStateException]s), or it crashed the previous
 * process. A CPU failure is not caught.
 */
internal object AcceleratorFallback {
    class Outcome<T>(val value: T, val accelerator: Accelerator, val note: String?)

    const val NO_NPU_BUILD = "This build has no NPU support"
    const val NOT_IN_BUILD = "$NO_NPU_BUILD; using the CPU"
    const val CRASHED = "The NPU crashed last time; using the CPU"

    /** [npuCrashed]: the NPU took the previous process down ([NpuCrashGuard]), so it is not tried again. */
    fun <T> load(
        requested: Accelerator,
        npuPackaged: Boolean,
        initNpu: () -> String?,
        npuCrashed: Boolean = false,
        load: (Accelerator) -> T,
    ): Outcome<T> {
        if (requested == Accelerator.CPU) return Outcome(load(Accelerator.CPU), Accelerator.CPU, null)
        if (!npuPackaged) return cpu(load, NOT_IN_BUILD)
        if (npuCrashed) return cpu(load, CRASHED)
        initNpu()?.let { return cpu(load, "NPU unavailable ($it); using the CPU") }
        val value = try {
            load(Accelerator.NPU)
        } catch (e: IllegalStateException) {
            return cpu(load, "The NPU could not run the model (${e.message}); using the CPU")
        }
        return Outcome(value, Accelerator.NPU, null)
    }

    private fun <T> cpu(load: (Accelerator) -> T, note: String) =
        Outcome(load(Accelerator.CPU), Accelerator.CPU, note)
}
