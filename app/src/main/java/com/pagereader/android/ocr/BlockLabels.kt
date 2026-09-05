package com.pagereader.android.ocr

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Labels blocks with what they are, and describes the page in words.
 *
 * Most kinds arrive already set from hOCR. Two do not, and both matter to a
 * listener far more than to a reader:
 *
 *  - **Running heads and page numbers.** hOCR has no equivalent of a
 *    "disregard this" class, so *"Chapter 4 — The Coastal Survey — 87"* is read
 *    out in the middle of a sentence. Someone reading skips it without
 *    noticing; someone listening has to work out what happened. This is the
 *    single most annoying failure in a linear reader, so it is derived from
 *    position and excluded from playback.
 *  - **Headings Tesseract missed.** It marks `ocr_header` inconsistently — one
 *    header line across ten real pages — but a heading is what tells a listener
 *    where they are in a document, so a size-based fallback promotes obvious
 *    ones.
 *
 * Nothing is ever discarded here. Labelled blocks stay in the page and stay
 * reachable by touch; they are only skipped in continuous playback.
 */
object BlockLabels {

    /**
     * A block wholly inside this fraction of the page top or bottom is a
     * candidate running head or footer.
     */
    const val MARGIN_FRACTION = 0.08

    /** Candidates longer than this are real text that happens to sit high. */
    const val MAX_MARGIN_WORDS = 8

    /**
     * Median word height, relative to the page median, at which an unmarked
     * block is promoted to a heading.
     */
    const val HEADING_SIZE_RATIO = 1.25

    /** A heading is short. Beyond this it is a paragraph in a large font. */
    const val MAX_HEADING_LINES = 3

    /** Words per minute used to estimate how long a page takes to hear. */
    const val WORDS_PER_MINUTE = 180

    /**
     * Applies the derived labels to every block on a page.
     *
     * Order matters: header/footer is decided first, because a page number in a
     * large font would otherwise be promoted to a heading and then announced.
     */
    fun label(blocks: List<TextBlock>, pageWidth: Int, pageHeight: Int): List<TextBlock> {
        if (blocks.isEmpty()) return blocks
        val pageMedian = medianWordHeight(blocks)

        return blocks.map { b ->
            when {
                b.text.isBlank() -> b
                isMarginal(b, pageHeight, pageMedian) -> b.copy(kind = BlockKind.HEADER_FOOTER)
                shouldPromote(b, pageMedian) -> b.copy(kind = BlockKind.HEADING)
                else -> b
            }
        }
    }

    /**
     * A short block sitting entirely within the top or bottom margin band.
     *
     * Applies to blocks Tesseract already classified as well as plain body,
     * deliberately: hOCR frequently marks a running head as `ocr_header`, and
     * on the academic fixture that is exactly what "324 C. A. PEREZ ET AL."
     * is. Demoting it is correct.
     *
     * The exception is size. A genuine chapter title can sit high on the page
     * and be short, and announcing it as a page marker would cost the listener
     * the one cue that says where they are. Running heads are set at body size
     * or smaller; titles are not. Measured on the academic fixture the running
     * head is ~1.15x the page median, comfortably under the heading ratio, so
     * this exemption does not reintroduce it.
     */
    private fun isMarginal(b: TextBlock, pageHeight: Int, pageMedianWordHeight: Float): Boolean {
        if (pageHeight <= 0) return false
        if (wordCount(b.text) > MAX_MARGIN_WORDS) return false
        // Large text is usually a title rather than a running head -- but not
        // when it is a page number, which is short, numeric, and sometimes set
        // large by design. Words earn the exemption; digits do not.
        if (pageMedianWordHeight > 0f &&
            b.medianWordHeight >= pageMedianWordHeight * HEADING_SIZE_RATIO &&
            !isNumberLike(b.text)
        ) {
            return false
        }
        val top = pageHeight * MARGIN_FRACTION
        val bottom = pageHeight * (1.0 - MARGIN_FRACTION)
        val entirelyAtTop = b.bbox.y + b.bbox.height <= top
        val entirelyAtBottom = b.bbox.y >= bottom
        return entirelyAtTop || entirelyAtBottom
    }

    /** Big text, few lines, not already classified as something else. */
    private fun shouldPromote(b: TextBlock, pageMedianWordHeight: Float): Boolean {
        if (b.kind != BlockKind.BODY) return false
        if (pageMedianWordHeight <= 0f || b.medianWordHeight <= 0f) return false
        if (b.lineCount > MAX_HEADING_LINES) return false
        return b.medianWordHeight >= pageMedianWordHeight * HEADING_SIZE_RATIO
    }

    /**
     * True when the text is a page marker rather than words -- digits, roman
     * numerals, and the punctuation that decorates them ("- 87 -", "xiv.").
     */
    private fun isNumberLike(text: String): Boolean {
        val stripped = text.filter { !it.isWhitespace() }
        if (stripped.isEmpty()) return true
        return stripped.all { it.isDigit() || it in ROMAN_AND_PUNCTUATION }
    }

    private const val ROMAN_AND_PUNCTUATION = "ivxlcdmIVXLCDM.,-–—()[]|/"

    private fun medianWordHeight(blocks: List<TextBlock>): Float {
        val heights = blocks.filter { it.text.isNotBlank() && it.medianWordHeight > 0f }
            .map { it.medianWordHeight }
            .sorted()
        if (heights.isEmpty()) return 0f
        val mid = heights.size / 2
        return if (heights.size % 2 == 1) heights[mid] else (heights[mid - 1] + heights[mid]) / 2f
    }

    /**
     * A short spoken name for a block, for the announcement before it is read
     * and for the touch-explore label.
     *
     * A heading is its own title. Everything else is named by kind plus enough
     * of its opening to be recognisable — long enough to identify, short
     * enough that scrubbing through a page is not itself a reading task.
     */
    fun title(block: TextBlock, words: Int = 6): String = when (block.kind) {
        BlockKind.HEADING -> block.text.ifBlank { "Heading" }
        BlockKind.FIGURE -> "Figure"
        BlockKind.SEPARATOR -> "Divider"
        BlockKind.HEADER_FOOTER -> "Page marker: ${snippet(block.text, words)}"
        BlockKind.CAPTION -> "Caption: ${snippet(block.text, words)}"
        BlockKind.SIDEBAR -> "Sidebar: ${snippet(block.text, words)}"
        // Never empty: an unlabelled node is announced by a screen reader as
        // "Unlabeled", which is the silence-as-crash failure in miniature.
        BlockKind.BODY -> snippet(block.text, words).ifBlank { "Unlabelled region" }
    }

    private fun snippet(text: String, words: Int): String {
        val parts = text.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val head = parts.take(words).joinToString(" ")
        return if (parts.size > words) "$head…" else head
    }

    /**
     * What to say on arriving at a page, before reading it.
     *
     * The point is orientation, not summary: how much is here, what shape it
     * is, and whether it is worth staying. A sighted reader gets that from one
     * glance, and it is the thing a linear reader most obviously lacks.
     *
     * Figures and separators carry no text but do carry meaning, so they are
     * announced and never read.
     */
    fun pageSummary(page: OcrPage): String {
        val blocks = page.blocks
        if (blocks.none { it.text.isNotBlank() }) return "I could not find any text on this page."

        val parts = mutableListOf<String>()

        val headline = blocks.firstOrNull { it.kind == BlockKind.HEADING && it.text.isNotBlank() }
        if (headline != null) parts += "Headline: ${headline.text.trimEnd('.')}."

        val body = blocks.filter { it.kind == BlockKind.BODY && it.text.isNotBlank() }
        if (body.isNotEmpty()) {
            // Time what will actually be played, not just the body. Playback
            // also reads headings, captions and sidebars, so counting body
            // alone quietly understates the wait -- and a page is a
            // multi-minute listen, so the error is not small.
            val words = playbackBlocks(page).sumOf { wordCount(it.text) }
            parts += "Body, ${count(body.size, "paragraph")}, about ${minutes(words)}."
        }

        val figures = blocks.count { it.kind == BlockKind.FIGURE }
        val captions = blocks.count { it.kind == BlockKind.CAPTION }
        if (figures > 0) {
            parts += if (captions > 0) {
                "${count(figures, "figure").replaceFirstChar { it.uppercase() }} with " +
                    "${if (captions == 1) "a caption" else "$captions captions"}."
            } else {
                "${count(figures, "figure").replaceFirstChar { it.uppercase() }}."
            }
        }

        val sidebars = blocks.count { it.kind == BlockKind.SIDEBAR }
        if (sidebars > 0) parts += "${count(sidebars, "sidebar").replaceFirstChar { it.uppercase() }}."

        return parts.joinToString(" ").ifBlank { "This page has text but no clear structure." }
    }

    /**
     * Estimated time to *hear* this many words read aloud, rounded to
     * something worth saying.
     *
     * Not processing time. Recognition takes seconds; listening to a page
     * takes minutes, and the two get confused easily because both end up in
     * the same debug line. This is the one the user is told, because it is the
     * one they have to decide about.
     */
    fun minutes(words: Int): String {
        if (words <= 0) return "no time at all"
        val mins = words.toDouble() / WORDS_PER_MINUTE
        return when {
            mins < 0.5 -> "half a minute"
            mins < 1.5 -> "a minute"
            else -> "${max(2, mins.roundToInt())} minutes"
        }
    }

    /**
     * Blocks to play in continuous reading.
     *
     * Running heads and dividers are skipped by default because hearing "87"
     * between two sentences is worse than not hearing it. They stay in
     * [OcrPage.blocks] and remain reachable by touch, so nothing is silently
     * lost — the user can still find the page number if they want it.
     */
    fun playbackBlocks(page: OcrPage): List<TextBlock> =
        page.blocks
            .filter { it.kind != BlockKind.HEADER_FOOTER && it.kind != BlockKind.SEPARATOR }
            .filter { it.text.isNotBlank() || it.kind == BlockKind.FIGURE }
            .sortedBy { it.order }

    private fun count(n: Int, noun: String) = if (n == 1) "one $noun" else "$n ${noun}s"

    private fun wordCount(text: String) =
        text.trim().split(WHITESPACE).count { it.isNotBlank() }

    private val WHITESPACE = Regex("\\s+")
}
