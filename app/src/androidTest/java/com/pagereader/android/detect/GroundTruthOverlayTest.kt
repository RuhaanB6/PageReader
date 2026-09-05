package com.pagereader.android.detect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
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
 * Draws the hand labels in `assets/frames/labels.json` onto their frames so a
 * human can confirm them before anything is tuned against them.
 *
 * Ground truth that nobody has looked at is not ground truth. Box fill -- the
 * metric this replaces -- scored a mask that filled the whole detection box
 * including its carpet corners at 0.98, which is precisely the failure being
 * fixed, so the labels have to be checked by eye once and then trusted.
 *
 * Output: /sdcard/Android/data/com.pagereader.android/files/labels/
 */
@RunWith(AndroidJUnit4::class)
class GroundTruthOverlayTest {

    @Test
    fun drawLabelsForReview() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null), "labels",
        ).apply { mkdirs() }

        val json = JSONObject(
            ctx.assets.open("frames/labels.json").use { String(it.readBytes()) }
        )

        for (name in json.keys()) {
            if (name.startsWith("_")) continue
            val frame = GroundTruth.loadFrame(ctx, name)
            val pts = GroundTruth.corners(json, name)

            for (i in pts.indices) {
                Imgproc.line(frame, pts[i], pts[(i + 1) % pts.size], Scalar(0.0, 255.0, 0.0), 2)
                Imgproc.circle(frame, pts[i], 6, Scalar(0.0, 0.0, 255.0), -1)
                Imgproc.putText(
                    frame, listOf("TL", "TR", "BR", "BL")[i],
                    Point(pts[i].x + 8, pts[i].y - 8),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 255.0, 255.0), 1,
                )
            }
            val stem = name.removeSuffix(".jpg")
            Imgcodecs.imwrite(File(out, "$stem-label.png").absolutePath, frame)
            Log.i(TAG, "$stem: ${pts.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }}")
            frame.release()
        }
        Log.i(TAG, "wrote overlays to ${out.absolutePath}")
    }

    companion object {
        private const val TAG = "GroundTruth"

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}

/** Shared fixture loading and scoring against the hand labels. */
object GroundTruth {

    /**
     * The fixtures are recorder snapshots stored as a squashed 480x360 resize of
     * the 480x640 analysis frame; un-squash them so the geometry matches what
     * the detector saw on the phone. Labels are in this 480x640 space.
     */
    fun loadFrame(ctx: android.content.Context, name: String): Mat {
        val bytes = ctx.assets.open("frames/$name").use { it.readBytes() }
        val enc = MatOfByte(*bytes)
        val squashed = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR)
        enc.release()
        val full = Mat()
        Imgproc.resize(squashed, full, Size(480.0, 640.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
        squashed.release()
        return full
    }

    fun labels(ctx: android.content.Context): JSONObject =
        JSONObject(ctx.assets.open("frames/labels.json").use { String(it.readBytes()) })

    fun corners(json: JSONObject, name: String): List<Point> {
        val arr: JSONArray = json.getJSONArray(name)
        return (0 until arr.length()).map {
            val p = arr.getJSONArray(it)
            Point(p.getDouble(0), p.getDouble(1))
        }
    }

    /** The label polygon rasterised into a mask of [size], scaling from 480x640. */
    fun mask(pts: List<Point>, size: Size): Mat {
        val sx = size.width / 480.0
        val sy = size.height / 640.0
        val scaled = MatOfPoint(*pts.map { Point(it.x * sx, it.y * sy) }.toTypedArray())
        val m = Mat.zeros(size, org.opencv.core.CvType.CV_8UC1)
        Imgproc.fillPoly(m, listOf(scaled), Scalar(255.0))
        scaled.release()
        return m
    }

    /** Intersection over union of two binary masks. */
    fun iou(a: Mat, b: Mat): Double {
        val inter = Mat()
        val union = Mat()
        org.opencv.core.Core.bitwise_and(a, b, inter)
        org.opencv.core.Core.bitwise_or(a, b, union)
        val i = org.opencv.core.Core.countNonZero(inter).toDouble()
        val u = org.opencv.core.Core.countNonZero(union).toDouble()
        inter.release(); union.release()
        return if (u <= 0.0) 0.0 else i / u
    }
}
