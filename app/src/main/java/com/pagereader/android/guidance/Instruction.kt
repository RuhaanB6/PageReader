package com.pagereader.android.guidance

/**
 * Everything the app can ask the user to do, and the exact words for it.
 *
 * Deliberately short. These are heard while the user is moving a camera with one
 * hand, and every extra syllable is a syllable during which the framing has
 * already changed. "Move left" beats "Please move the camera to the left."
 *
 * Only one of these is ever active at a time -- see [GuidancePolicy].
 */
enum class Instruction(val text: String) {
    /** Page runs off that border; move the camera that way to bring it into view. */
    MOVE_LEFT("Move left"),
    MOVE_RIGHT("Move right"),
    MOVE_UP("Move up"),
    MOVE_DOWN("Move down"),

    /** Page is larger than the frame, or too small in it. */
    MOVE_BACK("Move back"),
    MOVE_CLOSER("Move closer"),

    /** Page is in frame but rotated far enough to hurt the dewarp. */
    STRAIGHTEN("Straighten the page"),

    /** Framing is good but the phone is moving too much to capture. */
    HOLD_STILL("Hold still"),

    /** Nothing in view at all. Said once, then silence. */
    NO_PAGE("No page in view"),
}

/** Coarse state, for the debug overlay and for capture gating. */
enum class FramingState {
    /** No confident observation. */
    SEARCHING,

    /** Page seen, but something needs correcting. */
    ADJUSTING,

    /** Nothing left to correct, but not yet held still long enough. */
    FRAMED,

    /** Framed and steady -- capture fires. */
    STEADY,
}
