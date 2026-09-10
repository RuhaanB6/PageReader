package com.pagereader.android.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

/**
 * Derived labels and the spoken page description.
 *
 * The header/footer rule carries the most weight here. Reading *"Chapter 4 —
 * The Coastal Survey — 87"* aloud between two sentences is the most annoying
 * thing a linear reader can do, and the block that causes it is indistinguish-
 * able from body text except by where it sits.
 */
class BlockLabelsTest {

    private var nextId = 0
    private val pageW = 1000
    private val pageH = 1400

    private fun block(
        x: Int, y: Int, w: Int, h: Int,
        text: String,
        kind: BlockKind = BlockKind.BODY,
        wordHeight: Float = 20f,
        lines: Int = 1,
    ) = TextBlock(
        id = nextId++,
        order = nextId,
        text = text,
        bbox = Rect(x, y, w, h),
        confidence = 0.9f,
        kind = kind,
        medianWordHeight = wordHeight,
        lineCount = lines,
    )

    private fun body(y: Int, text: String = "some ordinary body text here", lines: Int = 4) =
        block(100, y, 800, 200, text, lines = lines)

    @Test
    fun aRunningHeadAtTheTopIsLabelled() {
        val head = block(100, 20, 400, 40, "Chapter 4 The Coastal Survey 87")
        val labelled = BlockLabels.label(listOf(head, body(400)), pageW, pageH)
        assertEquals(BlockKind.HEADER_FOOTER, labelled[0].kind)
        assertEquals("body must not be touched", BlockKind.BODY, labelled[1].kind)
    }

    @Test
    fun aPageNumberAtTheBottomIsLabelled() {
        val foot = block(480, 1340, 40, 30, "87")
        val labelled = BlockLabels.label(listOf(body(400), foot), pageW, pageH)
        assertEquals(BlockKind.HEADER_FOOTER, labelled[1].kind)
    }

    /**
     * The rule must not eat a real opening paragraph that happens to start
     * high on the page — losing the first paragraph would be far worse than
     * reading a page number.
     */
    @Test
    fun longTextHighOnThePageIsNotAHeaderFooter() {
        val long = block(
            100, 20, 800, 60,
            "This opening paragraph sits high on the page but is far too long to be a running head",
        )
        val labelled = BlockLabels.label(listOf(long, body(400)), pageW, pageH)
        assertEquals(BlockKind.BODY, labelled[0].kind)
    }

    /** Only blocks *entirely* inside the margin band qualify. */
    @Test
    fun aBlockCrossingOutOfTheMarginBandIsNotAHeaderFooter() {
        // Band is the top 8% = 112 px; this starts inside it and runs past.
        val straddling = block(100, 60, 800, 200, "short but tall")
        val labelled = BlockLabels.label(listOf(straddling, body(500)), pageW, pageH)
        assertEquals(BlockKind.BODY, labelled[0].kind)
    }

    @Test
    fun bigShortTextIsPromotedToAHeading() {
        val heading = block(100, 300, 700, 70, "The Coastal Survey", wordHeight = 40f, lines = 1)
        val labelled = BlockLabels.label(
            listOf(heading, body(500), body(800), body(1000)), pageW, pageH,
        )
        assertEquals(BlockKind.HEADING, labelled[0].kind)
    }

    /** Large text that runs on is a paragraph in a big font, not a heading. */
    @Test
    fun bigButLongTextIsNotPromoted() {
        val long = block(100, 300, 700, 400, "lots of large text", wordHeight = 40f, lines = 9)
        val labelled = BlockLabels.label(
            listOf(long, body(800), body(1000), body(1200)), pageW, pageH,
        )
        assertEquals(BlockKind.BODY, labelled[0].kind)
    }

    /**
     * A page number in a large font must stay a page number. Header/footer is
     * decided before promotion for exactly this reason.
     */
    @Test
    fun aLargePageNumberStaysAHeaderFooter() {
        val bigNumber = block(480, 1350, 60, 45, "87", wordHeight = 45f, lines = 1)
        val labelled = BlockLabels.label(
            listOf(body(300), body(600), bigNumber), pageW, pageH,
        )
        assertEquals(BlockKind.HEADER_FOOTER, labelled[2].kind)
    }

    @Test
    fun captionsAndSidebarsFromHocrAreNotOverwritten() {
        val caption = block(100, 600, 400, 40, "Council chamber, 1974", kind = BlockKind.CAPTION,
            wordHeight = 40f)
        val labelled = BlockLabels.label(listOf(caption, body(800), body(1000)), pageW, pageH)
        assertEquals(BlockKind.CAPTION, labelled[0].kind)
    }

    @Test
    fun titlesAreSpeakable() {
        assertEquals("The Coastal Survey",
            BlockLabels.title(block(0, 0, 10, 10, "The Coastal Survey", BlockKind.HEADING)))
        assertEquals("Caption: Council chamber, 1974",
            BlockLabels.title(block(0, 0, 10, 10, "Council chamber, 1974", BlockKind.CAPTION)))
        assertEquals("Figure", BlockLabels.title(block(0, 0, 10, 10, "", BlockKind.FIGURE)))
        assertTrue("a long body title should be trimmed",
            BlockLabels.title(
                block(0, 0, 10, 10, "one two three four five six seven eight nine")
            ).endsWith("…"))
    }

    @Test
    fun pageSummaryDescribesShape() {
        val page = OcrPage(
            pageWidth = pageW, pageHeight = pageH,
            blocks = listOf(
                block(100, 100, 800, 80, "The Coastal Survey", BlockKind.HEADING),
                body(300, "word ".repeat(200).trim()),
                body(700, "word ".repeat(160).trim()),
                block(100, 1000, 400, 300, "", BlockKind.FIGURE),
                block(100, 1310, 400, 30, "Council chamber, 1974", BlockKind.CAPTION),
            ),
            meanConfidence = 0.9f, elapsedMs = 1200,
        )
        val s = BlockLabels.pageSummary(page)
        assertTrue("should lead with the headline: $s", s.startsWith("Headline: The Coastal Survey."))
        assertTrue("should count paragraphs: $s", s.contains("2 paragraphs"))
        assertTrue("should estimate a time: $s", s.contains("minutes") || s.contains("a minute"))
        assertTrue("should mention the figure and its caption: $s",
            s.contains("One figure with a caption"))
    }

    @Test
    fun pageSummarySaysSoWhenThereIsNoText() {
        val page = OcrPage(pageW, pageH, emptyList(), 0f, 10)
        assertTrue(BlockLabels.pageSummary(page).contains("could not find any text"))
    }

    /**
     * The announced time must cover everything playback will read, not just
     * the body -- otherwise it understates the wait on any page with headings
     * or captions, and a page is a multi-minute listen.
     */
    @Test
    fun readingTimeCountsEverythingThatWillBePlayed() {
        val page = OcrPage(
            pageWidth = pageW, pageHeight = pageH,
            blocks = listOf(
                block(100, 100, 800, 80, "word ".repeat(300).trim(), BlockKind.HEADING),
                body(300, "word ".repeat(300).trim()),
                block(100, 900, 800, 80, "word ".repeat(300).trim(), BlockKind.CAPTION),
            ),
            meanConfidence = 0.9f, elapsedMs = 10,
        )
        // 900 words of playable text at 180 wpm is 5 minutes; body alone would
        // be 300 words and report under 2.
        val s = BlockLabels.pageSummary(page)
        assertTrue("expected the full playback time, got: $s", s.contains("5 minutes"))
    }

    @Test
    fun readingTimeIsRoundedToSomethingWorthSaying() {
        assertEquals("half a minute", BlockLabels.minutes(40))
        assertEquals("a minute", BlockLabels.minutes(180))
        assertTrue(BlockLabels.minutes(900).contains("5 minutes"))
    }

    /**
     * Skipped blocks must still be present. "Excluded from playback" and
     * "deleted" are very different promises, and only one of them is honest.
     */
    @Test
    fun playbackSkipsMarginsAndDividersButThePageKeepsThem() {
        val page = OcrPage(
            pageWidth = pageW, pageHeight = pageH,
            blocks = listOf(
                block(100, 20, 300, 30, "Chapter 4", BlockKind.HEADER_FOOTER),
                body(300),
                block(100, 600, 800, 4, "", BlockKind.SEPARATOR),
                body(700),
                block(480, 1350, 40, 30, "87", BlockKind.HEADER_FOOTER),
            ),
            meanConfidence = 0.9f, elapsedMs = 100,
        )
        val playable = BlockLabels.playbackBlocks(page)
        assertEquals("only the two body blocks should play", 2, playable.size)
        assertTrue(playable.none { it.kind == BlockKind.HEADER_FOOTER })
        assertTrue(playable.none { it.kind == BlockKind.SEPARATOR })
        assertEquals("nothing may be removed from the page", 5, page.blocks.size)
    }

    /** Figures play: they are announced, not read. */
    @Test
    fun figuresRemainInPlaybackDespiteHavingNoText() {
        val page = OcrPage(
            pageWidth = pageW, pageHeight = pageH,
            blocks = listOf(body(300), block(100, 600, 400, 300, "", BlockKind.FIGURE)),
            meanConfidence = 0.9f, elapsedMs = 100,
        )
        val playable = BlockLabels.playbackBlocks(page)
        assertEquals(2, playable.size)
        assertFalse(playable.none { it.kind == BlockKind.FIGURE })
    }
}
