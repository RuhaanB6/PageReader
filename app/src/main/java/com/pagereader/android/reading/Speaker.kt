package com.pagereader.android.reading

/**
 * The speech output [PagePlayer] needs, and nothing more.
 *
 * Exists so the player -- which owns the position logic, the queueing rules and
 * the advance-on-done state machine -- can be tested on the JVM without a TTS
 * engine. That logic is where the bugs will be, and it would otherwise only be
 * reachable on a device.
 */
interface Speaker {
    /**
     * @param utteranceId echoed back to the done-listener; the player uses it
     *   to know which sentence finished and whether it is still current.
     * @param flush true cuts off whatever is playing. Within a block the player
     *   queues; on any jump it flushes, because a stale sentence continuing
     *   after the user has moved is worse than a clipped word.
     */
    fun speak(text: String, utteranceId: String, flush: Boolean)

    /** Stops immediately and drops anything queued. */
    fun stop()

    /** Called with the utterance id when a queued utterance finishes. */
    fun setOnDone(listener: (String) -> Unit)
}
