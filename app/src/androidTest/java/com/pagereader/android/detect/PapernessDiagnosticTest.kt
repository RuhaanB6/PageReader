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
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Not an assertion test -- a measurement, and specifically the one that decides
 * whether the "Otsu is global" hypothesis is right.
 *
 * [ColorPageDetector] fails on shadowed pages. Two mechanisms explain that
 * equally well from outside:
 *
 *  A. The shadowed part of the page is still *colourless* (low chroma) and only
 *     dark. Paperness collapses because it leans on absolute lightness, and one
 *     global Otsu split cannot straddle a lit half and a shadowed half. Fix:
 *     normalise illumination, or weight chroma harder.
 *  B. The shadowed part genuinely stops being distinguishable from the carpet on
 *     *any* channel -- its chroma rises to meet the background's. Then no
 *     threshold rule saves it and the whole colour approach needs replacing.
 *
 * The discriminator is what the *rejected* pixels inside the network's box look
 * like. This measures L and chroma separately for the pixels Otsu kept and the
 * pixels it dropped, so A and B produce visibly different numbers, and then
 * trials three candidate fixes against the same frames.
 */
@RunWith(AndroidJUnit4::class)
class PapernessDiagnosticTest {

    private fun loadFrame(name: String): Mat {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bytes = ctx.assets.open("frames/$name").use { it.readBytes() }
        val enc = MatOfByte(*bytes)
        val squashed = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR)
        enc.release()
        val full = Mat()
        Imgproc.resize(squashed, full, Size(480.0, 640.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
        squashed.release()
        return full
    }

    private fun outDir(): File {
        val d = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null), "paperness",
        )
        d.mkdirs()
        return d
    }

    /** Mean of [src] over the non-zero pixels of [m]. */
    private fun meanIn(src: Mat, m: Mat): Double = Core.mean(src, m).`val`[0]

    private fun percentile(src: Mat, m: Mat, p: Double): Double {
        val vals = ArrayList<Float>()
        val s = Mat()
        src.convertTo(s, CvType.CV_32F)
        val buf = FloatArray((s.total() * s.channels()).toInt())
        s.get(0, 0, buf)
        val mb = ByteArray(m.total().toInt())
        m.get(0, 0, mb)
        for (i in buf.indices) if (mb[i].toInt() != 0) vals.add(buf[i])
        s.release()
        if (vals.isEmpty()) return Double.NaN
        vals.sort()
        return vals[((vals.size - 1) * p).toInt()].toDouble()
    }

    /** The production paperness channel, reimplemented so variants can be tried. */
    private fun labParts(smallBgr: Mat): Triple<Mat, Mat, Mat> {
        val lab = Mat()
        Imgproc.cvtColor(smallBgr, lab, Imgproc.COLOR_BGR2Lab)
        val ch = ArrayList<Mat>(3)
        Core.split(lab, ch)
        lab.release()
        val l = Mat(); val a = Mat(); val b = Mat()
        ch[0].convertTo(l, CvType.CV_32F)
        ch[1].convertTo(a, CvType.CV_32F, 1.0, -128.0)
        ch[2].convertTo(b, CvType.CV_32F, 1.0, -128.0)
        ch.forEach { it.release() }
        val chroma = Mat()
        Core.magnitude(a, b, chroma)
        a.release(); b.release()
        return Triple(l, chroma, Mat())
    }

    private fun otsuMask(f32: Mat): Pair<Mat, Double> {
        val u8 = Mat()
        f32.convertTo(u8, CvType.CV_8U)
        val m = Mat()
        val t = Imgproc.threshold(u8, m, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        u8.release()
        return m to t
    }

    /** Fraction of [box] covered by the largest connected component of [mask]. */
    private fun boxFill(mask: Mat, box: Rect): Double {
        val sub = Mat(mask, box)
        val n = Core.countNonZero(sub)
        sub.release()
        return n.toDouble() / (box.width * box.height)
    }

    @Test
    fun measureWhyTheMaskIsCut() {
        val engine = YoloDnnEngine.load(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "yolov8n_det_256.onnx",
        ) ?: run { Log.e(TAG, "engine did not load"); return }

        for (name in listOf("f00100.jpg", "f00072.jpg", "f00084.jpg")) {
            val stem = name.removeSuffix(".jpg")
            val frame = loadFrame(name)
            val best = engine.detect(frame, 0.25f, 0.45f).firstOrNull()
            if (best == null) { Log.w(TAG, "$stem: no detection"); frame.release(); continue }

            // The detector works at 640x480; put the box in those coordinates.
            val small = Mat()
            Imgproc.resize(frame, small, Size(640.0, 480.0))
            val sx = 640.0 / frame.width()
            val sy = 480.0 / frame.height()
            val box = Rect(
                (best.box.x * sx).toInt().coerceIn(0, 639),
                (best.box.y * sy).toInt().coerceIn(0, 479),
                (best.box.width * sx).toInt(),
                (best.box.height * sy).toInt(),
            ).let { Rect(it.x, it.y, it.width.coerceAtMost(640 - it.x), it.height.coerceAtMost(480 - it.y)) }

            val (l, chroma, _) = labParts(small)

            // --- production paperness -------------------------------------
            val weighted = Mat()
            Core.multiply(chroma, Scalar(2.0), weighted)
            val paperness = Mat()
            Core.subtract(l, weighted, paperness)
            weighted.release()

            val (mask, otsu) = otsuMask(paperness)

            // Restrict attention to the network's box: everything outside is
            // not part of this question.
            val boxMask = Mat.zeros(mask.size(), CvType.CV_8UC1)
            Imgproc.rectangle(boxMask, box.tl(), box.br(), Scalar(255.0), -1)

            val kept = Mat(); Core.bitwise_and(mask, boxMask, kept)
            val notMask = Mat(); Core.bitwise_not(mask, notMask)
            val dropped = Mat(); Core.bitwise_and(notMask, boxMask, dropped); notMask.release()

            val keptN = Core.countNonZero(kept)
            val dropN = Core.countNonZero(dropped)

            Log.i(TAG, "=== $stem  box=${box.width}x${box.height} at (${box.x},${box.y})  " +
                "otsu=$otsu  kept=$keptN dropped=$dropN (${(100.0 * dropN / (keptN + dropN)).toInt()}% of box dropped)")

            if (keptN > 0 && dropN > 0) {
                Log.i(TAG, "$stem  KEPT   L mean=${"%.1f".format(meanIn(l, kept))} " +
                    "p10=${"%.1f".format(percentile(l, kept, 0.10))} " +
                    "p90=${"%.1f".format(percentile(l, kept, 0.90))} | " +
                    "chroma mean=${"%.1f".format(meanIn(chroma, kept))} " +
                    "p90=${"%.1f".format(percentile(chroma, kept, 0.90))}")
                Log.i(TAG, "$stem  DROPPED L mean=${"%.1f".format(meanIn(l, dropped))} " +
                    "p10=${"%.1f".format(percentile(l, dropped, 0.10))} " +
                    "p90=${"%.1f".format(percentile(l, dropped, 0.90))} | " +
                    "chroma mean=${"%.1f".format(meanIn(chroma, dropped))} " +
                    "p90=${"%.1f".format(percentile(chroma, dropped, 0.90))}")
            }

            // Background reference: a border ring outside the box, which is
            // carpet by construction. If the dropped pixels look like this, the
            // box is simply larger than the page (mechanism B).
            val outside = Mat(); Core.bitwise_not(boxMask, outside)
            Log.i(TAG, "$stem  OUTSIDE L mean=${"%.1f".format(meanIn(l, outside))} " +
                "| chroma mean=${"%.1f".format(meanIn(chroma, outside))} " +
                "p10=${"%.1f".format(percentile(chroma, outside, 0.10))}")
            outside.release()

            Log.i(TAG, "$stem  production boxFill=${"%.2f".format(boxFill(mask, box))}")

            fun report(tag: String, m: Mat) {
                val inBox = boxFill(m, box)
                val total = Core.countNonZero(m).toDouble()
                val boxPix = inBox * box.width * box.height
                val outside = (total - boxPix) / (640.0 * 480.0 - box.width * box.height)
                Log.i(TAG, "$stem  $tag: boxFill=${"%.2f".format(inBox)} outsideFill=${"%.2f".format(outside)}")
                Imgcodecs.imwrite(File(outDir(), "$stem-$tag.png").absolutePath, m)
            }

            // --- illumination normalisation at several scales ---------------
            for (sigma in listOf(51.0, 121.0)) {
                val blur = Mat()
                Imgproc.GaussianBlur(l, blur, Size(0.0, 0.0), sigma)
                val norm = Mat()
                Core.divide(l, blur, norm, 128.0)
                blur.release()
                val w = Mat(); Core.multiply(chroma, Scalar(2.0), w)
                val p = Mat(); Core.subtract(norm, w, p); w.release(); norm.release()
                val (m2, _) = otsuMask(p)
                report("normS${sigma.toInt()}", m2)
                m2.release(); p.release()
            }

            // --- illumination estimated by a large morphological closing ----
            // Closing takes the local maximum envelope, so for a bright sheet it
            // tracks the light falling on the page rather than the page itself.
            for (k in listOf(31, 61)) {
                val ker = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
                val u8 = Mat(); l.convertTo(u8, CvType.CV_8U)
                val env = Mat(); Imgproc.morphologyEx(u8, env, Imgproc.MORPH_CLOSE, ker)
                Imgproc.GaussianBlur(env, env, Size(0.0, 0.0), 15.0)
                val envF = Mat(); env.convertTo(envF, CvType.CV_32F)
                val norm = Mat(); Core.divide(l, envF, norm, 200.0)
                val w = Mat(); Core.multiply(chroma, Scalar(2.0), w)
                val p = Mat(); Core.subtract(norm, w, p); w.release(); norm.release()
                val (m2, _) = otsuMask(p)
                report("closeK$k", m2)
                m2.release(); p.release(); env.release(); envF.release(); u8.release(); ker.release()
            }

            // --- ROI-first: build paperness and pick Otsu inside the box only -
            run {
                val sub = Mat(paperness, box)
                val (subMask, t) = otsuMask(sub)
                val full = Mat.zeros(mask.size(), CvType.CV_8UC1)
                subMask.copyTo(Mat(full, box))
                Log.i(TAG, "$stem  roiOtsu threshold=$t (frame otsu was $otsu)")
                report("roiOtsu", full)
                full.release(); subMask.release(); sub.release()
            }

            // --- ROI-first on illumination-normalised paperness --------------
            for (sigma in listOf(51.0, 121.0)) {
                val blur = Mat()
                Imgproc.GaussianBlur(l, blur, Size(0.0, 0.0), sigma)
                val norm = Mat(); Core.divide(l, blur, norm, 128.0); blur.release()
                val w = Mat(); Core.multiply(chroma, Scalar(2.0), w)
                val p = Mat(); Core.subtract(norm, w, p); w.release(); norm.release()
                val sub = Mat(p, box)
                val (subMask, t) = otsuMask(sub)
                val full = Mat.zeros(mask.size(), CvType.CV_8UC1)
                subMask.copyTo(Mat(full, box))
                Log.i(TAG, "$stem  roiNormS${sigma.toInt()} threshold=$t")
                report("roiNormS${sigma.toInt()}", full)
                full.release(); subMask.release(); sub.release(); p.release()
            }

            Imgcodecs.imwrite(File(outDir(), "$stem-prod.png").absolutePath, mask)
            // Lightness and chroma as images, so the shadow is visible as data.
            run {
                val v = Mat(); l.convertTo(v, CvType.CV_8U)
                Imgcodecs.imwrite(File(outDir(), "$stem-L.png").absolutePath, v); v.release()
                val c = Mat(); Core.multiply(chroma, Scalar(4.0), c)
                val cv = Mat(); c.convertTo(cv, CvType.CV_8U)
                Imgcodecs.imwrite(File(outDir(), "$stem-chroma4x.png").absolutePath, cv)
                c.release(); cv.release()
            }

            kept.release(); dropped.release(); boxMask.release()
            mask.release(); paperness.release(); l.release(); chroma.release()
            small.release(); frame.release()
        }
        Log.i(TAG, "wrote to ${outDir().absolutePath}")
    }

    companion object {
        private const val TAG = "Paperness"

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
