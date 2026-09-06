package com.pagereader.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.pagereader.android.audio.TtsManager
import com.pagereader.android.camera.CameraManager
import com.pagereader.android.detect.ColorPageDetector
import com.pagereader.android.detect.PageDetector
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.YoloDnnEngine
import com.pagereader.android.detect.YoloPageDetector
import com.pagereader.android.guidance.FramingState
import com.pagereader.android.guidance.GuidancePolicy
import com.pagereader.android.guidance.Instruction
import com.pagereader.android.guidance.ShakeDetector
import com.pagereader.android.dewarp.DewarpResult
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.reading.CaptureQuality
import com.pagereader.android.reading.PagePlayer
import com.pagereader.android.storage.CaptureStore
import com.pagereader.android.ocr.TesseractOcr
import com.pagereader.android.dewarp.PageDewarper
import com.pagereader.android.telemetry.SessionRecorder
import com.pagereader.android.ui.CaptureScreen
import com.pagereader.android.ui.CaptureStage
import com.pagereader.android.ui.PageScreen
import com.pagereader.android.ui.ProcessingScreen
import com.pagereader.android.ui.theme.PageReaderTheme
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TtsManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var cameraManager: CameraManager
    private lateinit var recorder: SessionRecorder

    private val policy = GuidancePolicy()

    private val hasCameraPermission = mutableStateOf(false)
    private val latestObservation = mutableStateOf<PageObservation?>(null)
    private val debugLine = mutableStateOf("starting")
    private var detectorLabel = "?"

    /** Guards against a second shutter while one is already in flight. */
    private var capturing = false
    private var lastPage: DewarpResult? = null

    /**
     * Post-capture work runs here, never on the main thread: re-detecting on
     * the still and warping a multi-megapixel image is a few hundred
     * milliseconds, and the shutter callback arrives on the main thread.
     */
    private val captureExecutor = Executors.newSingleThreadExecutor()

    /**
     * A second detector, for stills only.
     *
     * It cannot share the analysis detector: `cv::dnn::Net` is not thread-safe
     * and preview frames keep flowing during a capture, so one engine would be
     * used from two threads at once. Built on first capture rather than at
     * startup, so the ~12 MB is only paid by a session that actually shoots.
     */
    private val stillDewarper: PageDewarper by lazy { PageDewarper(buildDetector()) }

    /**
     * Built on the capture thread, on first use, and never touched from
     * anywhere else -- `TessBaseAPI` holds native state and is not thread-safe.
     * Null means the language data could not be prepared, which degrades to
     * "cannot read" rather than crashing.
     */
    private var ocr: TesseractOcr? = null
    private var ocrUnavailable = false
    private var lastPageText: OcrPage? = null

    /** Which screen is in front. The camera is only the first step. */
    private val mode = mutableStateOf(Mode.FRAMING)
    private val readingPage = mutableStateOf<OcrPage?>(null)
    private val currentBlockId = mutableStateOf<Int?>(null)

    /**
     * The live guidance decision, split into the two fields the framing card
     * draws.
     *
     * `handleFrame` used to keep only `latestObservation` and a debug string,
     * because guidance was spoken and nothing else. The card has to show the
     * same words, so the decision's own fields are held rather than parsed
     * back out of the debug line. `instruction` is the correction currently in
     * force and may persist across frames; `utterance` -- the one frame it
     * should be *said* -- stays with the TTS path and is deliberately not
     * mirrored here, or the card would blink once and clear.
     */
    private val latestInstruction = mutableStateOf<Instruction?>(null)
    private val latestFramingState = mutableStateOf(FramingState.SEARCHING)

    /**
     * What the processing screen is showing while the pipeline runs.
     *
     * The user cannot see a spinner, so these exist for the sighted half of
     * the audience; the spoken progress at quarter marks is the channel that
     * matters and is unchanged.
     */
    private val captureStage = mutableStateOf(CaptureStage.CAPTURING)
    private val ocrPercent = mutableIntStateOf(0)

    /**
     * The captured page, small enough to draw.
     *
     * Downscaled hard on purpose: a 3000 px page as ARGB_8888 is about 24 MB,
     * and this phone has already killed the process once over a full-
     * resolution bitmap. Nothing on screen can resolve more than this anyway.
     */
    private val pagePreview = mutableStateOf<ImageBitmap?>(null)

    /**
     * Playback state, mirrored into Compose.
     *
     * `PagePlayer` is a plain class with plain fields -- reading `isPlaying`
     * from a composable would not recompose when it changed. The player
     * already reports every transition through its state callback, so these
     * are written there rather than polled.
     */
    private val playerIsPlaying = mutableStateOf(false)
    private val playerProgress = mutableStateOf(0f)

    private lateinit var store: CaptureStore
    private var currentCaptureId: String? = null

    /**
     * Built once TTS is up, because it needs the engine as its [Speaker].
     * Position changes are written straight through to the store: being killed
     * mid-page is the normal case here, not an edge case.
     */
    private val player: PagePlayer by lazy {
        PagePlayer(ttsManager) { p ->
            currentBlockId.value = p.currentBlock?.id
            playerIsPlaying.value = p.isPlaying
            playerProgress.value =
                if (p.blockCount == 0) 0f
                else (p.position.blockIndex + 1).toFloat() / p.blockCount
            currentCaptureId?.let { store.savePosition(it, p.position) }
        }
    }
    private var lastProgressSpokenAt = 0

    /**
     * True only when the player was paused *by a focus change*, not by the
     * user pausing or stopping deliberately. `AUDIOFOCUS_GAIN` after a
     * transient loss should resume reading automatically; after a user's own
     * pause it must not, or tapping pause during a phone call would have the
     * page start talking again the moment the call ends.
     */
    private var pausedByFocusLoss = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCameraPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A page is minutes of audio with nobody touching the screen. Without
        // this the display sleeps and HarmonyOS PowerGenie suspends the whole
        // process a few seconds later -- the `Pged-Freezer` mechanism
        // documented in CLAUDE.md -- and speech stops dead with no way back.
        // Set once for the whole activity: there is no state, FRAMING or
        // READING, where letting the screen sleep is correct, and toggling it
        // per mode would only race PowerGenie for no benefit.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        OpenCVLoader.initLocal()

        ttsManager = TtsManager(this)
        recorder = SessionRecorder(this)
        store = CaptureStore(this)
        store.prune()
        recorder.start()

        // Wired here, once, rather than inside the `player` lazy block: the
        // listener only needs to run when a focus event actually happens,
        // by which point `player` is guaranteed to exist, and referencing it
        // from this lambda does not force it into existence any earlier.
        ttsManager.setOnFocusChange { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Transient: a notification, a nav prompt, a short system
                    // sound. Worth resuming once it clears, so remember that
                    // this pause was ours to undo, not the user's.
                    if (player.isPlaying) {
                        pausedByFocusLoss = true
                        player.pause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss -- another app now owns audio for good
                    // (a call, music playback taking over). Pause and leave it
                    // paused: unlike the transient case, there is no "it will
                    // clear itself" to resume into.
                    if (player.isPlaying) player.pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        // Queue rather than flush: nothing of ours is playing
                        // to cut off, and flushing here would only race
                        // whatever just gave focus back.
                        player.play(flush = false)
                    }
                }
            }
        }

        shakeDetector = ShakeDetector(
            context = this,
            // While framing, shake is a quality signal the policy reads via
            // isShaking. While reading it is the stop control -- the one
            // gesture that works without finding the screen at all, which
            // matters when the point is to make the talking stop.
            onShake = {
                if (mode.value != Mode.FRAMING && player.isPlaying) {
                    player.pause()
                    // Queue, not flush: pause() already stopped the engine
                    // and nothing of the page is left playing, so QUEUE_FLUSH
                    // here would only risk eating the tail of this very
                    // announcement.
                    ttsManager.say("Stopped.", flush = false)
                }
            },
            onStable = { }
        )

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            detector = buildDetector(),
            onFrame = ::handleFrame
        )

        setContent {
            PageReaderTheme {
                when (mode.value) {
                    // The preview is only bound once the permission is in, so
                    // there is nothing to draw before that; the spoken prompt
                    // from the permission callback covers the gap.
                    Mode.FRAMING -> if (hasCameraPermission.value) {
                        CaptureScreen(
                            observation = latestObservation.value,
                            instruction = latestInstruction.value,
                            state = latestFramingState.value,
                            debugLine = debugLine.value,
                            onShutter = { takeStill(auto = false) },
                            onPreviewView = cameraManager::bindCamera,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Deliberately camera-free. The preview used to stay live
                    // for the two to fourteen seconds this takes, which said
                    // "still framing" to anyone watching the screen while the
                    // app had in fact already committed to a photo.
                    Mode.PROCESSING -> ProcessingScreen(
                        stage = captureStage.value,
                        percent = ocrPercent.intValue,
                        pagePreview = pagePreview.value,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Mode.READING -> readingPage.value?.let { page ->
                        PageScreen(
                            page = page,
                            pageImage = pagePreview.value,
                            currentBlockId = currentBlockId.value,
                            isPlaying = playerIsPlaying.value,
                            progressFraction = playerProgress.value,
                            talkBackEnabled = isTalkBackEnabled(),
                            onTogglePlay = { player.toggle() },
                            onPreviousBlock = { player.previousBlock() },
                            onNextBlock = { player.nextBlock() },
                            onReadFrom = { block ->
                                hapticTick()
                                player.jumpToBlockId(block.id)
                                player.play()
                            },
                            onNewPage = { returnToCamera() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraPermissionResult(true)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Neural detector if the model loads, colour-only if it does not.
     *
     * OpenCV's ONNX importer has open failures on YOLOv8 graphs, so this cannot
     * be assumed to work -- and an accessibility app that dies at startup
     * because a model would not parse is worse than one that degrades. Which
     * path won is logged and shown on the debug overlay.
     */
    private fun buildDetector(): PageDetector {
        val engine = YoloDnnEngine.load(this, MODEL_ASSET)
        return if (engine != null) {
            detectorLabel = "YOLO"
            Log.i(TAG, "detector: YOLO (${MODEL_ASSET})")
            YoloPageDetector(engine)
        } else {
            detectorLabel = "COLOUR"
            Log.w(TAG, "detector: colour fallback -- model did not load")
            ColorPageDetector()
        }
    }

    /** Speech comes up first; flipping the flag composes the preview, which binds the camera. */
    private fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) return
        ttsManager.initialize { hasCameraPermission.value = true }
    }

    /**
     * Main thread. Owns [preview] and must release it.
     *
     * Note the ordering: the policy decides, speech follows the decision, and
     * the recorder logs the decision alongside the frame that produced it. That
     * pairing is the point -- a log line saying "confidence 0.11, said nothing"
     * is only actionable next to the picture it came from.
     */
    private fun handleFrame(observation: PageObservation, latencyMs: Long, preview: org.opencv.core.Mat) {
        try {
            // Analysis is bound to the Activity lifecycle, not to the
            // composition, so frames keep arriving after the user leaves the
            // camera. Without this gate the guidance policy stays live while a
            // page is being read: a framing cue speaks with QUEUE_FLUSH and
            // cuts the reading dead, and the capture latch can fire a second,
            // unrequested shutter mid-page. Both were reachable simply by
            // lowering the phone after a capture.
            // `capturing` stays true for the whole pipeline, not just the
            // shutter. On hardware the first page takes 13.6 s to recognise
            // (1.7 s once Tesseract is warm), and the mode only leaves FRAMING
            // when that finishes -- so without this the policy kept guiding and
            // auto-capture fired a second time mid-processing, replacing the
            // page the user was about to hear. Measured on the JSC-AL50.
            if (mode.value != Mode.FRAMING || capturing) return

            latestObservation.value = observation

            val shaking = shakeDetector.isShaking
            val decision = policy.update(observation, shaking, System.currentTimeMillis())

            latestInstruction.value = decision.instruction
            latestFramingState.value = decision.state

            decision.utterance?.let { ttsManager.speak(it) }
            if (decision.capture) {
                // Re-arm for the next page. Previously reset() ran only in
                // retake(), so the latch stayed armed against a scene the user
                // had already finished with.
                policy.reset()
                takeStill(auto = true)
            }

            debugLine.value = buildString {
                append(detectorLabel)
                append(' ')
                append(decision.state.name)
                append("  conf=%.2f".format(observation.confidence))
                append(" cov=%.2f".format(observation.coverage))
                append(" ${latencyMs}ms")
                decision.instruction?.let { append("  -> ${it.name}") }
                if (observation.clipped.isNotEmpty()) {
                    append("  clip=${observation.clipped.joinToString(",") { it.name.first().toString() }}")
                }
            }

            // Frames that explain a failure are worth a picture even if the
            // routine snapshot interval has not elapsed.
            val interesting = decision.utterance != null ||
                decision.capture ||
                observation.quad == null ||
                observation.confidence < LOW_CONFIDENCE

            recorder.logFrame(
                latencyMs = latencyMs,
                source = observation.source.name,
                confidence = observation.confidence,
                coverage = observation.coverage,
                clipped = observation.clipped.map { it.name },
                quad = observation.quad?.map { it.x to it.y },
                state = decision.state.name,
                instruction = decision.instruction?.name,
                utterance = decision.utterance?.name,
                isShaking = shaking,
                captured = decision.capture,
                snapshot = preview,
                interesting = interesting,
            )
        } finally {
            preview.release()
        }
    }

    /**
     * Shutter. Speaks before the work starts, not after.
     *
     * Everything between here and a spoken result is silent, and to someone who
     * cannot see the screen silence is indistinguishable from a crash. The
     * earcon marks the instant of capture; the words say what is happening next.
     */
    private fun takeStill(auto: Boolean) {
        if (capturing) return
        capturing = true

        // Leave the camera the instant the shutter fires, not when the page is
        // ready. Everything after this point is work on a photo that has
        // already been taken, and showing a live preview over it invites the
        // user to keep adjusting a frame that no longer matters.
        captureStage.value = CaptureStage.CAPTURING
        ocrPercent.intValue = 0
        pagePreview.value = null
        mode.value = Mode.PROCESSING

        ttsManager.earcon()
        ttsManager.say("Captured. Reading the page.")

        try {
            cameraManager.capture(
                onResult = { mat ->
                    // NOT cleared here. The shutter is the start of the work,
                    // not the end of it; `capturing` is released once the page
                    // has been read or has failed.
                    Log.i(TAG, "captured ${mat.width()}x${mat.height()} (auto=$auto)")
                    debugLine.value = "captured ${mat.width()}x${mat.height()}"
                    // Ownership of `mat` passes to the executor, which releases
                    // it. Holding it in a field instead would let the next
                    // capture free it from the main thread while the dewarp
                    // thread is still reading it.
                    dewarpOffThread(mat)
                },
                onFailure = { message ->
                    capturing = false
                    mode.value = Mode.FRAMING
                    Log.w(TAG, "capture failed: $message")
                    ttsManager.say(message)
                }
            )
        } catch (t: Throwable) {
            // Without this a synchronous throw strands `capturing` true and the
            // shutter never fires again -- silently, which is the failure mode
            // this app can least afford.
            capturing = false
            mode.value = Mode.FRAMING
            Log.e(TAG, "capture threw", t)
            ttsManager.say("The camera failed. Try again.")
        }
    }

    /**
     * Flattens the still into a head-on page, off the main thread.
     *
     * Speaks only on failure. Success is already covered by the "Reading the
     * page" said at the shutter, and an extra confirmation between the earcon
     * and the text would just delay the thing the user actually wants. Failure
     * has to be spoken because the fallback -- reading the whole photo,
     * background and all -- produces worse text for a reason the user cannot
     * see.
     */
    private fun dewarpOffThread(still: org.opencv.core.Mat) {
        runOnUiThread { captureStage.value = CaptureStage.FLATTENING }
        captureExecutor.execute {
            val result = try {
                stillDewarper.dewarp(still)
            } catch (t: Throwable) {
                // Never strand the user in silence: fall back to the whole
                // frame rather than letting the pipeline stop here.
                Log.e(TAG, "dewarp threw", t)
                DewarpResult(still.clone(), applied = false, homography = null)
            } finally {
                still.release()
            }
            // Built here, on this thread: the conversion walks every pixel of
            // a multi-megapixel image and would stall the main thread right
            // after the shutter if it were done in the hop below.
            val thumbnail = toImageBitmap(result.page)
            runOnUiThread {
                pagePreview.value = thumbnail
                lastPage?.release()
                lastPage = result
                val p = result.page
                Log.i(TAG, "page ${p.width()}x${p.height()} applied=${result.applied}")
                debugLine.value =
                    "page ${p.width()}x${p.height()} ${if (result.applied) "dewarped" else "RAW"}"
                if (!result.applied) {
                    ttsManager.say("I could not find the page edges, so I am reading the whole photo.")
                }
            }
            recogniseOffThread(result)
        }
    }

    /**
     * Reads the page, on the same background thread the dewarp just used.
     *
     * Recognition takes seconds. To someone who cannot see a spinner that
     * silence is indistinguishable from a crash, so progress is spoken at
     * intervals rather than left blank -- coarse on purpose, because a number
     * every 1% would be worse than saying nothing.
     */
    private fun recogniseOffThread(page: DewarpResult) {
        if (ocrUnavailable) {
            // Speak on every attempt. Returning silently after the first
            // failure meant every later shutter said "Captured. Reading the
            // page." and then nothing at all, permanently.
            runOnUiThread {
                capturing = false
                mode.value = Mode.FRAMING
                ttsManager.say("I cannot read text on this device.")
            }
            return
        }
        runOnUiThread { captureStage.value = CaptureStage.READING_TEXT }
        val engine = ocr ?: TesseractOcr.create(this) { percent ->
            // Called on this thread by Tesseract; hop to main to speak.
            // One progress source, two channels: the bar moves on every
            // report, the voice only at quarter marks -- a number every
            // percent would be worse than saying nothing.
            val bucket = percent / 25
            val speak = bucket > lastProgressSpokenAt
            if (speak) lastProgressSpokenAt = bucket
            runOnUiThread {
                ocrPercent.intValue = percent
                if (speak) ttsManager.say("$percent percent", flush = false)
            }
        }?.also { ocr = it }

        if (engine == null) {
            ocrUnavailable = true
            Log.e(TAG, "OCR unavailable; language data could not be prepared")
            runOnUiThread {
                capturing = false
                mode.value = Mode.FRAMING
                ttsManager.say("I cannot read text on this device.")
            }
            return
        }

        lastProgressSpokenAt = 0
        val result = try {
            engine.recognise(page.page)
        } catch (t: Throwable) {
            Log.e(TAG, "recognition threw", t)
            null
        }
        runOnUiThread { captureStage.value = CaptureStage.FINISHING }

        // The projection has to be in the same frame as the boxes drawn on it.
        // Space G is whatever orientation OCR actually read in, so a page
        // recovered by the rotation retry needs its picture turned to match --
        // otherwise every tappable region sits a quarter turn from the words
        // it claims to cover. Only rebuilt in that case; the common one
        // already has the right thumbnail from the dewarp.
        if (result != null && result.quarterTurnsClockwise == 1) {
            val turned = org.opencv.core.Mat()
            try {
                org.opencv.core.Core.rotate(
                    page.page, turned, org.opencv.core.Core.ROTATE_90_CLOCKWISE
                )
                val rotatedPreview = toImageBitmap(turned)
                runOnUiThread { pagePreview.value = rotatedPreview }
            } catch (t: Throwable) {
                Log.w(TAG, "could not rotate the page preview", t)
            } finally {
                turned.release()
            }
        }

        // Persist here, on this thread, before hopping back. Encoding a
        // multi-megapixel JPEG inside runOnUiThread stalls the main thread for
        // hundreds of milliseconds right after every capture.
        //
        // The image is rotated to match the frame OCR actually read in. Space
        // G is defined by that frame, so a page recovered by the rotation
        // retry has boxes a quarter turn from the unrotated still -- storing
        // the two together would put every box in the wrong place.
        val savedId = if (result != null && result.meanConfidence >= OcrPage.USABLE_CONFIDENCE) {
            val upright = org.opencv.core.Mat()
            try {
                if (result.quarterTurnsClockwise == 1) {
                    org.opencv.core.Core.rotate(
                        page.page, upright, org.opencv.core.Core.ROTATE_90_CLOCKWISE
                    )
                } else {
                    page.page.copyTo(upright)
                }
                store.save(upright, result)?.id
            } catch (t: Throwable) {
                Log.w(TAG, "could not store the capture", t)
                null
            } finally {
                upright.release()
            }
        } else {
            null
        }

        runOnUiThread {
            // The pipeline is done either way; re-arm the shutter.
            capturing = false
            if (result == null) {
                mode.value = Mode.FRAMING
                ttsManager.say("Something went wrong reading the page. Try again.")
                return@runOnUiThread
            }
            lastPageText = result
            val words = result.textBlocks.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
            Log.i(TAG, "ocr: ${result.blocks.size} blocks, $words words, " +
                "conf=${result.meanConfidence}, ${result.elapsedMs} ms")
            // "ocr" is processing time; the spoken summary quotes listening
            // time. Labelled so the two are never read as the same number.
            debugLine.value = "read $words words conf=%.2f ocr=%d ms"
                .format(result.meanConfidence, result.elapsedMs)

            // Judge the capture before reading a word of it. Reading a
            // garbled page to someone who cannot check it against the paper is
            // worse than asking them to retake.
            // Clipping comes from the dewarp's own detection on the still.
            // latestObservation is whatever the camera saw a moment ago -- by
            // now the phone has moved, so it describes a different scene.
            val verdict = CaptureQuality.assess(page.clipped, result)
            lastVerdictWasRetake = !verdict.usable
            if (!verdict.usable) {
                // Back to the camera, not stranded on a processing screen for
                // a page that will never be read.
                mode.value = Mode.FRAMING
                verdict.message?.let { ttsManager.say(it) }
                return@runOnUiThread
            }

            // Ordered so the structure is spoken before the contents: the
            // summary is what lets someone decide whether to sit through a
            // page that takes minutes to hear.
            readingPage.value = result
            mode.value = Mode.READING
            currentCaptureId = savedId
            player.load(result)
            // Queues rather than flushes: a retake can land here while the
            // previous page's "could not read" or verdict message is still
            // finishing, and QUEUE_FLUSH would clip the tail of that message
            // for no benefit -- nothing of *this* page is playing yet to
            // protect by cutting it off.
            ttsManager.say(
                com.pagereader.android.ocr.BlockLabels.pageSummary(result) +
                    " Double tap for the next page.",
                flush = false,
            )
            // Queue behind the summary. play() flushes by default, which
            // cancelled the summary milliseconds after it started -- every
            // word of the page description was inaudible.
            player.play(flush = false)
        }
    }

    /**
     * Volume keys are the manual shutter.
     *
     * They need no aim, work without looking, and stay live even while guidance
     * is still complaining -- the user's judgement about when the page is framed
     * overrides the gates. Returning true consumes the key so the volume does
     * not also change.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // While framing, either key is the shutter. While reading they are
            // paragraph navigation -- the only controls that need no aim and
            // stay usable with the phone in a pocket or on a table.
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (mode.value) {
                    Mode.FRAMING -> takeStill(auto = false)
                    else -> if (lastVerdictWasRetake) retake() else player.previousBlock()
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (mode.value) {
                    Mode.FRAMING -> takeStill(auto = false)
                    else -> player.nextBlock()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
        // A shot interrupted by pausing can leave the framing loop unbound on
        // devices that need the use-case swap. Heal it rather than coming back
        // to a preview that never speaks.
        cameraManager.recoverIfInterrupted()
        // Safety valve. `capturing` now spans the whole capture-to-speech
        // pipeline, so a process paused mid-recognition would otherwise come
        // back with a dead shutter and no way to say why. Clearing it here can
        // briefly disagree with CameraManager's own in-flight guard, which
        // costs a spoken "Captured" for a shot that does not happen -- a far
        // better failure than a shutter that never works again.
        capturing = false
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
        // Interrupt a recognition in flight rather than leaving it running
        // against a page the user has walked away from.
        ocr?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Order matters. shutdown() only refuses new work, so without the
        // wait, recycle() frees Tesseract's native state underneath a
        // recognition still running on the executor -- a native crash, and
        // reachable by nothing more than swiping the app away mid-page.
        ocr?.stop()
        captureExecutor.shutdown()
        runCatching {
            captureExecutor.awaitTermination(TEARDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        ocr?.close()
        ocr = null
        lastPage?.release()
        lastPage = null
        recorder.stop()
        ttsManager.shutdown()
    }

    /** Which screen is in front. */
    /**
     * The three states the app is actually in, in the order they happen.
     *
     * There is no separate explore mode any more: the reading screen draws the
     * page itself and a tap on a region is the same gesture explore existed to
     * provide, so keeping it as a mode meant two screens competing for one
     * drag stream.
     */
    private enum class Mode { FRAMING, PROCESSING, READING }

    /** True while the last capture was judged unusable, so volume up retakes. */
    private var lastVerdictWasRetake = false

    /** Back to the camera for another try, or for the next page. */
    private fun retake() = returnToCamera()

    /**
     * A drawable copy of a BGR page, small enough to hold in memory.
     *
     * Two conversions that are easy to skip and both wrong to skip. The long
     * side is capped because a full-resolution page as ARGB_8888 is tens of
     * megabytes and this phone has already killed the process over exactly
     * that. And OpenCV hands out BGR while `matToBitmap` reads a three-channel
     * Mat as RGB, so without the colour conversion the page comes out with
     * red and blue swapped -- which on a photograph of white paper is subtle
     * enough to look like a camera white-balance problem rather than a bug.
     *
     * Returns null rather than throwing: a missing picture costs a sighted
     * viewer some context, while a crash costs the listener the page.
     */
    private fun toImageBitmap(bgr: org.opencv.core.Mat): ImageBitmap? {
        if (bgr.empty()) return null
        val scaled = org.opencv.core.Mat()
        val rgba = org.opencv.core.Mat()
        return try {
            val longSide = maxOf(bgr.width(), bgr.height()).toDouble()
            if (longSide > PREVIEW_LONG_SIDE) {
                val f = PREVIEW_LONG_SIDE / longSide
                org.opencv.imgproc.Imgproc.resize(
                    bgr, scaled,
                    org.opencv.core.Size(
                        Math.round(bgr.width() * f).toDouble(),
                        Math.round(bgr.height() * f).toDouble(),
                    ),
                    0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA,
                )
            } else {
                bgr.copyTo(scaled)
            }
            org.opencv.imgproc.Imgproc.cvtColor(
                scaled, rgba, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA
            )
            val bitmap = android.graphics.Bitmap.createBitmap(
                rgba.width(), rgba.height(), android.graphics.Bitmap.Config.ARGB_8888
            )
            org.opencv.android.Utils.matToBitmap(rgba, bitmap)
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "could not build a page preview", t)
            null
        } finally {
            scaled.release()
            rgba.release()
        }
    }

    /**
     * Leaves reading and re-arms the camera.
     *
     * Until this existed there was no way back at all once a page had been
     * read successfully: `mode` was only ever set to FRAMING by the retake
     * path, which requires a *failed* verdict. A user who captured one page
     * well was stuck on it until they restarted the app.
     */
    private fun returnToCamera() {
        lastVerdictWasRetake = false
        player.stop()
        readingPage.value = null
        currentCaptureId = null
        currentBlockId.value = null
        pagePreview.value = null
        mode.value = Mode.FRAMING
        policy.reset()
        // Queues rather than flushes: player.stop() above already stopped
        // anything of the page, so there is nothing to protect by cutting
        // off, and a stray late utterance from that stop should not be able
        // to race this one away.
        ttsManager.say("Ready for the next page. Hold the phone over it.", flush = false)
    }

    /**
     * TalkBack changes what input is possible, so both screens ask before
     * installing a touch handler that would otherwise fight with it.
     */
    private fun isTalkBackEnabled(): Boolean {
        val am = getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    /** A short tick when the finger crosses into a new region. */
    private fun hapticTick() {
        window.decorView.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK
        )
    }

    companion object {
        private const val TAG = "PageReader"
        /**
         * Vertical drag, in pixels per event, that counts as a swipe.
         *
         * Deliberately large: this switches screens, and a blind user's hand
         * rests on the display constantly. A false positive here is far more
         * disorienting than a missed swipe, which simply needs repeating.
         */
        /**
         * Long side of the on-screen copy of the page, in pixels.
         *
         * Above the densest screen this app will ever run on, and an eighth of
         * the memory of the full-resolution page it is made from.
         */
        private const val PREVIEW_LONG_SIDE = 1400.0

        /**
         * How long teardown waits for an in-flight recognition. Long enough to
         * cover a normal page, short enough not to hang the destroy.
         */
        private const val TEARDOWN_WAIT_MS = 2_000L
        private const val LOW_CONFIDENCE = 0.35f
        private const val MODEL_ASSET = "yolov8n_det_256.onnx"
    }
}

