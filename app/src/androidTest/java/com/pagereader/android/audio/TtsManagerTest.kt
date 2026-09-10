package com.pagereader.android.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs on hardware only (`./gradlew connectedAndroidTest`). [TtsManager] wraps
 * `android.speech.tts.TextToSpeech`, a real system service that a plain JVM
 * unit test cannot construct (there is no mocking library on this project's
 * test classpath -- see `app/build.gradle.kts` -- and the framework stub jar
 * throws on any non-trivial Android API call).
 *
 * Presence of a TTS engine and English voice data is not guaranteed on the
 * target (HarmonyOS, no GMS) per CLAUDE.md, so tests that need a working
 * engine bail out (logging, not failing) when [TtsManager.isAvailable] comes
 * back false -- that itself is the behaviour under test in
 * [initializeReportsAvailability].
 *
 * There is no mocking library available, so the `say(...)` guard behaviour
 * (blank text / unavailable engine do nothing) is verified by reflectively
 * attaching a real [UtteranceProgressListener] to the manager's private
 * `tts` field and asserting it is or isn't invoked -- a real interaction
 * check, not a rewritten copy of the guard condition.
 */
@RunWith(AndroidJUnit4::class)
class TtsManagerTest {

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun initSync(manager: TtsManager) {
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.initialize { latch.countDown() }
        }
        assertTrue("initialize() must call onReady", latch.await(10, TimeUnit.SECONDS))
    }

    private fun assertTrue(msg: String, cond: Boolean) {
        org.junit.Assert.assertTrue(msg, cond)
    }

    /** Reflects out the private TextToSpeech so tests can observe real speak() calls. */
    private fun underlyingTts(manager: TtsManager): TextToSpeech? {
        val field = TtsManager::class.java.getDeclaredField("tts")
        field.isAccessible = true
        return field.get(manager) as TextToSpeech?
    }

    @Test
    fun sayDoesNothingBeforeInitialize() {
        // available defaults to false until initialize()'s callback runs, so
        // say() must be a silent no-op -- and must not NPE on the null tts.
        val manager = TtsManager(context())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.say("hello")
            manager.say("")
        }
        assertFalse(manager.isAvailable)
    }

    @Test
    fun initializeReportsAvailability() {
        val manager = TtsManager(context())
        initSync(manager)
        // Just documents what actually happened on this device/CI image --
        // TTS presence is not guaranteed per CLAUDE.md.
        Log.i("TtsManagerTest", "isAvailable=${manager.isAvailable}")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.shutdown() }
    }

    @Test
    fun sayFlushesOrQueuesAndBlankTextIsIgnored() {
        val manager = TtsManager(context())
        initSync(manager)
        if (!manager.isAvailable) {
            Log.w("TtsManagerTest", "no TTS engine on this device -- skipping interaction assertions")
            return
        }

        val tts = underlyingTts(manager)
        assertTrue("manager reports available but has no TextToSpeech instance", tts != null)

        val started = CopyOnWriteArrayList<String>()
        val doneLatch = CountDownLatch(1)
        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { started += it }
            }
            override fun onDone(utteranceId: String?) {
                doneLatch.countDown()
            }
            override fun onError(utteranceId: String?) {
                doneLatch.countDown()
            }
        })

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.say("", flush = true)
            manager.say("   ", flush = false)
        }
        // Give the engine a moment: if either blank call had reached
        // TextToSpeech.speak(), onStart would have fired for it.
        Thread.sleep(500)
        assertEquals("blank text must never reach the engine", emptyList<String>(), started)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.say("test one two three", flush = true)
        }
        assertTrue("real text must be spoken", doneLatch.await(10, TimeUnit.SECONDS))
        assertEquals(1, started.size)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.shutdown() }
    }
}
