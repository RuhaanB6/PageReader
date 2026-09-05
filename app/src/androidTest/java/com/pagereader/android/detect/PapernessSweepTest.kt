package com.pagereader.android.detect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot

/**
 * Scores candidate paperness channels against the hand-labelled page polygons.
 *
 * The metric matters as much as the candidates. The obvious one -- how much of
 * the network's box the mask fills -- rewards a mask that swallows the box
 * whole, carpet corners included, which fits a rectangle and delivers exactly
 * the missing perspective correction this work exists to fix. A mask that
 * filled the box scored 0.98 on it. So everything here is scored as IoU of the
 * *fitted quad* against the labelled page, plus the worst corner error in
 * pixels, which is the number that says whether a third of the sheet was lost.
 *
 * The two mechanisms under test, from the M4 task-zero note:
 *  - illumination normalisation, which restores shadowed paper's lightness
 *  - chroma weighting, which leans on paper staying colourless in shadow
 * Measured separately these each fail. The hypothesis is that they only work
 * together: normalisation flattens L until chroma is the sole discriminator.
 */
@RunWith(AndroidJUnit4::class)
class PapernessSweepTest {

    private enum class Norm { NONE, GAUSS51, GAUSS121, CLOSE31, CLOSE61, GAUSS51_FAST, BOX101 }

    /** Illumination-normalised lightness, or [l] itself for [Norm.NONE]. */
    private fun normalise(l: Mat, how: Norm): Mat {
        if (how == Norm.NONE) return l.clone()
        val env = Mat()
        when (how) {
            Norm.GAUSS51 -> Imgproc.GaussianBlur(l, env, Size(0.0, 0.0), 51.0)
            Norm.GAUSS121 -> Imgproc.GaussianBlur(l, env, Size(0.0, 0.0), 121.0)
            Norm.GAUSS51_FAST -> {
                // Same illumination estimate, computed on a 1/8 scale image.
                // A blur that wide is carrying no detail worth resolving, so the
                // downscale costs nothing and turns a ~300-tap kernel into a
                // ~40-tap one over 1/64 of the pixels.
                val tiny = Mat()
                Imgproc.resize(l, tiny, Size(80.0, 60.0), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.GaussianBlur(tiny, tiny, Size(0.0, 0.0), 51.0 / 8.0)
                Imgproc.resize(tiny, env, l.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                tiny.release()
            }
            Norm.BOX101 -> {
                // Box filter is integral-image based, so its cost does not grow
                // with the window. Cruder envelope; the question is whether the
                // score notices.
                Imgproc.blur(l, env, Size(101.0, 101.0))
            }
            Norm.CLOSE31, Norm.CLOSE61 -> {
                // Closing takes a local maximum envelope, so on a bright sheet it
                // tracks the light falling on the page rather than the page, and
                // it swallows handwriting instead of amplifying it the way a
                // symmetric blur does.
                val k = if (how == Norm.CLOSE31) 31.0 else 61.0
                val ker = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k))
                val u8 = Mat(); l.convertTo(u8, CvType.CV_8U)
                val closed = Mat()
                Imgproc.morphologyEx(u8, closed, Imgproc.MORPH_CLOSE, ker)
                Imgproc.GaussianBlur(closed, closed, Size(0.0, 0.0), 15.0)
                closed.convertTo(env, CvType.CV_32F)
                u8.release(); closed.release(); ker.release()
            }
            else -> {}
        }
        val norm = Mat()
        // Scale to a nominal 128 so a pixel matching its own local illumination
        // lands mid-range, leaving headroom for the chroma penalty below.
        Core.divide(l, env, norm, 128.0)
        env.release()
        return norm
    }

    private data class Score(
        val quadIou: Double,
        val maxCornerPx: Double,
        val coverageOfBox: Double,
        val gatePasses: Boolean,
    )

    private fun score(
        mask: Mat, box: Rect, gt: List<Point>, gtMask: Mat, frameW: Int, frameH: Int,
    ): Score {
        val obs = MaskToQuad.convert(
            mask = mask,
            scaleX = frameW.toDouble() / mask.width(),
            scaleY = frameH.toDouble() / mask.height(),
            frameWidth = frameW,
            frameHeight = frameH,
            confidence = 1f,
            source = ObservationSource.COLOR,
        )
        val q = obs.quad ?: return Score(0.0, Double.NaN, 0.0, false)

        val quadMask = Mat.zeros(gtMask.size(), CvType.CV_8UC1)
        val sx = gtMask.width() / frameW.toDouble()
        val sy = gtMask.height() / frameH.toDouble()
        val poly = MatOfPoint(*q.map { Point(it.x * sx, it.y * sy) }.toTypedArray())
        Imgproc.fillPoly(quadMask, listOf(poly), Scalar(255.0))
        poly.release()

        val iou = GroundTruth.iou(quadMask, gtMask)
        quadMask.release()

        val worst = q.indices.maxOf { hypot(q[it].x - gt[it].x, q[it].y - gt[it].y) }

        // The production gate: quad area against the network's box, in frame px.
        var area = 0.0
        for (i in q.indices) {
            val a = q[i]; val b = q[(i + 1) % q.size]
            area += a.x * b.y - b.x * a.y
        }
        area = kotlin.math.abs(area) / 2.0
        val boxAreaFrame = (box.width / sx) * (box.height / sy)
        val cov = if (boxAreaFrame <= 0) 0.0 else area / boxAreaFrame
        return Score(iou, worst, cov, cov >= 0.80)
    }

    @Test
    fun sweepNormalisationAgainstChromaWeight() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YoloDnnEngine.load(target, "yolov8n_det_256.onnx")
            ?: run { Log.e(TAG, "engine did not load"); return }
        val out = File(target.getExternalFilesDir(null), "sweep").apply { mkdirs() }
        val labels = GroundTruth.labels(ctx)
        val frames = listOf("f00100.jpg", "f00072.jpg", "f00084.jpg")

        // variant -> per-frame scores, so the winner has to win on all three.
        val table = LinkedHashMap<String, MutableList<Score>>()
        val timings = LinkedHashMap<String, MutableList<Double>>()

        for (name in frames) {
            val stem = name.removeSuffix(".jpg")
            val frame = GroundTruth.loadFrame(ctx, name)
            val frameW = frame.width(); val frameH = frame.height()
            val gt = GroundTruth.corners(labels, name)

            val best = engine.detect(frame, 0.25f, 0.45f).firstOrNull()
            if (best == null) { Log.w(TAG, "$stem: no detection"); frame.release(); continue }

            val small = Mat()
            Imgproc.resize(frame, small, Size(640.0, 480.0))
            val sx = 640.0 / frameW; val sy = 480.0 / frameH
            val box = Rect(
                (best.box.x * sx).toInt().coerceIn(0, 639),
                (best.box.y * sy).toInt().coerceIn(0, 479),
                (best.box.width * sx).toInt(), (best.box.height * sy).toInt(),
            ).let { Rect(it.x, it.y, it.width.coerceAtMost(640 - it.x), it.height.coerceAtMost(480 - it.y)) }

            val gtMask = GroundTruth.mask(gt, Size(640.0, 480.0))
            Imgcodecs.imwrite(File(out, "$stem-gt.png").absolutePath, gtMask)

            // Lab split, once per frame.
            val lab = Mat(); Imgproc.cvtColor(small, lab, Imgproc.COLOR_BGR2Lab)
            val ch = ArrayList<Mat>(3); Core.split(lab, ch); lab.release()
            val l = Mat(); val a = Mat(); val b = Mat()
            ch[0].convertTo(l, CvType.CV_32F)
            ch[1].convertTo(a, CvType.CV_32F, 1.0, -128.0)
            ch[2].convertTo(b, CvType.CV_32F, 1.0, -128.0)
            ch.forEach { it.release() }
            val chroma = Mat(); Core.magnitude(a, b, chroma); a.release(); b.release()

            for (norm in Norm.values()) {
                // Time the illumination estimate itself: it is the only new
                // per-frame cost, against a 17-18 ms budget for this detector.
                var ns = 0L
                repeat(5) {
                    val t0 = System.nanoTime()
                    normalise(l, norm).release()
                    ns += System.nanoTime() - t0
                }
                timings.getOrPut(norm.name) { mutableListOf() }.add(ns / 5 / 1e6)
                val base = normalise(l, norm)
                for (cw in listOf(2.0, 4.0, 6.0, 8.0, 12.0)) {
                    val w = Mat(); Core.multiply(chroma, Scalar(cw), w)
                    val p = Mat(); Core.subtract(base, w, p); w.release()
                    val u8 = Mat(); p.convertTo(u8, CvType.CV_8U); p.release()
                    val mask = Mat()
                    Imgproc.threshold(u8, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                    u8.release()

                    // Production restricts the mask to the network's box before
                    // fitting; do the same or the comparison is not like for like.
                    val restricted = MaskToQuad.restrictTo(mask, box)
                    mask.release()

                    val key = "$norm/cw$cw"
                    val s = score(restricted, box, gt, gtMask, frameW, frameH)
                    table.getOrPut(key) { mutableListOf() }.add(s)
                    if (norm == Norm.CLOSE31 || norm == Norm.NONE) {
                        Imgcodecs.imwrite(
                            File(out, "$stem-$norm-cw${cw.toInt()}.png").absolutePath, restricted)
                    }
                    restricted.release()
                }
                base.release()
            }

            gtMask.release(); l.release(); chroma.release(); small.release(); frame.release()
        }

        Log.i(TAG, "variant                 IoU(min/mean)   worstCornerPx   boxCov   gate")
        table.entries
            .sortedByDescending { it.value.minOf { s -> s.quadIou } }
            .forEach { (k, v) ->
                val minIou = v.minOf { it.quadIou }
                val meanIou = v.map { it.quadIou }.average()
                val worst = v.maxOf { if (it.maxCornerPx.isNaN()) 999.0 else it.maxCornerPx }
                val cov = v.map { it.coverageOfBox }.average()
                val passes = v.count { it.gatePasses }
                Log.i(TAG, "%-22s  %.3f / %.3f   %6.1f   %.2f   %d/%d".format(
                    k, minIou, meanIou, worst, cov, passes, v.size))
            }
        Log.i(TAG, "--- illumination estimate cost, ms per frame at 640x480 ---")
        timings.forEach { (k, v) -> Log.i(TAG, "%-14s %.2f ms".format(k, v.average())) }
        Log.i(TAG, "wrote masks to ${out.absolutePath}")
    }

    companion object {
        private const val TAG = "Sweep"

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
