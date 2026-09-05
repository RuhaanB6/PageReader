package com.pagereader.android.guidance

import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.Side
import kotlin.math.abs

/**
 * Decides the single thing to say next.
 *
 * The old guidance layer re-evaluated on every one of ~30 frames per second and
 * relied on per-cue cooldowns in the speech layer to keep the volume down. That
 * fails in both directions: cues from different code paths interleave and cut
 * each other off, and a framing that hovers on a threshold re-triggers forever.
 *
 * This one holds exactly one active instruction and speaks only on transition:
 *
 *  - **Priority.** Conditions are checked in a fixed order and the first match
 *    wins. Lower-priority problems are simply not mentioned until the current
 *    one is resolved, so the user is never handed two corrections at once.
 *  - **Dwell.** A condition has to survive [Config.dwellFrames] consecutive
 *    observations before it is allowed to become the active instruction. A
 *    single noisy frame cannot make the app talk.
 *  - **Hysteresis.** Release thresholds are looser than trigger thresholds, so a
 *    user sitting right on the boundary is not pushed back and forth.
 *  - **Silence by default.** Nothing confidently wrong means nothing said.
 *
 * Pure Kotlin on purpose -- no OpenCV, no Android -- so the whole thing is
 * testable on the JVM by feeding it observation sequences.
 *
 * Not thread-safe: call from the main thread only.
 */
class GuidancePolicy(private val config: Config = Config()) {

    data class Config(
        /** Consecutive observations a condition must hold before it is spoken. */
        val dwellFrames: Int = 3,
        /** Below this, an observation is treated as "no page". */
        /**
         * Measured on the 725-frame model: 0.45 keeps 99% of correct boxes but
         * lets through spurious carpet boxes at 0.31-0.59, where correct ones
         * sit at 0.84-0.91. Raising to 0.60 halves both the spurious-box count
         * and the oversized-box rate on unseen carpet, and the 5% of frames it
         * costs is nearly free because dwellFrames already tolerates drops.
         */
        val minConfidence: Float = 0.60f,

        val coverageTooCloseTrigger: Float = 0.90f,
        val coverageTooCloseRelease: Float = 0.80f,
        val coverageTooFarTrigger: Float = 0.20f,
        val coverageTooFarRelease: Float = 0.30f,

        val tiltTriggerDeg: Float = 15f,
        val tiltReleaseDeg: Float = 8f,

        /** Fraction of frame width/height the page centre may sit off-centre. */
        val offCenterTrigger: Float = 0.20f,
        val offCenterRelease: Float = 0.12f,

        /** Re-say an unresolved instruction after this long. Deliberately long. */
        val reassertAfterMs: Long = 6_000L,
        /** Say "no page in view" only after this much continuous nothing. */
        val noPageAfterMs: Long = 5_000L,
        /** Framed and steady for this long before capture fires. */
        val steadyHoldMs: Long = 800L,
    )

    data class Decision(
        val state: FramingState,
        /** The instruction currently in force, or null if nothing needs correcting. */
        val instruction: Instruction?,
        /** Non-null only on the update where this should actually be spoken. */
        val utterance: Instruction?,
        /** True on the single update where auto-capture should fire. */
        val capture: Boolean,
    )

    private var active: Instruction? = null
    private var activeSpokenAtMs = 0L

    private var pending: Instruction? = null
    private var pendingCount = 0

    private var lastSeenMs: Long? = null
    private var framedSinceMs: Long? = null
    private var captureFired = false

    /** Call after a capture completes, or when returning to the camera. */
    fun reset() {
        active = null
        pending = null
        pendingCount = 0
        lastSeenMs = null
        framedSinceMs = null
        captureFired = false
    }

    fun update(observation: PageObservation?, isShaking: Boolean, nowMs: Long): Decision {
        val hasPage = observation?.quad != null && observation.confidence >= config.minConfidence
        if (lastSeenMs == null || hasPage) lastSeenMs = nowMs

        val candidate: Instruction? = when {
            hasPage -> evaluate(observation!!, isShaking)
            // A brief dropout should not cancel the instruction the user is
            // still acting on -- keep saying nothing and hold what we had.
            nowMs - lastSeenMs!! < config.noPageAfterMs -> active
            else -> Instruction.NO_PAGE
        }

        var utterance: Instruction? = null

        if (candidate == active) {
            pending = null
            pendingCount = 0
            // NO_PAGE is exempt: announced once, then silence until a page shows up.
            if (active != null && active != Instruction.NO_PAGE &&
                nowMs - activeSpokenAtMs >= config.reassertAfterMs
            ) {
                activeSpokenAtMs = nowMs
                utterance = active
            }
        } else {
            if (candidate == pending) pendingCount++ else { pending = candidate; pendingCount = 1 }
            if (pendingCount >= config.dwellFrames) {
                active = candidate
                pending = null
                pendingCount = 0
                if (active != null) {
                    activeSpokenAtMs = nowMs
                    utterance = active
                }
            }
        }

        // Capture gating: only once everything is resolved and the page is there.
        //
        // "Resolved" has to mean *pending as well as active*. An instruction only
        // becomes `active` after surviving dwellFrames, so between a page starting
        // to run off the edge and the cue being confirmed there is a window --
        // 3 frames, ~400 ms on device -- where `active` is still null and the
        // steady timer is still running. Gating on `active` alone fired the
        // shutter inside that window and then said "Move left" 0.2 s after the
        // photo. Observed on device 2026-09-05: captures at clip=LEFT and
        // clip=RIGHT, each followed by its own correction.
        //
        // `clipped` is checked directly rather than via the instruction because it
        // is a topological fact about the mask, not a debounced opinion: if the
        // page touches the frame border then part of the page is not in the photo,
        // and no amount of confidence makes that a good capture.
        val correctionPending = pending != null && pending != Instruction.NO_PAGE
        val clipped = observation?.clipped?.isNotEmpty() == true

        var capture = false
        val state: FramingState
        if (!hasPage) {
            framedSinceMs = null
            state = FramingState.SEARCHING
        } else if (active != null || correctionPending || clipped) {
            framedSinceMs = null
            captureFired = false
            state = FramingState.ADJUSTING
        } else {
            val since = framedSinceMs ?: nowMs.also { framedSinceMs = it }
            if (nowMs - since >= config.steadyHoldMs) {
                state = FramingState.STEADY
                if (!captureFired) {
                    captureFired = true
                    capture = true
                }
            } else {
                state = FramingState.FRAMED
            }
        }

        return Decision(state, active, utterance, capture)
    }

    /**
     * First match wins. Order is deliberate: a page hanging off the edge of the
     * frame makes every other measurement meaningless -- coverage is truncated
     * and the centroid is pulled toward the visible part -- so clipping is
     * resolved before anything else is even considered.
     */
    private fun evaluate(o: PageObservation, isShaking: Boolean): Instruction? {
        val c = o.clipped
        if (c.isNotEmpty()) {
            // Clipped on both sides of an axis: the page is wider than the view,
            // so no amount of panning helps.
            if ((Side.LEFT in c && Side.RIGHT in c) || (Side.TOP in c && Side.BOTTOM in c)) {
                return Instruction.MOVE_BACK
            }
            // Fixed order, so two clipped borders resolve one at a time instead
            // of alternating between two instructions frame to frame.
            if (Side.LEFT in c) return Instruction.MOVE_LEFT
            if (Side.RIGHT in c) return Instruction.MOVE_RIGHT
            if (Side.TOP in c) return Instruction.MOVE_UP
            return Instruction.MOVE_DOWN
        }

        if (exceeds(o.coverage, config.coverageTooCloseTrigger, config.coverageTooCloseRelease, Instruction.MOVE_BACK)) {
            return Instruction.MOVE_BACK
        }
        if (falls(o.coverage, config.coverageTooFarTrigger, config.coverageTooFarRelease, Instruction.MOVE_CLOSER)) {
            return Instruction.MOVE_CLOSER
        }

        val tilt = o.tiltDegrees
        if (tilt != null &&
            exceeds(abs(tilt), config.tiltTriggerDeg, config.tiltReleaseDeg, Instruction.STRAIGHTEN)
        ) {
            return Instruction.STRAIGHTEN
        }

        val centre = o.center
        if (centre != null && o.frameWidth > 0 && o.frameHeight > 0) {
            val dx = (centre.x / o.frameWidth - 0.5).toFloat()
            val dy = (centre.y / o.frameHeight - 0.5).toFloat()
            // Correct the worse axis first rather than mentioning both.
            if (abs(dx) >= abs(dy)) {
                val ins = if (dx > 0) Instruction.MOVE_RIGHT else Instruction.MOVE_LEFT
                if (exceeds(abs(dx), config.offCenterTrigger, config.offCenterRelease, ins)) return ins
            } else {
                val ins = if (dy > 0) Instruction.MOVE_DOWN else Instruction.MOVE_UP
                if (exceeds(abs(dy), config.offCenterTrigger, config.offCenterRelease, ins)) return ins
            }
        }

        if (isShaking) return Instruction.HOLD_STILL

        return null
    }

    /** True if [v] is high enough to raise [ins], or -- if [ins] is already the
     *  active instruction -- still too high to release it. */
    private fun exceeds(v: Float, trigger: Float, release: Float, ins: Instruction) =
        if (active == ins) v > release else v > trigger

    /** Mirror of [exceeds] for conditions that fire when a value is too low. */
    private fun falls(v: Float, trigger: Float, release: Float, ins: Instruction) =
        if (active == ins) v < release else v < trigger
}
