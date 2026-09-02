package com.pagereader.android.guidance

import com.pagereader.android.audio.GuidanceCue
import com.pagereader.android.camera.PartialEdges

/**
 * Turns "which edges are missing" into one instruction. Stateless.
 *
 * A missing edge is off-frame, so the camera has to travel toward it. Both
 * edges of one axis missing means the opposite problem: the page is bigger than
 * the frame and the user needs distance, not translation.
 */
object PartialEdgeGuidance {

    fun getCue(partial: PartialEdges, frameW: Int, frameH: Int): GuidanceCue? {
        if (partial.sidesFound() == 0) return null

        val hasLeft = partial.left != null
        val hasRight = partial.right != null
        val hasTop = partial.top != null
        val hasBottom = partial.bottom != null

        // Overflow needs positive evidence: one axis fully bracketed while the
        // other is entirely absent. Testing only "both sides of an axis are
        // missing" is not enough -- with a single edge visible, the other axis
        // is always empty, so that test swallows every one-edge frame and
        // answers MOVE_BACK when the user needed a direction.
        if (hasTop && hasBottom && !hasLeft && !hasRight) return GuidanceCue.MOVE_BACK
        if (hasLeft && hasRight && !hasTop && !hasBottom) return GuidanceCue.MOVE_BACK

        // Act on an axis with exactly one boundary visible: that is the only
        // case where the missing side's direction is actually known. Move
        // toward the side that is missing.
        if (hasLeft != hasRight) {
            return if (hasLeft) GuidanceCue.MOVE_RIGHT else GuidanceCue.MOVE_LEFT
        }
        if (hasTop != hasBottom) {
            return if (hasTop) GuidanceCue.MOVE_DOWN else GuidanceCue.MOVE_UP
        }

        // All four sides seen but no closed quad -- the detector will likely
        // close it next frame. Saying nothing beats guessing.
        return null
    }
}
