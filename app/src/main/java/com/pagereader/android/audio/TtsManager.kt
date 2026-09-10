package com.pagereader.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import com.pagereader.android.guidance.Instruction
import java.util.Locale

/**
 * Speech output, written defensively because the target device has no Google
 * Mobile Services. A TTS engine is not guaranteed to be present on a HarmonyOS
 * build, and if the engine is missing or has no English voice data, `speak()`
 * silently does nothing and still returns SUCCESS. That failure is invisible
 * unless you look for it, so language availability is checked explicitly at init
 * and logged.
 *
 * Deliberately dumb: it says what it is told to say, once, and owns no
 * throttling policy. v1 spread debounce logic across per-cue cooldowns here
 * while the guidance layer re-decided every frame, so neither half knew what the
 * user was currently being told. Deciding *whether* to speak now belongs
 * entirely to `GuidancePolicy`.
 */
class TtsManager(private val context: Context) : com.pagereader.android.reading.Speaker {

    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null
    private var utteranceCounter = 0

    /**
     * Set by [PagePlayer] via [setOnDone]. Fires on the engine's own callback
     * thread, so it is hopped to the main thread before the player sees it --
     * the player and Compose state are main-thread only.
     */
    private var onUtteranceDone: ((String) -> Unit)? = null

    /**
     * Set by [PagePlayer] via [setOnStopped]. Fires when the engine reports an
     * utterance interrupted rather than finished -- see the `onStop` override
     * below for why this must not be folded back into [onUtteranceDone].
     */
    private var onUtteranceStopped: ((String) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Built once, not per-request: [AudioFocusRequest.Builder] needs it and so
     * does `TextToSpeech.setAudioAttributes`, and the two must agree or the
     * system has no reason to treat this app's speech and its focus request
     * as the same stream.
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(AudioManager::class.java)
    }
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Set by [MainActivity] via [setOnFocusChange]. `TtsManager` deliberately
     * does not hold a `PagePlayer` reference -- it is a leaf audio class and
     * the player is the thing that knows what "pause" and "resume" mean --
     * so a focus change is surfaced as a plain callback and whoever wires up
     * the player decides what to do with it.
     */
    private var onFocusChange: ((Int) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChange?.invoke(change)
    }

    @Volatile
    private var available = false

    fun initialize(onReady: () -> Unit) {
        tts = TextToSpeech(context) { initStatus ->
            if (initStatus != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed (code $initStatus). No engine installed?")
                available = false
                onReady()
                return@TextToSpeech
            }

            val engine = tts?.defaultEngine ?: "unknown"
            val locale = pickLocale()
            if (locale == null) {
                Log.e(TAG, "TTS engine '$engine' has no usable English voice")
                available = false
                onReady()
                return@TextToSpeech
            }

            tts?.language = locale
            tts?.setSpeechRate(SPEECH_RATE)
            tts?.setAudioAttributes(audioAttributes)

            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            } catch (t: Throwable) {
                Log.w(TAG, "no tone generator available", t)
                null
            }

            Log.i(TAG, "TTS ready: $engine / $locale")
            available = true
            // Utterance callbacks are how continuous reading advances. Without
            // this the player speaks its first sentence and then waits forever.
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: return
                    mainHandler.post { onUtteranceDone?.invoke(id) }
                }
                /**
                 * A flushed or stopped utterance reports HERE, not via onDone.
                 * Routing it into [onUtteranceDone] used to be load-bearing --
                 * without SOME routing the player is left believing it is
                 * still playing and waits for a callback that never arrives,
                 * so reading stops forever with no explanation, indistinguishable
                 * from a crash to a user who cannot see the screen. But treating
                 * every stop as a *done* is its own bug: `PagePlayer` reads an
                 * interrupted sentence as a completed one and advances past it,
                 * silently losing whatever was cut off. So this now has its own
                 * channel, [onUtteranceStopped], and the player decides what an
                 * interruption means instead of this class deciding for it.
                 * Reachable from any QUEUE_FLUSH: a guidance cue, a mode-change
                 * announcement, or the page summary itself.
                 */
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val id = utteranceId ?: return
                    mainHandler.post { onUtteranceStopped?.invoke(id) }
                }

                @Deprecated("required by the platform base class")
                override fun onError(utteranceId: String?) {
                    // Treat an error as a finished utterance: the alternative is
                    // playback stopping dead with no explanation, which to a user
                    // who cannot see the screen is indistinguishable from a crash.
                    Log.w(TAG, "utterance $utteranceId failed; advancing anyway")
                    val id = utteranceId ?: return
                    mainHandler.post { onUtteranceDone?.invoke(id) }
                }
            })
            onReady()
        }
    }

    private fun pickLocale(): Locale? {
        val engine = tts ?: return null
        for (locale in listOf(Locale.US, Locale.UK, Locale.ENGLISH, Locale.getDefault())) {
            val result = try {
                engine.isLanguageAvailable(locale)
            } catch (t: Throwable) {
                Log.w(TAG, "isLanguageAvailable threw for $locale", t)
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) return locale
        }
        return null
    }

    /**
     * Says [instruction] now, cutting off anything still playing.
     *
     * QUEUE_FLUSH is still right: the policy only calls this when the situation
     * has actually changed, and a stale instruction finishing over a new one is
     * worse than a clipped word.
     *
     * Main thread only.
     */
    fun speak(instruction: Instruction) {
        if (!available) return
        tts?.speak(instruction.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "pr-${utteranceCounter++}")
    }

    /**
     * Says arbitrary [text].
     *
     * [speak] is limited to the fixed [Instruction] strings, which is right for
     * the framing loop but cannot carry a page's contents, a capture
     * confirmation, or a failure explanation. Those are the whole product past
     * the shutter, so they need a channel that takes words.
     *
     * @param flush true cuts off whatever is playing; false queues behind it.
     * Framing corrections flush because a stale one is worse than a clipped
     * word. Read-aloud text queues, so sentences do not truncate each other.
     *
     * Main thread only.
     */
    fun say(text: String, flush: Boolean = true) {
        if (!available || text.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, Bundle(), "pr-${utteranceCounter++}")
    }

    // --- Speaker, for PagePlayer -----------------------------------------

    override fun speak(text: String, utteranceId: String, flush: Boolean) {
        if (!available || text.isBlank()) {
            // Nothing will be spoken, so nothing will report done. Synthesise
            // one, or continuous reading stalls silently on the first block.
            mainHandler.post { onUtteranceDone?.invoke(utteranceId) }
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, Bundle(), utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setOnDone(listener: (String) -> Unit) {
        onUtteranceDone = listener
    }

    override fun setOnStopped(listener: (String) -> Unit) {
        onUtteranceStopped = listener
    }

    override fun requestFocus() {
        val am = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    override fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Set by [MainActivity]. Fires on the same focus-change thread as any
     * other engine callback here, which is why [focusListener] is built with
     * [mainHandler] -- the listener this exposes must only ever see the main
     * thread, matching every other callback in this class.
     */
    fun setOnFocusChange(listener: (Int) -> Unit) {
        onFocusChange = listener
    }

    /** True when an engine and an English voice were both found at init. */
    val isAvailable: Boolean get() = available

    /** Short non-speech confirmation, for events that need no words. */
    fun earcon() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, EARCON_MS)
    }

    fun shutdown() {
        available = false
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        tone?.release()
        tone = null
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val SPEECH_RATE = 1.15f
        private const val TONE_VOLUME = 80
        private const val EARCON_MS = 150
    }
}
