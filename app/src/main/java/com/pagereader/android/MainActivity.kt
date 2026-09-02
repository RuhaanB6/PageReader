package com.pagereader.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pagereader.android.audio.GuidanceCue
import com.pagereader.android.audio.TtsManager
import com.pagereader.android.camera.CameraManager
import com.pagereader.android.camera.EdgeResult
import com.pagereader.android.guidance.PartialEdgeGuidance
import com.pagereader.android.guidance.ShakeDetector
import com.pagereader.android.ui.theme.PageReaderTheme
import kotlin.math.hypot
import kotlin.math.min
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TtsManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var cameraManager: CameraManager

    private val hasCameraPermission = mutableStateOf(false)
    private val shakeState = mutableStateOf("Stable")
    private val latestEdgeResult = mutableStateOf<EdgeResult?>(null)

    /** Tracks found/not-found transitions so FOUND only fires on the edge. */
    private var wasFound = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCameraPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        OpenCVLoader.initLocal()

        ttsManager = TtsManager(this)

        shakeDetector = ShakeDetector(
            context = this,
            onShake = {
                shakeState.value = "Shaking"
                ttsManager.speak(GuidanceCue.HOLD_STEADY, cooldownMs = 1500L)
            },
            onStable = {
                shakeState.value = "Stable"
                if (wasFound) ttsManager.speak(GuidanceCue.READY, cooldownMs = 2000L)
            }
        )

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            onEdgeResult = ::handleEdgeResult
        )

        setContent {
            PageReaderTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (hasCameraPermission.value) {
                        AndroidView(
                            factory = { context ->
                                PreviewView(context).also(cameraManager::bindCamera)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        EdgeOverlay(
                            result = latestEdgeResult.value,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(
                        text = shakeState.value,
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

    /** Speech comes up first; flipping the flag composes the preview, which binds the camera. */
    private fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) return
        ttsManager.initialize { hasCameraPermission.value = true }
    }

    private fun handleEdgeResult(result: EdgeResult) {
        latestEdgeResult.value = result

        if (result.found) {
            if (!wasFound) ttsManager.speak(GuidanceCue.FOUND, cooldownMs = 3000L)
            wasFound = true
            if (shakeDetector.isShaking) {
                ttsManager.speak(GuidanceCue.HOLD_STEADY, cooldownMs = 1500L)
            }
            return
        }

        wasFound = false
        val partial = result.partial
        if (partial == null || partial.sidesFound() == 0) {
            ttsManager.speak(GuidanceCue.NO_DOCUMENT, cooldownMs = 3000L)
            return
        }

        val cue = PartialEdgeGuidance.getCue(partial, result.frameWidth, result.frameHeight)
        if (cue != null) ttsManager.speak(cue, cooldownMs = 1500L)
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
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

    fun map(x: Float, y: Float) = Offset(x * scale + offsetX, y * scale + offsetY)
    fun map(x: Double, y: Double) = map(x.toFloat(), y.toFloat())
}

/**
 * Sighted-developer debug view only. The blind user never looks at this; every
 * signal it draws also exists as speech.
 */
@Composable
private fun EdgeOverlay(result: EdgeResult?, modifier: Modifier = Modifier) {
    // Single-slot cache so the transform is logged on change, not every frame.
    val lastLogged = remember { arrayOfNulls<String>(1) }

    Canvas(modifier = modifier) {
        if (result == null || result.frameWidth == 0 || result.frameHeight == 0) return@Canvas

        val frameW = result.frameWidth
        val frameH = result.frameHeight
        val t = FrameTransform(frameW, frameH, size)
        val stroke = 6f

        val signature = "${size.width}x${size.height}/${frameW}x$frameH"
        if (lastLogged[0] != signature) {
            lastLogged[0] = signature
            Log.d(
                "DocEdge",
                "overlay: canvas=${size.width}x${size.height} " +
                    "frame=${frameW}x$frameH " +
                    "scale=${t.scale} offsetX=${t.offsetX} offsetY=${t.offsetY}"
            )
        }

        if (result.found && result.corners != null) {
            val points = result.corners.map { t.map(it.x, it.y) }
            for (i in points.indices) {
                drawLine(Color.Green, points[i], points[(i + 1) % points.size], stroke)
            }
            return@Canvas
        }

        val partial = result.partial ?: return@Canvas
        if (partial.sidesFound() == 0) return@Canvas

        // Detected sides: blue horizontal spanning the frame, red vertical
        // clipped to the y-extent Hough actually reported for that segment.
        partial.top?.let { y ->
            drawLine(Color.Blue, t.map(0f, y), t.map(frameW.toFloat(), y), stroke)
        }
        partial.bottom?.let { y ->
            drawLine(Color.Blue, t.map(0f, y), t.map(frameW.toFloat(), y), stroke)
        }
        partial.left?.let { x ->
            val y1 = partial.leftY1 ?: partial.top ?: 0f
            val y2 = partial.leftY2 ?: partial.bottom ?: frameH.toFloat()
            drawLine(Color.Red, t.map(x, y1), t.map(x, y2), stroke)
        }
        partial.right?.let { x ->
            val y1 = partial.rightY1 ?: partial.top ?: 0f
            val y2 = partial.rightY2 ?: partial.bottom ?: frameH.toFloat()
            drawLine(Color.Red, t.map(x, y1), t.map(x, y2), stroke)
        }

        // Missing sides: yellow arrows pointing off-frame in that direction.
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val arrowLen = min(size.width, size.height) * 0.12f

        if (partial.top == null) {
            drawArrow(Offset(centerX, arrowLen), Offset(centerX, 0f), stroke)
        }
        if (partial.bottom == null) {
            drawArrow(Offset(centerX, size.height - arrowLen), Offset(centerX, size.height), stroke)
        }
        if (partial.left == null) {
            drawArrow(Offset(arrowLen, centerY), Offset(0f, centerY), stroke)
        }
        if (partial.right == null) {
            drawArrow(Offset(size.width - arrowLen, centerY), Offset(size.width, centerY), stroke)
        }
    }
}

private fun DrawScope.drawArrow(start: Offset, end: Offset, strokeWidth: Float) {
    drawLine(Color.Yellow, start, end, strokeWidth)

    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = hypot(dx, dy)
    if (len == 0f) return

    val ux = dx / len
    val uy = dy / len
    val headLen = strokeWidth * 3f

    // Perpendicular, for the two barbs.
    val px = -uy
    val py = ux

    drawLine(
        Color.Yellow,
        end,
        Offset(end.x - ux * headLen + px * headLen, end.y - uy * headLen + py * headLen),
        strokeWidth
    )
    drawLine(
        Color.Yellow,
        end,
        Offset(end.x - ux * headLen - px * headLen, end.y - uy * headLen - py * headLen),
        strokeWidth
    )
}
