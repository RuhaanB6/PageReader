package com.pagereader.android.ocr

/**
 * Puts blocks into the order a person would read them.
 *
 * Tesseract's own order is roughly top-to-bottom over the whole page, which is
 * right for a single column and wrong for everything else: on a two-column
 * page it interleaves the columns line-band by line-band, so the listener hears
 * half a sentence from the left column followed by half a sentence from the
 * right. Someone reading with their eyes recovers instantly. Someone listening
 * cannot.
 *
 * The rule is band-then-column:
 *
 *  1. A block spanning most of the page width (a headline, a rule, a wide
 *     figure) cannot be part of a column — it *divides* the page. These become
 *     band boundaries, ordered top to bottom.
 *  2. Inside a band, blocks whose horizontal extents substantially overlap
 *     belong to the same column. Columns are read left to right.
 *  3. Inside a column, top to bottom.
 *
 * This is applied as a re-sort over what Tesseract produced rather than a
 * replacement for it: where the page really is one column the two agree, and
 * [disagreesWithSourceOrder] reports when they do not, so a regression shows up
 * as a change in that number rather than as silently scrambled audio.
 */
object ReadingOrder {

    /**
     * A block at least this fraction of the page wide is treated as spanning
     * the page rather than sitting in a column.
     *
     * 0.80 rather than something nearer 1.0 because a full-width element
     * usually stops short of the margins on at least one side, and a headline
     * that clears both margins by a few percent is still a headline.
     */
    const val FULL_WIDTH_FRACTION = 0.80

    /**
     * Two blocks are in the same column when they overlap horizontally by at
     * least this fraction of the *narrower* one's width.
     *
     * Measuring against the narrower block is what makes an indented quote or
     * a short paragraph join the column it visually belongs to, instead of
     * being pushed out into a column of its own because the overlap looked
     * small next to a full-column-width neighbour.
     */
    const val COLUMN_OVERLAP_FRACTION = 0.50

    /**
     * Returns [blocks] renumbered into reading order.
     *
     * The returned list is sorted, and each block's [TextBlock.order] is its
     * index in it. Ids are left alone: [TextBlock.id] identifies a block for
     * the lifetime of the page, and touch-explore depends on that.
     */
    fun sort(blocks: List<TextBlock>, pageWidth: Int): List<TextBlock> {
        if (blocks.size <= 1) {
            return blocks.mapIndexed { i, b -> b.copy(order = i) }
        }

        val fullWidthMin = pageWidth * FULL_WIDTH_FRACTION
        val dividers = blocks.filter { it.bbox.width >= fullWidthMin }.sortedBy { it.bbox.y }
        val rest = blocks.filter { it.bbox.width < fullWidthMin }

        // Band index = how many dividers sit above this block's centre. Centre
        // rather than top, so a block that begins level with a divider but
        // clearly belongs below it lands in the right band.
        fun bandOf(b: TextBlock): Int {
            val centre = b.bbox.y + b.bbox.height / 2.0
            return dividers.count { it.bbox.y + it.bbox.height <= centre }
        }

        val banded = rest.groupBy(::bandOf)
        val out = mutableListOf<TextBlock>()
        for (band in 0..dividers.size) {
            banded[band]?.let { out += orderWithinBand(it) }
            // The divider that closes this band is read after its contents.
            dividers.getOrNull(band)?.let { out += it }
        }

        // Nothing may be dropped: a block whose band arithmetic went astray is
        // still part of the page, and silently losing it would be the worst
        // possible failure here.
        if (out.size != blocks.size) {
            val missing = blocks.filter { b -> out.none { it.id == b.id } }
            out += missing.sortedBy { it.bbox.y }
        }
        return out.mapIndexed { i, b -> b.copy(order = i) }
    }

    /** Groups a band into columns, left to right, each ordered top to bottom. */
    private fun orderWithinBand(band: List<TextBlock>): List<TextBlock> {
        if (band.size <= 1) return band

        // A block joins a column only if it overlaps EVERY member, not merely
        // one of them. Matching any member makes column membership transitive,
        // and transitivity is fatal here: a block wider than a column but
        // narrower than a divider -- a photo, a table, a pull-quote -- overlaps
        // the left column by 0.55 and the right by 0.55 and welds them into a
        // single column, which is precisely the interleaving this whole stage
        // exists to prevent. Requiring all members costs a ragged column being
        // split occasionally, which merely inserts a pause; the alternative
        // reads two columns as one and is unrecoverable by the listener.
        val columns = mutableListOf<MutableList<TextBlock>>()
        for (b in band.sortedBy { it.bbox.x }) {
            val hit = columns.firstOrNull { col -> col.all { sameColumn(it, b) } }
            if (hit != null) hit += b else columns += mutableListOf(b)
        }

        return columns
            .sortedBy { col -> col.minOf { it.bbox.x } }
            .flatMap { col -> col.sortedBy { it.bbox.y } }
    }

    /** True when two blocks overlap enough horizontally to share a column. */
    private fun sameColumn(a: TextBlock, b: TextBlock): Boolean {
        val left = maxOf(a.bbox.x, b.bbox.x)
        val right = minOf(a.bbox.x + a.bbox.width, b.bbox.x + b.bbox.width)
        val overlap = right - left
        if (overlap <= 0) return false
        val narrower = minOf(a.bbox.width, b.bbox.width)
        if (narrower <= 0) return false
        return overlap.toDouble() / narrower >= COLUMN_OVERLAP_FRACTION
    }

    /**
     * How much this ordering disagrees with the order the blocks arrived in,
     * as the fraction of block *pairs* whose relative order changed.
     *
     * Pairwise rather than positional. Comparing positions looks simpler but
     * is badly misleading here: moving one block to the front shifts every
     * other block by one, so a single correct fix reads as "95% of the page
     * moved" and drowns out the case where the ordering has genuinely gone
     * wrong. Swapping two neighbours costs one pair; reversing the page costs
     * all of them.
     *
     * 0.0 means Tesseract already had it right, which is what a single-column
     * page should produce.
     */
    fun disagreesWithSourceOrder(original: List<TextBlock>, sorted: List<TextBlock>): Double {
        if (original.size < 2) return 0.0
        val position = sorted.withIndex().associate { (i, b) -> b.id to i }
        var discordant = 0
        var total = 0
        for (i in original.indices) {
            for (j in i + 1 until original.size) {
                val a = position[original[i].id] ?: continue
                val b = position[original[j].id] ?: continue
                total++
                if (a > b) discordant++
            }
        }
        return if (total == 0) 0.0 else discordant.toDouble() / total
    }
}
