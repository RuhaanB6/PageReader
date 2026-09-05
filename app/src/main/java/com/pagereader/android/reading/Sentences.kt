package com.pagereader.android.reading

/**
 * Splits a block into sentences for playback.
 *
 * Sentences rather than whole blocks because they are the unit the user acts
 * on: pausing, resuming and "where was I" all want somewhere finer than a
 * paragraph, and a queued sentence can be cut off promptly on a jump.
 *
 * This is deliberately simple. OCR text is not clean enough to justify a real
 * sentence tokeniser -- it carries stray punctuation, dropped spaces and
 * hyphenation -- and the cost of a wrong split is small: two half-sentences read
 * back to back sound almost identical to one. The cases handled are the ones
 * that would otherwise break audibly mid-word.
 */
object Sentences {

    /**
     * Abbreviations that end in a period without ending a sentence. Kept short
     * on purpose: every entry is a guess about the document, and the failure it
     * prevents (a pause after "Dr.") is mild.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie",
        "fig", "no", "vol", "pp", "ed", "al", "approx", "dept", "univ",
    )

    /** Longest run allowed before a sentence is split anyway, in characters. */
    private const val MAX_CHARS = 320

    fun split(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()

        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            current.append(c)

            if (c == '.' || c == '!' || c == '?') {
                val next = clean.getOrNull(i + 1)
                val boundary = next == null || next.isWhitespace()
                if (boundary && !endsWithAbbreviation(current) && !isDecimalPoint(clean, i)) {
                    out += current.toString().trim()
                    current.setLength(0)
                    i++
                    while (i < clean.length && clean[i].isWhitespace()) i++
                    continue
                }
            }

            // A block with no terminal punctuation at all -- a heading, a table
            // cell, badly OCR'd text -- would otherwise be one enormous
            // utterance that cannot be paused part-way. Break at a word
            // boundary once it gets unreasonable.
            if (current.length >= MAX_CHARS && c.isWhitespace()) {
                out += current.toString().trim()
                current.setLength(0)
            }
            i++
        }

        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    private fun endsWithAbbreviation(sb: StringBuilder): Boolean {
        // Last word before the period, lowercased, without the period itself.
        var end = sb.length - 1
        if (end < 0 || sb[end] != '.') return false
        var start = end - 1
        while (start >= 0 && !sb[start].isWhitespace()) start--
        val word = sb.substring(start + 1, end).lowercase()
        if (word.isEmpty()) return false
        // A single initial ("J.") is an abbreviation too, and common in
        // citations, which is where a spurious break is most jarring.
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREVIATIONS
    }

    /** "3.5" and "1.2.3" are not sentence ends. */
    private fun isDecimalPoint(text: String, dotIndex: Int): Boolean {
        val before = text.getOrNull(dotIndex - 1)
        val after = text.getOrNull(dotIndex + 1)
        return before != null && before.isDigit() && after != null && after.isDigit()
    }
}
