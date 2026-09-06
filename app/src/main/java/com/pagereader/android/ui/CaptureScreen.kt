package com.pagereader.android.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pagereader.android.BuildConfig
import com.pagereader.android.detect.PageObservation
import com.pagereader.android.guidance.FramingState
import com.pagereader.android.guidance.Instruction
import com.pagereader.android.ui.theme.Ember
import com.pagereader.android.ui.theme.GuidanceAmber
import com.pagereader.android.ui.theme.GuidanceGreen
import com.pagereader.android.ui.theme.InkBackground

/**
 * FRAMING. Full-bleed camera preview with the guidance the app used to only
 * *say* now also shown, per the plan's "display them, don't just say them"
 * requirement -- speech stays primary (the instruction card binds to
 * `instruction`, which the caller keeps in sync with what
 * `GuidancePolicy.Decision.utterance` actually spoke), this is the low-vision
 * and sighted-helper channel on top of it.
 *
 * Deliberately takes no dependency on `CameraManager`: [onPreviewView] hands
 * the freshly-created `PreviewView` back to the caller, which is the only
 * thing that knows how to bind a camera to it. That keeps this file testable
 * and reusable without dragging CameraX's lifecycle wiring along.
 *
 * `scaleType` is set to FIT_CENTER by the caller's `CameraManager.bindCamera`
 * -- see [FrameTransform]'s doc for why that pairing is load-bearing and must
 * not drift.
 */
@Composable
fun CaptureScreen(
    observation: PageObservation?,
    instruction: Instruction?,
    state: FramingState,
    debugLine: String,
    onShutter: () -> Unit,
    onPreviewView: (PreviewView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(InkBackground)
            // Double-tap anywhere is the second manual shutter, alongside the
            // volume keys. The whole screen is the target because a blind
            // user cannot aim at a button, and double- rather than single-tap
            // so a hand steadying the phone does not fire it. Lifted from
            // `MainActivity`'s root Box unchanged in behaviour.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onShutter() })
            },
    ) {
        AndroidView(
            factory = { context -> PreviewView(context).also(onPreviewView) },
            modifier = Modifier
                .fillMaxSize()
                // The screen reader has nothing useful to say about a raw
                // camera feed; the instruction card below carries the words.
                .clearAndSetSemantics { },
        )

        QuadOverlay(observation = observation, state = state, modifier = Modifier.fillMaxSize())
        ClipBars(clipped = observation?.clipped.orEmpty(), modifier = Modifier.fillMaxSize())

        TopScrim(state = state, modifier = Modifier.align(Alignment.TopCenter))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InstructionCard(instruction = instruction, state = state)
            Spacer(modifier = Modifier.height(20.dp))
            ShutterButton(state = state, onShutter = onShutter)
        }

        if (BuildConfig.DEBUG) {
            Text(
                text = debugLine,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    // Developer debug only. Without this the screen reader
                    // announces it as page content, which is noise the actual
                    // user cannot act on.
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Faint top scrim plus a status chip naming [state] in words -- the same
 * words a sighted helper glancing at the phone would want, and nothing this
 * app doesn't already track for the debug line.
 */
@Composable
private fun TopScrim(state: FramingState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            .padding(16.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        val (label, colour) = when (state) {
            FramingState.SEARCHING -> "Searching" to GuidanceAmber
            FramingState.ADJUSTING -> "Adjusting" to GuidanceAmber
            FramingState.FRAMED -> "Framed" to Ember
            FramingState.STEADY -> "Holding steady" to GuidanceGreen
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colour),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The high-contrast pill at the bottom of [CaptureScreen]: the currently
 * *displayed* instruction in `displaySmall`, `Crossfade`d so a changing
 * instruction reads as a deliberate transition rather than a flicker, plus a
 * secondary status line for [FramingState].
 *
 * Binds to `instruction` (the one currently in force, which may persist
 * across many frames), not to the single-frame `utterance` TTS speaks --
 * `GuidancePolicy.Decision` already keeps the two separate for exactly this
 * reason: the card should keep showing the active correction even on frames
 * where nothing new is said.
 */
@Composable
private fun InstructionCard(instruction: Instruction?, state: FramingState) {
    val text = instruction?.text ?: when (state) {
        FramingState.STEADY -> "Hold still — capturing"
        FramingState.FRAMED -> "Looking good"
        else -> "Show me the page"
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                // One semantic node, since the instruction is spoken
                // separately by TtsManager and does not need to be announced
                // a second time by a screen reader whenever it recomposes.
                .clearAndSetSemantics { contentDescription = text },
        ) {
            Crossfade(targetState = text, label = "instructionText") { shown ->
                Text(
                    text = shown,
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Large, centred, thumb-reachable shutter. Not the primary control for a
 * blind user (the double-tap gesture and the volume keys are), but this is
 * the "the app looks and navigates badly" complaint's most literal fix: a
 * scanner app with no visible shutter button at all.
 */
@Composable
private fun ShutterButton(state: FramingState, onShutter: () -> Unit) {
    val colour = if (state == FramingState.STEADY) GuidanceGreen else Ember
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(colour)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onShutter() })
            }
            .semantics { contentDescription = "Take photo" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
