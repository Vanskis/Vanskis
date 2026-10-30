package com.vanskis.liquidpocket.ui

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vanskis.liquidpocket.chat.LiveCamera

/**
 * The live voice camera through [lens], bound to the screen's lifecycle. While it shows, [onFrameSource]
 * holds a function that grabs its current frame (main thread), so a turn can take what the user sees.
 */
@Composable
fun LiveCameraPreview(lens: LiveCamera, onFrameSource: ((() -> Bitmap?)?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView, so the rounded clip applies and the frame can be read back.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(lens, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var gone = false
        future.addListener({
            if (gone) return@addListener
            val camera = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            provider = camera
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val selector = if (lens == LiveCamera.BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                camera.unbindAll()
                camera.bindToLifecycle(owner, selector, preview)
            } catch (e: Exception) {
                // No camera on that side, or it is in use: the preview stays dark and no frame is sent.
            }
        }, ContextCompat.getMainExecutor(context))
        onFrameSource { view.bitmap }
        onDispose {
            gone = true
            onFrameSource(null)
            provider?.unbindAll()
        }
    }
}
