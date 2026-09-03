package com.pagereader.android.detect

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Turns a binary page mask into an ordered quad plus the framing facts guidance
 * needs.
 *
 * The "jagged corners" problem this is written against comes from approximating
 * a contour with a *fixed* epsilon: too small and a wobbly mask boundary yields
 * six or eight vertices, too large and a real corner gets cut off. v1 used a
 * single 2% factor (`DocumentEdgeDetector.APPROX_EPSILON_FACTOR`) and lived with
 * whatever that gave. Here epsilon is searched for the value that actually
 * produces four points.
 */
object MaskToQuad {

    /** Mask pixels within this many px of a border count as touching it. */
    private const val BORDER_TOUCH_PX = 2

    /** A component smaller than this fraction of the frame is not a page. */
    private const val MIN_AREA_FRACTION = 0.03

    private const val EPSILON_MIN_FACTOR = 0.005
    private const val EPSILON_MAX_FACTOR = 0.08
    private const val EPSILON_SEARCH_STEPS = 12

    /** Closes holes punched in the mask by dark text blocks or photos. */
    private const val MORPH_KERNEL_SIZE = 5

    /** Severs thin bright bridges between the page and other bright regions. */
    private const val OPEN_KERNEL_SIZE = 7

    /**
     * Contour area over its minimum-area rectangle. A page sits near 0.9; the
     * bar is low enough to tolerate perspective and a torn newspaper edge, but
     * high enough to drop irregular floor and furniture blobs.
     */
    private const val MIN_RECTANGULARITY = 0.55

    /**
     * @param mask CV_8UC1, non-zero where page. Consumed read-only; not released.
     * @param scaleX,scaleY multipliers from mask coordinates back to original frame.
     */
    fun convert(
        mask: Mat,
        scaleX: Double,
        scaleY: Double,
        frameWidth: Int,
        frameHeight: Int,
        confidence: Float,
        source: ObservationSource,
    ): PageObservation {
        val none = PageObservation.none(frameWidth, frameHeight)
        if (mask.empty()) return none

        val closed = Mat()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(MORPH_KERNEL_SIZE.toDouble(), MORPH_KERNEL_SIZE.toDouble())
        )
        val opener = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(OPEN_KERNEL_SIZE.toDouble(), OPEN_KERNEL_SIZE.toDouble())
        )
        // Open first, then close. Opening severs the thin bright bridges that
        // otherwise weld the page to whatever else in the scene passed the
        // threshold -- on the first real device session the page fused with a
        // pale carpet and the merged blob ran to the corner of the frame.
        Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_OPEN, opener)
        Imgproc.morphologyEx(closed, closed, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
        opener.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        try {
            val maskArea = (closed.width() * closed.height()).toDouble()
            if (maskArea <= 0.0) return none

            // Biggest-wins picks the carpet over the page whenever the carpet is
            // bigger, which it usually is. Score candidates on how *rectangular*
            // they are instead: a sheet of paper nearly fills its own minimum-area
            // rectangle, while a blob of floor or a merged page-plus-floor region
            // does not.
            val scored = contours.mapNotNull { c ->
                val a = Imgproc.contourArea(c)
                if (a / maskArea < MIN_AREA_FRACTION) return@mapNotNull null
                val r = rectangularity(c, a)
                if (r < MIN_RECTANGULARITY) return@mapNotNull null
                Triple(c, a, r)
            }
            val best = scored.maxByOrNull { (_, a, r) -> r * r * a } ?: return none
            val largest = best.first
            val area = best.second
            val rectangularity = best.third

            val clipped = clippedSides(largest, closed.width(), closed.height())
            val quad = fitQuad(largest)
                ?: return none.copy(
                    // Even without a clean quad the framing facts are still usable,
                    // and they are what guidance actually consumes.
                    confidence = confidence * rectangularity.toFloat() * 0.5f,
                    coverage = (area / maskArea).toFloat(),
                    clipped = clipped,
                    source = source,
                )

            val ordered = orderCorners(quad).map { Pt(it.x * scaleX, it.y * scaleY) }

            return PageObservation(
                quad = ordered,
                // Both halves matter: the threshold must have separated something
                // cleanly AND the thing it separated must look like a page.
                confidence = confidence * rectangularity.toFloat(),
                coverage = (area / maskArea).toFloat(),
                clipped = clipped,
                tiltDegrees = tiltOf(ordered),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                source = source,
            )
        } finally {
            contours.forEach { it.release() }
            closed.release()
        }
    }

    /** Contour area as a fraction of its minimum-area rectangle. */
    private fun rectangularity(contour: MatOfPoint, area: Double): Double {
        val curve = MatOfPoint2f(*contour.toArray())
        try {
            val r = Imgproc.minAreaRect(curve)
            val boxArea = r.size.width * r.size.height
            return if (boxArea <= 0.0) 0.0 else (area / boxArea).coerceIn(0.0, 1.0)
        } finally {
            curve.release()
        }
    }

    /**
     * Which frame borders the page runs off. This is the whole reason the
     * rewrite works where v1 did not: it is a question about *where pixels are*,
     * not about how much brighter they are than their neighbours, so it survives
     * a pale page on a pale desk.
     */
    private fun clippedSides(contour: MatOfPoint, width: Int, height: Int): Set<Side> {
        val rect = Imgproc.boundingRect(contour)
        val sides = mutableSetOf<Side>()
        if (rect.x <= BORDER_TOUCH_PX) sides += Side.LEFT
        if (rect.y <= BORDER_TOUCH_PX) sides += Side.TOP
        if (rect.x + rect.width >= width - BORDER_TOUCH_PX) sides += Side.RIGHT
        if (rect.y + rect.height >= height - BORDER_TOUCH_PX) sides += Side.BOTTOM
        return sides
    }

    /**
     * Searches epsilon for the value that yields exactly four vertices, rather
     * than hoping one fixed factor suits every mask. Falls back to the minimum-
     * area rotated rectangle, which is always four points and is a reasonable
     * page outline whenever perspective is mild.
     */
    private fun fitQuad(contour: MatOfPoint): List<Point>? {
        val curve = MatOfPoint2f(*contour.toArray())
        try {
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null

            var best: List<Point>? = null
            var lo = EPSILON_MIN_FACTOR
            var hi = EPSILON_MAX_FACTOR
            repeat(EPSILON_SEARCH_STEPS) {
                val mid = (lo + hi) / 2.0
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, mid * perimeter, true)
                val n = approx.total().toInt()
                val pts = approx.toArray().toList()
                approx.release()

                when {
                    // Exactly four: keep it, then keep searching downward for a
                    // tighter fit that still gives four.
                    n == 4 -> { best = pts; hi = mid }
                    // Too many vertices -> simplify harder.
                    n > 4 -> lo = mid
                    // Collapsed below four -> back off.
                    else -> hi = mid
                }
            }
            if (best != null) return best

            val rotated = Imgproc.minAreaRect(curve)
            val box = arrayOfNulls<Point>(4)
            rotated.points(box)
            return box.filterNotNull().takeIf { it.size == 4 }
        } finally {
            curve.release()
        }
    }

    /**
     * Orders corners TL, TR, BR, BL. Same sum/diff trick v1 used and it was
     * correct there: the top-left minimises x+y, the bottom-right maximises it,
     * and the other diagonal separates on y-x.
     */
    fun orderCorners(points: List<Point>): List<Point> {
        val tl = points.minByOrNull { it.x + it.y }!!
        val br = points.maxByOrNull { it.x + it.y }!!
        val tr = points.minByOrNull { it.y - it.x }!!
        val bl = points.maxByOrNull { it.y - it.x }!!
        return listOf(tl, tr, br, bl)
    }

    /** Rotation of the top edge from horizontal, in degrees. */
    private fun tiltOf(ordered: List<Pt>): Float {
        val tl = ordered[0]
        val tr = ordered[1]
        return Math.toDegrees(kotlin.math.atan2(tr.y - tl.y, tr.x - tl.x)).toFloat()
    }

    /** Builds a CV_8UC1 mask the same size as [like], all zeros. */
    fun emptyMaskLike(like: Mat): Mat = Mat.zeros(like.size(), CvType.CV_8UC1)

    /** Restricts [mask] to the given rectangle, zeroing everything outside it. */
    fun restrictTo(mask: Mat, roi: org.opencv.core.Rect): Mat {
        val out = Mat.zeros(mask.size(), CvType.CV_8UC1)
        val clamped = org.opencv.core.Rect(
            roi.x.coerceIn(0, mask.width() - 1),
            roi.y.coerceIn(0, mask.height() - 1),
            roi.width.coerceAtMost(mask.width() - roi.x.coerceIn(0, mask.width() - 1)),
            roi.height.coerceAtMost(mask.height() - roi.y.coerceIn(0, mask.height() - 1)),
        )
        if (clamped.width > 0 && clamped.height > 0) {
            val src = mask.submat(clamped)
            val dst = out.submat(clamped)
            src.copyTo(dst)
            src.release()
            dst.release()
        }
        return out
    }
}
