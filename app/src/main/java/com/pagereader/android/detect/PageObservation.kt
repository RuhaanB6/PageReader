package com.pagereader.android.detect

/** A point in original-frame pixel coordinates. */
data class Pt(val x: Double, val y: Double)

/** A frame border. */
enum class Side { LEFT, RIGHT, TOP, BOTTOM }

/** Where an observation came from, so guidance can weigh it. */
enum class ObservationSource {
    /** Neural segmentation mask. */
    NEURAL,

    /** Lab-space colour segmentation -- the fallback, and the interstitial
     *  detector on frames between neural inferences. */
    COLOR,

    /** Last good observation, held while the next inference runs. */
    STALE,

    /** Nothing found. */
    NONE,
}

/**
 * What the detector saw this frame. Replaces the old EdgeResult/PartialEdges pair.
 *
 * The important difference is [clipped]. The old detector inferred direction from
 * which Hough lines it had found and which side of a line looked brighter, which
 * needs real contrast to work at all -- the reason a pale page on a pale desk
 * failed. Clipping is topological: if the mask reaches the left border, the page
 * continues past it, and that is true regardless of how little contrast there is.
 */
data class PageObservation(
    /** Ordered TL, TR, BR, BL in original-frame coordinates. Null if no page. */
    val quad: List<Pt>?,
    /** 0..1. For [ObservationSource.COLOR] this is a heuristic score, not a probability. */
    val confidence: Float,
    /** Mask area as a fraction of frame area. */
    val coverage: Float,
    /** Frame borders the mask touches. Drives the move-the-camera instructions. */
    val clipped: Set<Side>,
    /** Rotation of the quad's long axis from horizontal, in degrees, or null if unknown. */
    val tiltDegrees: Float?,
    val frameWidth: Int,
    val frameHeight: Int,
    val source: ObservationSource,
) {
    /** Centroid of the quad, or null if there is no quad. */
    val center: Pt?
        get() = quad?.let { q ->
            Pt(q.sumOf { it.x } / q.size, q.sumOf { it.y } / q.size)
        }

    companion object {
        fun none(frameWidth: Int, frameHeight: Int) = PageObservation(
            quad = null,
            confidence = 0f,
            coverage = 0f,
            clipped = emptySet(),
            tiltDegrees = null,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            source = ObservationSource.NONE,
        )
    }
}
