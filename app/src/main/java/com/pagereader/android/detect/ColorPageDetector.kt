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
 *     paperness = 128 * L / illumination(L) - CHROMA_WEIGHT * chroma
 *
 * A single Otsu threshold on that adapts to the scene automatically -- no fixed
 * brightness floor of the kind (`MIN_INTERIOR_BRIGHTNESS = 120`) that made v1
 * fail under dim light.
 *
 * The division is illumination normalisation, and it is load-bearing. Using raw
 * L, the phone's own shadow across a page put the three populations in this
 * order by lightness (measured on device 2026-09-05):
 *
 *     shadowed page L~95  <  carpet L~146  <  lit page L~214
 *
 * The background sits *between* the two halves of the page, so no global split
 * point can separate them at any threshold -- Otsu at 119 kept the carpet inside
 * the box and discarded the shadowed third of the sheet. Dividing L by a heavily
 * blurred copy of itself removes the illumination gradient and collapses the
 * page back into one population. Weighting chroma harder was tried as an
 * alternative and does not work: the shadow costs ~119 units of L while the
 * page-vs-carpet chroma gap is ~5, so CHROMA_WEIGHT would have to reach ~12 to
 * invert the ordering, by which point everything clamps to zero.
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
        val illumination = Mat()
        val normalised = Mat()
        val weighted = Mat()
        try {
            channels[0].convertTo(lightness, CvType.CV_32F)
            // a and b are stored unsigned with the neutral axis at 128; recentre
            // so distance from neutral is just the magnitude.
            channels[1].convertTo(aSigned, CvType.CV_32F, 1.0, -NEUTRAL)
            channels[2].convertTo(bSigned, CvType.CV_32F, 1.0, -NEUTRAL)

            estimateIllumination(lightness, illumination)
            // +1 so a near-black region divides by 1 rather than by 0 and
            // amplifies its own sensor noise into a bright patch of "paper".
            Core.add(illumination, Scalar(1.0), illumination)
            Core.divide(lightness, illumination, normalised, NORMALISED_MID)

            Core.magnitude(aSigned, bSigned, chroma)
            Core.multiply(chroma, Scalar(CHROMA_WEIGHT), weighted)
            Core.subtract(normalised, weighted, weighted)

            // Saturating cast: strongly coloured pixels clamp at 0 rather than
            // wrapping around into "very paper-like".
            weighted.convertTo(out, CvType.CV_8U)
        } finally {
            channels.forEach { it.release() }
            lightness.release()
            aSigned.release()
            bSigned.release()
            chroma.release()
            illumination.release()
            normalised.release()
            weighted.release()
        }
    }

    /**
     * Writes a smooth estimate of the light falling on the scene into [out].
     *
     * A Gaussian this wide carries no detail worth resolving at full
     * resolution, so it is computed on a 1/8 scale copy and stretched back.
     * That is not an approximation traded for speed -- it scored identically to
     * the full-resolution blur to three decimal places on all three shadowed
     * fixtures -- but the full-resolution version costs **249 ms per frame**
     * against this detector's 17-18 ms budget, and this one costs **2.1 ms**.
     * Measured on the JSC-AL50, 2026-09-05.
     */
    private fun estimateIllumination(lightness: Mat, out: Mat) {
        val tiny = Mat()
        try {
            Imgproc.resize(
                lightness, tiny,
                Size(
                    (lightness.width() / ILLUM_DOWNSCALE).toDouble(),
                    (lightness.height() / ILLUM_DOWNSCALE).toDouble(),
                ),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            Imgproc.GaussianBlur(tiny, tiny, Size(0.0, 0.0), ILLUM_SIGMA / ILLUM_DOWNSCALE)
            Imgproc.resize(tiny, out, lightness.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        } finally {
            tiny.release()
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
         *
         * Raising it was swept against the shadowed fixtures (2, 4, 6, 8, 12)
         * and is not the knob: with illumination normalisation in place, 2 and
         * 4 are indistinguishable at 0.935 IoU and everything from 6 up is
         * worse, reaching total failure at 12 where coloured pixels clamp.
         */
        private const val CHROMA_WEIGHT = 2.0

        /**
         * Width of the illumination estimate, in pixels of the 640x480
         * processing frame.
         *
         * It has to be wide enough not to track the page itself -- a blur
         * narrower than the sheet normalises the page-against-background
         * contrast away along with the shadow. 51 is roughly an eighth of the
         * frame width and comfortably below a page filling it. Wider is not
         * better: sigma 121 scored 0.922 against 0.935, because it starts
         * averaging the page and the background together again.
         */
        private const val ILLUM_SIGMA = 51.0

        /**
         * Scale factor for computing the illumination estimate. See
         * [estimateIllumination] -- 8 turns a 249 ms blur into a 2.1 ms one
         * with no measurable change in the fitted quad.
         */
        private const val ILLUM_DOWNSCALE = 8

        /**
         * Lightness that a pixel matching its own local illumination maps to.
         * Mid-range, so the chroma penalty below has room to push a coloured
         * pixel down without clamping at zero.
         */
        private const val NORMALISED_MID = 128.0

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
