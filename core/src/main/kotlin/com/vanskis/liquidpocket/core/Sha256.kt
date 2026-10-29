package com.vanskis.liquidpocket.core

import java.io.File
import java.security.MessageDigest

object Sha256 {
    /** Hex sha256 of [file]; [onProgress] gets bytes hashed so far. */
    fun of(file: File, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedHex: String, onProgress: (Long) -> Unit = {}): Boolean =
        of(file, onProgress).equals(expectedHex, ignoreCase = true)
}
