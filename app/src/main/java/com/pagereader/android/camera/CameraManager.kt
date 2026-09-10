package com.pagereader.android.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pagereader.android.capture.StillCapture
import com.pagereader.android.detect.PageDetector
import com.pagereader.android.detect.PageObservation
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Drives the camera and runs [detector] over preview frames.
 *
 * Detection is deliberately rate-limited well below the camera's frame rate.
 * A person cannot act on framing corrections faster than a few times a second,
 * and v1's habit of re-deciding 30 times a second was a direct cause of its
 * speech being unusable -- so the pipeline runs at [MIN_INTERVAL_MS] and drops
 * everything in between.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val detector: PageDetector,
    /**
     * Called on the main thread. [preview] is a downscaled BGR copy of the
     * analysed frame and **ownership passes to the callback**, which must
     * release it.
     */
    private val onFrame: (observation: PageObservation, latencyMs: Long, preview: Mat) -> Unit
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastRunMs = 0L
    private var imageCapture: ImageCapture? = null

    // Kept so the degraded path can swap use cases around a shot.
    private var provider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

    /**
     * True when this device refused Preview + ImageAnalysis + ImageCapture
     * together, so ImageAnalysis has to be unbound for the duration of a shot.
     */
    private var swapForCapture = false

    /** Non-null while a shot is in flight. Main thread only. */
    private var inFlight: Runnable? = null

    /** True once the camera is bound and a still can actually be taken. */
    val isReady: Boolean get() = imageCapture != null

    fun bindCamera(previewView: PreviewView) {
        // Stated explicitly rather than relying on the default: the debug
        // overlay's coordinate transform in MainActivity has to mirror this
        // exactly, and a silent default change would misalign it.
        // FIT_CENTER, not FILL_CENTER: the analysis stream is 4:3 and the window
        // is ~9:20, so filling cropped ~20% of the frame width off each side of
        // the preview. What the detector sees and what the still captures is the
        // whole 4:3 frame, so the preview must show all of it -- letterboxed --
        // or the picture on screen disagrees with the picture being judged.
        // `MainActivity.FrameTransform` mirrors this exact layout math; change
        // one and you must change the other.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy -> processFrame(imageProxy) }
                }

            // MAXIMIZE_QUALITY, and no target resolution, so the still comes
            // back at sensor resolution. The dewarp needs the pixels: OCR wants
            // roughly 300 DPI across the page, and a page that filled only part
            // of the frame has to be upscaled to reach it.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            provider = cameraProvider
            previewUseCase = preview
            analysisUseCase = imageAnalysis
            imageCapture = capture

            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    capture
                )
                swapForCapture = false
            } catch (t: Throwable) {
                // Preview + ImageAnalysis + ImageCapture is a guaranteed CameraX
                // combination, but guaranteed by the spec is not the same as
                // supported by this vendor's HAL. Keep ImageCapture -- dropping
                // it would leave the app unable to photograph anything -- and
                // swap ImageAnalysis out for the duration of each shot instead.
                Log.w(TAG, "3 use cases rejected; will swap analysis out per shot", t)
                swapForCapture = true
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Takes a full-resolution still.
     *
     * [onResult] runs on the main thread and **owns the Mat**, which is upright
     * BGR in space S. [onError] carries a string already fit to speak, because
     * every failure here has to reach the user as words.
     */
    fun capture(onResult: (Mat) -> Unit, onFailure: (String) -> Unit) {
        if (inFlight != null) return
        val capture = imageCapture
        if (capture == null) {
            onFailure("This camera cannot take a photo.")
            return
        }
        if (swapForCapture && !bindForCapture()) {
            onFailure("The camera could not be prepared for a photo.")
            return
        }

        // A HAL that accepts takePicture and never calls back would leave the
        // shutter dead and the app silent forever -- the one failure this
        // product cannot have, because the user has no screen to check on it.
        // The watchdog turns a hang into a spoken failure.
        var settled = false
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            inFlight = null
            Log.e(TAG, "takePicture never called back")
            restoreAfterCapture()
            onFailure("The camera did not respond. Try again.")
        }
        inFlight = timeout
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)

        // Main thread. False when the watchdog already reported this shot.
        fun claim(): Boolean {
            if (settled) return false
            settled = true
            mainHandler.removeCallbacks(timeout)
            inFlight = null
            return true
        }

        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val mat = try {
                        StillCapture.toUprightBgr(image)
                    } catch (t: Throwable) {
                        Log.e(TAG, "decode failed", t)
                        null
                    } finally {
                        image.close()
                    }
                    mainHandler.post {
                        if (!claim()) {
                            // Watchdog already spoke; drop the late result
                            // instead of contradicting it.
                            mat?.release()
                            return@post
                        }
                        restoreAfterCapture()
                        if (mat == null) onFailure("The photo could not be read.")
                        else onResult(mat)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    mainHandler.post {
                        if (!claim()) return@post
                        restoreAfterCapture()
                        onFailure("The photo failed.")
                    }
                }
            }
        )
    }

    /** Degraded path only: trade ImageAnalysis for ImageCapture. Main thread. */
    private fun bindForCapture(): Boolean {
        val p = provider ?: return false
        val preview = previewUseCase ?: return false
        val capture = imageCapture ?: return false
        return try {
            analysisUseCase?.let { p.unbind(it) }
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not bind ImageCapture for the shot", t)
            restoreAfterCapture()
            false
        }
    }

    /**
     * Re-attaches the framing loop if a shot was interrupted while it was
     * swapped out. Called from onResume: pausing between bindForCapture and the
     * capture callback can otherwise leave ImageAnalysis unbound for good, and
     * guidance would be dead with nothing said about it.
     */
    fun recoverIfInterrupted() {
        if (!swapForCapture) return
        val p = provider ?: return
        val analysis = analysisUseCase ?: return
        if (p.isBound(analysis)) return
        Log.w(TAG, "framing loop was left unbound; re-attaching")
        inFlight?.let { mainHandler.removeCallbacks(it) }
        inFlight = null
        restoreAfterCapture()
    }

    /** Puts the framing loop back. Safe to call when no swap happened. */
    private fun restoreAfterCapture() {
        if (!swapForCapture) return
        val p = provider ?: return
        val preview = previewUseCase ?: return
        val analysis = analysisUseCase ?: return
        try {
            imageCapture?.let { p.unbind(it) }
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (t: Throwable) {
            Log.e(TAG, "could not restore the framing loop", t)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastRunMs < MIN_INTERVAL_MS) return
            lastRunMs = now

            val bgr = yuvToBgrMat(imageProxy)
            val rotated = rotateMat(bgr, imageProxy.imageInfo.rotationDegrees)
            if (rotated !== bgr) bgr.release()

            // detect() and resize() can both throw. Without this, every thrown
            // detection leaks a full-size Mat, and at ~6 frames a second that
            // is fatal in seconds.
            try {
                val t0 = System.nanoTime()
                val result = detector.detect(rotated, null)
                val latencyMs = (System.nanoTime() - t0) / 1_000_000

                // A small copy for the recorder, so a log line about a missed
                // page can be matched against the picture that produced it.
                val preview = Mat()
                Imgproc.resize(
                    rotated, preview,
                    Size(PREVIEW_WIDTH.toDouble(), PREVIEW_HEIGHT.toDouble())
                )
                mainHandler.post { onFrame(result, latencyMs, preview) }
            } finally {
                rotated.release()
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToBgrMat(imageProxy: ImageProxy): Mat {
        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBytes = extractPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height)
        val uBytes = extractPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2)
        val vBytes = extractPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2)

        val i420 = ByteArray(yBytes.size + uBytes.size + vBytes.size)
        System.arraycopy(yBytes, 0, i420, 0, yBytes.size)
        System.arraycopy(uBytes, 0, i420, yBytes.size, uBytes.size)
        System.arraycopy(vBytes, 0, i420, yBytes.size + uBytes.size, vBytes.size)

        val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuvMat.put(0, 0, i420)

        val bgrMat = Mat()
        Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_I420)
        yuvMat.release()

        return bgrMat
    }

    private fun extractPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val out = ByteArray(width * height)
        val duplicated = buffer.duplicate()
        val rowBytes = ByteArray(rowStride)
        var outOffset = 0

        for (row in 0 until height) {
            duplicated.position(row * rowStride)
            val bytesAvailable = min(rowStride, duplicated.remaining())
            duplicated.get(rowBytes, 0, bytesAvailable)

            if (pixelStride == 1) {
                System.arraycopy(rowBytes, 0, out, outOffset, width)
                outOffset += width
            } else {
                for (col in 0 until width) {
                    out[outOffset++] = rowBytes[col * pixelStride]
                }
            }
        }
        return out
    }

    private fun rotateMat(mat: Mat, rotationDegrees: Int): Mat {
        val rotateCode = when (rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val rotated = Mat()
        Core.rotate(mat, rotated, rotateCode)
        return rotated
    }

    companion object {
        /** ~6 detections per second. See the class comment. */
        private const val MIN_INTERVAL_MS = 150L

        private const val TAG = "CameraManager"

        /**
         * Generous on purpose: MAXIMIZE_QUALITY on a mid-range phone can take a
         * couple of seconds legitimately. This detects a hang; it is not a
         * latency budget.
         */
        private const val CAPTURE_TIMEOUT_MS = 8_000L

        private const val PREVIEW_WIDTH = 480
        private const val PREVIEW_HEIGHT = 360
    }
}
