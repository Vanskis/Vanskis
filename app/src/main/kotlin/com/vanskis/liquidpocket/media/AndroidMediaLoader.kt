package com.vanskis.liquidpocket.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.vanskis.liquidpocket.chat.MediaLoader
import com.vanskis.liquidpocket.engine.AudioInput
import com.vanskis.liquidpocket.engine.ImageInput
import java.io.File
import java.io.IOException

/** Decodes attachment files with Android: images upright and at most [ImageScale.MAX_SIDE], WAV clips resampled. */
class AndroidMediaLoader : MediaLoader {

    override fun image(path: String): ImageInput {
        val upright = decodeUpright(path, ImageScale.MAX_SIDE)
        try {
            val pixels = IntArray(upright.width * upright.height)
            upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
            return ImageInput(upright.width, upright.height, ImageScale.argbToRgb(pixels))
        } finally {
            upright.recycle()
        }
    }

    override fun audio(path: String, sampleRate: Int): AudioInput {
        val clip = Wav.decode(File(path).readBytes())
        return AudioInput(Wav.resample(clip.samples, clip.sampleRate, sampleRate))
    }

    companion object {
        /** [path] decoded, scaled so its long side is at most [maxSide], and turned upright per its EXIF orientation. */
        fun decodeUpright(path: String, maxSide: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image: $path")
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageScale.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(path, options) ?: throw IOException("Could not decode $path")
            val orientation = ImageScale.orientation(
                runCatching {
                    ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL),
            )
            val (w, h) = ImageScale.fit(decoded.width, decoded.height, maxSide)
            // One pass scales, rotates and mirrors; skipped when there is nothing to do.
            if (w == decoded.width && h == decoded.height && orientation.degrees == 0 && !orientation.mirror) return decoded
            val matrix = Matrix().apply {
                setScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
                postRotate(orientation.degrees.toFloat())
                if (orientation.mirror) postScale(-1f, 1f)
            }
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (result !== decoded) decoded.recycle()
            return result
        }
    }
}
