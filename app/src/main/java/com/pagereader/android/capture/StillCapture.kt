package com.pagereader.android.capture

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs

/**
 * Turns a captured [ImageProxy] into an upright BGR [Mat] in space **S**.
 *
 * `ImageCapture` hands back a JPEG in a single plane, not the YUV_420_888 the
 * analysis path decodes -- so this is a different conversion, not a reuse of
 * [com.pagereader.android.camera.CameraManager]'s.
 *
 * Rotation is applied *here*, at the boundary the still enters the pipeline.
 * Everything downstream -- edge re-detection, dewarp, OCR -- assumes upright
 * input, and the reference project shipped a bug from exactly this class of
 * mistake: an unrotated image silently misaligned every bounding box.
 */
object StillCapture {

    /** Nothing is retained; the caller owns the returned Mat and must release it. */
    fun toUprightBgr(proxy: ImageProxy): Mat? {
        val jpeg = readJpegBytes(proxy) ?: return null

        val encoded = MatOfByte(*jpeg)
        val decoded = try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
        if (decoded == null || decoded.empty()) {
            decoded?.release()
            return null
        }

        // rotate() can throw, and if it does nothing else owns `decoded`.
        // CLAUDE.md's rule is every Mat, every path -- including the throwing one.
        val upright = try {
            rotate(decoded, proxy.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            decoded.release()
            throw t
        }
        if (upright !== decoded) decoded.release()
        return upright
    }

    private fun readJpegBytes(proxy: ImageProxy): ByteArray? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return if (bytes.isEmpty()) null else bytes
    }

    /** Returns [mat] itself when no rotation is needed, so callers must identity-check. */
    private fun rotate(mat: Mat, degrees: Int): Mat {
        val code = when (degrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val out = Mat()
        Core.rotate(mat, out, code)
        return out
    }

    /** Visible for tests: builds a BGR Mat the same shape the pipeline expects. */
    fun blankLike(width: Int, height: Int): Mat = Mat(height, width, CvType.CV_8UC3)
}
