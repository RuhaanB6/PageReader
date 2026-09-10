package com.pagereader.collector

/**
 * On-device port of `tools/verify-collection.py`.
 *
 * The desktop script answers "is this take usable?" only once the collection has
 * been pulled over adb. That is the wrong moment: by then the scene is off the
 * desk and reshooting means rebuilding it. This asks the same question on the
 * phone, the instant a take ends, while everything is still set up.
 *
 * The thresholds are deliberately identical to the Python. If they drift, a take
 * passes here and fails there, which is worse than having no check at all.
 */
object TakeVerdict {

    /** Below this a take has too little to contribute to training. */
    const val MIN_FRAMES = 12

    /**
     * Mean L1 distance between 32x32 thumbnails, 0..255.
     *
     * Frame count alone cannot see this: 40 frames shot without moving the phone
     * are worth about as much as one frame. The novelty gate only rejects
     * *consecutive* near-duplicates, so a slow drift back and forth still yields
     * a uniform take that the counter reports as healthy.
     */
    const val MIN_DIVERSITY = 12.0

    /** Laplacian-variance median below which the whole take reads as soft. */
    const val SOFT_P50 = 40.0

    /** Fraction of judged frames rejected as blur above which the take is suspect. */
    const val BLUR_DOMINATED = 0.5

    /** Likewise for duplicates. */
    const val DUP_DOMINATED = 0.8

    /** Partial-edge frames drive the guidance cues; below this the set is thin. */
    const val MIN_PARTIAL_FRACTION = 0.30

    /** What was measured about a take while it was being shot. */
    data class Quality(
        val frames: Int,
        val sharpnessP50: Double,
        val diversity: Double,
    )

    /**
     * @param fatal reasons the take should be reshot now.
     * @param advice things worth knowing that do not by themselves condemn it.
     */
    data class Verdict(
        val fatal: List<String>,
        val advice: List<String>,
    ) {
        val ok: Boolean get() = fatal.isEmpty()

        /** Compact form for takes.csv, so the desktop tools see the same call. */
        val summary: String
            get() = when {
                fatal.isNotEmpty() -> "RESHOOT: ${fatal.joinToString("; ")}"
                advice.isNotEmpty() -> "ok (${advice.joinToString("; ")})"
                else -> "ok"
            }
    }

    fun judge(stats: TakeStats, quality: Quality): Verdict {
        val fatal = mutableListOf<String>()
        val advice = mutableListOf<String>()

        if (quality.frames < MIN_FRAMES) {
            fatal += "only ${quality.frames} frames, need $MIN_FRAMES"
        }
        // Guard against judging diversity on a sample too small to mean anything.
        if (quality.frames >= 2 && quality.diversity < MIN_DIVERSITY) {
            fatal += "too uniform (${fmt(quality.diversity)}) — move the phone more between frames"
        }
        if (quality.frames > 0 && quality.sharpnessP50 < SOFT_P50) {
            fatal += "soft (median ${fmt(quality.sharpnessP50)}) — steadier, or more light"
        }

        val judged = stats.kept + stats.blur + stats.duplicate + stats.shake
        if (judged > 0) {
            val blurFrac = stats.blur.toDouble() / judged
            val dupFrac = stats.duplicate.toDouble() / judged
            if (blurFrac > BLUR_DOMINATED) {
                advice += "${pct(blurFrac)} blur-rejected — sweeping too fast"
            }
            if (dupFrac > DUP_DOMINATED) {
                advice += "${pct(dupFrac)} duplicates — move more between frames"
            }
        }
        return Verdict(fatal, advice)
    }

    /** Which takes are still missing, and whether the partial-edge share is thin. */
    data class SessionVerdict(
        val missing: List<Take>,
        val totalFrames: Int,
        val partialFraction: Double,
    ) {
        val complete: Boolean get() = missing.isEmpty()
        val partialThin: Boolean
            get() = totalFrames > 0 && partialFraction < MIN_PARTIAL_FRACTION
    }

    fun judgeSession(counts: Map<Take, Int>): SessionVerdict {
        val missing = Take.entries.filter { (counts[it] ?: 0) == 0 }
        val total = counts.values.sum()
        val partial = counts.entries
            .filter { it.key.tag.startsWith("partial") }
            .sumOf { it.value }
        return SessionVerdict(
            missing = missing,
            totalFrames = total,
            partialFraction = if (total > 0) partial.toDouble() / total else 0.0,
        )
    }

    /**
     * Mean pairwise distance between thumbnails, matching the Python's
     * `np.abs(a - b).mean()` over 32x32 grayscale.
     */
    fun diversityOf(distances: List<Double>): Double =
        if (distances.isEmpty()) 0.0 else distances.average()

    private fun fmt(v: Double) = String.format("%.0f", v)
    private fun pct(v: Double) = String.format("%.0f%%", v * 100)
}
