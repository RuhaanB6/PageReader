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

    /**
     * Called with the utterance id when a queued utterance is interrupted
     * before finishing -- the engine's `onStop`, not its `onDone`. These used
     * to be routed to the same place, which reads "cut off mid-word" as
     * "finished cleanly" and lets [PagePlayer] advance past a sentence the
     * listener never actually heard. Kept as a separate channel so the player
     * can tell the two apart and re-speak rather than silently skip.
     */
    fun setOnStopped(listener: (String) -> Unit)

    /**
     * Requests audio focus for the duration of playback. Called once per
     * [PagePlayer.play]. Without this, a notification tone or another app's
     * own speech plays right over the page being read with no yielding on
     * either side -- audible, confusing, and on a phone this is the primary
     * output there is no visual cue that it happened.
     */
    fun requestFocus()

    /**
     * Releases focus requested by [requestFocus]. Called whenever playback
     * stops for any reason -- pause, explicit stop, or reaching the end of
     * the page -- so this app is not still holding the stream hostage while
     * silent.
     */
    fun abandonFocus()
}
