package com.pagereader.android.reading

import com.pagereader.android.detect.Side
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

/**
 * The verdict on a capture.
 *
 * The asymmetry is the whole point: reading a garbled page to someone who
 * cannot check it against the paper is worse than asking them to retake, so a
 * doubtful capture must be reported rather than read.
 */
class CaptureQualityTest {


    private fun page(confidence: Float, blocks: Int = 3) = OcrPage(
        pageWidth = 1000, pageHeight = 1400,
        blocks = (0 until blocks).map {
            TextBlock(it, it, "some text here", Rect(0, it * 100, 500, 80),
                confidence, BlockKind.BODY, 20f, 2)
        },
        meanConfidence = confidence, elapsedMs = 100,
    )

    @Test
    fun aGoodCaptureIsUsableAndSaysNothing() {
        val v = CaptureQuality.assess(emptySet(), page(0.88f))
        assertTrue(v.usable)
        assertNull("a good capture should not interrupt", v.message)
    }

    @Test
    fun aClippedEdgeIsNamed() {
        val v = CaptureQuality.assess(setOf(Side.LEFT), page(0.88f))
        assertFalse(v.usable)
        assertEquals("The left edge was cut off. Volume up to retake.", v.message)
    }

    /** Which edges matters: it is what tells the user how to move the phone. */
    @Test
    fun severalClippedEdgesAreAllNamed() {
        val v = CaptureQuality.assess(setOf(Side.LEFT, Side.TOP), page(0.88f))
        assertEquals("The left and top edges were cut off. Volume up to retake.", v.message)
    }

    @Test
    fun lowConfidenceIsReportedAsBlurry() {
        val v = CaptureQuality.assess(emptySet(), page(0.42f))
        assertFalse(v.usable)
        assertTrue(v.message!!.contains("blurry"))
        assertTrue("must say what to do", v.message!!.contains("retake"))
    }

    @Test
    fun noTextAtAllAsksForMoreLight() {
        val v = CaptureQuality.assess(emptySet(), page(0f, blocks = 0))
        assertFalse(v.usable)
        assertTrue(v.message!!.contains("could not find any text"))
        assertTrue(v.message!!.contains("light"))
    }

    /**
     * A clipped page usually also reads badly. Reporting the clipping is more
     * useful than reporting the blur, because it names something the user can
     * actually change.
     */
    @Test
    fun clippingIsReportedInPreferenceToBlur() {
        val v = CaptureQuality.assess(setOf(Side.RIGHT), page(0.30f))
        assertTrue("should name the edge, not the blur: ${v.message}",
            v.message!!.contains("right edge"))
    }

    @Test
    fun textIsStillJudgedWhenNothingWasClipped() {
        assertTrue(CaptureQuality.assess(emptySet(), page(0.88f)).usable)
        assertFalse(CaptureQuality.assess(emptySet(), page(0.31f)).usable)
    }

    /** The threshold is the empirically validated one, not a fresh guess. */
    @Test
    fun theThresholdMatchesTheValidatedValue() {
        assertTrue(CaptureQuality.assess(emptySet(), page(OcrPage.USABLE_CONFIDENCE)).usable)
        assertFalse(CaptureQuality.assess(emptySet(), page(OcrPage.USABLE_CONFIDENCE - 0.01f)).usable)
    }
}
