package com.pagereader.android.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader

/**
 * [HocrParser] against real Tesseract output.
 *
 * The fixtures in `assets/hocr` were produced by Tesseract 5.5.3 with
 * `--psm 3 --oem 1 --dpi 300` — the exact configuration and version the
 * pipeline was validated on — over four of the ten sample pages, chosen to
 * cover every hOCR class between them: `academic` has a header and many
 * separators, `ppt` has captions and photos, `textbook_test-1` has textfloats
 * and photos, and `newspaper` is the deliberately bad one.
 *
 * The expected counts below were derived from the fixtures with `grep -c`,
 * independently of the parser, so this checks the parser against Tesseract
 * rather than against itself.
 *
 * (The planning-era Python oracle that the handoff says to port does not exist
 * on this machine — it was never saved. These fixtures replace it, and are
 * arguably better: they test against actual output rather than a second
 * implementation.)
 */
@RunWith(AndroidJUnit4::class)
class HocrParserTest {

    private fun parse(name: String): HocrParser.ParsedPage {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("hocr/$name.hocr").use { String(it.readBytes()) }
        return StringReader(text).use { HocrParser.parse(it) }
    }

    private fun kinds(p: HocrParser.ParsedPage) =
        p.blocks.groupingBy { it.kind }.eachCount()

    @Test
    fun readsPageGeometry() {
        parse("academic").let { assertEquals(1653, it.pageWidth); assertEquals(2334, it.pageHeight) }
        parse("ppt").let { assertEquals(2667, it.pageWidth); assertEquals(1500, it.pageHeight) }
        parse("newspaper").let { assertEquals(680, it.pageWidth); assertEquals(622, it.pageHeight) }
    }

    /** grep: academic has 16 ocr_carea, 14 ocr_separator, 1 ocr_header. */
    @Test
    fun academicBlockCountsMatchTheSource() {
        val p = parse("academic")
        val k = kinds(p)
        assertEquals("separators", 14, k[BlockKind.SEPARATOR] ?: 0)
        assertEquals("figures", 0, k[BlockKind.FIGURE] ?: 0)
        // The one ocr_header line promotes exactly its own carea, not the page.
        assertEquals("headings", 1, k[BlockKind.HEADING] ?: 0)
        val careas = (k[BlockKind.HEADING] ?: 0) + (k[BlockKind.BODY] ?: 0) +
            (k[BlockKind.CAPTION] ?: 0) + (k[BlockKind.SIDEBAR] ?: 0)
        assertEquals("careas", 16, careas)
    }

    /** grep: ppt has 6 ocr_carea, 3 ocr_caption, 2 ocr_photo, 0 separators. */
    @Test
    fun pptCarriesCaptionsAndFigures() {
        val k = kinds(parse("ppt"))
        assertEquals("figures", 2, k[BlockKind.FIGURE] ?: 0)
        assertEquals("separators", 0, k[BlockKind.SEPARATOR] ?: 0)
        assertTrue("expected at least one caption, got $k", (k[BlockKind.CAPTION] ?: 0) >= 1)
    }

    /** grep: textbook_test-1 has 4 ocr_textfloat, 11 ocr_photo, 12 separators. */
    @Test
    fun textbookCarriesSidebarsAndFigures() {
        val k = kinds(parse("textbook_test-1"))
        assertEquals("figures", 11, k[BlockKind.FIGURE] ?: 0)
        assertEquals("separators", 12, k[BlockKind.SEPARATOR] ?: 0)
        assertTrue("expected at least one sidebar, got $k", (k[BlockKind.SIDEBAR] ?: 0) >= 1)
    }

    /**
     * The self-diagnostic has to actually separate good pages from bad, since
     * the whole rotation-retry and "I could not read this" path hangs off it.
     * `newspaper` is a 680 px thumbnail and genuinely unreadable; the rest are
     * fine. Word-level mean confidence measured with grep: 0.914, 0.872, 0.883
     * and 0.579 respectively.
     */
    @Test
    fun meanConfidenceSeparatesGoodPagesFromBad() {
        for (good in listOf("academic", "ppt", "textbook_test-1")) {
            val c = parse(good).meanConfidence
            assertTrue("$good scored $c, expected >= ${OcrPage.USABLE_CONFIDENCE}",
                c >= OcrPage.USABLE_CONFIDENCE)
        }
        val bad = parse("newspaper").meanConfidence
        assertTrue("newspaper scored $bad, expected < ${OcrPage.USABLE_CONFIDENCE}",
            bad < OcrPage.USABLE_CONFIDENCE)
    }

    /**
     * Text has to survive parsing intact. Figures and separators carry no
     * words; everything else should, and the words should be real.
     */
    @Test
    fun textIsExtractedAndFiguresAreEmpty() {
        val p = parse("academic")
        for (b in p.blocks) {
            if (b.kind == BlockKind.FIGURE || b.kind == BlockKind.SEPARATOR) {
                assertEquals("${b.kind} #${b.id} should carry no text", "", b.text)
            }
        }
        // Join only the blocks that carry words: figures and separators are
        // legitimately empty, and including them would manufacture the double
        // spaces this is meant to detect in the parser.
        val texty = p.blocks.filter { it.text.isNotBlank() }
        val all = texty.joinToString(" ") { it.text }
        assertTrue("expected recognisable words, got: ${all.take(120)}",
            all.contains("Table") && all.contains("PEREZ"))
        // No double spaces or stray newlines leaking out of the span joining.
        assertTrue("whitespace was not normalised inside a block",
            texty.none { it.text.contains("  ") || it.text.contains("\n") })
    }

    /** Geometry must be sane: inside the page, positive extent. */
    @Test
    fun everyBoxIsInsideThePage() {
        for (name in listOf("academic", "ppt", "textbook_test-1", "newspaper")) {
            val p = parse(name)
            for (b in p.blocks) {
                assertTrue("$name #${b.id} has non-positive size ${b.bbox}",
                    b.bbox.width > 0 && b.bbox.height > 0)
                assertTrue("$name #${b.id} escapes the page: ${b.bbox} vs ${p.pageWidth}x${p.pageHeight}",
                    b.bbox.x >= 0 && b.bbox.y >= 0 &&
                        b.bbox.x + b.bbox.width <= p.pageWidth &&
                        b.bbox.y + b.bbox.height <= p.pageHeight)
            }
        }
    }

    /** Word height feeds M6's heading promotion, so it must be populated. */
    @Test
    fun textBlocksReportAWordHeight() {
        val p = parse("academic")
        val texty = p.blocks.filter { it.text.isNotBlank() }
        assertTrue("no text blocks parsed", texty.isNotEmpty())
        assertTrue("every text block should report a word height",
            texty.all { it.medianWordHeight > 0f })
    }

    @Test
    fun emptyDocumentDoesNotThrow() {
        val p = StringReader(
            "<?xml version='1.0'?><html><body></body></html>"
        ).use { HocrParser.parse(it, fallbackWidth = 7, fallbackHeight = 9) }
        assertEquals(0, p.blocks.size)
        assertEquals(0f, p.meanConfidence, 0.001f)
        assertEquals(7, p.pageWidth)
        assertEquals(9, p.pageHeight)
    }
}
