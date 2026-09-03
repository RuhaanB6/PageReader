package com.pagereader.collector

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Why a frame was not kept. */
enum class Reject { NONE, RATE, SHAKE, BLUR, DUPLICATE }

/**
 * Decides which camera frames are worth writing to disk.
 *
 * A plain timer is not enough. Sampling every 500ms while sweeping a phone over
 * a desk produces a burst of near-identical, half-blurred frames -- the existing
 * 133-frame session is 133 pictures of one notebook on one carpet, which would
 * teach a model almost nothing. Three independent gates fix that:
 *
 *  - **Sharpness.** Variance of the Laplacian, measured rather than assumed.
 *    Motion blur collapses high-frequency energy, so a blurred frame scores low
 *    however long you waited for it.
 *  - **Motion.** The accelerometer catches blur the Laplacian misses, e.g. a
 *    frame that is internally sharp but taken mid-swing.
 *  - **Novelty.** A 32x32 thumbnail compared against recently kept frames. This
 *    is the gate that turns a burst into a dataset: hold still and the app stops
 *    saving, move and it resumes.
 *
 * The rate cap carries jitter so sampling cannot lock to a periodic hand tremor
 * or to the rolling shutter.
 */
class FrameGate(
    private val config: Config = Config(),
    private val random: () -> Double = Math::random,
) {

    data class Config(
        /** Target sampling period; actual interval is this ± [jitterMs]. */
        val periodMs: Long = 500,
        val jitterMs: Long = 150,
        /**
         * Absolute Laplacian-variance floor. Only a backstop against a truly
         * dead frame -- calibration measured 348..4588 on a lit desk, so an
         * absolute threshold tight enough to catch blur there would reject
         * every frame of a dim scene.
         */
        val minSharpness: Double = 80.0,
        /**
         * The gate that actually catches motion blur: reject a frame scoring
         * below this fraction of the running median of recent frames.
         *
         * Blur is a *drop relative to the same scene*, not an absolute value.
         * Laplacian variance scales with how much texture the scene has, so a
         * newspaper under a lamp and a blank sheet in dim light differ by more
         * than blur does -- which is why the first fixed threshold (25.0)
         * rejected exactly zero frames out of 48.
         */
        val relativeSharpness: Double = 0.45,
        /**
         * Floor as a fraction of the median across the whole *session*, not the
         * current take.
         *
         * A purely take-relative gate is blind to a take that is uniformly bad:
         * as every frame comes in soft the running median falls with them and
         * the floor follows it down. The steep take arrived at p50 556 against
         * 1250-2050 everywhere else, and its worst frames had no visible page
         * edge at all -- yet the gate accepted them. A session baseline cannot
         * be dragged down by one bad take.
         */
        val sessionSharpness: Double = 0.30,
        /** Frames observed before the relative gate is trusted. */
        val warmupFrames: Int = 8,
        /**
         * Mean absolute difference between 32x32 thumbnails, 0..255, below which
         * two frames count as the same shot.
         */
        val minThumbDistance: Double = 8.0,
        /** How many recently kept thumbnails a new frame is compared against. */
        val historySize: Int = 10,
    )

    data class Decision(val keep: Boolean, val reject: Reject, val sharpness: Double)

    private val history = ArrayDeque<Mat>()
    private var nextAllowedMs = 0L

    /** Recent sharpness readings, for the scene-relative blur gate. */
    private val sharpnessWindow = ArrayDeque<Double>()

    /** Sharpness across every take since the app started -- survives [reset]. */
    private val sessionWindow = ArrayDeque<Double>()

    /** Most recent sharpness reading, for the on-screen meter. */
    @Volatile
    var lastSharpness: Double = 0.0
        private set

    /**
     * @param bgr the analysed frame. Neither retained nor released here.
     */
    fun evaluate(bgr: Mat, isShaking: Boolean, nowMs: Long): Decision {
        if (nowMs < nextAllowedMs) return Decision(false, Reject.RATE, lastSharpness)

        val gray = Mat()
        val small = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            // Measure sharpness on a fixed-size image so the threshold means the
            // same thing regardless of the camera's analysis resolution.
            Imgproc.resize(gray, small, Size(SHARPNESS_W.toDouble(), SHARPNESS_H.toDouble()))

            val sharpness = laplacianVariance(small)
            lastSharpness = sharpness

            // Motion and blur are checked before novelty because they are cheap
            // and because a blurred frame must never enter the history -- it
            // would then suppress the sharp frame that follows it.
            // Observed before any gate can reject, so the running median tracks
            // the scene rather than only the frames that happened to pass.
            observeSharpness(sharpness)

            if (isShaking) return Decision(false, Reject.SHAKE, sharpness)
            if (sharpness < config.minSharpness) return Decision(false, Reject.BLUR, sharpness)
            val floor = relativeFloor()
            if (floor != null && sharpness < floor) return Decision(false, Reject.BLUR, sharpness)

            val thumb = Mat()
            Imgproc.resize(small, thumb, Size(THUMB.toDouble(), THUMB.toDouble()))
            if (tooSimilar(thumb)) {
                thumb.release()
                return Decision(false, Reject.DUPLICATE, sharpness)
            }

            remember(thumb)
            scheduleNext(nowMs)
            return Decision(true, Reject.NONE, sharpness)
        } finally {
            gray.release()
            small.release()
        }
    }

    private fun observeSharpness(v: Double) {
        sharpnessWindow.addLast(v)
        while (sharpnessWindow.size > SHARPNESS_WINDOW) sharpnessWindow.removeFirst()
        sessionWindow.addLast(v)
        while (sessionWindow.size > SESSION_WINDOW) sessionWindow.removeFirst()
    }

    /** Null until enough of the scene has been seen to have an opinion. */
    private fun relativeFloor(): Double? {
        if (sharpnessWindow.size < config.warmupFrames) return null
        val take = sharpnessWindow.sorted().let { it[it.size / 2] } * config.relativeSharpness
        // Whichever bar is higher: the scene has to be sharp for itself *and*
        // not far below the standard the rest of the session managed.
        if (sessionWindow.size < SESSION_MIN) return take
        val session = sessionWindow.sorted().let { it[it.size / 2] } * config.sessionSharpness
        return maxOf(take, session)
    }

    private fun scheduleNext(nowMs: Long) {
        val jitter = ((random() * 2 - 1) * config.jitterMs).toLong()
        nextAllowedMs = nowMs + (config.periodMs + jitter).coerceAtLeast(50L)
    }

    private fun laplacianVariance(gray8u: Mat): Double {
        val lap = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Imgproc.Laplacian(gray8u, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, stddev)
            val sd = stddev.toArray().firstOrNull() ?: 0.0
            return sd * sd
        } finally {
            lap.release()
            mean.release()
            stddev.release()
        }
    }

    private fun tooSimilar(thumb: Mat): Boolean =
        history.any { Core.norm(it, thumb, Core.NORM_L1) / (THUMB * THUMB) < config.minThumbDistance }

    private fun remember(thumb: Mat) {
        history.addLast(thumb)
        while (history.size > config.historySize) history.removeFirst().release()
    }

    fun reset() {
        history.forEach { it.release() }
        history.clear()
        sharpnessWindow.clear()
        nextAllowedMs = 0L
        lastSharpness = 0.0
    }

    companion object {
        private const val SHARPNESS_W = 320
        private const val SHARPNESS_H = 240
        private const val THUMB = 32
        private const val SHARPNESS_WINDOW = 40
        private const val SESSION_WINDOW = 600
        private const val SESSION_MIN = 60
    }
}
