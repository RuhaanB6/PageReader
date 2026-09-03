package com.pagereader.android.detect

import android.content.Context
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfRect2d
import org.opencv.core.Rect2d
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import java.io.File

/** One YOLO box, in original-frame pixel coordinates. */
data class Detection(val box: Rect2d, val score: Float, val classId: Int)

/**
 * Runs a YOLOv8 detection ONNX through OpenCV's bundled DNN module.
 *
 * Uses `cv::dnn` rather than adding a runtime because the OpenCV native is
 * already on the classpath -- this costs no new dependency and no APK growth
 * beyond the model file. The bet is not free, though: OpenCV's ONNX importer
 * has open failures on YOLOv8 graphs (opencv#24148 Reshape/DFL, opencv#28377
 * Resize->Concat), so [load] is written to fail softly and let the caller fall
 * back rather than taking the app down.
 *
 * Export must be **static shape**, `opset=12`, simplified, and must NOT include
 * end-to-end NMS -- cv::dnn cannot run those ops. NMS happens here instead,
 * which is cheap since we only ever want the best page.
 */
class YoloDnnEngine private constructor(
    private val net: Net,
    val inputSize: Int,
    private val numClasses: Int,
) {

    /**
     * @return detections in [bgr] coordinates, best first.
     */
    fun detect(bgr: Mat, scoreThreshold: Float, nmsThreshold: Float): List<Detection> {
        val blob = Dnn.blobFromImage(
            bgr,
            1 / 255.0,
            Size(inputSize.toDouble(), inputSize.toDouble()),
            org.opencv.core.Scalar(0.0, 0.0, 0.0),
            /* swapRB = */ true,
            /* crop = */ false
        )
        net.setInput(blob)

        val out = try {
            net.forward()
        } catch (t: Throwable) {
            Log.e(TAG, "forward() failed", t)
            blob.release()
            return emptyList()
        }
        blob.release()

        try {
            // YOLOv8 emits [1, 4 + numClasses, anchors]; transpose so each row is
            // one candidate box.
            val channels = out.size(1)
            val anchors = out.size(2)
            val flat = out.reshape(1, channels)
            val t = Mat()
            Core.transpose(flat, t)

            val data = FloatArray(anchors * channels)
            t.get(0, 0, data)
            t.release()
            flat.release()

            val boxes = ArrayList<Rect2d>()
            val scores = ArrayList<Float>()
            val classes = ArrayList<Int>()

            // Letterboxing is off (crop=false scales both axes), so mapping back
            // is a straight per-axis scale.
            val sx = bgr.width().toDouble() / inputSize
            val sy = bgr.height().toDouble() / inputSize

            for (i in 0 until anchors) {
                val o = i * channels
                var bestScore = 0f
                var bestClass = -1
                for (c in 0 until numClasses) {
                    val s = data[o + 4 + c]
                    if (s > bestScore) {
                        bestScore = s
                        bestClass = c
                    }
                }
                if (bestScore < scoreThreshold || bestClass !in acceptedClasses()) continue

                val cx = data[o] * sx
                val cy = data[o + 1] * sy
                val w = data[o + 2] * sx
                val h = data[o + 3] * sy
                boxes += Rect2d(cx - w / 2, cy - h / 2, w, h)
                scores += bestScore
                classes += bestClass
            }

            if (boxes.isEmpty()) return emptyList()

            val indices = MatOfInt()
            Dnn.NMSBoxes(
                MatOfRect2d(*boxes.toTypedArray()),
                MatOfFloat(*scores.toFloatArray()),
                scoreThreshold,
                nmsThreshold,
                indices
            )
            val kept = if (indices.empty()) IntArray(0) else indices.toArray()
            indices.release()

            return kept
                .map { Detection(boxes[it], scores[it], classes[it]) }
                .sortedByDescending { it.score }
        } finally {
            out.release()
        }
    }

    /**
     * A single-class model is our own page detector, so everything counts. An
     * 80-class model is stock COCO, which has no "page" -- `book` is the nearest
     * thing, and it is a stand-in to prove the runtime, not a real page finder.
     */
    private fun acceptedClasses(): Set<Int> =
        if (numClasses == 1) setOf(0) else COCO_PAGE_LIKE

    companion object {
        private const val TAG = "YoloDnnEngine"

        /** COCO `book`. See [acceptedClasses]. */
        private val COCO_PAGE_LIKE = setOf(73)

        /**
         * Copies the asset out (cv::dnn needs a real path) and loads it.
         * Returns null on any failure -- caller falls back.
         */
        fun load(context: Context, assetName: String): YoloDnnEngine? {
            val size = Regex("_(\\d{3,4})\\.onnx$").find(assetName)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (size == null) {
                Log.e(TAG, "cannot infer input size from '$assetName' (expected e.g. _256.onnx)")
                return null
            }

            val file = File(context.filesDir, assetName)
            try {
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open("models/$assetName").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "model asset 'models/$assetName' missing or unreadable", t)
                return null
            }

            val net = try {
                Dnn.readNetFromONNX(file.absolutePath)
            } catch (t: Throwable) {
                Log.e(TAG, "cv::dnn cannot load $assetName -- falling back to colour. ${t.message}")
                return null
            }
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU)

            // Probe once so a graph that imports but cannot execute is caught
            // here, at startup, rather than on the first camera frame.
            val classes = try {
                val probe = Mat.zeros(size, size, org.opencv.core.CvType.CV_8UC3)
                val blob = Dnn.blobFromImage(probe, 1 / 255.0, Size(size.toDouble(), size.toDouble()))
                net.setInput(blob)
                val o = net.forward()
                val n = o.size(1) - 4
                o.release(); blob.release(); probe.release()
                Log.i(TAG, "loaded $assetName: input=$size classes=$n")
                n
            } catch (t: Throwable) {
                Log.e(TAG, "$assetName imports but will not run -- falling back to colour. ${t.message}")
                return null
            }

            return YoloDnnEngine(net, size, classes)
        }
    }
}
