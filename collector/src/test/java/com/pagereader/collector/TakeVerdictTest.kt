package com.pagereader.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these is drift: the device verdict and tools/verify-collection.py
 * must make the same call. A take that passes on the phone and fails on the desk
 * is worse than no check at all, because it is trusted.
 */
class TakeVerdictTest {

    private fun good(frames: Int = 20) =
        TakeVerdict.Quality(frames = frames, sharpnessP50 = 1500.0, diversity = 30.0)

    private fun stats(kept: Int, blur: Int = 0, dup: Int = 0, shake: Int = 0) =
        TakeStats(kept = kept, blur = blur, duplicate = dup, shake = shake)

    @Test
    fun `a healthy take passes clean`() {
        val v = TakeVerdict.judge(stats(kept = 20), good())
        assertTrue(v.ok)
        assertTrue(v.fatal.isEmpty())
        assertTrue(v.advice.isEmpty())
        assertEquals("ok", v.summary)
    }

    @Test
    fun `too few frames is fatal`() {
        val v = TakeVerdict.judge(stats(kept = 5), good(frames = 5))
        assertFalse(v.ok)
        assertTrue(v.fatal.any { it.contains("5 frames") })
    }

    @Test
    fun `frames at the boundary are accepted`() {
        val v = TakeVerdict.judge(
            stats(kept = TakeVerdict.MIN_FRAMES),
            good(frames = TakeVerdict.MIN_FRAMES),
        )
        assertTrue(v.ok)
    }

    @Test
    fun `a uniform take is fatal even when the count looks healthy`() {
        // 40 frames shot without moving the phone: the counter says this is the
        // best take of the session. It is worth about one frame.
        val q = TakeVerdict.Quality(frames = 40, sharpnessP50 = 1500.0, diversity = 3.0)
        val v = TakeVerdict.judge(stats(kept = 40), q)
        assertFalse(v.ok)
        assertTrue(v.fatal.any { it.contains("uniform") })
    }

    @Test
    fun `diversity is not judged on a sample too small to mean anything`() {
        val q = TakeVerdict.Quality(frames = 1, sharpnessP50 = 1500.0, diversity = 0.0)
        val v = TakeVerdict.judge(stats(kept = 1), q)
        // Fails for being too few, but must not also claim it is uniform.
        assertTrue(v.fatal.any { it.contains("frames") })
        assertFalse(v.fatal.any { it.contains("uniform") })
    }

    @Test
    fun `a soft take is fatal`() {
        // The steep take of setup 01: p50 556 against 1250-2050 elsewhere.
        val q = TakeVerdict.Quality(frames = 21, sharpnessP50 = 30.0, diversity = 30.0)
        val v = TakeVerdict.judge(stats(kept = 21), q)
        assertFalse(v.ok)
        assertTrue(v.fatal.any { it.contains("soft") })
    }

    @Test
    fun `blur domination is advice, not a veto`() {
        // The take is usable; the shooting technique is the problem.
        val v = TakeVerdict.judge(stats(kept = 20, blur = 60), good())
        assertTrue(v.ok)
        assertTrue(v.advice.any { it.contains("blur") })
        assertTrue(v.summary.startsWith("ok ("))
    }

    @Test
    fun `duplicate domination is advice`() {
        val v = TakeVerdict.judge(stats(kept = 20, dup = 200), good())
        assertTrue(v.ok)
        assertTrue(v.advice.any { it.contains("duplicate") })
    }

    @Test
    fun `setup 01 partial-left reproduces as healthy`() {
        // Real numbers from collections/20260903-1411: 21 kept, 213 duplicates.
        val v = TakeVerdict.judge(stats(kept = 21, dup = 213), good(frames = 21))
        assertTrue(v.ok)
        assertTrue(v.advice.any { it.contains("duplicate") })
    }

    @Test
    fun `an unshot setup reports every take missing`() {
        val sv = TakeVerdict.judgeSession(Take.entries.associateWith { 0 })
        assertFalse(sv.complete)
        assertEquals(Take.entries.size, sv.missing.size)
        assertEquals(0, sv.totalFrames)
        assertFalse(sv.partialThin)   // no frames yet: nothing to be thin about
    }

    @Test
    fun `a fully shot setup reports complete`() {
        val sv = TakeVerdict.judgeSession(Take.entries.associateWith { 20 })
        assertTrue(sv.complete)
        assertEquals(Take.entries.size * 20, sv.totalFrames)
    }

    @Test
    fun `a set without enough partial-edge frames is flagged`() {
        val counts = Take.entries.associateWith { if (it.tag.startsWith("partial")) 2 else 40 }
        val sv = TakeVerdict.judgeSession(counts)
        assertTrue(sv.partialThin)
    }

    @Test
    fun `setup 01 partial share was healthy`() {
        // 84 partial frames of 193 total = 44%, above the 30% floor.
        val counts = mapOf(
            Take.FULL_OVERHEAD to 21, Take.PARTIAL_LEFT to 21, Take.PARTIAL_RIGHT to 23,
            Take.PARTIAL_TOP to 20, Take.PARTIAL_BOTTOM to 20, Take.DISTANCE to 23,
            Take.TILTED to 23, Take.STEEP to 21, Take.NEGATIVE to 21,
        )
        val sv = TakeVerdict.judgeSession(counts)
        assertTrue(sv.complete)
        assertFalse(sv.partialThin)
    }
}
