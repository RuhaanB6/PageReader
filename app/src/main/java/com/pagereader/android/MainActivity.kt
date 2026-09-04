package com.pagereader.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pagereader.android.audio.TtsManager
import com.pagereader.android.camera.CameraManager
import com.pagereader.android.detect.ColorPageDetector
import com.pagereader.android.detect.PageDetector
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.YoloDnnEngine
import com.pagereader.android.detect.YoloPageDetector
import com.pagereader.android.guidance.GuidancePolicy
import com.pagereader.android.guidance.ShakeDetector
import com.pagereader.android.telemetry.SessionRecorder
import com.pagereader.android.ui.theme.PageReaderTheme
import org.opencv.android.OpenCVLoader

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
    private var lastCapture: org.opencv.core.Mat? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCameraPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        OpenCVLoader.initLocal()

        ttsManager = TtsManager(this)
        recorder = SessionRecorder(this)
        recorder.start()

        shakeDetector = ShakeDetector(
            context = this,
            onShake = { },
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Double-tap anywhere is the second manual shutter. The
                        // whole screen is the target because a blind user cannot
                        // aim at a button, and double- rather than single-tap so
                        // a hand steadying the phone does not fire it.
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { takeStill(auto = false) })
                        }
                ) {
                    if (hasCameraPermission.value) {
                        AndroidView(
                            factory = { context ->
                                PreviewView(context).also(cameraManager::bindCamera)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        PageOverlay(
                            observation = latestObservation.value,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(
                        text = debugLine.value,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(16.dp)
                    )
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
            latestObservation.value = observation

            val shaking = shakeDetector.isShaking
            val decision = policy.update(observation, shaking, System.currentTimeMillis())

            decision.utterance?.let { ttsManager.speak(it) }
            if (decision.capture) takeStill(auto = true)

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

        ttsManager.earcon()
        ttsManager.say("Captured. Reading the page.")

        try {
            cameraManager.capture(
                onResult = { mat ->
                    capturing = false
                    lastCapture?.release()
                    lastCapture = mat
                    Log.i(TAG, "captured ${mat.width()}x${mat.height()} (auto=$auto)")
                    // M4 takes over here: re-detect the quad on the still, then
                    // dewarp. Until then the still is held so its size can be
                    // read off the debug line and the path verified on device.
                    debugLine.value = "captured ${mat.width()}x${mat.height()}"
                },
                onFailure = { message ->
                    capturing = false
                    Log.w(TAG, "capture failed: $message")
                    ttsManager.say(message)
                }
            )
        } catch (t: Throwable) {
            // Without this a synchronous throw strands `capturing` true and the
            // shutter never fires again -- silently, which is the failure mode
            // this app can least afford.
            capturing = false
            Log.e(TAG, "capture threw", t)
            ttsManager.say("The camera failed. Try again.")
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
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                takeStill(auto = false)
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
        capturing = false
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        lastCapture?.release()
        lastCapture = null
        recorder.stop()
        ttsManager.shutdown()
    }

    companion object {
        private const val TAG = "PageReader"
        private const val LOW_CONFIDENCE = 0.35f
        private const val MODEL_ASSET = "yolov8n_det_256.onnx"
    }
}

/**
 * Maps analysis-frame coordinates onto the canvas the way PreviewView's
 * FILL_CENTER lays out the preview: scale uniformly by the *larger* of the two
 * axis ratios so the frame covers the view, then centre it. The overflowing
 * axis gets a negative offset and is cropped, which is what the user sees.
 *
 * Using the smaller ratio instead (FIT_CENTER) would letterbox, and the overlay
 * would sit inside the real document boundary rather than on it.
 */
private class FrameTransform(frameWidth: Int, frameHeight: Int, canvas: Size) {
    val scale: Float = maxOf(canvas.width / frameWidth, canvas.height / frameHeight)
    val offsetX: Float = (canvas.width - frameWidth * scale) / 2f
    val offsetY: Float = (canvas.height - frameHeight * scale) / 2f

    fun map(x: Double, y: Double) =
        Offset(x.toFloat() * scale + offsetX, y.toFloat() * scale + offsetY)
}

/**
 * Sighted-developer debug view only. The blind user never looks at this; every
 * signal it draws also exists as speech or as a line in the session log.
 */
@Composable
private fun PageOverlay(observation: PageObservation?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val o = observation ?: return@Canvas
        if (o.frameWidth == 0 || o.frameHeight == 0) return@Canvas
        val t = FrameTransform(o.frameWidth, o.frameHeight, size)
        val stroke = 6f

        val quad = o.quad
        if (quad != null) {
            // Green once the detector is confident enough to be acted on,
            // amber when it is guessing.
            val colour = if (o.confidence >= 0.35f) Color.Green else Color(0xFFFFA000)
            val points = quad.map { t.map(it.x, it.y) }
            for (i in points.indices) {
                drawLine(colour, points[i], points[(i + 1) % points.size], stroke)
            }
        }

        // Clipped borders drawn as a red bar along the edge the page runs off.
        val bar = 14f
        o.clipped.forEach { side ->
            when (side) {
                com.pagereader.android.detect.Side.LEFT ->
                    drawLine(Color.Red, Offset(bar / 2, 0f), Offset(bar / 2, size.height), bar)
                com.pagereader.android.detect.Side.RIGHT ->
                    drawLine(Color.Red, Offset(size.width - bar / 2, 0f), Offset(size.width - bar / 2, size.height), bar)
                com.pagereader.android.detect.Side.TOP ->
                    drawLine(Color.Red, Offset(0f, bar / 2), Offset(size.width, bar / 2), bar)
                com.pagereader.android.detect.Side.BOTTOM ->
                    drawLine(Color.Red, Offset(0f, size.height - bar / 2), Offset(size.width, size.height - bar / 2), bar)
            }
        }
    }
}
