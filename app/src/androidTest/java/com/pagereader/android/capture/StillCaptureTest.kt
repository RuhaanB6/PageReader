package com.pagereader.android.capture

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pagereader.android.testutil.FakeImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Scalar
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream

/**
 * Runs on hardware only (`./gradlew connectedAndroidTest`) -- StillCapture links
 * OpenCV natives, which do not exist on the JVM unit test classpath (verified
 * empirically: `org.opencv.core.Mat()` fails to link there) and the app's
 * abiFilters exclude the x86 emulator ABI, so there is no emulator option either.
 *
 * Covers the behaviour [StillCapture.toUprightBgr] promises: apply
 * imageInfo.rotationDegrees, decode failure returns null, and the caller owns
 * (and must release) the result.
 */
@RunWith(AndroidJUnit4::class)
class StillCaptureTest {

    /** A JPEG with distinguishable width/height so rotation is checkable by shape. */
    private fun wideJpegBytes(width: Int = 40, height: Int = 20): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun noRotationKeepsOriginalOrientation() {
        val proxy = FakeImageProxy(wideJpegBytes(width = 40, height = 20), rotationDegrees = 0)
        val mat = StillCapture.toUprightBgr(proxy)
        try {
            assertTrue("expected a decoded Mat", mat != null)
            assertEquals(40, mat!!.width())
            assertEquals(20, mat.height())
        } finally {
            mat?.release()
        }
    }

    @Test
    fun rotation90SwapsWidthAndHeight() {
        val proxy = FakeImageProxy(wideJpegBytes(width = 40, height = 20), rotationDegrees = 90)
        val mat = StillCapture.toUprightBgr(proxy)
        try {
            assertTrue("expected a decoded Mat", mat != null)
            assertEquals("rotating 90 degrees should swap dimensions", 20, mat!!.width())
            assertEquals(40, mat.height())
        } finally {
            mat?.release()
        }
    }

    @Test
    fun rotation270SwapsWidthAndHeight() {
        val proxy = FakeImageProxy(wideJpegBytes(width = 40, height = 20), rotationDegrees = 270)
        val mat = StillCapture.toUprightBgr(proxy)
        try {
            assertTrue("expected a decoded Mat", mat != null)
            assertEquals(20, mat!!.width())
            assertEquals(40, mat.height())
        } finally {
            mat?.release()
        }
    }

    @Test
    fun rotation180KeepsDimensions() {
        val proxy = FakeImageProxy(wideJpegBytes(width = 40, height = 20), rotationDegrees = 180)
        val mat = StillCapture.toUprightBgr(proxy)
        try {
            assertTrue("expected a decoded Mat", mat != null)
            assertEquals(40, mat!!.width())
            assertEquals(20, mat.height())
        } finally {
            mat?.release()
        }
    }

    @Test
    fun emptyBytesDecodeToNull() {
        val proxy = FakeImageProxy(ByteArray(0), rotationDegrees = 0)
        val mat = StillCapture.toUprightBgr(proxy)
        assertNull("an empty plane buffer has no bytes to decode", mat)
    }

    @Test
    fun garbageBytesDecodeToNull() {
        val proxy = FakeImageProxy(byteArrayOf(1, 2, 3, 4, 5), rotationDegrees = 0)
        val mat = StillCapture.toUprightBgr(proxy)
        assertNull("undecodable JPEG bytes must yield null, not a throw or an empty Mat", mat)
    }

    @Test
    fun blankLikeBuildsARequestedSizeBgrMat() {
        val mat = StillCapture.blankLike(width = 12, height = 8)
        try {
            assertEquals(12, mat.width())
            assertEquals(8, mat.height())
            assertEquals(3, mat.channels())
        } finally {
            mat.release()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native load failed" }
        }
    }

    /**
     * The still must be capped before anything else touches it.
     *
     * The JSC-AL50 produces 6912x9216 stills -- 191 MB as a BGR Mat. Rotating
     * copies it, the dewarp's fallback clones it, and Tesseract then allocates
     * an ARGB bitmap of it at 255 MB, which killed the process on the first
     * real capture. Nothing downstream wants the pixels: the dewarp clamps its
     * own output to 3000 px.
     */
    @Test
    fun anOversizedStillIsScaledDown() {
        val huge = Mat(9216, 6912, CvType.CV_8UC3, Scalar(180.0, 180.0, 180.0))
        try {
            val capped = StillCapture.capResolution(huge)
            try {
                assertTrue("expected a smaller Mat", capped !== huge)
                assertEquals("long side should hit the cap",
                    StillCapture.MAX_LONG_SIDE.toLong(),
                    maxOf(capped.width(), capped.height()).toLong())
                val before = huge.width().toDouble() / huge.height()
                val after = capped.width().toDouble() / capped.height()
                assertTrue("aspect ratio drifted: $before vs $after",
                    kotlin.math.abs(before - after) < 0.01)
            } finally {
                if (capped !== huge) capped.release()
            }
        } finally {
            huge.release()
        }
    }

    /** A still already within the cap is passed straight through, not copied. */
    @Test
    fun aReasonableStillIsNotCopied() {
        val ok = Mat(1600, 1200, CvType.CV_8UC3, Scalar(180.0, 180.0, 180.0))
        try {
            assertSame("no copy should be made", ok, StillCapture.capResolution(ok))
        } finally {
            ok.release()
        }
    }
}
