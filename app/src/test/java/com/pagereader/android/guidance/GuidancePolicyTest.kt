package com.pagereader.android.guidance

import com.pagereader.android.detect.ObservationSource
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.Pt
import com.pagereader.android.detect.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these tests is the *number* of utterances, not just their
 * content. The previous guidance layer was correct about direction and still
 * unusable because it said it thirty times a second; asserting exact utterance
 * sequences is the only way to keep that from coming back.
 *
 * Observations are fed at 200ms intervals, matching the ~5Hz the detector will
 * actually run at.
 */
class GuidancePolicyTest {

    private val frameW = 640
    private val frameH = 480

    private fun obs(
        coverage: Float = 0.5f,
        clipped: Set<Side> = emptySet(),
        cx: Double = 320.0,
        cy: Double = 240.0,
        tilt: Float? = 0f,
        confidence: Float = 0.9f,
    ) = PageObservation(
        quad = listOf(
            Pt(cx - 100, cy - 80), Pt(cx + 100, cy - 80),
            Pt(cx + 100, cy + 80), Pt(cx - 100, cy + 80),
        ),
        confidence = confidence,
        coverage = coverage,
        clipped = clipped,
        tiltDegrees = tilt,
        frameWidth = frameW,
        frameHeight = frameH,
        source = ObservationSource.NEURAL,
    )

    /** Runs [frames] updates at 200ms intervals and returns everything spoken. */
    private fun run(
        policy: GuidancePolicy,
        frames: Int,
        startMs: Long = 0L,
        observation: (Int) -> PageObservation?,
    ): List<Instruction> {
        val spoken = mutableListOf<Instruction>()
        for (i in 0 until frames) {
            val d = policy.update(observation(i), isShaking = false, nowMs = startMs + i * 200L)
            d.utterance?.let { spoken += it }
        }
        return spoken
    }

    @Test
    fun `page clipped on the left is announced once, not every frame`() {
        val spoken = run(GuidancePolicy(), frames = 20) { obs(clipped = setOf(Side.LEFT)) }
        assertEquals(listOf(Instruction.MOVE_LEFT), spoken)
    }

    @Test
    fun `good framing says nothing at all`() {
        val spoken = run(GuidancePolicy(), frames = 20) { obs() }
        assertEquals(emptyList<Instruction>(), spoken)
    }

    @Test
    fun `a condition flickering across its threshold never speaks`() {
        // Coverage alternating either side of the 0.90 trigger. Neither the
        // "too close" nor the "fine" candidate ever survives the dwell window,
        // so the correct output is silence -- this is the exact case where the
        // old per-cue cooldown design would have chattered.
        val spoken = run(GuidancePolicy(), frames = 20) { i ->
            obs(coverage = if (i % 2 == 0) 0.92f else 0.88f)
        }
        assertEquals(emptyList<Instruction>(), spoken)
    }

    @Test
    fun `once active, an instruction is not re-triggered by threshold hysteresis`() {
        val policy = GuidancePolicy()
        // Clearly too close: MOVE_BACK latches.
        val first = run(policy, frames = 4) { obs(coverage = 0.95f) }
        assertEquals(listOf(Instruction.MOVE_BACK), first)

        // Now drift into the band between release (0.80) and trigger (0.90).
        // The instruction stays in force and stays quiet -- it must not drop out
        // and re-announce itself.
        val second = run(policy, frames = 20, startMs = 800L) { i ->
            obs(coverage = if (i % 2 == 0) 0.85f else 0.82f)
        }
        assertEquals(emptyList<Instruction>(), second)
        val d = policy.update(obs(coverage = 0.85f), isShaking = false, nowMs = 5000L)
        assertEquals(Instruction.MOVE_BACK, d.instruction)
    }

    @Test
    fun `a single noisy frame cannot make the app talk`() {
        val spoken = run(GuidancePolicy(), frames = 30) { i ->
            if (i % 5 == 0) obs(clipped = setOf(Side.LEFT)) else obs()
        }
        assertEquals(emptyList<Instruction>(), spoken)
    }

    @Test
    fun `resolving one problem advances to the next, one at a time`() {
        val policy = GuidancePolicy()
        val spoken = mutableListOf<Instruction>()
        // Clipped left for 10 frames, then centred but far too small.
        for (i in 0 until 20) {
            val o = if (i < 10) obs(clipped = setOf(Side.LEFT)) else obs(coverage = 0.10f)
            policy.update(o, isShaking = false, nowMs = i * 200L).utterance?.let { spoken += it }
        }
        assertEquals(listOf(Instruction.MOVE_LEFT, Instruction.MOVE_CLOSER), spoken)
    }

    @Test
    fun `clipping on both sides of an axis asks for distance, not a pan`() {
        val spoken = run(GuidancePolicy(), frames = 10) {
            obs(clipped = setOf(Side.LEFT, Side.RIGHT))
        }
        assertEquals(listOf(Instruction.MOVE_BACK), spoken)
    }

    @Test
    fun `clipping outranks being off-centre`() {
        // Badly off-centre AND clipped: only the clipping is mentioned, because
        // the centroid of a truncated page is not a meaningful measurement.
        val spoken = run(GuidancePolicy(), frames = 10) {
            obs(clipped = setOf(Side.TOP), cx = 600.0)
        }
        assertEquals(listOf(Instruction.MOVE_UP), spoken)
    }

    @Test
    fun `an unresolved instruction is repeated only after a long silence`() {
        // 40 frames at 200ms = 8s, so exactly one re-assertion at the 6s mark.
        val spoken = run(GuidancePolicy(), frames = 40) { obs(clipped = setOf(Side.LEFT)) }
        assertEquals(listOf(Instruction.MOVE_LEFT, Instruction.MOVE_LEFT), spoken)
    }

    @Test
    fun `no page is announced once and then left alone`() {
        // 60 frames = 12s of nothing: well past both the 5s announce and what
        // would have been two 6s re-assertions.
        val spoken = run(GuidancePolicy(), frames = 60) { null }
        assertEquals(listOf(Instruction.NO_PAGE), spoken)
    }

    @Test
    fun `a brief dropout does not cancel the instruction in force`() {
        val policy = GuidancePolicy()
        val spoken = mutableListOf<Instruction>()
        for (i in 0 until 20) {
            // Every fourth frame the detector returns nothing.
            val o = if (i % 4 == 3) null else obs(clipped = setOf(Side.LEFT))
            policy.update(o, isShaking = false, nowMs = i * 200L).utterance?.let { spoken += it }
        }
        assertEquals(listOf(Instruction.MOVE_LEFT), spoken)
    }

    @Test
    fun `low confidence observations are treated as no page`() {
        val spoken = run(GuidancePolicy(), frames = 60) { obs(confidence = 0.2f) }
        assertEquals(listOf(Instruction.NO_PAGE), spoken)
    }

    @Test
    fun `capture fires once, after the page has been held steady`() {
        val policy = GuidancePolicy()
        val captureFrames = mutableListOf<Int>()
        for (i in 0 until 20) {
            if (policy.update(obs(), isShaking = false, nowMs = i * 200L).capture) captureFrames += i
        }
        // steadyHoldMs is 800ms, so the fifth frame is the first eligible one.
        assertEquals(listOf(4), captureFrames)
    }

    @Test
    fun `shaking blocks capture and asks the user to hold still`() {
        val policy = GuidancePolicy()
        val spoken = mutableListOf<Instruction>()
        var captures = 0
        for (i in 0 until 20) {
            val d = policy.update(obs(), isShaking = true, nowMs = i * 200L)
            d.utterance?.let { spoken += it }
            if (d.capture) captures++
        }
        assertEquals(listOf(Instruction.HOLD_STILL), spoken)
        assertEquals(0, captures)
    }

    @Test
    fun `state reaches STEADY only when nothing is left to correct`() {
        val policy = GuidancePolicy()
        assertEquals(
            FramingState.SEARCHING,
            policy.update(null, isShaking = false, nowMs = 0L).state
        )
        repeat(4) { i ->
            policy.update(obs(clipped = setOf(Side.LEFT)), isShaking = false, nowMs = 200L + i * 200L)
        }
        assertEquals(
            FramingState.ADJUSTING,
            policy.update(obs(clipped = setOf(Side.LEFT)), isShaking = false, nowMs = 1200L).state
        )
        var last = FramingState.SEARCHING
        repeat(10) { i -> last = policy.update(obs(), isShaking = false, nowMs = 1400L + i * 200L).state }
        assertEquals(FramingState.STEADY, last)
    }

    @Test
    fun `tilt is corrected once the page is otherwise well framed`() {
        val spoken = run(GuidancePolicy(), frames = 10) { obs(tilt = 25f) }
        assertEquals(listOf(Instruction.STRAIGHTEN), spoken)
        // Under the trigger, nothing is said about a slightly crooked page.
        assertTrue(run(GuidancePolicy(), frames = 10) { obs(tilt = 10f) }.isEmpty())
    }

    // ---- capture must not fire while a correction is in flight (device bug, 2026-09-05)

    /**
     * A clipped page is never a good capture, however confident the detector is:
     * part of the page is outside the photo. Regression test for a device session
     * that captured at clip=LEFT and clip=RIGHT and only then said "Move left".
     */
    @Test
    fun captureNeverFiresWhileThePageIsClipped() {
        val policy = GuidancePolicy()
        var captured = false
        for (i in 0 until 40) {
            val d = policy.update(
                obs(clipped = setOf(Side.LEFT)), isShaking = false, nowMs = i * 200L,
            )
            if (d.capture) captured = true
        }
        assertFalse("clipped page must never auto-capture", captured)
    }

    /**
     * The window this actually failed in: an instruction is pending but has not
     * yet survived dwellFrames, so `active` is still null. The steady timer must
     * reset on the pending correction, not only on the confirmed one.
     */
    @Test
    fun captureNeverFiresInTheDwellWindowOfANewCorrection() {
        val policy = GuidancePolicy()
        // Settle: well framed for long enough that a capture has already fired.
        var first = false
        for (i in 0 until 12) {
            if (policy.update(obs(), isShaking = false, nowMs = i * 200L).capture) first = true
        }
        assertTrue("a clean, steady page should capture", first)

        // Now drift off the edge. For the next few frames the cue is only pending.
        var capturedWhileDrifting = false
        for (i in 12 until 40) {
            val d = policy.update(
                obs(clipped = setOf(Side.RIGHT), cx = 560.0),
                isShaking = false, nowMs = i * 200L,
            )
            if (d.capture) capturedWhileDrifting = true
        }
        assertFalse("must not capture once the page starts leaving frame",
            capturedWhileDrifting)
    }

    /** The gate must not be so tight that a good page stops capturing at all. */
    @Test
    fun aCleanSteadyPageStillCaptures() {
        val policy = GuidancePolicy()
        var captured = false
        for (i in 0 until 20) {
            if (policy.update(obs(), isShaking = false, nowMs = i * 200L).capture) captured = true
        }
        assertTrue("a clean centred page must still auto-capture", captured)
    }

    /**
     * Counts the updates on which auto-capture fired, over [frames] steps at
     * 200ms, so double-shutter regressions show up as a number.
     */
    private fun captures(
        policy: GuidancePolicy,
        frames: Int,
        observation: (Int) -> PageObservation?,
    ): List<Long> {
        val fired = mutableListOf<Long>()
        for (i in 0 until frames) {
            val now = i * 200L
            if (policy.update(observation(i), isShaking = false, nowMs = now).capture) fired += now
        }
        return fired
    }

    /**
     * One page must produce one shutter.
     *
     * The latch used to reopen on a single ADJUSTING frame, so a momentary
     * wobble -- which is exactly what a hand does the instant after a capture
     * -- fired a second shutter about 1.3 s later. Harmless until the page is
     * read aloud, at which point it restarts the reading from the top
     * mid-sentence for no reason the user can perceive.
     */
    @Test
    fun `a single wobble after capture does not fire a second shutter`() {
        val policy = GuidancePolicy()
        // Framed throughout except one clipped frame shortly after the capture.
        val fired = captures(policy, 40) { i ->
            if (i == 12) obs(clipped = setOf(Side.LEFT)) else obs()
        }
        assertEquals("expected exactly one capture, got $fired", 1, fired.size)
    }

    /** Sustained clipping is not enough either, while the refractory window holds. */
    @Test
    fun `losing framing briefly inside the refractory window does not re-arm`() {
        val policy = GuidancePolicy()
        val fired = captures(policy, 40) { i ->
            if (i in 10..14) obs(clipped = setOf(Side.LEFT)) else obs()
        }
        assertEquals("expected exactly one capture, got $fired", 1, fired.size)
    }

    /**
     * The happy path, which is the failure mode of this fix: "never capture
     * twice" must not become "never capture again". A genuinely new page --
     * framing given up for well past the refractory window -- has to shoot.
     */
    @Test
    fun `a genuinely new page captures again after the refractory window`() {
        val policy = GuidancePolicy()
        // Framed, then nothing at all for 6s (past captureRefractoryMs), then
        // framed again: the user lowered the phone and lifted a second sheet.
        val fired = captures(policy, 90) { i ->
            when {
                i < 20 -> obs()
                i < 55 -> null
                else -> obs()
            }
        }
        assertEquals("expected two captures, got $fired", 2, fired.size)
        assertTrue(
            "second capture at ${fired[1]} should be well after the first at ${fired[0]}",
            fired[1] - fired[0] >= 4_000L,
        )
    }

    /** reset() is called after a capture completes; it must fully re-arm. */
    @Test
    fun `reset re-arms the shutter immediately`() {
        val policy = GuidancePolicy()
        assertEquals(1, captures(policy, 20) { obs() }.size)
        policy.reset()
        assertEquals(1, captures(policy, 20) { obs() }.size)
    }
}
