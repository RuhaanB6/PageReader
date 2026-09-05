package com.pagereader.android.dewarp

import android.util.Log
import com.pagereader.android.detect.MaskToQuad
import com.pagereader.android.detect.PageDetector
import com.pagereader.android.detect.Pt
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Flattens the captured still into a head-on view of the page.
 *
 * Two properties matter more than the geometry, which is textbook.
 *
 * **It re-detects rather than reusing the guidance quad.** The analysis stream
 * and the still have different crops, are captured at different moments, and
 * the hand moves between them, so a P-space quad does not describe S. The
 * handoff states the rule as "P -> S must never be constructed".
 *
 * **It never dead-ends.** A blind user cannot see that dewarping failed and
 * retry; silence or a blank result is indistinguishable from a crash. Every
 * failure path here returns the full frame with [DewarpResult.applied] false,
 * so the pipeline downstream always has something to read.
 */
class PageDewarper(private val detector: PageDetector) {

    /**
     * @param still full-resolution upright BGR frame (**S**). Not released.
     * @return the page in **G**, which the caller owns and must release.
     */
    fun dewarp(still: Mat): DewarpResult {
        if (still.empty() || still.width() < 2 || still.height() < 2) {
            Log.w(TAG, "still is empty; nothing to dewarp")
            return DewarpResult(still.clone(), applied = false, homography = null)
        }

        val quad = detectOnStill(still)
            ?: return DewarpResult(still.clone(), applied = false, homography = null).also {
                Log.w(TAG, "no quad on the still; reading the whole photo")
            }

        return warp(still, quad)
    }

    /**
     * Runs the detector on a downscaled copy and maps the quad back up.
     *
     * Detection happens at [WORKING_WIDTH] because the detector's own stages
     * resize internally anyway and a 4000 px still would be scaled down twice
     * for nothing. The quad is scaled back to full resolution, so the warp
     * samples every pixel the sensor captured.
     */
    private fun detectOnStill(still: Mat): List<Point>? {
        val scale = WORKING_WIDTH.toDouble() / still.width()
        // Never upscale to detect: a still narrower than the working width is
        // already easier, and enlarging it would only invent detail.
        val work = Mat()
        try {
            if (scale < 1.0) {
                Imgproc.resize(
                    still, work,
                    Size(WORKING_WIDTH.toDouble(), (still.height() * scale).roundToInt().toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
            } else {
                still.copyTo(work)
            }

            val observed = detector.detect(work).quad ?: return null
            val back = if (scale < 1.0) 1.0 / scale else 1.0
            val scaled = observed.map { Point(it.x * back, it.y * back) }

            // The detector orders corners already, but it is cheap insurance:
            // a quad in the wrong rotation produces a silently sideways page,
            // which OCR reports as garbage rather than as an error.
            val ordered = MaskToQuad.orderCorners(scaled)
            return if (isDegenerate(ordered, still)) {
                Log.w(TAG, "quad is degenerate; reading the whole photo")
                null
            } else {
                ordered
            }
        } finally {
            work.release()
        }
    }

    /**
     * Rejects quads that would warp into nonsense.
     *
     * `getPerspectiveTransform` is happy to build a homography from collapsed
     * or self-intersecting corners, and `warpPerspective` will then produce a
     * smear that OCR reads as garbage rather than as a failure. Cheaper to
     * refuse and read the whole photo.
     */
    private fun isDegenerate(q: List<Point>, still: Mat): Boolean {
        if (q.size != 4) return true
        if (q.distinct().size != 4) return true
        val w = edgeLengths(q)
        if (w.any { it < MIN_EDGE_PX }) return true
        // Shoelace area against the frame: a quad covering almost nothing is a
        // collapsed fit, not a page.
        var area = 0.0
        for (i in q.indices) {
            val a = q[i]
            val b = q[(i + 1) % q.size]
            area += a.x * b.y - b.x * a.y
        }
        area = kotlin.math.abs(area) / 2.0
        return area < MIN_AREA_FRACTION * still.width() * still.height()
    }

    private fun edgeLengths(q: List<Point>): List<Double> =
        q.indices.map { hypot(q[(it + 1) % q.size].x - q[it].x, q[(it + 1) % q.size].y - q[it].y) }

    private fun warp(still: Mat, q: List<Point>): DewarpResult {
        val (tl, tr, br, bl) = listOf(q[0], q[1], q[2], q[3])

        // Take the longer of each opposing pair: under perspective the near
        // edge is the longer one and the far edge is foreshortened, so the
        // maximum is the one that has not lost detail. Using the mean would
        // shrink the page and throw away resolution the sensor did capture.
        var w = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y))
        var h = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y))
        if (w < 1.0 || h < 1.0) {
            Log.w(TAG, "degenerate output size; reading the whole photo")
            return DewarpResult(still.clone(), applied = false, homography = null)
        }

        // Scale the whole page rather than either side alone, so the aspect
        // ratio the corners imply survives. Clamp down first, then up: a page
        // can be both larger than the cap and, after clamping, still be fine,
        // but the minimum must win because it is an OCR requirement, not a
        // preference -- Tesseract collapses at low resolution (56 words against
        // EasyOCR's 338 on a 680 px thumbnail), so a small page is upscaled
        // even though that invents no detail. It gives the recogniser the pixel
        // density it needs to segment characters at all.
        val long = max(w, h)
        var factor = 1.0
        if (long > MAX_LONG_SIDE) factor = MAX_LONG_SIDE / long
        if (long * factor < MIN_LONG_SIDE) factor = MIN_LONG_SIDE / long
        w *= factor
        h *= factor

        val outW = w.roundToInt().coerceAtLeast(1)
        val outH = h.roundToInt().coerceAtLeast(1)

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val homography = Imgproc.getPerspectiveTransform(src, dst)
        src.release()
        dst.release()

        val out = Mat()
        // INTER_CUBIC because the common case is an upscale to clear the OCR
        // minimum, where bilinear visibly softens character edges.
        Imgproc.warpPerspective(still, out, homography, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC)

        Log.i(TAG, "dewarped ${still.width()}x${still.height()} -> ${outW}x$outH (factor %.2f)"
            .format(factor))
        return DewarpResult(out, applied = true, homography = homography)
    }

    companion object {
        private const val TAG = "PageDewarper"

        /** Detection working width; the quad is mapped back to full resolution. */
        private const val WORKING_WIDTH = 1024

        /**
         * Long side is clamped to this. Beyond it the extra pixels cost OCR
         * time and memory without adding legibility.
         */
        private const val MAX_LONG_SIDE = 3000.0

        /**
         * Long side is upscaled to at least this, with INTER_CUBIC.
         *
         * **Load-bearing, not an optimisation.** Tesseract degrades much harder
         * than EasyOCR at low resolution -- measured at 56 words against 338 on
         * the same 680 px thumbnail -- so a page captured small must be enlarged
         * before OCR rather than passed through at native size.
         */
        private const val MIN_LONG_SIDE = 2000.0

        /** Shorter than this in the still and the "corner" is a fit collapse. */
        private const val MIN_EDGE_PX = 8.0

        /** A quad smaller than this fraction of the still is not a page. */
        private const val MIN_AREA_FRACTION = 0.02
    }
}

/**
 * The dewarped page and how it was produced.
 *
 * @property page the page in **G**. Owned by the caller, always non-empty.
 * @property applied false when this is the whole photo because no usable quad
 *   was found. The caller must say so aloud -- the user cannot see it.
 * @property homography the 3x3 S -> G transform, or null when [applied] is
 *   false. Kept so text boxes found in G can be mapped back to the still.
 */
data class DewarpResult(
    val page: Mat,
    val applied: Boolean,
    val homography: Mat?,
) {
    fun release() {
        page.release()
        homography?.release()
    }
}
