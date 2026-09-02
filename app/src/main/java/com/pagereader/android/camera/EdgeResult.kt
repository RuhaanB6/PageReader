package com.pagereader.android.camera

import org.opencv.core.Point

/**
 * Boundaries the detector could see when it could not close a full quad.
 * Each value is a position in original-frame coordinates; null means that side
 * was not visible.
 */
data class PartialEdges(
    val top: Float?,
    val bottom: Float?,
    val left: Float?,
    val right: Float?,
    /**
     * y-extent of the selected left/right vertical segments, in original-frame
     * coordinates, ordered so Y1 <= Y2. Lets the debug overlay clip the drawn
     * line to the segment Hough actually found instead of spanning the screen.
     */
    val leftY1: Float? = null,
    val leftY2: Float? = null,
    val rightY1: Float? = null,
    val rightY2: Float? = null
) {
    fun sidesFound(): Int = listOf(top, bottom, left, right).count { it != null }
}

data class EdgeResult(
    val found: Boolean,
    /** [TL, TR, BR, BL] in original-frame coordinates, or null. */
    val corners: List<Point>?,
    val center: Point?,
    val coverage: Float?,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Populated when [found] is false. */
    val partial: PartialEdges?
)
