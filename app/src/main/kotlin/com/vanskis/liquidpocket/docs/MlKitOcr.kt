package com.vanskis.liquidpocket.docs

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * On-device OCR with ML Kit's Latin text recognizer, bundled in the APK (no download, works offline).
 * The only file that touches ML Kit. Close it when extraction is done.
 */
class MlKitOcr : PageOcr, Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Listeners run on ML Kit's thread rather than hopping to the main thread. */
    private val direct = Executor { it.run() }

    // Not cancellable on purpose: ML Kit cannot stop a running recognition, and the caller recycles the
    // bitmap as soon as this returns, so it must wait for ML Kit to be done with it (well under a second).
    override suspend fun recognize(bitmap: Bitmap): String = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(direct) { result -> cont.resume(result.text) }
            .addOnFailureListener(direct) { e -> cont.resumeWithException(e) }
    }

    override fun close() = recognizer.close()
}
