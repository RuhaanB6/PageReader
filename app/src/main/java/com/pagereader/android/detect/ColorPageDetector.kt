package com.pagereader.android.detect

import org.opencv.core.Core
import org.opencv.core.CvType
import androidx.annotation.VisibleForTesting
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Finds the page by colour rather than by gradient.
 *
 * v1 asked "is there a brightness step here?", which is why a white page on a
 * cream desk defeated it: there is no step to find. This asks a different
 * question -- "is this pixel *paper-like*?" -- and paper has a specific,
 * checkable signature in CIELAB: **bright and colourless**.
 *
 * Most surfaces that beat the old edge detector are bright but not colourless.
 * Pale wood, beige carpet, a cream tablecloth, a wooden desk under warm light
 * all carry real chroma. Paper sits near the a=b=128 neutral axis while they do
 * not, so the two separate cleanly on chroma even when their lightness is almost
 * identical.
 *
 * Rather than thresholding L and chroma independently and intersecting, both are
 * folded into one "paperness" channel:
 *
 *     paperness = L - CHROMA_WEIGHT * chroma
 *
 * A single Otsu threshold on that adapts to the scene automatically -- no fixed
 * brightness floor of the kind (`MIN_INTERIOR_BRIGHTNESS = 120`) that made v1
 * fail under dim light.
 */
class ColorPageDetector(
    private val processWidth: Int = PROCESS_WIDTH,
    private val processHeight: Int = PROCESS_HEIGHT,
) : PageDetector {

    /**
     * @param bgr full-resolution BGR frame. Not modified, not released.
     * @param roi optional restriction in [bgr] coordinates -- pass the neural
     *   detector's box to solve the much easier local problem.
     */
    /**
     * Debug-only: invoked with the thresholded mask on every [detect], before
     * corner fitting. Set by diagnostic tests to separate "the mask was wrong"
     * from "the mask was right and the quad fit was wrong". Never set in
     * production.
     */
    @VisibleForTesting
    var maskProbe: ((Mat) -> Unit)? = null

    override fun detect(bgr: Mat, roi: Rect?): PageObservation {
        val frameW = bgr.width()
        val frameH = bgr.height()
        if (frameW == 0 || frameH == 0) return PageObservation.none(frameW, frameH)

        val small = Mat()
        Imgproc.resize(bgr, small, Size(processWidth.toDouble(), processHeight.toDouble()))
        val scaleX = frameW.toDouble() / processWidth
        val scaleY = frameH.toDouble() / processHeight

        val paperness = Mat()
        val mask = Mat()
        try {
            buildPaperness(small, paperness)

            // Otsu picks the split point per frame, so the same code works on a
            // dim desk and in direct sun.
            val otsu = Imgproc.threshold(
                paperness, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )

            if (roi != null) {
                val scaled = Rect(
                    (roi.x / scaleX).toInt(), (roi.y / scaleY).toInt(),
                    (roi.width / scaleX).toInt(), (roi.height / scaleY).toInt()
                )
                val restricted = MaskToQuad.restrictTo(mask, scaled)
                mask.release()
                restricted.copyTo(mask)
                restricted.release()
            }

            val confidence = separationConfidence(paperness, mask, otsu)

            // Diagnostic seam. The mask and the quad fitted from it fail in
            // different ways and look identical from outside, so this hands the
            // binary mask to a test before MaskToQuad sees it. Null in
            // production; nothing is retained and the Mat stays owned here.
            maskProbe?.invoke(mask)

            return MaskToQuad.convert(
                mask = mask,
                scaleX = scaleX,
                scaleY = scaleY,
                frameWidth = frameW,
                frameHeight = frameH,
                confidence = confidence,
                source = ObservationSource.COLOR,
            )
        } finally {
            small.release()
            paperness.release()
            mask.release()
        }
    }

    /** Writes an 8-bit "how paper-like is this pixel" map into [out]. */
    private fun buildPaperness(smallBgr: Mat, out: Mat) {
        val lab = Mat()
        Imgproc.cvtColor(smallBgr, lab, Imgproc.COLOR_BGR2Lab)

        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        lab.release()

        val lightness = Mat()
        val aSigned = Mat()
        val bSigned = Mat()
        val chroma = Mat()
        val weighted = Mat()
        try {
            channels[0].convertTo(lightness, CvType.CV_32F)
            // a and b are stored unsigned with the neutral axis at 128; recentre
            // so distance from neutral is just the magnitude.
            channels[1].convertTo(aSigned, CvType.CV_32F, 1.0, -NEUTRAL)
            channels[2].convertTo(bSigned, CvType.CV_32F, 1.0, -NEUTRAL)

            Core.magnitude(aSigned, bSigned, chroma)
            Core.multiply(chroma, Scalar(CHROMA_WEIGHT), weighted)
            Core.subtract(lightness, weighted, weighted)

            // Saturating cast: strongly coloured pixels clamp at 0 rather than
            // wrapping around into "very paper-like".
            weighted.convertTo(out, CvType.CV_8U)
        } finally {
            channels.forEach { it.release() }
            lightness.release()
            aSigned.release()
            bSigned.release()
            chroma.release()
            weighted.release()
        }
    }

    /**
     * How confidently the two Otsu classes are actually separated, as the mean
     * paperness gap across the threshold.
     *
     * Otsu always returns *a* split, even for a uniform wall. Reporting the gap
     * lets guidance ignore a meaningless one instead of confidently steering the
     * user toward a patch of carpet -- v1 had no equivalent and would happily
     * announce "document found" on an interior text block.
     */
    private fun separationConfidence(paperness: Mat, mask: Mat, threshold: Double): Float {
        val total = (mask.width() * mask.height()).toDouble()
        if (total <= 0.0) return 0f

        // A near-empty class means Otsu found no real split -- it was handed a
        // blank wall and cut it somewhere arbitrary. `Core.mean` over an empty
        // mask returns 0 rather than NaN, so without this guard the gap comes
        // out enormous and a featureless scene reports full confidence. That is
        // exactly what happened on device: a uniform frame scored 1.00.
        val foreground = Core.countNonZero(mask) / total
        if (foreground < MIN_CLASS_FRACTION || foreground > 1.0 - MIN_CLASS_FRACTION) return 0f

        val inverse = Mat()
        try {
            Core.bitwise_not(mask, inverse)
            val above = Core.mean(paperness, mask).`val`[0]
            val below = Core.mean(paperness, inverse).`val`[0]
            if (above.isNaN() || below.isNaN()) return 0f
            val gap = (above - below) / 255.0
            return (gap / TARGET_SEPARATION).coerceIn(0.0, 1.0).toFloat()
        } finally {
            inverse.release()
        }
    }

    companion object {
        const val PROCESS_WIDTH = 640
        const val PROCESS_HEIGHT = 480

        /** CIELAB neutral point for the a and b channels in OpenCV's 8-bit encoding. */
        private const val NEUTRAL = 128.0

        /**
         * How hard chroma is penalised relative to lightness. At 2.0 a surface
         * needs to be roughly twice as bright to beat paper for every unit of
         * colour it carries -- enough to reject pale wood and beige fabric while
         * still admitting slightly warm-lit or off-white paper. Tune on device
         * against real light-on-light frames before changing.
         */
        private const val CHROMA_WEIGHT = 2.0

        /**
         * Paperness gap treated as full confidence.
         *
         * Was 0.25, which saturated: on the first real device session 127 of 128
         * frames clamped to exactly 1.00, so confidence carried no information
         * and the policy's `minConfidence` gate was inert -- it accepted a patch
         * of carpet as readily as a page. Otsu maximises inter-class variance by
         * construction, so *any* split it returns has a healthy gap; the number
         * has to sit near the top of the achievable range to discriminate.
         *
         * 0.60 then proved too harsh in the other direction: the light-on-light
         * case measured 0.28 on device, under the policy's 0.45 gate, so a
         * correctly-found page would have been ignored. Light-on-light genuinely
         * has a smaller gap -- that is what makes it hard -- so separation is not
         * the right knob for rejecting a patch of floor. Rectangularity in
         * MaskToQuad does that job; this stays moderate.
         */
        private const val TARGET_SEPARATION = 0.35

        /** Otsu classes smaller than this are treated as "no real split". */
        private const val MIN_CLASS_FRACTION = 0.02
    }
}
