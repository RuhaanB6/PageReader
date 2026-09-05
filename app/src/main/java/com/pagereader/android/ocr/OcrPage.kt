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
 */
data class TextBlock(
    val id: Int,
    val order: Int,
    val text: String,
    val bbox: Rect,
    val confidence: Float,
    val kind: BlockKind,
    val medianWordHeight: Float,
)

/**
 * A recognised page.
 *
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
) {
    /** Blocks that carry words, in current order. */
    val textBlocks: List<TextBlock>
        get() = blocks.filter { it.text.isNotBlank() }

    companion object {
        /**
         * Below this a page is treated as not properly read. See
         * [OcrPage.meanConfidence] for the measurements behind the number.
         */
        const val USABLE_CONFIDENCE = 0.60f

        fun empty(width: Int, height: Int, elapsedMs: Long = 0L) =
            OcrPage(width, height, emptyList(), 0f, elapsedMs)
    }
}
