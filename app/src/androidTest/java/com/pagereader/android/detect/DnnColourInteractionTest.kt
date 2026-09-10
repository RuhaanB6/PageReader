package com.pagereader.android.detect

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Isolates the interleaving the app actually performs every frame:
 * [YoloDnnEngine.detect] (a cv::dnn forward) immediately followed by
 * [ColorPageDetector.detect] on the same thread -- see
 * `YoloPageDetector.detect`, which refines the network's box with colour.
 *
 *
 * IF THIS CLASS APPEARS TO HANG, IT IS ALMOST CERTAINLY NOT THE CODE.
 * HarmonyOS/EMUI's PowerGenie freezes any process without a foreground
 * window a few seconds after it starts, and an instrumentation run never
 * has one. The frozen process sits at 0% CPU with every thread in D state
 * and stops answering binder, which is indistinguishable from a native
 * deadlock -- and Gradle's default `testTimeoutSeconds` is 31536000 (one
 * year), so the run waits forever instead of failing. Confirmed in logcat:
 *   Pged-Freezer: Freeze process: <pid>
 *   HiberManagerService::DoReclaim ok, pid=<pid>, reclaimMode=hiber_anon
 * (the DoReclaim is what makes VmSwap balloon, which looks like a leak and
 * is not). `dumpsys deviceidle whitelist +<pkg>` does NOT help -- PowerGenie
 * ignores the AOSP list. The fix is on the phone:
 *   Settings > Battery > App launch > PageReader > Manage manually,
 *   with Auto-launch / Secondary launch / Run in background all enabled.
 * Check for the Freeze line in logcat before debugging anything else here.
 *
 * Each ordering is its own test method so that a hang identifies *which*
 * ordering hangs: a wedged method reports as the last one started.
 * Every method logs progress before and after each stage for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class DnnColourInteractionTest {

    private fun scene(): Mat {
        val m = Mat(720, 1280, CvType.CV_8UC3, Scalar(200.0, 215.0, 232.0))
        Imgproc.rectangle(m, Point(340.0, 130.0), Point(940.0, 590.0), Scalar(242.0, 242.0, 243.0), -1)
        return m
    }

    private fun engine(): YoloDnnEngine? = YoloDnnEngine.load(
        InstrumentationRegistry.getInstrumentation().targetContext,
        "yolov8n_det_256.onnx",
    )

    /** Baseline: colour alone, no dnn in the process. Must always pass. */
    @Test
    fun colourAloneIsFine() {
        val m = scene()
        try {
            val d = ColorPageDetector()
            repeat(10) { d.detect(m) }
            Log.i(TAG, "STAGE colourAlone: 10 colour detects OK")
        } finally {
            m.release()
        }
    }

    /** The production ordering. */
    @Test
    fun dnnThenColour() {
        val m = scene()
        val e = engine()
        try {
            assertNotNull("engine must load", e)
            Log.i(TAG, "STAGE dnnThenColour: engine loaded")
            e!!.detect(m, 0.25f, 0.45f)
            Log.i(TAG, "STAGE dnnThenColour: forward OK -- now colour")
            val d = ColorPageDetector()
            repeat(10) { i ->
                d.detect(m)
                Log.i(TAG, "STAGE dnnThenColour: colour detect ${i + 1}/10 OK")
            }
            Log.i(TAG, "STAGE dnnThenColour: COMPLETE")
        } finally {
            m.release()
        }
    }

    /** The full production path, repeated the way the analysis loop repeats it. */
    @Test
    fun yoloPageDetectorEndToEnd() {
        val m = scene()
        val e = engine()
        try {
            assertNotNull("engine must load", e)
            val det = YoloPageDetector(e!!)
            repeat(FRAMES) { i ->
                det.detect(m)
                if ((i + 1) % 5 == 0) Log.i(TAG, "STAGE endToEnd: frame ${i + 1}/$FRAMES OK")
            }
            Log.i(TAG, "STAGE endToEnd: COMPLETE")
        } finally {
            m.release()
        }
    }

    /**
     * The probe's shape: many raw forwards, then colour. Discriminates
     * "one forward is fine" from "forwards accumulate something".
     */
    @Test
    fun manyForwardsThenColour() {
        val m = scene()
        val e = engine()
        try {
            assertNotNull("engine must load", e)
            repeat(FRAMES) { i ->
                e!!.detect(m, 0.25f, 0.45f)
                if ((i + 1) % 5 == 0) Log.i(TAG, "STAGE manyForwards: forward ${i + 1}/$FRAMES OK")
            }
            Log.i(TAG, "STAGE manyForwards: forwards done -- now colour")
            val d = ColorPageDetector()
            repeat(10) { i ->
                d.detect(m)
                Log.i(TAG, "STAGE manyForwards: colour ${i + 1}/10 OK")
            }
            Log.i(TAG, "STAGE manyForwards: COMPLETE")
        } finally {
            m.release()
        }
    }

    companion object {
        private const val TAG = "PageReaderBench"

        /** Enough to cover the probe's 35 and then some. */
        private const val FRAMES = 40

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }
}
