package com.pagereader.android.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sentence splitting, which only has to be good enough.
 *
 * A wrong split costs a small pause; the real failures are the ones that break
 * audibly — stopping mid-decimal, or never splitting at all so a paragraph
 * cannot be paused part-way.
 */
class SentencesTest {

    @Test
    fun splitsOnOrdinarySentenceEnds() {
        assertEquals(
            listOf("One two.", "Three four!", "Five six?"),
            Sentences.split("One two. Three four! Five six?"),
        )
    }

    @Test
    fun keepsDecimalsIntact() {
        val out = Sentences.split("The value is 3.5 metres exactly.")
        assertEquals(1, out.size)
        assertTrue(out[0].contains("3.5"))
    }

    @Test
    fun doesNotBreakOnCommonAbbreviations() {
        assertEquals(1, Sentences.split("Dr. Ellis reviewed it.").size)
        assertEquals(1, Sentences.split("See fig. 4 for details.").size)
    }

    /** Initials are common in citations, where a spurious break is jarring. */
    @Test
    fun doesNotBreakOnInitials() {
        assertEquals(1, Sentences.split("Written by J. R. Hartley today.").size)
    }

    /**
     * A block with no terminal punctuation must still be splittable, or it
     * becomes one huge utterance that cannot be paused part-way.
     */
    @Test
    fun breaksVeryLongRunsWithoutPunctuation() {
        val runOn = (1..200).joinToString(" ") { "word$it" }
        val out = Sentences.split(runOn)
        assertTrue("expected several chunks, got ${out.size}", out.size > 1)
        assertTrue("chunks should be bounded", out.all { it.length < 400 })
    }

    @Test
    fun trimsAndDropsEmpties() {
        assertEquals(listOf("First one.", "Second one."),
            Sentences.split("   First one.    Second one.   "))
        assertTrue(Sentences.split("").isEmpty())
        assertTrue(Sentences.split("    ").isEmpty())
    }

    /**
     * A known and deliberate ambiguity: a single capital followed by a period
     * is read as an initial, not as a one-letter sentence.
     *
     * The two are genuinely indistinguishable without understanding the text.
     * Initials are everywhere in citations, where breaking mid-name is jarring;
     * one-letter sentences essentially do not occur. The cost of being wrong
     * here is one missing pause, so the trade is worth taking -- but it is a
     * choice, and this records it rather than leaving it to be rediscovered.
     */
    @Test
    fun aLoneCapitalIsTreatedAsAnInitialNotASentence() {
        assertEquals(1, Sentences.split("A. B.").size)
        assertEquals(1, Sentences.split("Written by J. R. Hartley today.").size)
    }

    /** No text may be lost: every word must survive the split. */
    @Test
    fun losesNoWords() {
        val text = "First one here. Second at 2.5 metres. Dr. Ellis agreed! Really?"
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = Sentences.split(text).joinToString(" ")
        val outWords = out.split(Regex("\\s+")).filter { it.isNotBlank() }
        assertEquals(words, outWords)
    }
}
