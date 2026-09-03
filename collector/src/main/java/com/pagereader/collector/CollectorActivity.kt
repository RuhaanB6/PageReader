package com.pagereader.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Training-data collection. Deliberately silent and entirely manual: you start
 * and stop every take yourself, and the app never speaks or advances on its own.
 *
 * Feedback is the counter block. The rejection breakdown is the useful part --
 * climbing `blur` means you are sweeping too fast, climbing `dup` means you are
 * barely moving. That is the control loop that replaces spoken prompts.
 */
class CollectorActivity : ComponentActivity(), SensorEventListener {

    private lateinit var writer: CollectionWriter
    private val gate = FrameGate()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sensorManager: SensorManager
    private val accelBuffer = ArrayDeque<Float>()
    @Volatile private var isShaking = false

    private val hasPermission = mutableStateOf(false)
    private val setup = mutableStateOf(Setup())
    private val take = mutableStateOf(Take.FULL_OVERHEAD)
    private val recording = mutableStateOf(false)
    private val stats = mutableStateOf(TakeStats())
    private val sharpness = mutableStateOf(0.0)
    private val freeMb = mutableStateOf(0L)
    private val status = mutableStateOf("")

    /** Verdict on the take just finished; null while recording or before the first. */
    private val verdict = mutableStateOf<TakeVerdict.Verdict?>(null)
    private val verdictTake = mutableStateOf<Take?>(null)

    /** Frames per take in this collection, for the session panel. */
    private val takeCounts = mutableStateOf<Map<Take, Int>>(emptyMap())
    private var takeStartedMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission.value = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()

        writer = CollectionWriter(this)
        writer.begin(setup.value, FrameGate.Config())
        freeMb.value = writer.freeMb()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent { CollectorScreen() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission.value = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------------------------------------------------------- camera

    private fun bindCamera(previewView: PreviewView) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // 720p: what the detector effectively sees after its own downscale,
            // so training data matches deployment rather than being sharper than
            // anything the model will meet in the field.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            if (!recording.value) return

            val bgr = yuvToBgr(proxy)
            val rotated = rotate(bgr, proxy.imageInfo.rotationDegrees)
            if (rotated !== bgr) bgr.release()

            try {
                val now = System.currentTimeMillis()
                val d = gate.evaluate(rotated, isShaking, now)
                if (d.keep) {
                    writer.write(rotated, take.value, setup.value, d.sharpness, now)
                }
                if (d.reject != Reject.RATE) {
                    mainHandler.post {
                        stats.value = stats.value.plus(d.reject)
                        sharpness.value = d.sharpness
                    }
                }
            } finally {
                rotated.release()
            }
        } finally {
            proxy.close()
        }
    }

    // ------------------------------------------------------------- controls

    private fun startTake() {
        val existing = writer.countFor(take.value)
        if (existing > 0) {
            // Replace rather than append, so a fumbled take cannot quietly
            // pollute a good one. Two presses to confirm.
            if (status.value != CONFIRM_REPLACE) {
                status.value = CONFIRM_REPLACE
                return
            }
            writer.discard(take.value)
        }
        status.value = ""
        verdict.value = null
        verdictTake.value = null
        gate.reset()
        stats.value = TakeStats()
        takeStartedMs = System.currentTimeMillis()
        recording.value = true
    }

    private fun stopTake() {
        recording.value = false
        freeMb.value = writer.freeMb()
        val s = stats.value
        val finished = take.value

        // Judged here, on the phone, while the scene is still on the desk.
        // Pulling the collection first would mean reshooting a rebuilt scene.
        val quality = gate.takeQuality(s.kept)
        val v = TakeVerdict.judge(s, quality)
        writer.finishTake(
            finished, s, (System.currentTimeMillis() - takeStartedMs) / 1000, quality, v
        )
        verdict.value = v
        verdictTake.value = finished
        status.value = ""
        refreshCounts()

        // Advance only when the take is worth keeping, so a failed one stays
        // selected and the next press reshoots it rather than moving on.
        if (v.ok) {
            val next = Take.entries.indexOf(finished) + 1
            if (next < Take.entries.size) take.value = Take.entries[next]
        }
    }

    private fun refreshCounts() {
        val counts = Take.entries.associateWith { writer.countFor(it) }
        mainHandler.post { takeCounts.value = counts }
    }

    // -------------------------------------------------------------- sensors

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        recording.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        writer.close()
        gate.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val n = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        if (accelBuffer.size == 3) accelBuffer.removeFirst()
        accelBuffer.addLast(n)
        isShaking = accelBuffer.any { it > SHAKE_THRESHOLD }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ UI

    @Composable
    private fun CollectorScreen() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(color = Color.Black) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (hasPermission.value) {
                            AndroidView(
                                factory = { ctx -> PreviewView(ctx).also(::bindCamera) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (recording.value) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp)
                                    .background(Color.Red).size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    SetupRow()
                    Spacer(Modifier.height(4.dp))
                    Picker("take", Take.entries.map { it.tag }, take.value.tag) { t ->
                        take.value = Take.entries.first { it.tag == t }
                        status.value = ""
                    }
                    Text(
                        take.value.hint,
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    Counters()

                    VerdictPanel()
                    SessionPanel()

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { if (recording.value) stopTake() else startTake() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (recording.value) Color(0xFFB00020) else Color(0xFF2E7D32)
                        )
                    ) {
                        Text(
                            if (recording.value) "STOP"
                            else if (status.value == CONFIRM_REPLACE) "PRESS AGAIN TO REPLACE"
                            else if (verdict.value?.ok == false) "RESHOOT ${take.value.tag}"
                            else "START",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    /**
     * The verdict on the take just finished. Loud on purpose: this is the only
     * moment where reshooting is cheap, and a quiet advisory would be missed.
     */
    @Composable
    private fun VerdictPanel() {
        val v = verdict.value ?: return
        val t = verdictTake.value ?: return
        val bg = if (v.ok) Color(0xFF1B5E20) else Color(0xFFB00020)
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(bg).padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                if (v.ok) "${t.tag}  —  GOOD" else "${t.tag}  —  RESHOOT",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            v.fatal.forEach {
                Text("• $it", color = Color.White, fontSize = 13.sp)
            }
            v.advice.forEach {
                Text("• $it", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            }
            if (v.ok && v.advice.isEmpty()) {
                Text(
                    "${stats.value.kept} frames kept",
                    color = Color(0xFFC8E6C9), fontSize = 12.sp
                )
            }
        }
    }

    /** What is still missing from this setup, so nothing is discovered later. */
    @Composable
    private fun SessionPanel() {
        val counts = takeCounts.value
        if (counts.isEmpty()) return
        val sv = TakeVerdict.judgeSession(counts)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                Take.entries.joinToString("  ") { tk ->
                    val n = counts[tk] ?: 0
                    if (n > 0) "${tk.tag.take(4)}:$n" else "${tk.tag.take(4)}:—"
                },
                color = Color(0xFF9E9E9E), fontSize = 10.sp
            )
            if (sv.complete) {
                Text(
                    "all ${sv.totalFrames} frames · setup complete",
                    color = Color(0xFF81C784), fontSize = 11.sp
                )
            } else {
                Text(
                    "${sv.totalFrames} frames · still to shoot: " +
                        sv.missing.joinToString(", ") { it.tag },
                    color = Color(0xFFFFB74D), fontSize = 11.sp
                )
            }
            if (sv.partialThin) {
                Text(
                    "only ${"%.0f".format(sv.partialFraction * 100)}% partial-edge — " +
                        "those drive the guidance cues, aim for ~40%",
                    color = Color(0xFFFFB74D), fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun SetupRow() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                Picker("surface", Setup.SURFACES, setup.value.surface) {
                    setup.value = setup.value.copy(surface = it); rebegin()
                }
            }
            Box(Modifier.weight(1f)) {
                Picker("doc", Setup.DOCUMENTS, setup.value.document) {
                    setup.value = setup.value.copy(document = it); rebegin()
                }
            }
            Box(Modifier.weight(1f)) {
                Picker("light", Setup.LIGHTING, setup.value.lighting) {
                    setup.value = setup.value.copy(lighting = it); rebegin()
                }
            }
        }
    }

    /** A changed setup is a new physical scene, so it gets its own folder. */
    private fun rebegin() {
        if (recording.value) return
        writer.begin(setup.value, FrameGate.Config())
        take.value = Take.FULL_OVERHEAD
        stats.value = TakeStats()
        status.value = ""
        verdict.value = null
        verdictTake.value = null
        refreshCounts()
    }

    @Composable
    private fun Picker(
        label: String,
        options: List<String>,
        selected: String,
        onPick: (String) -> Unit,
    ) {
        var open by remember { mutableStateOf(false) }
        Column {
            Text(label, color = Color(0xFF757575), fontSize = 10.sp)
            OutlinedButton(
                onClick = { open = true },
                enabled = !recording.value,
                modifier = Modifier.fillMaxWidth()
            ) { Text(selected, fontSize = 13.sp, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) })
                }
            }
        }
    }

    @Composable
    private fun Counters() {
        val s = stats.value
        Column {
            Text(
                "KEPT ${s.kept}",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "blur ${s.blur} · dup ${s.duplicate} · shake ${s.shake}",
                color = Color(0xFFBDBDBD),
                fontSize = 14.sp
            )
            // A bar rather than a number: the useful judgement is "well above the
            // line" or "hovering at it", not the exact variance.
            val ratio = (sharpness.value / (FrameGate.Config().minSharpness * 3)).coerceIn(0.0, 1.0)
            LinearProgressIndicator(
                progress = { ratio.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = if (sharpness.value >= FrameGate.Config().minSharpness)
                    Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
            Text(
                "sharpness %.0f · free %d MB%s".format(
                    sharpness.value, freeMb.value,
                    if (status.value.isNotEmpty() && status.value != CONFIRM_REPLACE)
                        " · ${status.value}" else ""
                ),
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    // --------------------------------------------------------- frame decode

    private fun yuvToBgr(proxy: ImageProxy): Mat {
        val w = proxy.width
        val h = proxy.height
        val y = plane(proxy.planes[0].buffer, proxy.planes[0].rowStride, proxy.planes[0].pixelStride, w, h)
        val u = plane(proxy.planes[1].buffer, proxy.planes[1].rowStride, proxy.planes[1].pixelStride, w / 2, h / 2)
        val v = plane(proxy.planes[2].buffer, proxy.planes[2].rowStride, proxy.planes[2].pixelStride, w / 2, h / 2)

        val i420 = ByteArray(y.size + u.size + v.size)
        System.arraycopy(y, 0, i420, 0, y.size)
        System.arraycopy(u, 0, i420, y.size, u.size)
        System.arraycopy(v, 0, i420, y.size + u.size, v.size)

        val yuv = Mat(h + h / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, i420)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
        yuv.release()
        return bgr
    }

    private fun plane(buf: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val d = buf.duplicate()
        val row = ByteArray(rowStride)
        var o = 0
        for (r in 0 until h) {
            d.position(r * rowStride)
            d.get(row, 0, min(rowStride, d.remaining()))
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, o, w)
                o += w
            } else {
                for (c in 0 until w) out[o++] = row[c * pixelStride]
            }
        }
        return out
    }

    private fun rotate(mat: Mat, degrees: Int): Mat {
        val code = when (degrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val out = Mat()
        Core.rotate(mat, out, code)
        return out
    }

    companion object {
        private const val TAG = "Collector"
        private const val SHAKE_THRESHOLD = 1.2f
        private const val CONFIRM_REPLACE = "confirm-replace"
    }
}
