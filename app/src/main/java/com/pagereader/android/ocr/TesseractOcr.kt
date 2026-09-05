package com.pagereader.android.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
// The artifact is cz.adaptech.tesseract4android, but it keeps tess-two's
// original package so it stays a drop-in replacement for it.
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.StringReader

/**
 * Tesseract behind [PageOcr].
 *
 * Three things here are not obvious and each cost something to establish:
 *
 * **The traineddata must live on the filesystem.** Tesseract opens it with
 * native file I/O, which cannot see inside an APK, so the asset is copied to
 * `filesDir/tessdata/` on first use. The datapath handed to `init` is the
 * *parent* of `tessdata`, not `tessdata` itself.
 *
 * **Feed it grayscale and let it threshold.** Tesseract 5's internal
 * binarisation is better than a hand-rolled one, and pre-binarising throws away
 * the information it would have used. This is the opposite of the advice that
 * circulates for Tesseract 3.
 *
 * **Confidence is a real signal, so act on it.** Measured across ten pages,
 * every result below 0.60 was genuinely broken and everything above was fine.
 * The commonest cause is a page rotated 90 degrees, which scores ~0.36 and
 * recovers to ~0.87 when rerun rotated — so a low score triggers exactly that
 * retry rather than being reported as failure.
 *
 * Not thread-safe: [TessBaseAPI] holds native state, so this is one instance on
 * one background thread.
 */
class TesseractOcr private constructor(
    private val api: TessBaseAPI,
) : PageOcr {

    override fun recognise(page: Mat): OcrPage {
        val started = System.currentTimeMillis()
        val first = runOnce(page)

        // A page photographed sideways is the one failure the confidence score
        // reliably catches, and it is cheap to undo. Rotating costs one more
        // pass; getting it wrong costs the user the entire document.
        if (first.meanConfidence >= OcrPage.USABLE_CONFIDENCE) {
            return first.toPage(started)
        }

        Log.i(TAG, "confidence ${first.meanConfidence} below ${OcrPage.USABLE_CONFIDENCE}; retrying rotated")
        val rotated = Mat()
        return try {
            Core.rotate(page, rotated, Core.ROTATE_90_CLOCKWISE)
            val second = runOnce(rotated)
            if (second.meanConfidence > first.meanConfidence) {
                Log.i(TAG, "rotated read is better (${second.meanConfidence} vs ${first.meanConfidence})")
                second.toPage(started, rotatedQuarterTurn = true)
            } else {
                first.toPage(started)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rotated retry failed; keeping the first read", t)
            first.toPage(started)
        } finally {
            rotated.release()
        }
    }

    /**
     * Wraps a parse result, recording the rotation its geometry is expressed in.
     *
     * The caller must rotate the page image to match before storing or showing
     * it beside these boxes. It is deliberately NOT the boxes that get rotated
     * back: reading order is computed from geometry, so putting the boxes into
     * the sideways frame would make band-then-column resolve columns along the
     * wrong axis and garble the reading sequence -- the upright frame is the
     * correct one to read in, which is the entire point of the retry.
     */
    private fun HocrParser.ParsedPage.toPage(
        startedMs: Long,
        rotatedQuarterTurn: Boolean = false,
    ): OcrPage {
        if (rotatedQuarterTurn) {
            Log.i(TAG, "page was read rotated a quarter turn clockwise")
        }
        return OcrPage(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            blocks = blocks,
            meanConfidence = meanConfidence,
            elapsedMs = System.currentTimeMillis() - startedMs,
            quarterTurnsClockwise = if (rotatedQuarterTurn) 1 else 0,
        )
    }

    /** One recognition pass. Returns an empty page rather than throwing. */
    private fun runOnce(page: Mat): HocrParser.ParsedPage {
        val gray = Mat()
        var bitmap: Bitmap? = null
        try {
            // Tesseract 5 thresholds better than we would; hand it luminance.
            if (page.channels() >= 3) {
                Imgproc.cvtColor(page, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                page.copyTo(gray)
            }
            bitmap = Bitmap.createBitmap(gray.width(), gray.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, bitmap)

            api.setImage(bitmap)
            val hocr = api.getHOCRText(0)
            if (hocr.isNullOrBlank()) {
                Log.w(TAG, "tesseract returned no hOCR")
                return HocrParser.ParsedPage(gray.width(), gray.height(), emptyList(), 0f)
            }
            return StringReader(hocr).use {
                HocrParser.parse(it, fallbackWidth = gray.width(), fallbackHeight = gray.height())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognition failed", t)
            return HocrParser.ParsedPage(page.width(), page.height(), emptyList(), 0f)
        } finally {
            // Clear the native image reference before the Bitmap goes away.
            runCatching { api.clear() }
            bitmap?.recycle()
            gray.release()
        }
    }

    /** Interrupts a running recognition. Safe to call from another thread. */
    fun stop() {
        runCatching { api.stop() }
    }

    override fun close() {
        runCatching { api.recycle() }
    }

    companion object {
        private const val TAG = "TesseractOcr"
        private const val LANGUAGE = "eng"
        private const val TESSDATA = "tessdata"

        /**
         * @param onProgress 0..100 while a page is being recognised. Wire this
         *   to something audible: recognition takes seconds, and to a user who
         *   cannot see a spinner, silence is indistinguishable from a crash.
         * @return null if the language data could not be prepared, which is a
         *   real possibility (no space, corrupt copy) and must degrade rather
         *   than crash.
         */
        fun create(context: Context, onProgress: ((Int) -> Unit)? = null): TesseractOcr? {
            val dataDir = try {
                prepareTessdata(context)
            } catch (t: Throwable) {
                Log.e(TAG, "could not prepare tessdata", t)
                return null
            }

            val api = if (onProgress != null) {
                TessBaseAPI { progress -> onProgress(progress.percent) }
            } else {
                TessBaseAPI()
            }

            // Datapath is the PARENT of tessdata/, not tessdata/ itself.
            if (!api.init(dataDir.absolutePath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
                Log.e(TAG, "tesseract init failed for $LANGUAGE in $dataDir")
                api.recycle()
                return null
            }

            // The page is a dewarped photo with no real DPI. Telling Tesseract
            // 300 stops it guessing from image size, which it does badly on an
            // upscaled page and which changes its segmentation decisions.
            api.setVariable("user_defined_dpi", "300")
            // PSM_AUTO (3), not PSM_AUTO_OSD (1): OSD needs osd.traineddata, a
            // further 10 MB, and the confidence-triggered rotation retry covers
            // the same failure for free.
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

            return TesseractOcr(api)
        }

        /**
         * Copies every file in the `assets/tessdata` directory to
         * `filesDir/tessdata`, and returns the directory to hand to `init` --
         * which is the parent of that, not the tessdata directory itself.
         *
         * (Avoid writing a slash-star glob in a KDoc here: Kotlin nests block
         * comments, so it opens one that never closes and the rest of the
         * companion object silently vanishes.)
         *
         * Copies only when missing or a different size, so a reinstall with new
         * language data refreshes but an ordinary launch does not rewrite 4 MB.
         */
        private fun prepareTessdata(context: Context): File {
            val dest = File(context.filesDir, TESSDATA)
            dest.mkdirs()
            val names = context.assets.list(TESSDATA).orEmpty()
            check(names.isNotEmpty()) {
                "no $TESSDATA in assets -- run tools/fetch-tessdata.sh"
            }
            // Stamp the copy with the install time rather than comparing
            // sizes. Asking AssetManager for an asset's size means openFd,
            // which throws on anything AAPT compressed -- and the traineddata
            // was compressed until `noCompress` was set, so this path is one
            // build setting away from breaking again. The install time changes
            // on every reinstall, which is exactly when a refresh is wanted.
            val stamp = File(dest, ".installed")
            val version = context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val current = stamp.takeIf { it.exists() }?.readText()

            for (name in names) {
                val out = File(dest, name)
                if (current == version && out.exists() && out.length() > 0) continue
                Log.i(TAG, "copying $name to ${out.absolutePath}")
                context.assets.open("$TESSDATA/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            stamp.writeText(version)
            return context.filesDir
        }
    }
}
