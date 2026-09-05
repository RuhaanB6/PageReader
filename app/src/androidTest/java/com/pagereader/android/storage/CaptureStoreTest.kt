package com.pagereader.android.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock
import com.pagereader.android.reading.PagePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

/**
 * The capture store, which owns the user's document and their place in it.
 *
 * The round trip is the important part: a page written and read back has to be
 * identical in the fields playback depends on, because a listener resuming a
 * page has no way to notice that a block changed kind or lost its text.
 */
@RunWith(AndroidJUnit4::class)
class CaptureStoreTest {

    private lateinit var store: CaptureStore
    private val written = mutableListOf<String>()

    @Before
    fun setUp() {
        store = CaptureStore(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        written.forEach { store.delete(it) }
        written.clear()
    }

    private fun page(blocks: Int = 3) = OcrPage(
        pageWidth = 1200, pageHeight = 1600,
        blocks = (0 until blocks).map {
            TextBlock(
                id = it, order = it,
                text = "Block $it text with some words.",
                bbox = Rect(10 * it, 20 * it, 400, 90),
                confidence = 0.8f + it * 0.01f,
                kind = if (it == 0) BlockKind.HEADING else BlockKind.BODY,
                medianWordHeight = 18f + it,
                lineCount = it + 1,
            )
        },
        meanConfidence = 0.87f, elapsedMs = 2345,
    )

    private fun image() = Mat(80, 60, CvType.CV_8UC3, Scalar(200.0, 190.0, 180.0))

    private fun save(p: OcrPage = page()): CaptureStore.Capture {
        val img = image()
        val c = store.save(img, p)
        img.release()
        assertNotNull("save returned null", c)
        written += c!!.id
        return c
    }

    @Test
    fun savesThreeFilesPerCapture() {
        val c = save()
        assertTrue("page.jpg missing", c.pageFile.exists() && c.pageFile.length() > 0)
        assertTrue("ocr.json missing", c.ocrFile.exists())
        assertTrue("state.json missing", c.stateFile.exists())
    }

    @Test
    fun ocrSurvivesTheRoundTripIntact() {
        val original = page(4)
        val c = save(original)
        val back = store.loadOcr(c.id)
        assertNotNull("could not read back", back)
        assertEquals(original.pageWidth, back!!.pageWidth)
        assertEquals(original.pageHeight, back.pageHeight)
        assertEquals(original.meanConfidence, back.meanConfidence, 0.001f)
        assertEquals(original.elapsedMs, back.elapsedMs)
        assertEquals(original.blocks.size, back.blocks.size)

        original.blocks.zip(back.blocks).forEach { (a, b) ->
            assertEquals("id", a.id, b.id)
            assertEquals("order", a.order, b.order)
            assertEquals("text", a.text, b.text)
            assertEquals("kind", a.kind, b.kind)
            assertEquals("lineCount", a.lineCount, b.lineCount)
            assertEquals("bbox x", a.bbox.x, b.bbox.x)
            assertEquals("bbox y", a.bbox.y, b.bbox.y)
            assertEquals("bbox w", a.bbox.width, b.bbox.width)
            assertEquals("bbox h", a.bbox.height, b.bbox.height)
            assertEquals("confidence", a.confidence, b.confidence, 0.001f)
            assertEquals("wordHeight", a.medianWordHeight, b.medianWordHeight, 0.001f)
        }
    }

    @Test
    fun positionIsSavedAndRestored() {
        val c = save()
        assertEquals("a fresh capture starts at the beginning",
            PagePlayer.Position.START, store.loadPosition(c.id))

        store.savePosition(c.id, PagePlayer.Position(2, 5))
        assertEquals(PagePlayer.Position(2, 5), store.loadPosition(c.id))
    }

    /** Being killed mid-page is the normal case, so a missing state file is not an error. */
    @Test
    fun aMissingStateFileReadsAsTheStartOfThePage() {
        val c = save()
        c.stateFile.delete()
        assertEquals(PagePlayer.Position.START, store.loadPosition(c.id))
    }

    @Test
    fun corruptFilesDegradeRatherThanThrow() {
        val c = save()
        c.ocrFile.writeText("{ not json at all")
        c.stateFile.writeText("also not json")
        assertNull("corrupt OCR should read as null", store.loadOcr(c.id))
        assertEquals("corrupt state should read as the start",
            PagePlayer.Position.START, store.loadPosition(c.id))
    }

    @Test
    fun listReturnsMostRecentFirst() {
        val a = save()
        Thread.sleep(1100) // ids and mtimes are second-resolution
        val b = save()
        val ids = store.list().map { it.id }
        assertTrue("expected both captures, got $ids", ids.containsAll(listOf(a.id, b.id)))
        assertTrue("newest should come first",
            ids.indexOf(b.id) < ids.indexOf(a.id))
        assertEquals(b.id, store.mostRecent()?.id)
    }

    /**
     * Nothing else deletes these and a page is around a megabyte, so the cap
     * is what stops the store growing without bound.
     */
    @Test
    fun pruneKeepsOnlyTheNewest() {
        val ids = (0 until 4).map { save(page(1)).id }
        store.prune(keep = 2)
        val left = store.list().map { it.id }
        assertEquals("should keep exactly two", 2, left.count { it in ids })
        assertTrue("the two newest should survive", left.containsAll(ids.takeLast(2)))
    }

    /** An unknown kind from a newer build must not silently drop the block. */
    @Test
    fun anUnknownBlockKindFallsBackToBodyRatherThanVanishing() {
        val c = save(page(2))
        val text = c.ocrFile.readText().replace("\"kind\":\"BODY\"", "\"kind\":\"FUTURE_KIND\"")
        c.ocrFile.writeText(text)
        val back = store.loadOcr(c.id)
        assertNotNull(back)
        assertEquals("no block may be lost", 2, back!!.blocks.size)
        assertTrue("text must survive", back.blocks.all { it.text.isNotBlank() })
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
