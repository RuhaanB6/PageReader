package com.pagereader.android.reading

import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.BlockLabels
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock

/**
 * Reads a page aloud, and keeps track of where the listener is in it.
 *
 * A page is minutes of audio — a single textbook page measured about three at
 * ordinary speaking rate — so this is closer to an audio player than to a
 * "speak this string" call. Position, resumption and prompt response to a jump
 * are the whole product; a listener who cannot see the screen has no way to
 * skim back to where they were.
 *
 * Position is `(blockIndex, sentenceIndex)` and is exposed so it can be
 * persisted: the app is expected to be interrupted mid-page and to come back to
 * the same sentence.
 *
 * Two queueing rules, both audible when broken:
 *
 *  - **Within a block, queue.** Sentences must run together without a gap, or
 *    ordinary prose sounds like a list.
 *  - **On any jump, flush.** When the user moves, the sentence they were on has
 *    to stop *now*. Hearing the tail of the old paragraph after asking for the
 *    next one is the single most confusing thing this class can do.
 *
 * Not thread-safe: main thread only, like the TTS layer underneath it.
 */
class PagePlayer(
    private val speaker: Speaker,
    private val onStateChanged: (PagePlayer) -> Unit = {},
) {

    data class Position(val blockIndex: Int, val sentenceIndex: Int) {
        companion object {
            val START = Position(0, 0)
        }
    }

    /** A block flattened into what will actually be spoken. */
    private data class Playable(val block: TextBlock, val sentences: List<String>)

    private var playables: List<Playable> = emptyList()

    var position: Position = Position.START
        private set

    var isPlaying: Boolean = false
        private set

    /** True once the last sentence of the last block has been spoken. */
    var isFinished: Boolean = false
        private set

    /**
     * The utterance the player is waiting on, plus a counter to keep ids
     * unique.
     *
     * Ids used to be derived from the position alone, which is not enough:
     * re-speaking the same sentence -- resume after pause, repeat, or jumping
     * onto the block already playing -- reuses the id, so a `done` left over
     * from the previous attempt matches the new one and advances the position
     * a sentence early. Auto-advance queues rather than flushes, so nothing
     * sounds wrong; the damage is silent, surfacing as a saved resume point
     * that skips a sentence on the next launch.
     */
    private var utteranceSeq = 0
    private var outstanding: String? = null

    init {
        speaker.setOnDone { id -> onUtteranceDone(id) }
    }

    /**
     * Loads a page and rewinds to [from].
     *
     * Figures and separators are not read, but figures *are* announced: a
     * listener needs to know a picture is there, and silence would imply the
     * page simply ended.
     */
    fun load(page: OcrPage, from: Position = Position.START) {
        stop()
        playables = BlockLabels.playbackBlocks(page).map { b ->
            val spoken = when (b.kind) {
                BlockKind.FIGURE -> listOf(BlockLabels.title(b))
                BlockKind.HEADING -> listOf("Heading. ${b.text}")
                BlockKind.CAPTION -> Sentences.split("Caption. ${b.text}")
                BlockKind.SIDEBAR -> Sentences.split("Sidebar. ${b.text}")
                else -> Sentences.split(b.text)
            }.filter { it.isNotBlank() }
            Playable(b, spoken)
        }.filter { it.sentences.isNotEmpty() }

        position = clamp(from)
        isFinished = false
        onStateChanged(this)
    }

    val blockCount: Int get() = playables.size

    /** The block currently being read, or null when the page is empty. */
    val currentBlock: TextBlock?
        get() = playables.getOrNull(position.blockIndex)?.block

    /** A short spoken name for where the listener is. */
    fun currentTitle(): String = currentBlock?.let { BlockLabels.title(it) } ?: ""

    /**
     * @param flush true cuts off whatever is speaking. Pass false to start
     *   reading *behind* something already queued -- the page summary, which
     *   would otherwise be cancelled by its own first sentence milliseconds
     *   after it began.
     */
    fun play(flush: Boolean = true) {
        if (playables.isEmpty() || isPlaying) return
        isPlaying = true
        isFinished = false
        speakCurrent(flush = flush)
        onStateChanged(this)
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        outstanding = null
        speaker.stop()
        onStateChanged(this)
    }

    fun toggle() = if (isPlaying) pause() else play()

    /** Stops and rewinds nothing -- position is kept so play() resumes here. */
    fun stop() {
        isPlaying = false
        outstanding = null
        speaker.stop()
        onStateChanged(this)
    }

    /**
     * Moves to the start of the next block.
     *
     * Blocks rather than sentences because the user is navigating structure,
     * not text: "next" means the next paragraph, which is what a sighted reader
     * would skip to.
     */
    fun nextBlock() {
        if (playables.isEmpty()) return
        if (position.blockIndex >= playables.lastIndex) {
            // Already at the last block: restart it rather than falling silent,
            // so the control always does something audible.
            jumpTo(Position(playables.lastIndex, 0))
            return
        }
        jumpTo(Position(position.blockIndex + 1, 0))
    }

    /**
     * Moves to the start of the previous block, or restarts the current one.
     *
     * The restart-first behaviour matches every audio player: pressing back
     * part-way through means "start this again", and only from the very start
     * does it mean "the one before".
     */
    fun previousBlock() {
        if (playables.isEmpty()) return
        if (position.sentenceIndex > 0 || position.blockIndex == 0) {
            jumpTo(Position(position.blockIndex, 0))
        } else {
            jumpTo(Position(position.blockIndex - 1, 0))
        }
    }

    /** Re-reads the current block from its start. */
    fun repeatBlock() {
        if (playables.isEmpty()) return
        jumpTo(Position(position.blockIndex, 0))
    }

    /** Jumps to a block by [TextBlock.id], for touch-explore's "read from here". */
    fun jumpToBlockId(id: Int) {
        val index = playables.indexOfFirst { it.block.id == id }
        if (index >= 0) jumpTo(Position(index, 0))
    }

    /** Moves the position and, if playing, starts speaking there immediately. */
    fun jumpTo(target: Position) {
        if (playables.isEmpty()) return
        position = clamp(target)
        isFinished = false
        // Flush unconditionally: a jump while paused must still drop anything
        // the engine has already buffered, or it speaks after the user stopped.
        outstanding = null
        speaker.stop()
        if (isPlaying) speakCurrent(flush = true)
        onStateChanged(this)
    }

    /**
     * Called when the engine finishes an utterance.
     *
     * The id is checked against the current position before advancing. Without
     * that, a `done` for a sentence the user has already jumped away from --
     * which arrives routinely, because stopping is not instantaneous -- would
     * advance the position a second time and skip a sentence.
     */
    private fun onUtteranceDone(id: String) {
        if (!isPlaying) return
        // Compare against the outstanding id, not one derived from the
        // position: the same position can legitimately be spoken twice.
        if (id != outstanding) return
        outstanding = null

        val block = playables.getOrNull(position.blockIndex) ?: return
        val next = if (position.sentenceIndex + 1 < block.sentences.size) {
            Position(position.blockIndex, position.sentenceIndex + 1)
        } else if (position.blockIndex + 1 < playables.size) {
            Position(position.blockIndex + 1, 0)
        } else {
            isPlaying = false
            isFinished = true
            onStateChanged(this)
            return
        }
        position = next
        // Always queue on auto-advance, never flush. The previous utterance has
        // just finished, so there is nothing to cut off, and flushing here
        // would race the engine and can drop the sentence we are about to
        // queue. Flushing is for jumps, where there really is speech to stop.
        speakCurrent(flush = false)
        onStateChanged(this)
    }

    private fun speakCurrent(flush: Boolean) {
        val block = playables.getOrNull(position.blockIndex) ?: return
        val text = block.sentences.getOrNull(position.sentenceIndex) ?: return
        val id = "u${utteranceSeq++}-b${position.blockIndex}-s${position.sentenceIndex}"
        outstanding = id
        speaker.speak(text, id, flush)
    }

    private fun clamp(p: Position): Position {
        if (playables.isEmpty()) return Position.START
        val b = p.blockIndex.coerceIn(0, playables.lastIndex)
        val s = p.sentenceIndex.coerceIn(0, (playables[b].sentences.size - 1).coerceAtLeast(0))
        return Position(b, s)
    }

}
