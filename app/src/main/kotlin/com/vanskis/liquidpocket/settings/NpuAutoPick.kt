package com.vanskis.liquidpocket.settings

import android.content.Context
import android.os.BatteryManager
import com.vanskis.liquidpocket.engine.BenchResult
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Benchmarks the loaded model and keeps the accelerator that wins: the NPU only where
 * [BenchResult.npuWins]. Runs once quietly after the first model load, and on Run benchmark.
 */
class NpuAutoPick(context: Context, private val engine: Engine, private val settings: SettingsRepository) {
    private val battery = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val lock = Mutex()

    /** Benchmarks and stores the pick; null while another run is going. */
    suspend fun run(): BenchResult? {
        if (!lock.tryLock()) return null
        try {
            val result = withContext(Dispatchers.IO) { engine.benchmark(::chargeMicroAh) }
            if (engine.npuAvailable) settings.setUseNpu(result.npuWins)
            settings.setNpuBenchmarked()
            return result
        } finally {
            lock.unlock()
        }
    }

    /**
     * The quiet run, once per install, after the first model is ready and idle. Marked done before it
     * starts, so an NPU that takes the process down is not tried again on every launch.
     */
    suspend fun firstRun() {
        if (!engine.npuAvailable || settings.npuBenchmarked.first()) return
        engine.state.first { it is EngineState.Ready }
        delay(SETTLE_MS)
        if (engine.state.value !is EngineState.Ready) return
        settings.setNpuBenchmarked()
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stays on what the user had; Run benchmark in Settings shows the error.
        }
    }

    /** Remaining charge in µAh, null where the phone does not report it. */
    private fun chargeMicroAh(): Long? =
        battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE }

    private companion object {
        /** Lets a reply the user starts right after loading take the model first. */
        const val SETTLE_MS = 3_000L
    }
}
