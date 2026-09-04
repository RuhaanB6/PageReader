package com.pagereader.android.audio

import android.content.Context
import android.media.AudioAttributes
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
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null
    private var utteranceCounter = 0

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
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            } catch (t: Throwable) {
                Log.w(TAG, "no tone generator available", t)
                null
            }

            Log.i(TAG, "TTS ready: $engine / $locale")
            available = true
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

    /** True when an engine and an English voice were both found at init. */
    val isAvailable: Boolean get() = available

    /** Short non-speech confirmation, for events that need no words. */
    fun earcon() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, EARCON_MS)
    }

    fun shutdown() {
        available = false
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
