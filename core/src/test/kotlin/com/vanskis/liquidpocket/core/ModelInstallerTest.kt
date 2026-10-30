package com.vanskis.liquidpocket.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections

/** End-to-end against a local server that mimics the HF tree API, the LFS redirect and Range. */
class ModelInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val projContent = ByteArray(120_000) { (it * 17 % 241).toByte() }
    private var advertisedSha = sha(content)
    private val projSha = sha(projContent)
    private var honourRange = true
    private val rangeHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val projRangeHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val spec = ModelCatalog.LFM3_OMNI_2B

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/models/${spec.repo}/tree/main") { ex ->
            val body = """[{"type":"file","path":"mmproj-x-BF16.gguf","size":10,"lfs":{"oid":"00","size":10}},
                {"type":"file","path":"mmproj-x-Q8_0.gguf","size":134,"lfs":{"oid":"$projSha","size":${projContent.size}}},
                {"type":"file","path":"LFM3-Omni-2B-Q4_K_M.gguf","size":134,"lfs":{"oid":"$advertisedSha","size":${content.size}}}]"""
                .toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/${spec.repo}/resolve/main/") { ex ->
            ex.responseHeaders.add("Location", "/cdn/" + ex.requestURI.path.substringAfterLast('/'))
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.createContext("/cdn/") { ex ->
            val isProj = ex.requestURI.path.contains("mmproj")
            val data = if (isProj) projContent else content
            val range = ex.requestHeaders.getFirst("Range")
            (if (isProj) projRangeHeaders else rangeHeaders) += range
            val start = range?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (start != null) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, (data.size - start).toLong())
                ex.responseBody.use { it.write(data, start, data.size - start) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun installer(dir: File) = ModelInstaller(dir, HttpFetcher(), base)

    @Test fun freshInstallFollowsRedirectAndVerifies() {
        val dir = tmp.newFolder()
        val f = installer(dir).install(spec)
        assertEquals("LFM3-Omni-2B-Q4_K_M.gguf", f.name)
        assertArrayEquals(content, f.readBytes())
        assertEquals(f, installer(dir).installedFile(spec))
        assertEquals(listOf<String?>(null), rangeHeaders)
        assertFalse(File(dir, f.name + ".part").exists())
        assertArrayEquals(projContent, installer(dir).installedMmproj(spec)!!.readBytes())
        assertEquals("mmproj-x-Q8_0.gguf", installer(dir).installedMmproj(spec)!!.name)
    }

    @Test fun installFetchesBothFilesWithCombinedProgress() {
        val dir = tmp.newFolder()
        val events = mutableListOf<InstallProgress>()
        installer(dir).install(spec) { events += it }
        val total = (content.size + projContent.size).toLong()
        val downloads = events.filterIsInstance<InstallProgress.Downloading>()
        assertTrue(downloads.all { it.total == total })
        assertEquals(total, downloads.last().bytes)
        assertEquals(downloads.map { it.bytes }, downloads.map { it.bytes }.sorted())
        assertTrue(downloads.any { it.bytes in (content.size + 1) until total.toInt() })
        assertEquals(total, events.filterIsInstance<InstallProgress.Verifying>().last().bytes)
        assertEquals(listOf<String?>(null), projRangeHeaders)
    }

    @Test fun resumesPartialMmproj() {
        val dir = tmp.newFolder()
        val inst = installer(dir)
        inst.resolve(spec)
        File(dir, "mmproj-x-Q8_0.gguf.part").writeBytes(projContent.copyOf(5000))
        File(dir, "mmproj-x-Q8_0.gguf.part.target").writeText(projSha)
        assertEquals(5000L, inst.partialBytes(spec))
        inst.install(spec)
        assertEquals(listOf<String?>("bytes=5000-"), projRangeHeaders)
        assertArrayEquals(projContent, inst.installedMmproj(spec)!!.readBytes())
    }

    @Test fun notInstalledWhenMmprojMissing() {
        val dir = tmp.newFolder()
        val inst = installer(dir)
        val model = inst.install(spec)
        File(dir, "mmproj-x-Q8_0.gguf").delete()
        assertTrue(model.isFile)
        assertNull(inst.installedFile(spec))
        assertNull(inst.installedMmproj(spec))
        inst.install(spec)
        assertEquals(model, inst.installedFile(spec))
    }

    @Test fun oldResolvedWithoutMmprojIsReResolved() {
        val dir = tmp.newFolder()
        File(dir, "${spec.id}.resolved").writeText("LFM3-Omni-2B-Q4_K_M.gguf\n${content.size}\n$advertisedSha\n")
        File(dir, "LFM3-Omni-2B-Q4_K_M.gguf").writeBytes(content)
        val inst = installer(dir)
        assertNull(inst.installedFile(spec))
        assertNull(inst.installedMmproj(spec))
        inst.install(spec)
        assertEquals(listOf<String?>(null), projRangeHeaders)
        assertTrue(rangeHeaders.isEmpty())
        assertNotNull(inst.installedFile(spec))
        assertNotNull(inst.installedMmproj(spec))
    }

    @Test fun fallbackQuantIsUsedWhenTheFirstIsMissing() {
        val s = spec.copy(quant = "IQ2_XS", fallbackQuants = listOf("Q5_K_M", "Q4_K_M"))
        assertEquals("LFM3-Omni-2B-Q4_K_M.gguf", installer(tmp.newFolder()).resolve(s).path)
    }

    @Test fun modelWithoutProjectorIgnoresMmproj() {
        val s = spec.copy(mmprojQuants = null)
        val dir = tmp.newFolder()
        val inst = installer(dir)
        inst.install(s)
        assertNull(inst.installedMmproj(s))
        assertEquals(listOf("LFM3-Omni-2B-Q4_K_M.gguf", "${s.id}.resolved").sorted(), dir.list()!!.sorted())
    }

    @Test fun resumesAfterInterruptionWithRangeHeader() {
        val dir = tmp.newFolder()
        var seen = 0L
        try {
            installer(dir).install(spec, isCancelled = { seen > 100_000 }) { p ->
                if (p is InstallProgress.Downloading) seen = p.bytes
            }
            fail("expected cancellation")
        } catch (_: DownloadCancelledException) { }
        val partial = installer(dir).partialBytes(spec)
        assertTrue(partial in 1 until content.size)

        // A new installer instance stands in for an app restart.
        val f = installer(dir).install(spec)
        assertArrayEquals(content, f.readBytes())
        assertEquals("bytes=$partial-", rangeHeaders.last())
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        val dir = tmp.newFolder()
        val inst = installer(dir)
        val r = inst.resolve(spec)
        File(dir, "${r.path}.part").writeBytes(content.copyOf(1000))
        File(dir, "${r.path}.part.target").writeText(advertisedSha)
        honourRange = false
        assertArrayEquals(content, inst.install(spec).readBytes())
        assertEquals("bytes=1000-", rangeHeaders.single())
    }

    @Test fun partialForADifferentFileIsDiscarded() {
        val dir = tmp.newFolder()
        val inst = installer(dir)
        val r = inst.resolve(spec)
        File(dir, "${r.path}.part").writeBytes(ByteArray(1000))
        File(dir, "${r.path}.part.target").writeText("deadbeef")
        assertArrayEquals(content, inst.install(spec).readBytes())
        assertEquals(listOf<String?>(null), rangeHeaders)
    }

    @Test fun checksumMismatchDeletesDownload() {
        advertisedSha = "0".repeat(64)
        val dir = tmp.newFolder()
        try {
            installer(dir).install(spec); fail("expected mismatch")
        } catch (_: ChecksumMismatchException) { }
        assertNull(installer(dir).installedFile(spec))
        assertEquals(0L, installer(dir).partialBytes(spec))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun deleteRemovesEverything() {
        val dir = tmp.newFolder()
        installer(dir).install(spec)
        installer(dir).delete(spec)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
