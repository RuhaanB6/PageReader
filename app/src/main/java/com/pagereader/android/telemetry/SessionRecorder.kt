package com.pagereader.android.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.json.JSONArray
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
 * Records what the detector saw and what the app said, so a real session on real
 * hardware can be read back afterwards instead of reconstructed from memory.
 *
 * Two streams, written side by side:
 *  - `events.jsonl` -- one line per analysed frame: latency, coverage,
 *    confidence, clipped borders, the instruction in force, anything spoken.
 *  - JPEG snapshots under `frames/`, so a line saying "confidence 0.11"
 *    can be matched to the picture that produced it. Numbers alone cannot tell
 *    us *why* a newspaper was missed.
 *
 * Everything lands in the app's external files dir, which `adb pull` can reach
 * with no root and no permissions.
 *
 * All disk work happens on a single background thread; the analysis thread hands
 * over a cloned Mat and moves on.
 *
 * **Debug builds only.** The snapshots are pictures of whatever the user pointed
 * the camera at, which for this app means their documents. Recording that by
 * default, with no indication it is happening and no way for a blind user to
 * notice, is not acceptable in a shipped build -- so on a non-debuggable build
 * every method here is a no-op and nothing is ever written.
 */
class SessionRecorder(private val context: Context) {

    /**
     * Read from the installed application info rather than `BuildConfig.DEBUG`,
     * which is not generated unless the `buildConfig` feature is switched on.
     */
    private val enabled: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val io = Executors.newSingleThreadExecutor()

    private var dir: File? = null
    private var events: File? = null
    private var framesDir: File? = null

    private var frameIndex = 0
    private var snapshots = 0
    private var lastSnapshotMs = 0L
    private var startedMs = 0L

    fun start() {
        if (!enabled) {
            Log.i(TAG, "release build: session recording disabled")
            return
        }
        io.execute {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
            val root = File(context.getExternalFilesDir(null), "sessions/$stamp")
            val frames = File(root, "frames")
            frames.mkdirs()

            dir = root
            framesDir = frames
            events = File(root, "events.jsonl")
            startedMs = System.currentTimeMillis()

            File(root, "meta.json").writeText(
                JSONObject()
                    .put("startedAt", stamp)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("androidSdk", Build.VERSION.SDK_INT)
                    .put("fingerprint", Build.FINGERPRINT)
                    .toString(2)
            )
            Log.i(TAG, "recording session to ${root.absolutePath}")
        }
    }

    /**
     * @param snapshot a small BGR Mat to consider saving. **Cloned here** -- the
     *   caller keeps ownership of the original and may release it immediately.
     * @param interesting forces a snapshot regardless of the rate limit; use it
     *   for the frames that actually explain a failure.
     */
    fun logFrame(
        latencyMs: Long,
        source: String,
        confidence: Float,
        coverage: Float,
        clipped: Collection<String>,
        quad: List<Pair<Double, Double>>?,
        state: String,
        instruction: String?,
        utterance: String?,
        isShaking: Boolean,
        captured: Boolean,
        snapshot: Mat?,
        interesting: Boolean,
    ) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        val wantSnapshot = snapshot != null && snapshots < MAX_SNAPSHOTS &&
            (interesting || nowMs - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS)
        val clone = if (wantSnapshot) snapshot!!.clone() else null
        if (wantSnapshot) lastSnapshotMs = nowMs

        io.execute {
            val eventsFile = events ?: run { clone?.release(); return@execute }
            val index = frameIndex++

            var imageName: String? = null
            if (clone != null) {
                try {
                    val name = "f%05d.jpg".format(index)
                    val target = File(framesDir, name)
                    val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
                    val buf = MatOfByte()
                    if (Imgcodecs.imencode(".jpg", clone, buf, params)) {
                        target.writeBytes(buf.toArray())
                        imageName = "frames/$name"
                        snapshots++
                    }
                    buf.release()
                    params.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "snapshot failed", t)
                } finally {
                    clone.release()
                }
            }

            val o = JSONObject()
                .put("i", index)
                .put("tMs", nowMs - startedMs)
                .put("latencyMs", latencyMs)
                .put("source", source)
                .put("confidence", confidence.toDouble())
                .put("coverage", coverage.toDouble())
                .put("clipped", JSONArray(clipped))
                .put("state", state)
                .put("instruction", instruction ?: JSONObject.NULL)
                .put("utterance", utterance ?: JSONObject.NULL)
                .put("shaking", isShaking)
                .put("captured", captured)
                .put("image", imageName ?: JSONObject.NULL)

            if (quad != null) {
                val arr = JSONArray()
                quad.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
                o.put("quad", arr)
            }

            try {
                eventsFile.appendText(o.toString() + "\n")
            } catch (t: Throwable) {
                Log.w(TAG, "event write failed", t)
            }
        }
    }

    /** Free-text marker, for anything worth finding later in the log. */
    fun note(text: String) {
        if (!enabled) return
        val at = System.currentTimeMillis() - startedMs
        io.execute {
            try {
                events?.appendText(
                    JSONObject().put("tMs", at).put("note", text).toString() + "\n"
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        if (!enabled) return
        note("session end")
        io.shutdown()
        try {
            io.awaitTermination(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        Log.i(TAG, "session closed: ${dir?.absolutePath} ($frameIndex frames, $snapshots snapshots)")
    }

    companion object {
        private const val TAG = "SessionRecorder"

        /** Routine snapshots at this interval; failures bypass it. */
        private const val SNAPSHOT_INTERVAL_MS = 2_000L

        /** Hard cap so a long session cannot fill the device. */
        private const val MAX_SNAPSHOTS = 120

        private const val JPEG_QUALITY = 70
    }
}
