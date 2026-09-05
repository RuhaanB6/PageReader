package com.pagereader.android.reading

import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

/**
 * The player's state machine, driven by a fake engine.
 *
 * A real TTS engine would make these tests slow, device-bound and timing
 * dependent, and the bugs here are not in the speaking — they are in the
 * bookkeeping: advancing twice, advancing after a jump, queueing when it should
 * flush. [FakeSpeaker] makes all of that observable and deterministic.
 */
class PagePlayerTest {

    /** Records what was said and lets the test decide when an utterance ends. */
    private class FakeSpeaker : Speaker {
        data class Utterance(val text: String, val id: String, val flush: Boolean)

        val spoken = mutableListOf<Utterance>()
        var stops = 0
        private var onDone: (String) -> Unit = {}

        override fun speak(text: String, utteranceId: String, flush: Boolean) {
            spoken += Utterance(text, utteranceId, flush)
        }

        override fun stop() {
            stops++
        }

        override fun setOnDone(listener: (String) -> Unit) {
            onDone = listener
        }

        /** Signals that the most recent utterance finished. */
        fun finishLast() {
            spoken.lastOrNull()?.let { onDone(it.id) }
        }

        /** Signals a done for an utterance that is no longer current. */
        fun finish(id: String) = onDone(id)

        val lastText: String? get() = spoken.lastOrNull()?.text
    }

    private var nextId = 0

    private fun block(text: String, kind: BlockKind = BlockKind.BODY) = TextBlock(
        id = nextId++,
        order = nextId,
        text = text,
        bbox = Rect(0, nextId * 100, 500, 80),
        confidence = 0.9f,
        kind = kind,
        medianWordHeight = 20f,
        lineCount = 2,
    )

    private fun page(vararg blocks: TextBlock) =
        OcrPage(1000, 1400, blocks.toList(), 0.9f, 100)

    @Test
    fun playsTheFirstSentenceOfTheFirstBlock() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("One two three. Four five six.")))
        player.play()

        assertTrue(player.isPlaying)
        assertEquals("One two three.", speaker.lastText)
        assertEquals("b0-s0", speaker.spoken.last().id)
        assertTrue("starting playback should flush", speaker.spoken.last().flush)
    }

    /** Sentences inside a block must queue, or prose sounds like a list. */
    @Test
    fun sentencesWithinABlockAreQueuedNotFlushed() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("One two three. Four five six.")))
        player.play()
        speaker.finishLast()

        assertEquals("Four five six.", speaker.lastText)
        assertFalse("continuing within a block must not flush", speaker.spoken.last().flush)
        assertEquals(PagePlayer.Position(0, 1), player.position)
    }

    @Test
    fun advancesFromOneBlockToTheNext() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("Alpha."), block("Beta.")))
        player.play()
        speaker.finishLast()

        assertEquals("Beta.", speaker.lastText)
        assertEquals(PagePlayer.Position(1, 0), player.position)
    }

    @Test
    fun stopsAtTheEndOfThePage() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("Only this.")))
        player.play()
        speaker.finishLast()

        assertFalse(player.isPlaying)
        assertTrue(player.isFinished)
    }

    /**
     * The race this class most needs to survive. Stopping is not instantaneous,
     * so a `done` for an abandoned sentence arrives routinely after a jump;
     * acting on it would advance the position twice and skip a paragraph.
     */
    @Test
    fun aLateDoneFromAnAbandonedSentenceIsIgnored() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("A one. A two."), block("B one."), block("C one.")))
        player.play()

        player.nextBlock()
        assertEquals(PagePlayer.Position(1, 0), player.position)

        // The engine reports the first block's first sentence finishing, late.
        speaker.finish("b0-s0")

        assertEquals("position must not move on a stale done",
            PagePlayer.Position(1, 0), player.position)
    }

    @Test
    fun jumpingFlushesAndSpeaksImmediatelyWhilePlaying() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("A."), block("B."), block("C.")))
        player.play()
        val stopsBefore = speaker.stops

        player.nextBlock()

        assertTrue("a jump must stop current speech", speaker.stops > stopsBefore)
        assertEquals("B.", speaker.lastText)
        assertTrue("a jump must flush", speaker.spoken.last().flush)
    }

    /** A jump while paused must not start talking. */
    @Test
    fun jumpingWhilePausedMovesButStaysSilent() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("A."), block("B.")))
        val before = speaker.spoken.size

        player.nextBlock()

        assertEquals(PagePlayer.Position(1, 0), player.position)
        assertEquals("nothing should be spoken while paused", before, speaker.spoken.size)
    }

    /**
     * Back part-way through means "start this again"; only from the start does
     * it mean the previous one. Matches every audio player, which is the point.
     */
    @Test
    fun previousRestartsTheBlockBeforeSteppingBack() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("A one. A two."), block("B one. B two.")))
        player.play()
        player.nextBlock()
        speaker.finishLast()
        assertEquals(PagePlayer.Position(1, 1), player.position)

        player.previousBlock()
        assertEquals("should restart the current block",
            PagePlayer.Position(1, 0), player.position)

        player.previousBlock()
        assertEquals("should now step back a block",
            PagePlayer.Position(0, 0), player.position)
    }

    @Test
    fun nextAtTheEndRestartsTheLastBlockRatherThanFallingSilent() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("A."), block("B.")))
        player.play()
        player.nextBlock()
        val before = speaker.spoken.size

        player.nextBlock()

        assertEquals(PagePlayer.Position(1, 0), player.position)
        assertTrue("the control must always do something audible",
            speaker.spoken.size > before)
    }

    @Test
    fun pauseThenPlayResumesWhereItStopped() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("One. Two. Three.")))
        player.play()
        speaker.finishLast()
        assertEquals(PagePlayer.Position(0, 1), player.position)

        player.pause()
        assertFalse(player.isPlaying)
        player.play()

        assertEquals(PagePlayer.Position(0, 1), player.position)
        assertEquals("Two.", speaker.lastText)
    }

    /** Position survives a reload, which is what resuming a saved page needs. */
    @Test
    fun loadCanStartFromASavedPosition() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        val p = page(block("One. Two."), block("Three. Four."))
        player.load(p, PagePlayer.Position(1, 1))
        player.play()

        assertEquals("Four.", speaker.lastText)
    }

    /** A saved position from a different, longer page must not crash. */
    @Test
    fun anOutOfRangeSavedPositionIsClamped() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("Only.")), PagePlayer.Position(99, 99))
        assertEquals(PagePlayer.Position(0, 0), player.position)
    }

    /** Figures are announced, never read; separators are skipped entirely. */
    @Test
    fun figuresAreAnnouncedAndSeparatorsSkipped() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(
            page(
                block("", BlockKind.SEPARATOR),
                block("", BlockKind.FIGURE),
                block("Body text."),
            )
        )
        player.play()
        assertEquals("Figure", speaker.lastText)
        speaker.finishLast()
        assertEquals("Body text.", speaker.lastText)
    }

    /** Headings say so, or a listener cannot tell structure from prose. */
    @Test
    fun headingsAreAnnouncedAsHeadings() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page(block("Speed and velocity", BlockKind.HEADING)))
        player.play()
        assertTrue("expected a heading announcement, got ${speaker.lastText}",
            speaker.lastText!!.startsWith("Heading."))
    }

    @Test
    fun anEmptyPageDoesNotCrashOrSpeak() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        player.load(page())
        player.play()
        player.nextBlock()
        player.previousBlock()
        assertTrue(speaker.spoken.isEmpty())
        assertFalse(player.isPlaying)
    }

    @Test
    fun jumpToBlockIdFindsTheRightBlock() {
        val speaker = FakeSpeaker()
        val player = PagePlayer(speaker)
        val a = block("Alpha.")
        val b = block("Beta.")
        val c = block("Gamma.")
        player.load(page(a, b, c))
        player.play()

        player.jumpToBlockId(c.id)
        assertEquals("Gamma.", speaker.lastText)
        assertEquals(c.id, player.currentBlock?.id)
    }
}
