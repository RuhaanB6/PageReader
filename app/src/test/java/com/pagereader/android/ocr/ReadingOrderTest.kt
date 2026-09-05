package com.pagereader.android.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

/**
 * Reading order, on synthetic layouts where the correct answer is not in doubt.
 *
 * These are JVM tests: the logic is pure geometry over [TextBlock] and needs no
 * device, so it runs in milliseconds and can be exercised on every change.
 *
 * The failure this guards against is specific. Tesseract orders roughly
 * top-to-bottom across the whole page, which on two columns interleaves them —
 * the listener gets half a sentence from the left, then half from the right.
 * A sighted reader recovers instantly; a listener cannot, which is why this is
 * worth its own stage.
 */
class ReadingOrderTest {

    private var nextId = 0

    private fun block(
        x: Int, y: Int, w: Int, h: Int,
        text: String = "text",
        kind: BlockKind = BlockKind.BODY,
    ) = TextBlock(
        id = nextId++,
        order = 0,
        text = text,
        bbox = Rect(x, y, w, h),
        confidence = 0.9f,
        kind = kind,
        medianWordHeight = 20f,
        lineCount = 1,
    )

    private fun orderOf(sorted: List<TextBlock>) = sorted.map { it.id }

    private val pageW = 1000

    @Test
    fun singleColumnIsLeftAlone() {
        val a = block(100, 100, 800, 80)
        val b = block(100, 200, 800, 80)
        val c = block(100, 300, 800, 80)
        val sorted = ReadingOrder.sort(listOf(a, b, c), pageW)
        assertEquals(listOf(a.id, b.id, c.id), orderOf(sorted))
        assertEquals("a single column should not be reordered", 0.0,
            ReadingOrder.disagreesWithSourceOrder(listOf(a, b, c), sorted), 0.0001)
    }

    /**
     * The case the stage exists for. Fed in the interleaved order Tesseract
     * produces, both columns must come out whole and in the right sequence.
     */
    @Test
    fun twoColumnsAreReadOneAtATime() {
        val l1 = block(50, 100, 400, 80)
        val r1 = block(550, 110, 400, 80)
        val l2 = block(50, 200, 400, 80)
        val r2 = block(550, 210, 400, 80)
        val l3 = block(50, 300, 400, 80)
        val r3 = block(550, 310, 400, 80)

        // Interleaved, as a top-to-bottom sweep over the whole page gives it.
        val source = listOf(l1, r1, l2, r2, l3, r3)
        val sorted = ReadingOrder.sort(source, pageW)

        assertEquals(listOf(l1.id, l2.id, l3.id, r1.id, r2.id, r3.id), orderOf(sorted))
        assertTrue("the two orders should differ on a two-column page",
            ReadingOrder.disagreesWithSourceOrder(source, sorted) > 0.0)
    }

    /** A headline divides the page: everything above it precedes it. */
    @Test
    fun aFullWidthHeadlineSplitsThePageIntoBands() {
        val above = block(50, 50, 400, 60)
        val headline = block(40, 150, 920, 90, kind = BlockKind.HEADING)
        val belowL = block(50, 300, 400, 80)
        val belowR = block(550, 310, 400, 80)

        val sorted = ReadingOrder.sort(listOf(above, headline, belowL, belowR), pageW)
        assertEquals(listOf(above.id, headline.id, belowL.id, belowR.id), orderOf(sorted))
    }

    /**
     * Columns must not leak across a divider. Before banding, the left column
     * of the lower half would join the left column of the upper half and be
     * read as one run.
     */
    @Test
    fun columnsDoNotSpanAFullWidthDivider() {
        val topL = block(50, 100, 400, 80)
        val topR = block(550, 100, 400, 80)
        val rule = block(40, 250, 920, 4, text = "", kind = BlockKind.SEPARATOR)
        val botL = block(50, 350, 400, 80)
        val botR = block(550, 350, 400, 80)

        val sorted = ReadingOrder.sort(listOf(topL, topR, rule, botL, botR), pageW)
        assertEquals(
            listOf(topL.id, topR.id, rule.id, botL.id, botR.id),
            orderOf(sorted),
        )
    }

    /**
     * An indented block — a pull quote, a list — belongs to the column it sits
     * in. Overlap is measured against the narrower block for exactly this.
     */
    @Test
    fun anIndentedBlockJoinsItsColumn() {
        val wide = block(50, 100, 400, 80)
        val indented = block(110, 200, 280, 60)
        val below = block(50, 300, 400, 80)
        val other = block(550, 100, 400, 80)

        val sorted = ReadingOrder.sort(listOf(wide, indented, below, other), pageW)
        assertEquals(listOf(wide.id, indented.id, below.id, other.id), orderOf(sorted))
    }

    @Test
    fun orderFieldIsTheIndexInTheResult() {
        val blocks = listOf(
            block(550, 110, 400, 80),
            block(50, 100, 400, 80),
            block(50, 200, 400, 80),
        )
        val sorted = ReadingOrder.sort(blocks, pageW)
        sorted.forEachIndexed { i, b -> assertEquals("order should equal position", i, b.order) }
    }

    /** Ids must survive: touch-explore identifies a block by id for the page's life. */
    @Test
    fun everyBlockSurvivesAndKeepsItsId() {
        val blocks = (0 until 12).map { i ->
            block(if (i % 2 == 0) 50 else 550, 100 + (i / 2) * 100, 400, 80)
        }
        val sorted = ReadingOrder.sort(blocks, pageW)
        assertEquals(blocks.size, sorted.size)
        assertEquals(blocks.map { it.id }.toSet(), sorted.map { it.id }.toSet())
    }

    @Test
    fun handlesEmptyAndSingleBlockPages() {
        assertTrue(ReadingOrder.sort(emptyList(), pageW).isEmpty())
        val one = block(10, 10, 100, 20)
        assertEquals(listOf(one.id), orderOf(ReadingOrder.sort(listOf(one), pageW)))
    }

    /** Three columns, to check the grouping is not hardcoded to two. */
    @Test
    fun threeColumnsAreOrderedLeftToRight() {
        val a1 = block(30, 100, 280, 60)
        val b1 = block(360, 100, 280, 60)
        val c1 = block(690, 100, 280, 60)
        val a2 = block(30, 200, 280, 60)
        val b2 = block(360, 200, 280, 60)
        val c2 = block(690, 200, 280, 60)

        val sorted = ReadingOrder.sort(listOf(a1, b1, c1, a2, b2, c2), pageW)
        assertEquals(listOf(a1.id, a2.id, b1.id, b2.id, c1.id, c2.id), orderOf(sorted))
    }

    /**
     * The disagreement metric must not be inflated by a single move.
     *
     * A positional comparison reports ~100% when one block goes to the front,
     * because everything else shifts by one -- which would hide a genuinely
     * scrambled page behind a number that is always near 1.
     */
    @Test
    fun movingOneBlockDoesNotLookLikeATotalReordering() {
        val blocks = (0 until 10).map { block(100, 100 + it * 100, 800, 80) }
        val movedToFront = listOf(blocks.last()) + blocks.dropLast(1)
        val d = ReadingOrder.disagreesWithSourceOrder(blocks, movedToFront)
        assertTrue("one block moved should be a small disagreement, got $d", d < 0.25)

        val reversed = blocks.reversed()
        val full = ReadingOrder.disagreesWithSourceOrder(blocks, reversed)
        assertTrue("a full reversal should be near total, got $full", full > 0.95)
    }
}
