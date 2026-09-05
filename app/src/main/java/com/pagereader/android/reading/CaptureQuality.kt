package com.pagereader.android.reading

import com.pagereader.android.detect.Side
import com.pagereader.android.ocr.OcrPage

/**
 * Decides whether a capture was good enough, and says what to do about it.
 *
 * This closes the loop back to guidance. Everything upstream measures the page
 * before the shutter; this is the only place that judges the photo *after*
 * reading it, which is the first point at which "was that any good" can
 * actually be answered.
 *
 * The stakes are asymmetric and that shapes the whole design. Reading a garbled
 * page aloud to someone who cannot check it against the paper is worse than
 * asking them to retake: they have no way to know the text is wrong, and may
 * act on it. So a doubtful capture is reported, not read.
 *
 * Every message names the problem *and* the remedy, because "that didn't work"
 * with no next step is the most frustrating thing an accessibility app can say.
 */
object CaptureQuality {

    data class Verdict(
        /** True when the page is worth reading aloud. */
        val usable: Boolean,
        /** What to say. Null only when there is nothing to report. */
        val message: String?,
    )

    /**
     * @param clipped which borders the page ran off **in the still that was
     *   read**. Deliberately a plain set rather than a `PageObservation`: the
     *   only observation lying around at this point is the live framing one,
     *   which by now describes a scene seconds old and a phone that has moved,
     *   and passing it made the clipping verdict essentially random. Taking
     *   just the set forces the caller to supply the right one.
     * @param page the OCR result for the captured still.
     */
    fun assess(clipped: Set<Side>, page: OcrPage): Verdict {
        // Order matters: report the *cause* the user can act on. A clipped page
        // usually also reads badly, and "the left edge was cut off" is a far
        // more useful instruction than "the text came out blurry".
        if (clipped.isNotEmpty()) {
            return Verdict(false, "${describe(clipped)} Volume up to retake.")
        }

        if (page.textBlocks.isEmpty()) {
            return Verdict(
                false,
                "I could not find any text on this page. Try again with more light.",
            )
        }

        if (page.meanConfidence < OcrPage.USABLE_CONFIDENCE) {
            return Verdict(false, "The text came out blurry. Volume up to retake.")
        }

        return Verdict(true, null)
    }

    /**
     * Names the cut edges from the user's point of view.
     *
     * Plural edges are worth spelling out: "the left and top edges were cut
     * off" tells someone how to move the phone, where "the page was cut off"
     * does not.
     */
    private fun describe(sides: Set<Side>): String {
        val names = ORDER.filter { it in sides }.map { NAMES.getValue(it) }
        val list = when (names.size) {
            0 -> return "The page was cut off."
            1 -> "The ${names[0]} edge was"
            2 -> "The ${names[0]} and ${names[1]} edges were"
            else -> "The ${names.dropLast(1).joinToString(", ")} and ${names.last()} edges were"
        }
        return "$list cut off."
    }

    /** Fixed order so the phrasing is stable between captures. */
    private val ORDER = listOf(Side.LEFT, Side.RIGHT, Side.TOP, Side.BOTTOM)

    private val NAMES = mapOf(
        Side.LEFT to "left",
        Side.RIGHT to "right",
        Side.TOP to "top",
        Side.BOTTOM to "bottom",
    )
}
