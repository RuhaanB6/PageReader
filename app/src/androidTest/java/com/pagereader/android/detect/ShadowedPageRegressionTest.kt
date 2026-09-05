package com.pagereader.android.detect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The shadowed-page fix, as an assertion rather than a measurement.
 *
 * Before illumination normalisation, the phone's own shadow across a sheet put
 * the background *between* the two halves of the page in lightness, so Otsu
 * discarded the shadowed third and `MaskToQuad` faithfully fitted the truncated
 * contour. `MIN_REFINE_BOX_COVERAGE` caught it and fell back to the axis-aligned
 * box, which keeps the whole page but gives no perspective correction at all --
 * the quads measured 0.68 and 0.71 of their box, and live testing saw 0.12.
 *
 * These three fixtures are the frames that failed. The thresholds below are set
 * well clear of both sides: the broken detector scored 0.690 IoU with a worst
 * corner 157 px out, the fixed one scores 0.935 with 21 px, and 0.90 sits in the
 * gap so this fails loudly if the normalisation is ever removed or detuned.
 *
 * Per the playbook, a gating fix has to assert the happy path too, since the
 * failure mode of "reject bad quads" is "reject every quad" -- hence the
 * explicit check that the refined quad is used and is *not* merely the box.
 */
@RunWith(AndroidJUnit4::class)
class ShadowedPageRegressionTest {

    @Test
    fun shadowedPagesProduceRealQuadsCoveringTheWholeSheet() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YoloDnnEngine.load(target, "yolov8n_det_256.onnx")
        assertNotNull("detector weights failed to load", engine)

        val labels = GroundTruth.labels(ctx)
        val failures = StringBuilder()

        for (name in listOf("f00100.jpg", "f00072.jpg", "f00084.jpg")) {
            val frame = GroundTruth.loadFrame(ctx, name)
            try {
                val obs = YoloPageDetector(engine!!).detect(frame)
                val quad = obs.quad
                if (quad == null) {
                    failures.append("$name: no quad at all\n")
                    continue
                }

                val gt = GroundTruth.corners(labels, name)
                val size = Size(frame.width().toDouble(), frame.height().toDouble())
                val gtMask = GroundTruth.mask(gt, size)
                val quadMask = Mat.zeros(size, CvType.CV_8UC1)
                val poly = MatOfPoint(*quad.map { Point(it.x, it.y) }.toTypedArray())
                Imgproc.fillPoly(quadMask, listOf(poly), Scalar(255.0))
                poly.release()

                val iou = GroundTruth.iou(quadMask, gtMask)
                gtMask.release(); quadMask.release()

                // A rectangle means colour refinement was rejected and the
                // axis-aligned box was used -- the exact non-correction this
                // fix exists to remove. A real quad has unequal corner heights.
                val axisAligned = quad[0].y == quad[1].y && quad[2].y == quad[3].y &&
                    quad[0].x == quad[3].x && quad[1].x == quad[2].x

                if (iou < MIN_IOU) failures.append("$name: IoU $iou below $MIN_IOU\n")
                if (axisAligned) failures.append("$name: fell back to the axis-aligned box\n")
            } finally {
                frame.release()
            }
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    companion object {
        /**
         * Broken scored 0.690, fixed scores 0.935, so this sits in the gap with
         * room for the hand labels' few pixels of error. Measured on the
         * JSC-AL50, 2026-09-05.
         */
        private const val MIN_IOU = 0.90

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
