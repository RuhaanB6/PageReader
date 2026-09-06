package com.pagereader.android.ocr

import org.opencv.core.Rect

/**
 * What kind of thing a block is.
 *
 * These map 1:1 onto the classes Tesseract already emits in hOCR, rather than
 * being a taxonomy of our own: every one of them was confirmed to fire across
 * ten real pages, so none is speculative. [HEADER_FOOTER] is the exception --
 * hOCR has no such class and it is derived from position in M6.
 */
enum class BlockKind {
    HEADING,
    BODY,
    CAPTION,
    SIDEBAR,
    FIGURE,
    SEPARATOR,
    HEADER_FOOTER,
}

/**
 * One region of the page.
 *
 * @property order reading order, filled in by M6. Parsing leaves it at the
 *   document order hOCR happened to use, which is not the same thing on a
 *   multi-column page.
 * @property text empty for [BlockKind.FIGURE] and [BlockKind.SEPARATOR], which
 *   carry position but no words.
 * @property bbox always in space **G**, the dewarped page. Never store a
 *   screen coordinate here.
 * @property confidence 0..1, the mean of the hOCR `x_wconf` values.
 * @property medianWordHeight used by M6 to promote large text to a heading;
 *   0 when there are no words.
 * @property lineCount how many text lines the block holds. Counted from the
 *   hOCR line spans rather than estimated from box height later, because line
 *   spacing varies enough between documents that dividing height by word
 *   height guesses wrong on exactly the short blocks the heading rule cares
 *   about.
 */
data class TextBlock(
    val id: Int,
    val order: Int,
    val text: String,
    val bbox: Rect,
    val confidence: Float,
    val kind: BlockKind,
    val medianWordHeight: Float,
    val lineCount: Int = 0,
)

/**
 * A recognised page.
 *
 * @property quarterTurnsClockwise how far the page image had to be rotated
 *   before OCR succeeded, 0 or 1. Space **G** is defined as the orientation
 *   the text was actually read in, so every bbox here is in that frame -- and
 *   any image shown or stored alongside these boxes must be rotated to match.
 *   Rotating the boxes back instead would be wrong: reading order is computed
 *   from geometry, so boxes in the sideways frame make band-then-column
 *   resolve columns along the wrong axis.
 * @property meanConfidence the page's self-diagnostic, and load-bearing.
 *   Measured over ten real pages, everything below 0.60 was genuinely broken
 *   (a rotated page at 0.36, a 680 px thumbnail at 0.54, English OCR over
 *   Chinese at 0.30) and everything at or above it was fine. That separation is
 *   what lets the app decide to retry rotated, or tell the user the photo is
 *   not readable, instead of confidently reading nonsense aloud.
 */
data class OcrPage(
    val pageWidth: Int,
    val pageHeight: Int,
    val blocks: List<TextBlock>,
    val meanConfidence: Float,
    val elapsedMs: Long,
    val quarterTurnsClockwise: Int = 0,
) {
    /** Blocks that carry words, in current order. */
    val textBlocks: List<TextBlock>
        get() = blocks.filter { it.text.isNotBlank() }

    companion object {
        /**
         * Below this a page is not read aloud.
         *
         * Was 0.60, which came from ten sample images that were scans and
         * screenshots. On the first real phone capture -- a newspaper front
         * page -- Tesseract returned 0.629 and the app confidently read out
         * "DRERSDAY, SEPTEMBER 4 2026" and "SAR HA PEER De ent raaicke". The
         * threshold let genuine nonsense through, which is the one failure
         * this product cannot have: the listener has no way to know.
         *
         * Measured 2026-09-05, as the word-weighted page confidence the
         * parser now reports:
         *
         *     academic scan        0.914   reads correctly
         *     textbook scan        0.883   reads correctly
         *     ppt screenshot       0.872   reads correctly
         *     newspaper PHOTO      0.657   unreadable garbage
         *     newspaper thumbnail  0.579   unreadable garbage
         *
         * Everything good sits at or above 0.87 and everything broken at or
         * below 0.66, so 0.75 sits in an empty gap rather than on a cliff.
         *
         * The statistic matters as much as the number. Averaging over blocks
         * instead put those same pages 0.06 apart (0.629 garbage against 0.688
         * good) with no safe threshold between them, because it gave a
         * two-word caption the same weight as a 200-word article.
         *
         * Text size was the other candidate and the data refutes it: the
         * academic scan reads correctly at a 19.0 px median word height, and
         * the failing photo measured 19.8 px. Size is not what separates them;
         * photographic quality is, and confidence is what sees it.
         */
        const val USABLE_CONFIDENCE = 0.75f

        /**
         * Below this a page is retried rotated a quarter turn.
         *
         * Deliberately lower than [USABLE_CONFIDENCE] and left at the old
         * value. A genuinely sideways page scores around 0.36 and recovers to
         * 0.87, so 0.60 catches it comfortably; tying this to the stricter
         * reading threshold would spend a second full recognition pass -- up
         * to 13 s on this phone -- on every merely-mediocre page, to no end.
         */
        const val ROTATION_RETRY_CONFIDENCE = 0.60f

        fun empty(width: Int, height: Int, elapsedMs: Long = 0L) =
            OcrPage(width, height, emptyList(), 0f, elapsedMs)
    }
}
