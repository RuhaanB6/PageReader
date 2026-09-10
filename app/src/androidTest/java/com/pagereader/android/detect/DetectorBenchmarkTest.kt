package com.pagereader.android.detect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * The M0 gate, plus a correctness smoke test for the colour path.
 *
 * Runs on hardware only (`./gradlew connectedAndroidTest`) -- the app's
 * `abiFilters` exclude x86, so there is no emulator option.
 *
 * Deliberately useful with **no model file present**: the colour detector needs
 * nothing but OpenCV, and the ONNX probe reports "absent" rather than failing.
 * Drop a `.onnx` into `app/src/androidTest/assets/models/` and the same run
 * answers the runtime question.
 */
@RunWith(AndroidJUnit4::class)
class DetectorBenchmarkTest {

    private lateinit var detector: ColorPageDetector

    @Before
    fun setUp() {
        detector = ColorPageDetector()
    }

    // ---------------------------------------------------------------- scenes

    /**
     * Builds a synthetic scene: a neutral bright "page" on a coloured
     * background. [bg] and [page] are BGR triples.
     */
    private fun scene(
        width: Int = 1280,
        height: Int = 720,
        bg: Triple<Double, Double, Double>,
        page: Triple<Double, Double, Double>,
        pageRect: Rect,
    ): Mat {
        val m = Mat(height, width, CvType.CV_8UC3, Scalar(bg.first, bg.second, bg.third))
        Imgproc.rectangle(
            m,
            Point(pageRect.x.toDouble(), pageRect.y.toDouble()),
            Point((pageRect.x + pageRect.width).toDouble(), (pageRect.y + pageRect.height).toDouble()),
            Scalar(page.first, page.second, page.third),
            -1
        )
        return m
    }

    /** Warm cream desk: bright, but carries real chroma. */
    private val creamDesk = Triple(200.0, 215.0, 232.0)
    /** Near-white paper: bright and essentially neutral. */
    private val whitePaper = Triple(242.0, 242.0, 243.0)
    private val darkDesk = Triple(45.0, 42.0, 40.0)

    // ----------------------------------------------------------- correctness

    @Test
    fun findsAPageOnADarkDesk() {
        val m = scene(bg = darkDesk, page = whitePaper, pageRect = Rect(340, 130, 600, 460))
        try {
            val o = detector.detect(m)
            assertNotNull("expected a quad on the easy dark-desk case", o.quad)
            assertTrue("coverage ${o.coverage} implausible", o.coverage in 0.15f..0.60f)
            assertEquals("page is fully inside the frame", emptySet<Side>(), o.clipped)
        } finally {
            m.release()
        }
    }

    /**
     * The case from the email: a light page on a light background. Brightness
     * alone barely separates these two (L differs by well under the 8 grey
     * levels v1's side-classifier needed), but their chroma does.
     */
    @Test
    fun findsALightPageOnALightBackground() {
        val m = scene(bg = creamDesk, page = whitePaper, pageRect = Rect(340, 130, 600, 460))
        try {
            val o = detector.detect(m)
            assertNotNull("light-on-light is the case this rewrite exists for", o.quad)
            assertTrue("confidence ${o.confidence} too low to act on", o.confidence > 0.2f)
            assertTrue("coverage ${o.coverage} implausible", o.coverage in 0.15f..0.60f)
            Log.i(TAG, "light-on-light: coverage=${o.coverage} confidence=${o.confidence}")
        } finally {
            m.release()
        }
    }

    @Test
    fun reportsWhichBordersThePageRunsOff() {
        // Page pushed off the left edge of the frame.
        val m = scene(bg = darkDesk, page = whitePaper, pageRect = Rect(-1, 130, 700, 460))
        try {
            val o = detector.detect(m)
            assertTrue("expected LEFT in ${o.clipped}", Side.LEFT in o.clipped)
            assertTrue("did not expect RIGHT in ${o.clipped}", Side.RIGHT !in o.clipped)
        } finally {
            m.release()
        }
    }

    @Test
    fun cornersComeBackOrderedTopLeftFirst() {
        val m = scene(bg = darkDesk, page = whitePaper, pageRect = Rect(340, 130, 600, 460))
        try {
            val q = detector.detect(m).quad
            assertNotNull(q)
            val (tl, tr, br, bl) = q!!
            assertTrue("TL should be left of TR", tl.x < tr.x)
            assertTrue("TL should be above BL", tl.y < bl.y)
            assertTrue("BR should be right of BL", br.x > bl.x)
        } finally {
            m.release()
        }
    }

    @Test
    fun aBlankSceneYieldsNoConfidentPage() {
        val m = Mat(720, 1280, CvType.CV_8UC3, Scalar(creamDesk.first, creamDesk.second, creamDesk.third))
        try {
            val o = detector.detect(m)
            // Otsu will still split a uniform field; the separation confidence is
            // what must stay low so guidance ignores it.
            assertTrue("blank scene reported confidence ${o.confidence}", o.confidence < 0.35f)
        } finally {
            m.release()
        }
    }

    // -------------------------------------------------------------- latency

    @Test
    fun colourDetectorLatency() {
        val m = scene(bg = creamDesk, page = whitePaper, pageRect = Rect(340, 130, 600, 460))
        try {
            repeat(WARMUP) { detector.detect(m) }
            val times = LongArray(RUNS) {
                val t0 = System.nanoTime()
                detector.detect(m)
                (System.nanoTime() - t0) / 1_000_000
            }
            report("ColorPageDetector @1280x720", times)
            assertTrue("colour path must be cheap enough to run between inferences", p(times, 95) < 120)
        } finally {
            m.release()
        }
    }

    // ------------------------------------------------------- the M0 question

    /**
     * Answers, on the actual device: does OpenCV's bundled DNN load our ONNX,
     * and how fast is it?
     *
     * OpenCV's ONNX importer has open, unresolved failures on YOLOv8 graphs
     * (opencv#24148 Reshape/DFL, opencv#28377 Resize->Concat), so a load failure
     * here is a *result*, not a broken test -- it means the ONNX Runtime path is
     * required. The test logs and passes either way.
     */
    @Test
    fun onnxRuntimeProbe() {
        // Assets live in the test APK, but its cache dir is not writable here
        // (EACCES on device) -- so read from one context and write to the other.
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val writableCache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val names = try {
            ctx.assets.list("models")?.filter { it.endsWith(".onnx") }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        if (names.isEmpty()) {
            Log.w(TAG, "M0: no .onnx in androidTest assets/models -- skipping inference benchmark")
            return
        }

        for (name in names) {
            val f = File(writableCache, name)
            ctx.assets.open("models/$name").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }

            val net = try {
                Dnn.readNetFromONNX(f.absolutePath)
            } catch (t: Throwable) {
                Log.e(TAG, "M0 RESULT: cv::dnn CANNOT load $name -> use ONNX Runtime. ${t.message}")
                continue
            }
            Log.i(TAG, "M0: cv::dnn loaded $name (${f.length() / 1024} KB)")

            // A static-shape export accepts exactly the size it was exported at,
            // so the size is read off the filename (`..._256.onnx`) rather than
            // guessed. Export at several sizes to compare them.
            val sizes = Regex("_(\\d{3,4})\\.onnx$").find(name)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { intArrayOf(it) }
                ?: intArrayOf(256, 320, 416)

            for (size in sizes) {
                val input = Mat(size, size, CvType.CV_8UC3, Scalar(128.0, 128.0, 128.0))
                val blob = Dnn.blobFromImage(input, 1 / 255.0, org.opencv.core.Size(size.toDouble(), size.toDouble()))
                try {
                    net.setInput(blob)
                    repeat(WARMUP) { net.forward() }
                    val times = LongArray(RUNS) {
                        val t0 = System.nanoTime()
                        net.forward()
                        (System.nanoTime() - t0) / 1_000_000
                    }
                    report("cv::dnn $name @${size}x$size", times)
                    Log.i(TAG, "M0 RESULT: $name RUNS on cv::dnn at $size")
                } catch (t: Throwable) {
                    Log.e(TAG, "M0 RESULT: $name loads but forward() FAILS at $size -> ONNX Runtime. ${t.message}")
                } finally {
                    input.release()
                    blob.release()
                }
            }
        }
    }

    private fun report(label: String, times: LongArray) {
        Log.i(
            TAG,
            "BENCH $label: p50=${p(times, 50)}ms p95=${p(times, 95)}ms " +
                "min=${times.min()}ms max=${times.max()}ms n=${times.size}"
        )
    }

    private fun p(times: LongArray, percentile: Int): Long {
        val sorted = times.sorted()
        return sorted[((percentile / 100.0) * (sorted.size - 1)).toInt()]
    }

    companion object {
        private const val TAG = "PageReaderBench"
        private const val WARMUP = 5
        private const val RUNS = 30

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
