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
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Measures how much faster the emulator is than the phone, **per class of
 * work**, so the ratio can be used honestly instead of assumed.
 *
 * The tempting shortcut after seeing the suite run 9x faster is to treat ~10x
 * as a universal conversion factor. The two numbers that suggested it -- the
 * YOLO forward and `ColorPageDetector` -- are both CPU-bound float work through
 * OpenCV, so they are two samples of one class, not evidence about everything.
 * These cases deliberately span classes that scale differently: raw memory
 * bandwidth, cache-friendly arithmetic, a wide separable filter, a
 * gather-heavy warp, and libjpeg.
 *
 * Run on both, compare the tables. A ratio that holds across all of them can be
 * used as a rule of thumb; one that varies says which kinds of measurement have
 * to stay on hardware.
 */
@RunWith(AndroidJUnit4::class)
class HostRatioBenchmarkTest {

    private fun bench(name: String, warmup: Int = 3, iters: Int = 15, body: () -> Unit) {
        repeat(warmup) { body() }
        val samples = LongArray(iters)
        for (i in 0 until iters) {
            val t0 = System.nanoTime()
            body()
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val p50 = samples[iters / 2] / 1e6
        val p95 = samples[(iters * 95 / 100).coerceAtMost(iters - 1)] / 1e6
        Log.i(TAG, "RATIO %-26s p50=%7.2f ms  p95=%7.2f ms".format(name, p50, p95))
    }

    @Test
    fun measurePerClassCost() {
        Log.i(TAG, "RATIO === ${android.os.Build.MODEL} / ${android.os.Build.HARDWARE} ===")

        val big = Mat(3000, 4000, CvType.CV_8UC3, Scalar(120.0, 120.0, 120.0))
        val mid = Mat(720, 1280, CvType.CV_8UC3, Scalar(120.0, 120.0, 120.0))
        val midF = Mat(); mid.convertTo(midF, CvType.CV_32F)

        try {
            // Memory bandwidth: almost pure memcpy, 36 MB a go. The Mac has far
            // more of it than a phone, so this should show the widest ratio.
            bench("clone 4000x3000") { big.clone().release() }

            // Cache-friendly per-pixel arithmetic.
            bench("cvtColor BGR2Lab 1280x720") {
                val d = Mat(); Imgproc.cvtColor(mid, d, Imgproc.COLOR_BGR2Lab); d.release()
            }

            // The decision that produced ILLUM_DOWNSCALE. Wide separable
            // filter, heavily optimised and multi-threaded in OpenCV.
            bench("GaussianBlur s=51 640x480", iters = 5) {
                val src = Mat(480, 640, CvType.CV_32F, Scalar(120.0))
                val d = Mat()
                Imgproc.GaussianBlur(src, d, Size(0.0, 0.0), 51.0)
                d.release(); src.release()
            }
            bench("GaussianBlur s=6.4 80x60") {
                val src = Mat(60, 80, CvType.CV_32F, Scalar(120.0))
                val d = Mat()
                Imgproc.GaussianBlur(src, d, Size(0.0, 0.0), 6.4)
                d.release(); src.release()
            }

            // M4's real per-capture cost: a gather-heavy warp with cubic taps,
            // which is random-access rather than streaming.
            bench("warpPerspective 4000x3000 -> 2000", iters = 5) {
                val src = MatOfPoint2f(
                    Point(100.0, 100.0), Point(3900.0, 200.0),
                    Point(3800.0, 2900.0), Point(200.0, 2800.0))
                val dst = MatOfPoint2f(
                    Point(0.0, 0.0), Point(1999.0, 0.0),
                    Point(1999.0, 1499.0), Point(0.0, 1499.0))
                val h = Imgproc.getPerspectiveTransform(src, dst)
                val out = Mat()
                Imgproc.warpPerspective(big, out, h, Size(2000.0, 1500.0), Imgproc.INTER_CUBIC)
                out.release(); h.release(); src.release(); dst.release()
            }

            // libjpeg rather than OpenCV's own kernels -- a different code path
            // with different vectorisation, and the one OCR will lean on.
            val enc = MatOfByte()
            Imgcodecs.imencode(".jpg", mid, enc)
            bench("imdecode jpeg 1280x720") {
                val d = Imgcodecs.imdecode(enc, Imgcodecs.IMREAD_COLOR); d.release()
            }
            enc.release()

            // Reduction over a large buffer: bandwidth-bound but no allocation.
            bench("mean over 4000x3000") { Core.mean(big) }

            // Sustained load. The phone throttles under this and the emulator
            // does not, so a ratio taken cold understates the real phone cost.
            val t0 = System.nanoTime()
            var n = 0
            while (System.nanoTime() - t0 < 8_000_000_000L) {
                val d = Mat(); Imgproc.cvtColor(mid, d, Imgproc.COLOR_BGR2Lab); d.release(); n++
            }
            Log.i(TAG, "RATIO sustained 8s cvtColor    n=$n iterations")
        } finally {
            big.release(); mid.release(); midF.release()
        }
    }

    companion object {
        private const val TAG = "PageReaderBench"

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
