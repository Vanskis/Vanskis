package com.vanskis.liquidpocket.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class DownloadCancelledException : IOException("Download cancelled")

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/**
 * Minimal HTTP client on [HttpURLConnection]. Redirects are followed by hand so the `Range`
 * header is re-sent on every hop (Hugging Face redirects LFS files to a CDN).
 */
class HttpFetcher(
    private val userAgent: String = "LiquidPocket/1.0",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun fetchText(url: String): String = open(url, emptyMap()).let { conn ->
        try {
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode, url)
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads [url] into [partFile], resuming from its current size when [plan] says so.
     * Returns once [partFile] holds [expectedSize] bytes. Throws [DownloadCancelledException]
     * when [isCancelled] turns true; the partial file is kept for a later resume.
     */
    fun download(
        url: String,
        partFile: File,
        expectedSize: Long,
        plan: StartPlan,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ) {
        var offset = when (plan) {
            StartPlan.AlreadyComplete -> return
            StartPlan.Fresh -> { partFile.delete(); 0L }
            is StartPlan.Resume -> plan.offset
        }
        var restarted = false
        while (true) {
            val headers = if (offset > 0) mapOf("Range" to ResumePlanner.rangeHeader(offset)) else emptyMap()
            val conn = open(url, headers)
            try {
                val action = ResumePlanner.onResponse(offset, conn.responseCode, conn.getHeaderField("Content-Range"), expectedSize)
                when (action) {
                    ResponseAction.Complete -> return
                    is ResponseAction.Fail -> throw HttpStatusException(action.code, url)
                    ResponseAction.Restart -> {
                        if (restarted) throw IOException("Server keeps rejecting the resume request")
                        restarted = true
                        partFile.delete()
                        offset = 0
                        continue
                    }
                    is ResponseAction.Append -> conn.inputStream.use { copy(it, partFile, append = true, offset, isCancelled, onProgress) }
                    ResponseAction.WriteFromStart -> conn.inputStream.use { copy(it, partFile, append = false, 0, isCancelled, onProgress) }
                }
            } finally {
                conn.disconnect()
            }
            val have = partFile.length()
            if (have == expectedSize) return
            if (have > expectedSize) { partFile.delete(); throw IOException("Downloaded more bytes than expected") }
            offset = have // connection dropped early: continue where it stopped
        }
    }

    private fun copy(
        input: InputStream, file: File, append: Boolean, start: Long,
        isCancelled: () -> Boolean, onProgress: (Long) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val buf = ByteArray(1 shl 16)
        var done = start
        FileOutputStream(file, append).use { out ->
            while (true) {
                if (isCancelled()) throw DownloadCancelledException()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                conn.disconnect()
                current = URI(current).resolve(location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private companion object { const val MAX_REDIRECTS = 10 }
}
