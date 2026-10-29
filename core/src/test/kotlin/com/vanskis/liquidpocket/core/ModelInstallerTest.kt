package com.vanskis.liquidpocket.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var advertisedSha = sha(content)
    private var honourRange = true
    private val rangeHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val spec = ModelCatalog.LFM3_OMNI_2B

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/models/${spec.repo}/tree/main") { ex ->
            val body = """[{"type":"file","path":"mmproj-x-Q4_K_M.gguf","size":10,"lfs":{"oid":"00","size":10}},
                {"type":"file","path":"LFM3-Omni-2B-Q4_K_M.gguf","size":134,"lfs":{"oid":"$advertisedSha","size":${content.size}}}]"""
                .toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/${spec.repo}/resolve/main/") { ex ->
            ex.responseHeaders.add("Location", "/cdn/blob")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.createContext("/cdn/blob") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            rangeHeaders += range
            val start = range?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (start != null) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                ex.sendResponseHeaders(206, (content.size - start).toLong())
                ex.responseBody.use { it.write(content, start, content.size - start) }
            } else {
                ex.sendResponseHeaders(200, content.size.toLong())
                ex.responseBody.use { it.write(content) }
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
