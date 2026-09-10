package com.pagereader.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.pagereader.android.ui.theme.Ember
import com.pagereader.android.ui.theme.InkBackground
import com.pagereader.android.ui.theme.InkSurfaceVariant
import com.pagereader.android.ui.theme.Paper

/**
 * The pipeline stages the user is told about, in order. Defined here (not in
 * `MainActivity`) so this file is self-contained and the orchestrator only
 * needs to import it, not define its own parallel enum.
 */
enum class CaptureStage { CAPTURING, FLATTENING, READING_TEXT, FINISHING }

/**
 * PROCESSING. No camera preview composes here -- that is the explicit
 * requirement this screen exists to satisfy: the shutter used to leave the
 * live preview on screen for the 2-14 s the pipeline runs, which looked like
 * the app had frozen, or worse, that the second the photo happened was still
 * "live" and could be re-framed.
 *
 * Spoken equivalents already exist and are unchanged by this screen: the
 * earcon, "Captured. Reading the page.", and the quarter-percent progress
 * announcements at `MainActivity`'s existing Tesseract progress lambda. This
 * screen is the visual half of those same events, not a new source of them --
 * per CLAUDE.md, every signal drawn here already has a spoken equivalent
 * elsewhere, so nothing here needs a semantic node of its own beyond the
 * stage caption (screen readers get that for free from the [Text]).
 */
@Composable
fun ProcessingScreen(
    stage: CaptureStage,
    percent: Int,
    pagePreview: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(InkBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PageThumbnail(pagePreview = pagePreview, percent = percent, indeterminate = stage != CaptureStage.READING_TEXT)

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 32.dp))

            Text(
                text = captionFor(stage),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .clearAndSetSemantics { },
                color = Ember,
                trackColor = InkSurfaceVariant,
            )
        }
    }
}

/**
 * The dewarped page, dimmed, with a scan line sweeping down it -- the
 * CamScanner beat the design brief asked for. Honest rather than purely
 * decorative: once OCR is actually running (`indeterminate == false`) the
 * sweep tracks [percent] instead of looping freely, so its position means
 * something.
 *
 * Before the dewarped bitmap exists (still capturing, or still flattening),
 * a neutral paper-coloured placeholder in the same aspect ratio stands in,
 * so the layout does not jump the instant [pagePreview] arrives.
 */
@Composable
private fun PageThumbnail(pagePreview: ImageBitmap?, percent: Int, indeterminate: Boolean) {
    val infinite = rememberInfiniteTransition(label = "scanLineLoop")
    val loopFraction by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SCAN_LOOP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanLineFraction",
    )
    val sweepFraction = if (indeterminate) loopFraction else (percent / 100f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAGE_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .background(Paper),
    ) {
        if (pagePreview != null) {
            Image(
                painter = BitmapPainter(pagePreview),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Drawn as a separate layer on top of the bitmap, not as the
            // Image's own `background` modifier -- that paints *behind* the
            // bitmap, not over it, and would leave the photo at full
            // brightness underneath the scan line.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = DIM_ALPHA)))
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height * sweepFraction
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Ember.copy(alpha = 0.9f), Color.Transparent),
                    startY = (y - SCAN_BAND_PX).coerceAtLeast(0f),
                    endY = (y + SCAN_BAND_PX).coerceAtMost(size.height),
                ),
                topLeft = Offset(0f, (y - SCAN_BAND_PX).coerceAtLeast(0f)),
            )
        }
    }
}

private fun captionFor(stage: CaptureStage): String = when (stage) {
    CaptureStage.CAPTURING -> "Capturing…"
    CaptureStage.FLATTENING -> "Flattening the page…"
    CaptureStage.READING_TEXT -> "Reading the text…"
    CaptureStage.FINISHING -> "Almost done"
}

/** A typical portrait page. Only affects the placeholder before a real photo
 *  arrives; the actual bitmap keeps its own aspect ratio via ContentScale.Crop. */
private const val PAGE_ASPECT = 0.77f

/** One full sweep, top to bottom, while indeterminate. Slow enough to read as
 *  a scan rather than a strobe. */
private const val SCAN_LOOP_MS = 1800

private const val SCAN_BAND_PX = 60f

/** How much the thumbnail is darkened so the scan line reads clearly against it. */
private const val DIM_ALPHA = 0.45f
