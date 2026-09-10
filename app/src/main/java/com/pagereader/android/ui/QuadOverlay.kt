package com.pagereader.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.detect.Side
import com.pagereader.android.guidance.FramingState
import com.pagereader.android.ui.theme.Ember
import com.pagereader.android.ui.theme.GuidanceAmber
import com.pagereader.android.ui.theme.GuidanceGreen

/**
 * Maps analysis-frame coordinates onto the canvas the way PreviewView's
 * FIT_CENTER lays out the preview: scale uniformly by the *smaller* of the two
 * axis ratios so the whole frame fits, then centre it. The short axis gets a
 * positive offset and letterboxes.
 *
 * This deliberately mirrors `PreviewView.ScaleType.FIT_CENTER` in
 * `CameraManager.bindCamera`. **Change one and you must change the other** or
 * the overlay stops sitting on the real document boundary.
 *
 * It used to be FILL_CENTER, matching the preview at the time. That cropped the
 * 4:3 analysis stream into a 9:20 window and hid ~20% of the frame width on each
 * side, so a page could be well outside the visible preview while still sitting
 * comfortably inside the frame the detector and the still capture actually use.
 * Testing on device on 2026-09-05 that showed up as "the left and right edges
 * need the page ~40% off before anything is said" -- 40% being exactly the
 * hidden fraction. Letterboxing shows the true capture area, so what is on
 * screen is what will be photographed.
 *
 * Lifted out of `MainActivity` unchanged (this is the mathematically load-
 * bearing part) so `CaptureScreen` can use it without a dependency on the
 * activity.
 */
class FrameTransform(frameWidth: Int, frameHeight: Int, canvas: Size) {
    val scale: Float = minOf(canvas.width / frameWidth, canvas.height / frameHeight)
    val offsetX: Float = (canvas.width - frameWidth * scale) / 2f
    val offsetY: Float = (canvas.height - frameHeight * scale) / 2f

    fun map(x: Double, y: Double) =
        Offset(x.toFloat() * scale + offsetX, y.toFloat() * scale + offsetY)
}

/**
 * Sighted-developer-and-low-vision-user debug/assist view over the live
 * preview. The blind user never looks at this; every signal it draws also
 * exists as speech (`GuidancePolicy.Decision.utterance`, spoken by
 * `TtsManager`) or as a line in the session log -- this file must never grow
 * a visual cue with no spoken equivalent.
 *
 * Replaces the old bare-polygon `MainActivity.PageOverlay`. Two upgrades:
 *
 *  - **Corners glide instead of snap.** The analysis stream runs at ~6 fps
 *    (`CameraManager`, KEEP_ONLY_LATEST), so a quad drawn at the raw
 *    per-frame position visibly jumps every ~160 ms. `animateOffsetAsState`
 *    on each of the four corners turns that into a smooth glide, which reads
 *    as "the detector is tracking the page" rather than "the detector is
 *    twitchy" -- the same underlying data, a calmer signal.
 *  - **Colour keyed to [FramingState]**, not to a raw confidence number:
 *    amber while [FramingState.SEARCHING] or [FramingState.ADJUSTING] (something
 *    still needs correcting), the warm accent once [FramingState.FRAMED], and
 *    green at [FramingState.STEADY] (capture is about to fire). This is the
 *    same state the guidance policy already computed for speech, so the
 *    overlay and the voice never disagree about what is happening.
 */
@Composable
fun QuadOverlay(
    observation: PageObservation?,
    state: FramingState,
    modifier: Modifier = Modifier,
) {
    val targetColor = when (state) {
        FramingState.SEARCHING, FramingState.ADJUSTING -> GuidanceAmber
        FramingState.FRAMED -> Ember
        FramingState.STEADY -> GuidanceGreen
    }
    // Tween rather than spring: a quad that overshoots its target corner
    // reads as measurement noise, which is exactly the impression the
    // animation exists to remove.
    val color by animateColorAsState(targetColor, tween(CORNER_ANIM_MS), label = "quadColor")
    val fillAlpha by animateFloatAsState(
        if (state == FramingState.STEADY) STEADY_FILL_ALPHA else IDLE_FILL_ALPHA,
        tween(CORNER_ANIM_MS),
        label = "quadFillAlpha",
    )

    val quad = observation?.quad
    val frameWidth = observation?.frameWidth ?: 0
    val frameHeight = observation?.frameHeight ?: 0

    Canvas(modifier = modifier) {
        if (quad == null || quad.size != 4 || frameWidth == 0 || frameHeight == 0) return@Canvas
        val t = FrameTransform(frameWidth, frameHeight, size)
        val points = quad.map { t.map(it.x, it.y) }

        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1..3) lineTo(points[i].x, points[i].y)
                close()
            },
            color = color.copy(alpha = fillAlpha),
        )

        val bracketLen = (points[0] - points[1]).getDistance().coerceAtMost(BRACKET_MAX_PX) *
            BRACKET_FRACTION
        for (i in points.indices) {
            val corner = points[i]
            val prev = points[(i + 3) % points.size]
            val next = points[(i + 1) % points.size]
            drawLine(color, corner, corner + (prev - corner).normalizedTo(bracketLen), STROKE_PX, StrokeCap.Round)
            drawLine(color, corner, corner + (next - corner).normalizedTo(bracketLen), STROKE_PX, StrokeCap.Round)
        }
    }
}

private fun Offset.normalizedTo(length: Float): Offset {
    val d = getDistance()
    return if (d < 0.0001f) Offset.Zero else this * (length / d)
}

/**
 * Per-[Side] clip bar, unchanged in meaning from the original `PageOverlay`:
 * a bar along the frame border the detected page mask actually touches, so
 * "the page runs off this edge" is visible as well as spoken
 * (`Instruction.MOVE_LEFT` etc., via `GuidancePolicy`).
 *
 * Restyled with rounded caps and reduced opacity so it reads as an
 * attention marker rather than a hazard stripe -- the earlier flat red bar
 * tested as alarming for a condition that is completely routine mid-frame.
 */
@Composable
fun ClipBars(clipped: Set<Side>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (clipped.isEmpty()) return@Canvas
        val bar = CLIP_BAR_PX
        val colour = Color(0xFFFF5252).copy(alpha = 0.75f)
        clipped.forEach { side ->
            when (side) {
                Side.LEFT -> drawLine(
                    colour, Offset(bar / 2, 0f), Offset(bar / 2, size.height), bar, StrokeCap.Round
                )
                Side.RIGHT -> drawLine(
                    colour, Offset(size.width - bar / 2, 0f), Offset(size.width - bar / 2, size.height), bar, StrokeCap.Round
                )
                Side.TOP -> drawLine(
                    colour, Offset(0f, bar / 2), Offset(size.width, bar / 2), bar, StrokeCap.Round
                )
                Side.BOTTOM -> drawLine(
                    colour, Offset(0f, size.height - bar / 2), Offset(size.width, size.height - bar / 2), bar, StrokeCap.Round
                )
            }
        }
    }
}

/** How long a corner takes to glide to its new position. Shorter than the
 *  ~160 ms analysis interval would re-introduce the jitter this exists to
 *  remove; much longer and the overlay visibly lags a fast pan. */
private const val CORNER_ANIM_MS = 220

/** Fill opacity while nothing is resolved yet -- present but not loud. */
private const val IDLE_FILL_ALPHA = 0.12f

/** Fill opacity once STEADY, so "about to capture" reads as more solid. */
private const val STEADY_FILL_ALPHA = 0.22f

private const val STROKE_PX = 10f

/** Corner brackets, not a full outline: fraction of the corner's adjacent
 *  edge length each bracket arm reaches. */
private const val BRACKET_FRACTION = 0.28f

/** Cap on bracket arm length so a huge, near-frame-filling quad does not
 *  grow brackets long enough to nearly meet in the middle. */
private const val BRACKET_MAX_PX = 220f

/** Clip bar thickness, matched to the original `PageOverlay` value. */
private const val CLIP_BAR_PX = 14f
