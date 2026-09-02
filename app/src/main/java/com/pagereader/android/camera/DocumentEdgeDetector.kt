package com.pagereader.android.camera

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Pure-OpenCV document edge detector, ported from edges.py. No ML.
 *
 * The partial-edge (Hough) path is not a fallback, it is the primary state
 * during real use: a user starts with no document in frame, approaches, sees
 * one to three sides, and only then closes all four. Both branches of [detect]
 * have to stay useful.
 */
object DocumentEdgeDetector {

    // Far below textbook Canny values. A newspaper on a similarly-coloured desk
    // produces a very shallow paper-to-desk gradient; at 30/80 Canny walks past
    // it and findQuad settles for the outermost *content* contour instead --
    // the text block -- which is much smaller than the page. The MORPH_ELLIPSE
    // dilation closes the extra gaps these low thresholds introduce.
    private const val CANNY_LOW = 15.0
    private const val CANNY_HIGH = 40.0

    private const val MIN_AREA_FRACTION = 0.15

    /**
     * Diagnostic only, never a rejection: a quad below this fraction of the
     * frame is more likely a text block than a page, and worth a log line.
     */
    private const val SUSPICIOUS_AREA_FRACTION = 0.35

    private const val TAG = "DocEdge"
    private const val APPROX_EPSILON_FACTOR = 0.02
    private const val MORPH_KERNEL_SIZE = 3
    private const val HOUGH_THRESHOLD = 80

    /** Filters interior text lines and column rules, which are always short. */
    private const val HOUGH_MIN_LINE_FRAC = 0.50
    private const val HOUGH_MAX_LINE_GAP = 20.0

    private const val PROCESS_WIDTH = 640
    private const val PROCESS_HEIGHT = 480

    /** Rejects dark quads -- shadows and desk fixtures, not paper. */
    private const val MIN_INTERIOR_BRIGHTNESS = 120.0

    private const val MIN_ASPECT_RATIO = 0.3f
    private const val MAX_ASPECT_RATIO = 3.5f

    /** Ignore lines hugging the frame border; those are framing artifacts. */
    private const val BORDER_EXCLUSION_FRAC = 0.08

    /**
     * A quad with a corner this close to the frame edge is a document the frame
     * has cut off, not a document the frame contains. findContours clips
     * contours at the image boundary, so a half-off-screen page still yields a
     * clean convex 4-gon -- bright, big enough, right aspect -- that sails
     * through every other check. Rejecting it hands the frame to the
     * partial-edge path, which is what should be talking in that situation.
     */
    private const val QUAD_BORDER_MARGIN_FRAC = 0.02

    /** Half-width of the brightness sampling band either side of a segment. */
    private const val SIDE_SAMPLE_BAND = 12

    /** Below this gray-level difference the two sides are too close to call. */
    private const val SIDE_BRIGHTNESS_MARGIN = 8.0

    /**
     * [position] is the axis-crossing coordinate (midY for horizontals, midX
     * for verticals); [extent1]/[extent2] are the segment's endpoints along its
     * own direction, ordered ascending, so the overlay can clip to it.
     */
    private data class LineCandidate(
        val position: Double,
        val length: Double,
        val extent1: Double,
        val extent2: Double
    )

    fun detect(frame: Mat): EdgeResult {
        val originalWidth = frame.cols()
        val originalHeight = frame.rows()

        val scaleX = originalWidth / PROCESS_WIDTH.toFloat()
        val scaleY = originalHeight / PROCESS_HEIGHT.toFloat()

        Log.d(
            TAG,
            "frame=${originalWidth}x$originalHeight " +
                "process=${PROCESS_WIDTH}x$PROCESS_HEIGHT scaleX=$scaleX scaleY=$scaleY"
        )

        val resized = Mat()
        Imgproc.resize(frame, resized, Size(PROCESS_WIDTH.toDouble(), PROCESS_HEIGHT.toDouble()))

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
        resized.release()

        val edges = edgeMap(gray)

        // gray stays alive through both branches: findQuad's interior-brightness
        // test and findPartialEdges' paper-side test both read it.
        val quad = findQuad(edges, gray, PROCESS_WIDTH, PROCESS_HEIGHT)

        val result = if (quad != null) {
            val corners = orderCorners(quad, scaleX, scaleY)
            val center = Point(
                corners.sumOf { it.x } / corners.size,
                corners.sumOf { it.y } / corners.size
            )
            val coverage =
                (Imgproc.contourArea(quad) / (PROCESS_WIDTH * PROCESS_HEIGHT).toDouble()).toFloat()
            quad.release()

            if (coverage < SUSPICIOUS_AREA_FRACTION) {
                Log.w(
                    TAG,
                    "quad suspiciously small -- possible interior contour " +
                        "(coverage=$coverage < $SUSPICIOUS_AREA_FRACTION)"
                )
            }

            EdgeResult(
                found = true,
                corners = corners,
                center = center,
                coverage = coverage,
                frameWidth = originalWidth,
                frameHeight = originalHeight,
                partial = null
            )
        } else {
            EdgeResult(
                found = false,
                corners = null,
                center = null,
                coverage = null,
                frameWidth = originalWidth,
                frameHeight = originalHeight,
                partial = findPartialEdges(
                    edges, gray, PROCESS_WIDTH, PROCESS_HEIGHT, scaleX, scaleY
                )
            )
        }

        gray.release()
        edges.release()
        return result
    }

    private fun edgeMap(gray: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH)
        blurred.release()

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(MORPH_KERNEL_SIZE.toDouble(), MORPH_KERNEL_SIZE.toDouble())
        )
        val dilated = Mat()
        Imgproc.dilate(edges, dilated, kernel)
        edges.release()
        kernel.release()

        return dilated
    }

    private fun findQuad(edgeMap: Mat, gray: Mat, frameW: Int, frameH: Int): MatOfPoint? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edgeMap, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()

        val minArea = MIN_AREA_FRACTION * (frameW * frameH).toDouble()
        val ranked = contours.sortedByDescending { Imgproc.contourArea(it) }.take(10)

        var winner: MatOfPoint? = null
        for (contour in ranked) {
            if (winner != null) break

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx2f = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx2f, APPROX_EPSILON_FACTOR * peri, true)
            contour2f.release()

            if (approx2f.total() != 4L) {
                approx2f.release()
                continue
            }
            val approx = MatOfPoint(*approx2f.toArray())
            approx2f.release()

            if (!Imgproc.isContourConvex(approx)) {
                approx.release()
                continue
            }
            if (Imgproc.contourArea(approx) < minArea) {
                approx.release()
                continue
            }

            if (touchesBorder(approx, frameW, frameH)) {
                Log.d(TAG, "quad rejected: corner on frame border (document is cut off)")
                approx.release()
                continue
            }

            val rect = Imgproc.boundingRect(approx)
            if (rect.height == 0) {
                approx.release()
                continue
            }
            val aspect = rect.width.toFloat() / rect.height.toFloat()
            if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) {
                approx.release()
                continue
            }

            if (interiorBrightness(gray, approx) < MIN_INTERIOR_BRIGHTNESS) {
                approx.release()
                continue
            }

            Log.d(
                TAG,
                "quad found: corners=${approx.toArray().joinToString { "(${it.x},${it.y})" }} " +
                    "areaFrac=${(Imgproc.contourArea(approx) / (frameW * frameH)).toFloat()}"
            )
            winner = approx
        }

        // The winner is a fresh Mat built from approx points, so releasing the
        // source contours is safe.
        contours.forEach { it.release() }
        return winner
    }

    /** True when any corner sits in the border band, i.e. the page is clipped. */
    private fun touchesBorder(quad: MatOfPoint, frameW: Int, frameH: Int): Boolean {
        val marginX = frameW * QUAD_BORDER_MARGIN_FRAC
        val marginY = frameH * QUAD_BORDER_MARGIN_FRAC
        return quad.toArray().any { p ->
            p.x <= marginX || p.x >= frameW - marginX ||
                p.y <= marginY || p.y >= frameH - marginY
        }
    }

    private fun interiorBrightness(gray: Mat, quad: MatOfPoint): Double {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, quad, Scalar(255.0))
        val mean = Core.mean(gray, mask)
        mask.release()
        return mean.`val`[0]
    }

    /** Sum/diff trick: TL = min(x+y), BR = max(x+y), TR = min(y-x), BL = max(y-x). */
    private fun orderCorners(pts: MatOfPoint, scaleX: Float, scaleY: Float): List<Point> {
        val points = pts.toArray()

        val tl = points.minByOrNull { it.x + it.y }!!
        val br = points.maxByOrNull { it.x + it.y }!!
        val tr = points.minByOrNull { it.y - it.x }!!
        val bl = points.maxByOrNull { it.y - it.x }!!

        return listOf(tl, tr, br, bl).map { Point(it.x * scaleX, it.y * scaleY) }
    }

    private fun meanIn(gray: Mat, rect: Rect): Double {
        val sub = gray.submat(rect)
        val mean = Core.mean(sub)
        sub.release()
        return mean.`val`[0]
    }

    /**
     * Which side of a segment the paper lies on, by comparing mean brightness in
     * a band either side of it. True when the brighter side is the positive one
     * (right of a vertical, below a horizontal), false when it is the negative
     * one, null when the two are too close to call.
     *
     * This is what separates a document's left boundary from its right boundary.
     * Classifying by which half of the frame the line falls in -- the obvious
     * shortcut -- inverts the moment the whole page sits off to one side: a page
     * entering from the left shows its *right* boundary in the frame's *left*
     * half. That is precisely the one-edge case the guidance most needs correct.
     */
    private fun paperOnPositiveSide(
        gray: Mat,
        vertical: Boolean,
        position: Double,
        extentStart: Double,
        extentEnd: Double
    ): Boolean? {
        val w = gray.cols()
        val h = gray.rows()

        val negRect: Rect
        val posRect: Rect

        if (vertical) {
            val y0 = extentStart.toInt().coerceIn(0, h - 1)
            val bandH = extentEnd.toInt().coerceIn(0, h) - y0
            val x = position.toInt().coerceIn(0, w - 1)
            val negX = (x - SIDE_SAMPLE_BAND).coerceAtLeast(0)
            val negW = x - negX
            val posX = (x + 1).coerceAtMost(w - 1)
            val posW = min(SIDE_SAMPLE_BAND, w - posX)
            if (bandH < 1 || negW < 1 || posW < 1) return null
            negRect = Rect(negX, y0, negW, bandH)
            posRect = Rect(posX, y0, posW, bandH)
        } else {
            val x0 = extentStart.toInt().coerceIn(0, w - 1)
            val bandW = extentEnd.toInt().coerceIn(0, w) - x0
            val y = position.toInt().coerceIn(0, h - 1)
            val negY = (y - SIDE_SAMPLE_BAND).coerceAtLeast(0)
            val negH = y - negY
            val posY = (y + 1).coerceAtMost(h - 1)
            val posH = min(SIDE_SAMPLE_BAND, h - posY)
            if (bandW < 1 || negH < 1 || posH < 1) return null
            negRect = Rect(x0, negY, bandW, negH)
            posRect = Rect(x0, posY, bandW, posH)
        }

        val diff = meanIn(gray, posRect) - meanIn(gray, negRect)
        if (abs(diff) < SIDE_BRIGHTNESS_MARGIN) return null
        return diff > 0
    }

    private fun findPartialEdges(
        edgeMap: Mat,
        gray: Mat,
        frameW: Int,
        frameH: Int,
        scaleX: Float,
        scaleY: Float
    ): PartialEdges {
        val lines = Mat()
        val minLineLength = HOUGH_MIN_LINE_FRAC * min(frameW, frameH)
        Imgproc.HoughLinesP(
            edgeMap, lines, 1.0, PI / 180, HOUGH_THRESHOLD,
            minLineLength, HOUGH_MAX_LINE_GAP
        )

        val borderX = frameW * BORDER_EXCLUSION_FRAC
        val borderY = frameH * BORDER_EXCLUSION_FRAC

        var top: LineCandidate? = null
        var bottom: LineCandidate? = null
        var left: LineCandidate? = null
        var right: LineCandidate? = null

        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0)
            val x1 = l[0]
            val y1 = l[1]
            val x2 = l[2]
            val y2 = l[3]

            val midX = (x1 + x2) / 2.0
            val midY = (y1 + y2) / 2.0

            if (midY < borderY || midY > frameH - borderY) continue
            if (midX < borderX || midX > frameW - borderX) continue

            val length = hypot(x2 - x1, y2 - y1)
            val angle = abs(Math.toDegrees(atan2(y2 - y1, x2 - x1)))

            if (angle < 15.0 || angle > 165.0) {
                // Horizontal: extent runs along x. Paper below the boundary
                // means it is the document's top edge. Frame half is only the
                // fallback for when the two sides are indistinguishable.
                val candidate = LineCandidate(midY, length, minOf(x1, x2), maxOf(x1, x2))
                val paperBelow = paperOnPositiveSide(
                    gray, vertical = false, midY, candidate.extent1, candidate.extent2
                )
                if (paperBelow ?: (midY < frameH * 0.5)) {
                    if (top == null || length > top.length) top = candidate
                } else {
                    if (bottom == null || length > bottom.length) bottom = candidate
                }
            } else if (angle in 75.0..105.0) {
                // Vertical: extent runs along y. Paper to the right of the
                // boundary means it is the document's left edge.
                val candidate = LineCandidate(midX, length, minOf(y1, y2), maxOf(y1, y2))
                val paperRight = paperOnPositiveSide(
                    gray, vertical = true, midX, candidate.extent1, candidate.extent2
                )
                if (paperRight ?: (midX < frameW * 0.5)) {
                    if (left == null || length > left.length) left = candidate
                } else {
                    if (right == null || length > right.length) right = candidate
                }
            }
        }
        lines.release()

        val result = PartialEdges(
            top = top?.position?.times(scaleY)?.toFloat(),
            bottom = bottom?.position?.times(scaleY)?.toFloat(),
            left = left?.position?.times(scaleX)?.toFloat(),
            right = right?.position?.times(scaleX)?.toFloat(),
            leftY1 = left?.extent1?.times(scaleY)?.toFloat(),
            leftY2 = left?.extent2?.times(scaleY)?.toFloat(),
            rightY1 = right?.extent1?.times(scaleY)?.toFloat(),
            rightY2 = right?.extent2?.times(scaleY)?.toFloat()
        )

        Log.d(
            TAG,
            "partial: top=${result.top} bot=${result.bottom} " +
                "left=${result.left} right=${result.right} " +
                "leftExtent=[${result.leftY1},${result.leftY2}] " +
                "rightExtent=[${result.rightY1},${result.rightY2}]"
        )

        return result
    }
}
