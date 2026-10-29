package com.vanskis.liquidpocket.download

import com.vanskis.liquidpocket.core.InstallProgress
import com.vanskis.liquidpocket.download.WorkSnapshot.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {
    private fun work(phase: Phase, progress: InstallProgress? = null, error: String? = null) =
        WorkSnapshot(phase, progress, error)

    @Test fun `an installed model is ready whatever the work says`() {
        assertEquals(DownloadStatus.Ready, downloadStatus(true, 0, null))
        assertEquals(DownloadStatus.Ready, downloadStatus(true, 0, work(Phase.FAILED, error = "x")))
    }

    @Test fun `no work or cancelled work shows the partial download for a resume`() {
        assertEquals(DownloadStatus.NotDownloaded(0), downloadStatus(false, 0, null))
        assertEquals(DownloadStatus.NotDownloaded(500), downloadStatus(false, 500, work(Phase.CANCELLED)))
    }

    @Test fun `succeeded work without the file means it was deleted afterwards`() {
        assertEquals(DownloadStatus.NotDownloaded(0), downloadStatus(false, 0, work(Phase.SUCCEEDED)))
    }

    @Test fun `running work maps each install phase`() {
        assertEquals(DownloadStatus.Resolving, downloadStatus(false, 0, work(Phase.RUNNING)))
        assertEquals(
            DownloadStatus.Downloading(5, 10),
            downloadStatus(false, 0, work(Phase.RUNNING, InstallProgress.Downloading(5, 10))),
        )
        assertEquals(
            DownloadStatus.Verifying(7, 10),
            downloadStatus(false, 0, work(Phase.RUNNING, InstallProgress.Verifying(7, 10))),
        )
    }

    @Test fun `failed work carries its message`() {
        assertEquals(DownloadStatus.Failed("HTTP 404"), downloadStatus(false, 0, work(Phase.FAILED, error = "HTTP 404")))
        assertEquals(DownloadStatus.Failed("Download failed"), downloadStatus(false, 0, work(Phase.FAILED)))
    }

    @Test fun `only live work blocks download and delete`() {
        assertTrue(DownloadStatus.Waiting.active)
        assertTrue(DownloadStatus.Downloading(1, 2).active)
        assertFalse(DownloadStatus.Failed("x").active)
        assertFalse(DownloadStatus.Ready.active)
    }

    @Test fun `percent is whole and safe for an unknown total`() {
        assertEquals(0, percent(10, 0))
        assertEquals(33, percent(1, 3))
        assertEquals(100, percent(20, 10))
    }
}
