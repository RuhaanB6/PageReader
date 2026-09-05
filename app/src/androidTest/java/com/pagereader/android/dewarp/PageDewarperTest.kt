package com.pagereader.android.dewarp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagereader.android.detect.GroundTruth
import com.pagereader.android.detect.ObservationSource
import com.pagereader.android.detect.PageDetector
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.Pt
import com.pagereader.android.detect.Side
import com.pagereader.android.detect.YoloDnnEngine
import com.pagereader.android.detect.YoloPageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * The dewarp's contract, which is mostly about what happens when it fails.
 *
 * A blind user cannot see a blank result and retry, so "never dead-ends" is not
 * a nicety -- every path has to return a readable image and say so when it is
 * not the page. Most of these cases are therefore failure cases.
 */
@RunWith(AndroidJUnit4::class)
class PageDewarperTest {

    /**
     * A detector that returns whatever the test wants, including nothing.
     *
     * [quad] is given in [refW] x [refH] and rescaled to whatever frame the
     * dewarper actually hands over. That matters: `PageDewarper` detects on a
     * 1024-wide downscaled copy and scales the quad back up, so a fake that
     * answered in still coordinates regardless would have its quad multiplied
     * by the downscale factor and pushed off the page -- which is a bug in the
     * fake, not in the code under test, and it looks exactly like a broken
     * homography.
     */
    private class FakeDetector(
        private val quad: List<Pt>?,
        private val refW: Int,
        private val refH: Int,
    ) : PageDetector {
        override fun detect(bgr: Mat, roi: Rect?) = PageObservation(
            quad = quad?.map {
                Pt(it.x * bgr.width() / refW, it.y * bgr.height() / refH)
            },
            confidence = 1f,
            coverage = 1f,
            clipped = emptySet(),
            tiltDegrees = 0f,
            frameWidth = bgr.width(),
            frameHeight = bgr.height(),
            source = ObservationSource.COLOR,
        )
    }

    private fun canvas(w: Int, h: Int): Mat =
        Mat(h, w, CvType.CV_8UC3, Scalar(20.0, 20.0, 20.0))

    /**
     * Paints a skewed white quad with a black stripe near its top edge, so the
     * output can be checked for orientation rather than just for size.
     */
    private fun pageOn(canvas: Mat, q: List<Pt>) {
        val poly = org.opencv.core.MatOfPoint(
            *q.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray()
        )
        Imgproc.fillPoly(canvas, listOf(poly), Scalar(240.0, 240.0, 240.0))
        poly.release()
        // Stripe at 10% down the page, interpolated along both sides.
        val l = org.opencv.core.Point(
            q[0].x + (q[3].x - q[0].x) * 0.1, q[0].y + (q[3].y - q[0].y) * 0.1)
        val r = org.opencv.core.Point(
            q[1].x + (q[2].x - q[1].x) * 0.1, q[1].y + (q[2].y - q[1].y) * 0.1)
        Imgproc.line(canvas, l, r, Scalar(0.0, 0.0, 0.0), 12)
    }

    @Test
    fun noQuadReturnsTheWholeFrameAndSaysSo() {
        val still = canvas(1200, 900)
        val r = PageDewarper(FakeDetector(null, 1200, 900)).dewarp(still)
        try {
            assertFalse("must report that it did not dewarp", r.applied)
            assertNull("no homography when nothing was warped", r.homography)
            assertEquals(1200, r.page.width())
            assertEquals(900, r.page.height())
        } finally {
            r.release(); still.release()
        }
    }

    @Test
    fun collapsedQuadIsRejectedRatherThanWarpedIntoASmear() {
        // All four corners within a few pixels: getPerspectiveTransform would
        // accept this happily and warpPerspective would produce garbage that
        // OCR reads as text rather than as a failure.
        val still = canvas(1200, 900)
        val collapsed = listOf(
            Pt(600.0, 450.0), Pt(603.0, 450.0), Pt(603.0, 453.0), Pt(600.0, 453.0))
        val r = PageDewarper(FakeDetector(collapsed, 1200, 900)).dewarp(still)
        try {
            assertFalse("a collapsed quad must not be warped", r.applied)
            assertEquals(1200, r.page.width())
        } finally {
            r.release(); still.release()
        }
    }

    @Test
    fun tinyPageIsUpscaledToTheOcrMinimum() {
        // Tesseract collapses at low resolution, so a small page must come out
        // enlarged rather than at native size.
        val still = canvas(1200, 900)
        val small = listOf(
            Pt(500.0, 400.0), Pt(700.0, 400.0), Pt(700.0, 560.0), Pt(500.0, 560.0))
        val r = PageDewarper(FakeDetector(small, 1200, 900)).dewarp(still)
        try {
            assertTrue("should have dewarped", r.applied)
            assertTrue(
                "long side ${max(r.page.width(), r.page.height())} below the 2000 px OCR minimum",
                max(r.page.width(), r.page.height()) >= 2000,
            )
        } finally {
            r.release(); still.release()
        }
    }

    @Test
    fun hugePageIsClampedButKeepsItsAspectRatio() {
        val still = canvas(6000, 4000)
        val huge = listOf(
            Pt(100.0, 100.0), Pt(5900.0, 100.0), Pt(5900.0, 3900.0), Pt(100.0, 3900.0))
        val r = PageDewarper(FakeDetector(huge, 6000, 4000)).dewarp(still)
        try {
            assertTrue(r.applied)
            val long = max(r.page.width(), r.page.height())
            assertTrue("long side $long exceeds the 3000 px clamp", long <= 3000)
            val want = 5800.0 / 3800.0
            val got = r.page.width().toDouble() / r.page.height()
            assertTrue("aspect ratio $got drifted from $want", abs(got - want) < 0.02)
        } finally {
            r.release(); still.release()
        }
    }

    @Test
    fun skewedPageComesOutRectangularAndTheRightWayUp() {
        val still = canvas(1600, 1200)
        // Deliberately a trapezoid: the top edge is much shorter than the
        // bottom, which is what a page photographed at an angle looks like.
        val skew = listOf(
            Pt(500.0, 200.0), Pt(1050.0, 260.0), Pt(1250.0, 1050.0), Pt(300.0, 980.0))
        pageOn(still, skew)

        val r = PageDewarper(FakeDetector(skew, 1600, 1200)).dewarp(still)
        try {
            assertTrue(r.applied)
            assertNotNull("the S->G homography must be kept for mapping back", r.homography)

            // The corners should now be the corners of the image: sample just
            // inside each and require page-bright, which a bad ordering or a
            // wrong output size would fail.
            val p = r.page
            val inset = 12
            for ((x, y) in listOf(
                inset to inset,
                p.width() - inset to inset,
                p.width() - inset to p.height() - inset,
                inset to p.height() - inset,
            )) {
                val v = p.get(y, x)[0]
                assertTrue("corner ($x,$y) is $v, not page-bright", v > 150)
            }

            // The stripe was painted 10% down the page. Finding it in the top
            // fifth of the output proves the page is upright, not flipped or
            // rotated a quarter turn.
            val col = p.width() / 2
            var darkest = 255.0
            var darkestRow = -1
            for (y in 0 until p.height()) {
                val v = p.get(y, col)[0]
                if (v < darkest) { darkest = v; darkestRow = y }
            }
            assertTrue("no stripe found", darkest < 100)
            val where = darkestRow.toDouble() / p.height()
            assertTrue("stripe at ${"%.2f".format(where)} of the page, expected near 0.10",
                where < 0.20)
        } finally {
            r.release(); still.release()
        }
    }

    /**
     * A page running off the still's own border must be reported.
     *
     * It passes every degeneracy check -- four distinct corners, long edges,
     * ample area -- and warps into a clean-looking rectangle, so nothing
     * downstream can tell that a strip of the page is simply missing. Read
     * aloud to someone who cannot see the paper, that is the worst outcome in
     * the pipeline: text presented as complete when it is not.
     */
    @Test
    fun clippingOnTheStillIsCarriedForward() {
        val still = canvas(1200, 900)
        val clippedDetector = object : PageDetector {
            override fun detect(bgr: Mat, roi: Rect?) = PageObservation(
                quad = listOf(
                    Pt(0.0, 40.0), Pt(bgr.width() - 1.0, 40.0),
                    Pt(bgr.width() - 1.0, bgr.height() - 40.0), Pt(0.0, bgr.height() - 40.0),
                ),
                confidence = 1f,
                coverage = 0.9f,
                clipped = setOf(Side.LEFT, Side.RIGHT),
                tiltDegrees = 0f,
                frameWidth = bgr.width(),
                frameHeight = bgr.height(),
                source = ObservationSource.NEURAL,
            )
        }
        val r = PageDewarper(clippedDetector).dewarp(still)
        try {
            assertTrue("a usable quad should still dewarp", r.applied)
            assertEquals("the still's clipping must reach the caller",
                setOf(Side.LEFT, Side.RIGHT), r.clipped)
        } finally {
            r.release(); still.release()
        }
    }

    /** A page well inside the frame reports nothing clipped. */
    @Test
    fun anUnclippedPageReportsNoClipping() {
        val still = canvas(1200, 900)
        val q = listOf(Pt(200.0, 150.0), Pt(1000.0, 150.0), Pt(1000.0, 750.0), Pt(200.0, 750.0))
        val r = PageDewarper(FakeDetector(q, 1200, 900)).dewarp(still)
        try {
            assertTrue(r.clipped.isEmpty())
        } finally {
            r.release(); still.release()
        }
    }

    @Test
    fun realShadowedFrameDewarpsThroughTheProductionDetector() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val engine = YoloDnnEngine.load(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "yolov8n_det_256.onnx",
        )
        assertNotNull("weights failed to load", engine)

        val still = GroundTruth.loadFrame(ctx, "f00100.jpg")
        val r = PageDewarper(YoloPageDetector(engine!!)).dewarp(still)
        try {
            assertTrue("the shadowed fixture should now dewarp", r.applied)
            val long = max(r.page.width(), r.page.height())
            assertTrue("long side $long below the OCR minimum", long >= 2000)
            // A page is taller than it is wide here; a sideways result would
            // mean the corner ordering broke somewhere in the scale-up.
            assertTrue("page came out landscape", r.page.height() > r.page.width())
        } finally {
            r.release(); still.release()
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
