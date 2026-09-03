package com.pagereader.collector

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The framing categories a take can capture.
 *
 * The partial ones are the point. A page running off the edge of the frame is
 * the state the user is in while being guided, so a model trained only on
 * complete pages would fail exactly when it is needed -- these four are worth
 * more shooting time than [FULL_OVERHEAD] is.
 */
enum class Take(val tag: String, val hint: String) {
    FULL_OVERHEAD("full-overhead", "Whole page in frame, camera flat above it"),
    PARTIAL_LEFT("partial-left", "Slide until the page runs off the left edge, then back"),
    PARTIAL_RIGHT("partial-right", "Slide off the right edge, then back"),
    PARTIAL_TOP("partial-top", "Slide off the top edge, then back"),
    PARTIAL_BOTTOM("partial-bottom", "Slide off the bottom edge, then back"),
    DISTANCE("distance", "Move in until the page overflows, then far back"),
    TILTED("tilted", "Tilt to about 30 degrees and orbit slowly"),
    STEEP("steep", "Steep angle, close to edge-on"),
    NEGATIVE("negative", "Remove the document, sweep the bare surface"),
}

/** The physical scene. Becomes part of the folder name and every filename. */
data class Setup(
    val surface: String = "darkdesk",
    val document: String = "sheet",
    val lighting: String = "bright",
) {
    val tag: String get() = "${surface}_${document}_$lighting"

    companion object {
        val SURFACES = listOf("darkdesk", "lightdesk", "wood", "carpet", "cluttered")
        val DOCUMENTS = listOf("sheet", "newspaper", "book", "magazine", "letter", "none")
        val LIGHTING = listOf("bright", "dim", "sidelit", "shadowed", "mixed")
    }
}

/** Running tally for the on-screen counters. */
data class TakeStats(
    val kept: Int = 0,
    val blur: Int = 0,
    val duplicate: Int = 0,
    val shake: Int = 0,
) {
    fun plus(reject: Reject) = when (reject) {
        Reject.NONE -> copy(kept = kept + 1)
        Reject.BLUR -> copy(blur = blur + 1)
        Reject.DUPLICATE -> copy(duplicate = duplicate + 1)
        Reject.SHAKE -> copy(shake = shake + 1)
        Reject.RATE -> this
    }
}

/**
 * Writes one setup's takes to disk.
 *
 * Layout, chosen so the tags survive into the filenames -- the labelling and
 * review tools group by them, and the partial-* takes are where hand-correction
 * time is best spent:
 *
 *     collections/<stamp>_<setup>/
 *       meta.json
 *       manifest.csv
 *       frames/<take>_00013.jpg
 */
class CollectionWriter(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor()

    private var root: File? = null
    private var framesDir: File? = null
    private var manifest: File? = null
    private var takeLog: File? = null
    private var counter = 0

    val directory: File? get() = root

    fun begin(setup: Setup, gateConfig: FrameGate.Config) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(System.currentTimeMillis())
        val dir = File(context.getExternalFilesDir(null), "collections/${stamp}_${setup.tag}")
        File(dir, "frames").mkdirs()

        root = dir
        framesDir = File(dir, "frames")
        manifest = File(dir, "manifest.csv").apply {
            if (!exists()) writeText("file,take,surface,document,lighting,sharpness,tMs\n")
        }
        takeLog = File(dir, "takes.csv").apply {
            if (!exists()) writeText("take,kept,blur,duplicate,shake,seconds\n")
        }
        counter = existingFrameCount()

        File(dir, "meta.json").writeText(
            JSONObject()
                .put("startedAt", stamp)
                .put("surface", setup.surface)
                .put("document", setup.document)
                .put("lighting", setup.lighting)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("androidSdk", Build.VERSION.SDK_INT)
                // Every gate, not just the absolute one. Which config produced a
                // collection has to be readable from the collection itself --
                // the relative gates are the ones that do the real work, and
                // without them here you cannot tell two shoots apart.
                .put("minSharpness", gateConfig.minSharpness)
                .put("relativeSharpness", gateConfig.relativeSharpness)
                .put("sessionSharpness", gateConfig.sessionSharpness)
                .put("minThumbDistance", gateConfig.minThumbDistance)
                .put("periodMs", gateConfig.periodMs)
                .put("warmupFrames", gateConfig.warmupFrames)
                .toString(2)
        )
        Log.i(TAG, "collecting into ${dir.absolutePath}")
    }

    private fun existingFrameCount(): Int = framesDir?.listFiles()?.size ?: 0

    /**
     * Records how a take went, including what was thrown away.
     *
     * The rejection tallies are the calibration data. Without them the manifest
     * only describes frames that passed, which says nothing about whether the
     * gates are set sensibly -- the first threshold rejected zero frames out of
     * 48 and that was invisible from the manifest alone.
     */
    fun finishTake(take: Take, stats: TakeStats, seconds: Long) {
        val log = takeLog ?: return
        io.execute {
            try {
                log.appendText(
                    "${take.tag},${stats.kept},${stats.blur},${stats.duplicate}," +
                        "${stats.shake},$seconds\n"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "take log failed", t)
            }
        }
    }

    /** How many frames the named take already has, so a reshoot can replace it. */
    fun countFor(take: Take): Int =
        framesDir?.listFiles { f -> f.name.startsWith(take.tag + "_") }?.size ?: 0

    /** Deletes a take's frames. Used when re-recording a category. */
    fun discard(take: Take) {
        val dir = framesDir ?: return
        io.execute {
            dir.listFiles { f -> f.name.startsWith(take.tag + "_") }?.forEach { it.delete() }
            Log.i(TAG, "discarded previous $take")
        }
    }

    /** @param bgr cloned internally; the caller keeps ownership. */
    fun write(bgr: Mat, take: Take, setup: Setup, sharpness: Double, tMs: Long) {
        val clone = bgr.clone()
        val index = counter++
        io.execute {
            try {
                val name = "${take.tag}_%05d.jpg".format(index)
                val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
                val buf = MatOfByte()
                if (Imgcodecs.imencode(".jpg", clone, buf, params)) {
                    File(framesDir, name).writeBytes(buf.toArray())
                    manifest?.appendText(
                        "$name,${take.tag},${setup.surface},${setup.document}," +
                            "${setup.lighting},%.1f,$tMs\n".format(sharpness)
                    )
                }
                buf.release()
                params.release()
            } catch (t: Throwable) {
                Log.w(TAG, "write failed", t)
            } finally {
                clone.release()
            }
        }
    }

    /** Free space in MB on the volume the frames are written to. */
    fun freeMb(): Long {
        val dir = context.getExternalFilesDir(null) ?: return 0
        val st = StatFs(dir.absolutePath)
        return st.availableBytes / (1024 * 1024)
    }

    fun close() {
        io.shutdown()
        try {
            io.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "CollectionWriter"
        private const val JPEG_QUALITY = 88
    }
}
