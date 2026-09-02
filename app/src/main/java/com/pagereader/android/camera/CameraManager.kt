package com.pagereader.android.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onEdgeResult: (EdgeResult) -> Unit
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun bindCamera(previewView: PreviewView) {
        // Stated explicitly rather than relying on the default: the debug
        // overlay's coordinate transform in MainActivity has to mirror this
        // exactly, and a silent default change would misalign it.
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

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

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bgr = yuvToBgrMat(imageProxy)
            val rotated = rotateMat(bgr, imageProxy.imageInfo.rotationDegrees)
            if (rotated !== bgr) bgr.release()

            val result = DocumentEdgeDetector.detect(rotated)
            rotated.release()

            mainHandler.post { onEdgeResult(result) }
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
}
