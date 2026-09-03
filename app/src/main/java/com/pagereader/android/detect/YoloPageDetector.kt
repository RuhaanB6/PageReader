package com.pagereader.android.detect

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Rect
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

        val clipped = clippedSides(box, frameW, frameH)
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

    private fun clippedSides(box: Rect, frameW: Int, frameH: Int): Set<Side> {
        val s = mutableSetOf<Side>()
        if (box.x <= BORDER_TOUCH_PX) s += Side.LEFT
        if (box.y <= BORDER_TOUCH_PX) s += Side.TOP
        if (box.x + box.width >= frameW - BORDER_TOUCH_PX) s += Side.RIGHT
        if (box.y + box.height >= frameH - BORDER_TOUCH_PX) s += Side.BOTTOM
        return s
    }

    companion object {
        private const val TAG = "YoloPageDetector"
        private const val BORDER_TOUCH_PX = 3

        /** Below this the refined quad is not trusted and the box is used instead. */
        private const val MIN_REFINE_CONFIDENCE = 0.2f
    }
}
