package com.pagereader.android.detect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Not an assertion test -- a measurement.
 *
 * Device testing on 2026-09-05 produced quads whose fourth corner sat in the
 * middle of the page, cutting off the left third of the sheet. Two causes look
 * identical from outside: the colour mask itself is cut (segmentation losing
 * part of the page), or the mask is right and [MaskToQuad.fitQuad]'s epsilon
 * search collapses a real corner. This runs the production path over the frames
 * that failed and writes the mask, the largest contour and the fitted quad to
 * external files so the two can be told apart by eye.
 *
 * The fixtures in `assets/frames` are recorder snapshots, which are stored as a
 * squashed 480x360 resize of the 480x640 analysis frame -- they are un-squashed
 * back to 480x640 here so the geometry the detector sees matches the geometry it
 * saw on the phone.
 *
 * Output: /sdcard/Android/data/com.pagereader.android/files/quadfit/
 */
@RunWith(AndroidJUnit4::class)
class QuadFitDiagnosticTest {

    private fun outDir(): File {
        val d = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null), "quadfit",
        )
        d.mkdirs()
        return d
    }

    private fun loadFrame(name: String): Mat {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bytes = ctx.assets.open("frames/$name").use { it.readBytes() }
        val enc = MatOfByte(*bytes)
        val squashed = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR)
        enc.release()
        // Undo the recorder's 480x640 -> 480x360 preview resize.
        val full = Mat()
        Imgproc.resize(squashed, full, Size(480.0, 640.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
        squashed.release()
        return full
    }

    @Test
    fun dumpMaskAndQuadForTheFramesThatFailed() {
        val engine = YoloDnnEngine.load(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "yolov8n_det_256.onnx",
        ) ?: run { Log.e(TAG, "engine did not load"); return }

        for (name in listOf("f00100.jpg", "f00072.jpg", "f00084.jpg")) {
            val frame = loadFrame(name)
            val colour = ColorPageDetector()
            var maskCopy: Mat? = null
            colour.maskProbe = { m -> maskCopy = m.clone() }

            try {
                val o = YoloPageDetector(engine, colour).detect(frame)
                val stem = name.removeSuffix(".jpg")

                Imgcodecs.imwrite(File(outDir(), "$stem-frame.png").absolutePath, frame)

                val mask = maskCopy
                if (mask == null) {
                    Log.w(TAG, "$stem: colour refinement never ran (box fallback)")
                } else {
                    Imgcodecs.imwrite(File(outDir(), "$stem-mask.png").absolutePath, mask)

                    // Largest contour of the mask, drawn on the frame, so a cut
                    // mask is visibly distinct from a bad fit over a good mask.
                    val contours = ArrayList<MatOfPoint>()
                    val hierarchy = Mat()
                    Imgproc.findContours(
                        mask.clone(), contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
                    )
                    hierarchy.release()
                    val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }
                    val vis = Mat()
                    Imgproc.resize(frame, vis, mask.size())
                    if (biggest != null) {
                        Imgproc.drawContours(vis, listOf(biggest), -1, Scalar(0.0, 255.0, 0.0), 2)
                        val area = Imgproc.contourArea(biggest)
                        Log.i(TAG, "$stem: contour area=${area.toInt()} " +
                            "of mask ${mask.width()}x${mask.height()} " +
                            "(${(100 * area / (mask.width() * mask.height())).toInt()}%)")
                    }
                    Imgcodecs.imwrite(File(outDir(), "$stem-contour.png").absolutePath, vis)
                    vis.release()
                    contours.forEach { it.release() }
                    mask.release()
                }

                val q = o.quad
                Log.i(TAG, "$stem: source=${o.source} conf=${o.confidence} quad=$q")
                if (q != null) {
                    val drawn = frame.clone()
                    val pts = q.map { Point(it.x, it.y) }
                    for (i in pts.indices) {
                        Imgproc.line(drawn, pts[i], pts[(i + 1) % pts.size],
                            Scalar(0.0, 255.0, 0.0), 2)
                        Imgproc.circle(drawn, pts[i], 5, Scalar(0.0, 0.0, 255.0), -1)
                    }
                    Imgcodecs.imwrite(File(outDir(), "$stem-quad.png").absolutePath, drawn)
                    drawn.release()
                }
            } finally {
                frame.release()
            }
        }
        Log.i(TAG, "wrote diagnostics to ${outDir().absolutePath}")
    }

    companion object {
        private const val TAG = "QuadFit"

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
