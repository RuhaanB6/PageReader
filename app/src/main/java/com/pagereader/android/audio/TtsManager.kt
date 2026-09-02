package com.pagereader.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.EnumMap
import java.util.Locale

enum class GuidanceCue(val text: String) {
    HOLD_STEADY("Hold steady"),
    READY("Ready to capture"),
    MOVE_LEFT("Move left"),
    MOVE_RIGHT("Move right"),
    MOVE_UP("Move up"),
    MOVE_DOWN("Move down"),
    MOVE_BACK("Move the camera back"),
    NO_DOCUMENT("No document detected"),
    FOUND("Document found. Hold steady.")
}

/**
 * Speech output, written defensively because the target device has no Google
 * Mobile Services. A TTS engine is not guaranteed to be present on a HarmonyOS
 * build, and if the engine is missing or has no English voice data, `speak()`
 * silently does nothing and still returns SUCCESS. That failure is invisible
 * unless you look for it, so language availability is checked explicitly at init
 * and logged.
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var utteranceCounter = 0

    @Volatile
    private var available = false

    /** Per-cue last-spoken timestamps. Each cue debounces independently. */
    private val lastSpokenAt = EnumMap<GuidanceCue, Long>(GuidanceCue::class.java)

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
     * Speaks [cue] unless the same cue was spoken less than [cooldownMs] ago.
     * The cooldown is tracked per cue, so a change of instruction is never
     * blocked by the previous one.
     *
     * Always QUEUE_FLUSH: guidance is real-time, and an instruction that is
     * still playing after the situation changed is actively misleading.
     *
     * Call from the main thread.
     */
    fun speak(cue: GuidanceCue, cooldownMs: Long = 2000L) {
        if (!available) return

        val now = System.currentTimeMillis()
        val last = lastSpokenAt[cue]
        if (last != null && now - last < cooldownMs) return
        lastSpokenAt[cue] = now

        tts?.speak(cue.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "pr-${utteranceCounter++}")
    }

    fun shutdown() {
        available = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val SPEECH_RATE = 1.15f
    }
}
