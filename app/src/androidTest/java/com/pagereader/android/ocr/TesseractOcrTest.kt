package com.pagereader.android.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * The real engine, end to end.
 *
 * Uses a rendered page rather than a photograph so the expected text is known
 * exactly — a photo would test the camera and the dewarp at the same time, and
 * a failure would not say which layer broke.
 */
@RunWith(AndroidJUnit4::class)
class TesseractOcrTest {

    private var ocr: TesseractOcr? = null

    @Before
    fun setUp() {
        ocr = TesseractOcr.create(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        assertNotNull("tesseract failed to initialise -- is assets/tessdata present?", ocr)
    }

    @After
    fun tearDown() {
        ocr?.close()
        ocr = null
    }

    /** White page, black text, roughly the proportions of a real capture. */
    private fun renderedPage(lines: List<String>, width: Int = 1200, height: Int = 1600): Mat {
        val m = Mat(height, width, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        var y = 200.0
        for (line in lines) {
            Imgproc.putText(
                m, line, Point(120.0, y),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, Scalar(0.0, 0.0, 0.0), 3,
            )
            y += 110.0
        }
        return m
    }

    private val sample = listOf(
        "The quick brown fox",
        "jumps over the lazy dog",
        "PageReader reads this aloud",
    )

    @Test
    fun readsRenderedText() {
        val page = renderedPage(sample)
        try {
            val result = ocr!!.recognise(page)
            val text = result.blocks.joinToString(" ") { it.text }.lowercase()
            // Word-level rather than exact-string: OCR on synthetic glyphs is
            // allowed the odd substitution, and asserting an exact transcript
            // would make this test brittle for no gain.
            val wanted = listOf("quick", "brown", "jumps", "lazy", "pagereader", "aloud")
            val missing = wanted.filter { !text.contains(it) }
            assertTrue("missing $missing in: ${text.take(200)}", missing.isEmpty())
            assertTrue("confidence ${result.meanConfidence} too low for clean rendered text",
                result.meanConfidence >= OcrPage.USABLE_CONFIDENCE)
            assertTrue("elapsedMs was not recorded", result.elapsedMs > 0)
        } finally {
            page.release()
        }
    }

    @Test
    fun reportsPageGeometryAndBoxesInsideIt() {
        val page = renderedPage(sample)
        try {
            val r = ocr!!.recognise(page)
            assertTrue("no blocks", r.blocks.isNotEmpty())
            for (b in r.blocks) {
                assertTrue("block ${b.id} escapes the page: ${b.bbox}",
                    b.bbox.x >= 0 && b.bbox.y >= 0 &&
                        b.bbox.x + b.bbox.width <= r.pageWidth &&
                        b.bbox.y + b.bbox.height <= r.pageHeight)
            }
        } finally {
            page.release()
        }
    }

    /**
     * The rotation fallback, which is the one recovery this stage owns.
     *
     * A sideways page scored 0.36 on real data and recovered to 0.87 rerun
     * rotated -- 1 sample in 10, so a genuine failure mode rather than a
     * theoretical one. Feeding a page rotated 90 degrees anticlockwise means
     * the internal clockwise retry lands it upright.
     */
    @Test
    fun recoversAPageThatWasPhotographedSideways() {
        val upright = renderedPage(sample)
        val sideways = Mat()
        try {
            Core.rotate(upright, sideways, Core.ROTATE_90_COUNTERCLOCKWISE)
            val r = ocr!!.recognise(sideways)
            val text = r.blocks.joinToString(" ") { it.text }.lowercase()
            assertTrue(
                "sideways page was not recovered: conf=${r.meanConfidence} text=${text.take(150)}",
                text.contains("quick") || text.contains("brown"),
            )
            assertTrue("confidence ${r.meanConfidence} after retry is still unusable",
                r.meanConfidence >= OcrPage.USABLE_CONFIDENCE)
        } finally {
            upright.release()
            sideways.release()
        }
    }

    /** A blank page is not an error; it is a page with nothing on it. */
    @Test
    fun blankPageReturnsNoBlocksRatherThanThrowing() {
        val blank = Mat(800, 600, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        try {
            val r = ocr!!.recognise(blank)
            assertTrue("expected no text, got ${r.textBlocks.size} blocks",
                r.textBlocks.isEmpty())
        } finally {
            blank.release()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
