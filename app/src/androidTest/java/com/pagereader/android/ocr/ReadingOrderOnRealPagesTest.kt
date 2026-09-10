package com.pagereader.android.ocr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader

/**
 * M6 over real Tesseract output, rather than synthetic layouts.
 *
 * The unit tests prove the ordering rules on shapes where the right answer is
 * obvious. This proves they do something sensible on actual pages, and reports
 * how far the result moves from Tesseract's own order — the spec asks for that
 * number explicitly, because it is the early warning if the re-sort ever starts
 * scrambling pages that were already correct.
 *
 * These are assertions about *invariants*, not about exact orderings: the right
 * reading order for a real academic paper is a judgement call, and pinning it
 * to a golden list would make the test a transcript of whatever the code did on
 * the day it was written.
 */
@RunWith(AndroidJUnit4::class)
class ReadingOrderOnRealPagesTest {

    private fun page(name: String): HocrParser.ParsedPage {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("hocr/$name.hocr").use { String(it.readBytes()) }
        return StringReader(text).use { HocrParser.parse(it) }
    }

    private val samples = listOf("academic", "ppt", "textbook_test-1", "newspaper")

    /** Nothing may be lost or duplicated by the re-sort, on any real page. */
    @Test
    fun everyBlockSurvivesOnEveryPage() {
        for (name in samples) {
            val p = page(name)
            val sorted = ReadingOrder.sort(p.blocks, p.pageWidth)
            assertEquals("$name: block count changed", p.blocks.size, sorted.size)
            assertEquals("$name: ids changed", p.blocks.map { it.id }.toSet(),
                sorted.map { it.id }.toSet())
            sorted.forEachIndexed { i, b ->
                assertEquals("$name: order should be the index", i, b.order)
            }
        }
    }

    /**
     * Reports the disagreement with Tesseract's order. Not an assertion about
     * a specific number -- it is a measurement printed for a human, plus a
     * sanity bound: a re-sort that moved *every* block on a mostly
     * single-column page would mean the rules are inverted.
     */
    @Test
    fun reportsWhereItDisagreesWithTesseract() {
        for (name in samples) {
            val p = page(name)
            val sorted = ReadingOrder.sort(p.blocks, p.pageWidth)
            val moved = ReadingOrder.disagreesWithSourceOrder(p.blocks, sorted)
            Log.i(TAG, "$name: ${p.blocks.size} blocks, " +
                "${"%.3f".format(moved)} of pairs reordered")
            // Pairwise, so this is not inflated by a single block moving. Past
            // half the pairs would mean the ordering is closer to a reversal of
            // Tesseract's than a correction of it, which is not plausible on a
            // real page and would mean the rules are inverted.
            assertTrue(
                "$name: ${"%.3f".format(moved)} of pairs reordered -- the ordering " +
                    "rules look inverted rather than corrective",
                moved < 0.5,
            )
        }
    }

    /**
     * Labelling must not swallow the page. If the header/footer rule or the
     * heading promotion fired on most blocks, something is wrong with the
     * thresholds rather than with the page.
     */
    @Test
    fun derivedLabelsStayAMinorityOnRealPages() {
        for (name in samples) {
            val p = page(name)
            val labelled = BlockLabels.label(p.blocks, p.pageWidth, p.pageHeight)
            val texty = labelled.count { it.text.isNotBlank() }
            val margins = labelled.count { it.kind == BlockKind.HEADER_FOOTER }
            val headings = labelled.count { it.kind == BlockKind.HEADING }
            Log.i(TAG, "$name: $texty text blocks, $margins header/footer, $headings headings")
            if (texty > 4) {
                assertTrue("$name: $margins of $texty text blocks called header/footer",
                    margins * 2 < texty)
                assertTrue("$name: $headings of $texty text blocks promoted to heading",
                    headings * 2 < texty)
            }
        }
    }

    /** The spoken summary has to be sayable for every page, including the bad one. */
    @Test
    fun everyPageProducesASpeakableSummary() {
        for (name in samples) {
            val p = page(name)
            val labelled = BlockLabels.label(
                ReadingOrder.sort(p.blocks, p.pageWidth), p.pageWidth, p.pageHeight,
            )
            val ocrPage = OcrPage(p.pageWidth, p.pageHeight, labelled, p.meanConfidence, 0)
            val summary = BlockLabels.pageSummary(ocrPage)
            Log.i(TAG, "$name summary: $summary")
            assertTrue("$name produced an empty summary", summary.isNotBlank())
            // No stray markup or newlines: this string goes straight to TTS.
            assertTrue("$name summary contains a newline", !summary.contains("\n"))
            assertTrue("$name summary contains markup", !summary.contains("<"))
        }
    }

    /** Playback must not be empty on a page that genuinely has text. */
    @Test
    fun playbackKeepsTheBodyOfARealPage() {
        for (name in listOf("academic", "textbook_test-1")) {
            val p = page(name)
            val labelled = BlockLabels.label(
                ReadingOrder.sort(p.blocks, p.pageWidth), p.pageWidth, p.pageHeight,
            )
            val ocrPage = OcrPage(p.pageWidth, p.pageHeight, labelled, p.meanConfidence, 0)
            val playable = BlockLabels.playbackBlocks(ocrPage)
            val words = playable.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
            Log.i(TAG, "$name: ${playable.size} playable blocks, $words words")
            assertTrue("$name: playback dropped everything", playable.isNotEmpty())
            assertTrue("$name: only $words words survived playback filtering", words > 50)
        }
    }

    companion object {
        private const val TAG = "ReadingOrder"
    }
}
