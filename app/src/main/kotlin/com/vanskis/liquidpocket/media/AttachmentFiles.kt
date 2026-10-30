package com.vanskis.liquidpocket.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.vanskis.liquidpocket.chat.LiveCamera
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Attachment files in app-private storage (`filesDir/attachments`), and the camera's scratch file in
 * the cache, shared with the camera app through the FileProvider declared in the manifest.
 */
class AttachmentFiles(context: Context) {
    private val app = context.applicationContext
    private val dir: File get() = File(app.filesDir, "attachments").apply { mkdirs() }
    private val cameraDir: File get() = File(app.cacheDir, "camera").apply { mkdirs() }

    /** Copies a picked image into private storage; the original bytes, so the EXIF orientation is kept. Blocks. */
    fun importImage(uri: Uri): File {
        val target = File(dir, "img-${UUID.randomUUID()}")
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the image")
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return target
    }

    /** A fresh file for the camera to write into, and the content URI to hand it. */
    fun newCameraCapture(): Pair<File, Uri> {
        val file = File(cameraDir, "capture-${UUID.randomUUID()}.jpg")
        return file to FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    /** Moves a finished camera capture into private storage. Blocks. */
    fun importCapture(capture: File): File {
        val target = File(dir, "img-${UUID.randomUUID()}.jpg")
        if (!capture.renameTo(target)) {
            capture.copyTo(target)
            capture.delete()
        }
        return target
    }

    /** Writes a live camera frame as a JPEG, shrunk to [LiveCamera.FRAME_MAX_SIDE]. Blocks. */
    fun saveFrame(frame: Bitmap): File {
        val (w, h) = LiveCamera.fit(frame.width, frame.height)
        val scaled = if (w == frame.width && h == frame.height) frame else Bitmap.createScaledBitmap(frame, w, h, true)
        val target = File(dir, "img-${UUID.randomUUID()}.jpg")
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return target
    }

    /** Writes a recorded clip as a WAV. Blocks. */
    fun saveVoice(samples: FloatArray, sampleRate: Int): File =
        File(dir, "voice-${UUID.randomUUID()}.wav").apply { writeBytes(Wav.encode(samples, sampleRate)) }

    /** Deletes an attachment that was removed before it was sent. Only files in private storage. */
    fun delete(path: String) {
        val file = File(path)
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
    }
}
