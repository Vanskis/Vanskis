package com.vanskis.liquidpocket.engine

/**
 * Inference thread count: all cores but two, between 2 and 6. On the Galaxy S26 Ultra (8 cores:
 * 2 prime + 6 performance) that is 6 threads, leaving two cores for the UI and the system.
 * More threads than fast cores make llama.cpp's synchronized matmuls wait on the slowest core.
 */
internal object ThreadPolicy {
    const val MIN_THREADS = 2
    const val MAX_THREADS = 6
    const val HEADROOM = 2

    fun threadsFor(cores: Int): Int = (cores - HEADROOM).coerceIn(MIN_THREADS, MAX_THREADS)
}
