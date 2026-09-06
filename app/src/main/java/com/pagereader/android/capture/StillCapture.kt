package com.pagereader.android.capture

import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

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

        // Cap the resolution before anything else touches it.
        //
        // The JSC-AL50 hands back 6912x9216 stills -- 63 megapixels, 191 MB as
        // a BGR Mat. Rotating copies it, the dewarp's fallback path clones it,
        // and Tesseract then allocates an ARGB bitmap of it at 255 MB. That
        // killed the process on the first real capture: WINDOW DIED, app
        // relaunched, no crash log. An emulator's synthetic camera produces
        // small frames, so nothing upstream could surface it.
        //
        // Nothing downstream wants this many pixels: the dewarp clamps its
        // output to 3000 px on the long side, so anything past ~4000 px of
        // source is discarded moments later anyway.
        val capped = capResolution(decoded)
        if (capped !== decoded) decoded.release()

        // rotate() can throw, and if it does nothing else owns `capped`.
        // CLAUDE.md's rule is every Mat, every path -- including the throwing one.
        val upright = try {
            rotate(capped, proxy.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            capped.release()
            throw t
        }
        if (upright !== capped) capped.release()
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
    /**
     * Scales [src] down so its long side is at most [MAX_LONG_SIDE].
     *
     * Returns [src] itself when it is already small enough, so the caller must
     * compare identities before releasing -- the same contract [rotate] uses.
     */
    @VisibleForTesting
    fun capResolution(src: Mat): Mat {
        val longSide = maxOf(src.width(), src.height())
        if (longSide <= MAX_LONG_SIDE) return src
        val scale = MAX_LONG_SIDE.toDouble() / longSide
        val out = Mat()
        // INTER_AREA is the right filter for shrinking; it averages rather
        // than sampling, so text stays legible instead of aliasing.
        Imgproc.resize(
            src, out,
            Size((src.width() * scale).roundToInt().toDouble(),
                (src.height() * scale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        return out
    }

    /**
     * Largest still we will carry, on the long side.
     *
     * Above the dewarp's own 3000 px output clamp with room for a perspective
     * correction to stretch an edge, and far below the 63 MP this phone
     * actually produces.
     */
    const val MAX_LONG_SIDE = 4000

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

