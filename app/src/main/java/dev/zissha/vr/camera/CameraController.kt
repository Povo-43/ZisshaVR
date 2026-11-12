package dev.zissha.vr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat
import dev.zissha.vr.core.AppLog
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX integration (spec 2/6.1). Renders into a SurfaceTexture owned by the GL renderer.
 * Resolution, lens and frame rate are configurable; the actual buffer size is reported back
 * so the compositor can size its FBOs.
 */class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    companion object {
        private const val TAG = "CameraController"
    }

    data class BoundCamera(
        val sensorOrientation: Int,
        val isFront: Boolean,
        val bufferWidth: Int,
        val bufferHeight: Int,
    )

    var onBound: ((BoundCamera) -> Unit)? = null

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var surfaceRequest: SurfaceRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(context) }

    private var surfaceTexture: SurfaceTexture? = null

    /** The SurfaceTexture created by the GL renderer; must be set before [start]. */
    fun attachSurfaceTexture(st: SurfaceTexture) {
        surfaceTexture = st
    }

    suspend fun start(front: Boolean, requestWidth: Int, requestHeight: Int, fps: Int) {
        val cameraProvider = provider ?: awaitProcessCameraProvider(context).also { provider = it }
        stopPreviewOnly()

        val facing = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        val sensorOrientation = readSensorOrientation(facing) ?: 90

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(requestWidth, requestHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val st = surfaceTexture ?: throw IllegalStateException("GL surface texture not attached")
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(Surface.ROTATION_0)
        if (fps in 15..60) {
            runCatching { previewBuilder.setTargetFrameRate(Range(fps, fps)) }
        }
        val newPreview = previewBuilder.build()
        newPreview.setSurfaceProvider(mainExecutor) { request ->
            surfaceRequest = request
            val surface = Surface(st)
            request.provideSurface(surface, mainExecutor) { result ->
                Log.d(TAG, "surface result code ${result.resultCode}")
                surface.release()
            }
            request.resolution?.let { res ->
                onBound?.invoke(
                    BoundCamera(sensorOrientation, front, res.width, res.height),
                )
            }
        }

        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, newPreview)
        } catch (e: IllegalArgumentException) {
            // exact frame rate may not be supported: retry without it
            AppLog.w("camera bind with fps=$fps failed (${e.message}); retrying without fps constraint")
            val retry = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            retry.setSurfaceProvider(mainExecutor) { request ->
                surfaceRequest = request
                val surface = Surface(st)
                request.provideSurface(surface, mainExecutor) { result ->
                    surface.release()
                }
                request.resolution?.let { res ->
                    onBound?.invoke(BoundCamera(sensorOrientation, front, res.width, res.height))
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, retry)
            preview = retry
            return
        }
        preview = newPreview
        AppLog.i("camera bound: front=$front request=${requestWidth}x${requestHeight} fps=$fps sensor=$sensorOrientation")
    }

    fun stop() {
        mainHandler.post {
            stopPreviewOnly()
            provider?.unbindAll()
        }
    }

    private fun stopPreviewOnly() {
        preview?.let { p ->
            runCatching { provider?.unbind(p) }
        }
        preview = null
    }

    private fun readSensorOrientation(facing: Int): Int? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val lens = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lens == facing) {
                return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            }
        }
        return null
    }
}

/** awaitInstance helper for the Guava ListenableFuture returned by ProcessCameraProvider. */
private suspend fun awaitProcessCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        cont.invokeOnCancellation { future.cancel(false) }
    }
