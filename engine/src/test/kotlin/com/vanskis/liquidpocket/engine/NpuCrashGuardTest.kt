package com.vanskis.liquidpocket.engine

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuCrashGuardTest {
    private val dir = Files.createTempDirectory("lp-guard").toFile()
    private val marker = File(dir, "no_backup/npu-armed")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun firstRunHasNoCrash() {
        assertFalse(NpuCrashGuard(marker).crashedLastRun)
    }

    @Test
    fun armedWithoutDisarmIsACrashNextRun() {
        NpuCrashGuard(marker).arm()
        assertTrue(NpuCrashGuard(marker).crashedLastRun)
    }

    @Test
    fun aCrashIsReportedOnce() {
        NpuCrashGuard(marker).arm()
        NpuCrashGuard(marker)
        assertFalse(NpuCrashGuard(marker).crashedLastRun)
    }

    @Test
    fun disarmedIsNoCrash() {
        val guard = NpuCrashGuard(marker)
        guard.arm()
        guard.disarm()
        assertFalse(NpuCrashGuard(marker).crashedLastRun)
    }

    @Test
    fun engineReportsTheGuard() {
        NpuCrashGuard(marker).arm()
        assertTrue(LlamaEngine("", NpuCrashGuard(marker)).npuCrashedLastRun)
        assertFalse(LlamaEngine("", NpuCrashGuard(marker)).npuCrashedLastRun)
    }
}
