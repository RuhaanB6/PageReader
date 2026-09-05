package com.pagereader.android.detect

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Rect2d
import kotlin.math.roundToInt

/**
 * Finds the page with a neural detector, then refines its corners with colour.
 *
 * The split matters. Everything guidance needs -- which borders the page runs
 * off, how much of the frame it fills, where its centre is -- comes from a plain
 * bounding box. Only the dewarp needs true corners, and only at capture time.
 *
 * Running colour segmentation **inside the detected box** is what makes the
 * colour stage workable at all. Full-frame, it has no way to tell paper from a
 * pale achromatic carpet -- on the first device session it locked onto the floor
 * and reported a confident page. Restricted to a region the network already
 * believes is a page, that ambiguity mostly disappears.
 */
class YoloPageDetector(
    private val engine: YoloDnnEngine,
    private val colour: ColorPageDetector = ColorPageDetector(),
    private val scoreThreshold: Float = 0.25f,
    private val nmsThreshold: Float = 0.45f,
) : PageDetector {

    override fun detect(bgr: Mat, roi: Rect?): PageObservation {
        val frameW = bgr.width()
        val frameH = bgr.height()
        if (frameW == 0 || frameH == 0) return PageObservation.none(frameW, frameH)

        val best = engine.detect(bgr, scoreThreshold, nmsThreshold).firstOrNull()
            ?: return PageObservation.none(frameW, frameH)

        val box = Rect(
            best.box.x.roundToInt().coerceIn(0, frameW - 1),
            best.box.y.roundToInt().coerceIn(0, frameH - 1),
            best.box.width.roundToInt(),
            best.box.height.roundToInt(),
        ).let {
            Rect(it.x, it.y, it.width.coerceAtMost(frameW - it.x), it.height.coerceAtMost(frameH - it.y))
        }
        if (box.width <= 1 || box.height <= 1) return PageObservation.none(frameW, frameH)

        // Try for real corners inside the box. If colour cannot resolve them,
        // the box itself is still a perfectly good answer for guidance.
        val refined = try {
            colour.detect(bgr, box)
        } catch (t: Throwable) {
            Log.w(TAG, "corner refinement failed; using the box", t)
            null
        }

        val clipped = clippedSides(best.box, box, frameW, frameH)
        val coverage = (box.width.toFloat() * box.height) / (frameW.toFloat() * frameH)

        val usable = refined?.quad != null && refined.confidence > MIN_REFINE_CONFIDENCE
        return if (usable) {
            refined!!.copy(
                // The network decides whether this is a page; colour only decides
                // where its corners are. So confidence comes from the detector.
                confidence = best.score,
                coverage = coverage,
                clipped = clipped,
                source = ObservationSource.NEURAL,
            )
        } else {
            PageObservation(
                quad = box.toQuad(),
                confidence = best.score,
                coverage = coverage,
                clipped = clipped,
                tiltDegrees = 0f,
                frameWidth = frameW,
                frameHeight = frameH,
                source = ObservationSource.NEURAL,
            )
        }
    }

    private fun Rect.toQuad(): List<Pt> = listOf(
        Pt(x.toDouble(), y.toDouble()),
        Pt((x + width).toDouble(), y.toDouble()),
        Pt((x + width).toDouble(), (y + height).toDouble()),
        Pt(x.toDouble(), (y + height).toDouble()),
    )

    /**
     * Which frame borders the page runs off.
     *
     * Takes [raw], the network's prediction *before* it is clamped into the
     * frame, as well as the clamped [box]. When the network places an edge
     * outside the frame it is saying the page continues past it -- which is the
     * single strongest clipping signal available -- and `coerceIn()` erases
     * exactly that evidence. Checking only the clamped box was why the page had
     * to be a long way out before anything was announced (device session
     * 2026-09-05).
     *
     * The touch tolerance is a fraction of the frame rather than a fixed pixel
     * count: 3 px was 0.5% of a 640-wide frame, so a page genuinely leaving the
     * shot but whose predicted box stopped a few pixels short went unreported.
     * Detection boxes are regressions and routinely land slightly inside the
     * true extent, so the tolerance has to absorb that error.
     */
    private fun clippedSides(raw: Rect2d, box: Rect, frameW: Int, frameH: Int): Set<Side> {
        val tx = (frameW * BORDER_TOUCH_FRACTION).roundToInt().coerceAtLeast(BORDER_TOUCH_MIN_PX)
        val ty = (frameH * BORDER_TOUCH_FRACTION).roundToInt().coerceAtLeast(BORDER_TOUCH_MIN_PX)
        val s = mutableSetOf<Side>()
        if (raw.x < 0 || box.x <= tx) s += Side.LEFT
        if (raw.y < 0 || box.y <= ty) s += Side.TOP
        if (raw.x + raw.width > frameW || box.x + box.width >= frameW - tx) s += Side.RIGHT
        if (raw.y + raw.height > frameH || box.y + box.height >= frameH - ty) s += Side.BOTTOM
        return s
    }

    companion object {
        private const val TAG = "YoloPageDetector"
        /**
         * A page whose detected edge sits within this fraction of the frame
         * dimension of a border is treated as running off it -- ~29 px on the
         * 480x640 analysis frame this device produces.
         *
         * This is a deliberate safety band, not just slop tolerance. A page
         * flush against the sensor edge is technically "in frame" and was
         * reported clean at the old 2%, but it leaves nothing for the dewarp to
         * work with and nothing for the small field-of-view difference between
         * the analysis stream and the still. A page that is slightly too small
         * costs an upscale; a page with its edge cut off cannot be recovered at
         * all, so the asymmetry justifies erring wide.
         *
         * The preview is FIT_CENTER, so the whole 4:3 frame is on screen and the
         * visible edge is the real capture edge -- this band can now be judged
         * by eye against the preview, which was not true when the preview was
         * FILL_CENTER and hid ~20% of the frame width on each side. Retune on
         * hardware. Measured on device 2026-09-05.
         */
        private const val BORDER_TOUCH_FRACTION = 0.06f
        private const val BORDER_TOUCH_MIN_PX = 3

        /** Below this the refined quad is not trusted and the box is used instead. */
        private const val MIN_REFINE_CONFIDENCE = 0.2f
    }
}
