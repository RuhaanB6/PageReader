package com.pagereader.android.storage

import android.content.Context
import android.util.Log
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock
import com.pagereader.android.reading.PagePlayer
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Keeps captured pages and where the listener got to in each.
 *
 * One directory per capture holding three plain files — `page.jpg`, `ocr.json`,
 * `state.json`. No database: there is nothing to query, the volume is a handful
 * of pages, and files can be pulled off the device and inspected directly,
 * which a Room database cannot. That last point matters more than it sounds,
 * because these captures double as a real-world evaluation corpus.
 *
 * This is deliberately **not** gated on `FLAG_DEBUGGABLE`, unlike
 * `telemetry/SessionRecorder`. That records the framing hunt and is a
 * developer tool; this is the user's document and their place in it. The
 * settled decision is to keep the captured page and the reading position, and
 * not to accumulate the framing phase.
 */
class CaptureStore(context: Context) {

    private val root = File(context.filesDir, DIR).apply { mkdirs() }

    data class Capture(
        val id: String,
        val dir: File,
        val savedAtMs: Long,
    ) {
        val pageFile: File get() = File(dir, PAGE)
        val ocrFile: File get() = File(dir, OCR)
        val stateFile: File get() = File(dir, STATE)
    }

    /**
     * Writes a capture and returns it, or null if it could not be stored.
     *
     * Never throws: a failure to save must not lose the page the user is about
     * to hear. The reading path carries on from memory.
     */
    fun save(page: Mat, ocr: OcrPage, id: String = newId()): Capture? {
        val dir = File(root, id)
        return try {
            dir.mkdirs()
            // Quality 85: the page is already dewarped and the store exists to
            // re-read and to evaluate, not to archive at source fidelity.
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            val wrote = Imgcodecs.imwrite(File(dir, PAGE).absolutePath, page, params)
            params.release()
            if (!wrote) error("imwrite failed for ${dir.name}")

            File(dir, OCR).writeText(toJson(ocr).toString())
            savePosition(id, PagePlayer.Position.START)
            Capture(id, dir, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.e(TAG, "could not save capture $id", t)
            dir.deleteRecursively()
            null
        }
    }

    /** Most recent first. */
    fun list(): List<Capture> =
        root.listFiles { f: File -> f.isDirectory && File(f, OCR).exists() }
            .orEmpty()
            .map { Capture(it.name, it, it.lastModified()) }
            .sortedByDescending { it.savedAtMs }

    fun mostRecent(): Capture? = list().firstOrNull()

    fun loadOcr(id: String): OcrPage? = try {
        File(File(root, id), OCR).takeIf { it.exists() }
            ?.let { fromJson(JSONObject(it.readText())) }
    } catch (t: Throwable) {
        Log.e(TAG, "could not read OCR for $id", t)
        null
    }

    /**
     * Saves the reading position.
     *
     * Written on every block change, so it is small and cheap on purpose — the
     * app being killed mid-page is the normal case, not an edge case.
     */
    fun savePosition(id: String, position: PagePlayer.Position) {
        try {
            val dir = File(root, id)
            if (!dir.exists()) return
            File(dir, STATE).writeText(
                JSONObject()
                    .put("blockIndex", position.blockIndex)
                    .put("sentenceIndex", position.sentenceIndex)
                    .toString()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not save position for $id", t)
        }
    }

    /** The saved position, or the start of the page if there is none. */
    fun loadPosition(id: String): PagePlayer.Position = try {
        val f = File(File(root, id), STATE)
        if (!f.exists()) {
            PagePlayer.Position.START
        } else {
            val o = JSONObject(f.readText())
            PagePlayer.Position(o.optInt("blockIndex", 0), o.optInt("sentenceIndex", 0))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read position for $id", t)
        PagePlayer.Position.START
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Deletes the oldest captures beyond [keep].
     *
     * A cap rather than unbounded growth, because nothing else ever deletes
     * these and a page is on the order of a megabyte. `SessionRecorder` has the
     * same problem and has not been given the same treatment yet.
     */
    fun prune(keep: Int = MAX_CAPTURES) {
        list().drop(keep).forEach {
            Log.i(TAG, "pruning old capture ${it.id}")
            it.dir.deleteRecursively()
        }
    }

    // --- serialisation ----------------------------------------------------
    // Hand-rolled with org.json rather than adding a JSON library: the shape is
    // small, fixed, and this keeps the dependency list short on a device with
    // no Play Services.

    private fun toJson(page: OcrPage) = JSONObject().apply {
        put("pageWidth", page.pageWidth)
        put("pageHeight", page.pageHeight)
        put("meanConfidence", page.meanConfidence.toDouble())
        put("elapsedMs", page.elapsedMs)
        put("quarterTurnsClockwise", page.quarterTurnsClockwise)
        put("blocks", JSONArray().apply {
            page.blocks.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("order", b.order)
                    put("text", b.text)
                    put("x", b.bbox.x); put("y", b.bbox.y)
                    put("w", b.bbox.width); put("h", b.bbox.height)
                    put("confidence", b.confidence.toDouble())
                    put("kind", b.kind.name)
                    put("medianWordHeight", b.medianWordHeight.toDouble())
                    put("lineCount", b.lineCount)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): OcrPage {
        val arr = o.optJSONArray("blocks") ?: JSONArray()
        val blocks = (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            TextBlock(
                id = b.optInt("id"),
                order = b.optInt("order"),
                text = b.optString("text"),
                bbox = Rect(b.optInt("x"), b.optInt("y"), b.optInt("w"), b.optInt("h")),
                confidence = b.optDouble("confidence", 0.0).toFloat(),
                // An unknown kind means a newer build wrote this; treat it as
                // body rather than dropping the block, which would silently
                // lose text.
                kind = runCatching { BlockKind.valueOf(b.optString("kind")) }
                    .getOrDefault(BlockKind.BODY),
                medianWordHeight = b.optDouble("medianWordHeight", 0.0).toFloat(),
                lineCount = b.optInt("lineCount"),
            )
        }
        return OcrPage(
            pageWidth = o.optInt("pageWidth"),
            pageHeight = o.optInt("pageHeight"),
            blocks = blocks,
            meanConfidence = o.optDouble("meanConfidence", 0.0).toFloat(),
            elapsedMs = o.optLong("elapsedMs"),
            quarterTurnsClockwise = o.optInt("quarterTurnsClockwise", 0),
        )
    }

    companion object {
        private const val TAG = "CaptureStore"
        private const val DIR = "captures"
        private const val PAGE = "page.jpg"
        private const val OCR = "ocr.json"
        private const val STATE = "state.json"
        private const val JPEG_QUALITY = 85

        /** Pages kept before the oldest are pruned. */
        const val MAX_CAPTURES = 20

        fun newId(): String = "cap-${System.currentTimeMillis()}"
    }
}
