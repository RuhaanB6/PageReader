package com.pagereader.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.BlockLabels
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock
import com.pagereader.android.ui.theme.InkBackground
import kotlin.math.max
import org.opencv.core.Rect as CvRect

/**
 * READING. Replaces both the old `ReadingScreen` and `ExploreScreen`: the
 * projection *is* the dewarped photo, and tapping a region on that photo is
 * exactly what `ExploreScreen`'s separate touch-explore mode used to provide,
 * so there is no longer a reason for it to be a different screen the user has
 * to swipe into.
 *
 * [pageImage] is the same rotated, upright Mat `recogniseOffThread` hands to
 * `store.save`, already converted to an [ImageBitmap] by the caller (never by
 * this file -- OpenCV conversion has no business happening inside a
 * composable's recomposition). It is in page space **G**, so every
 * [TextBlock.bbox] lines up with it directly, `quarterTurnsClockwise`
 * included.
 *
 * Two ways to consume a page, toggled at the top:
 *  - **Photo** (default): the actual page photo, zoom/pan 1x-4x, with
 *    translucent coloured boxes per block. Tap a box to jump there; tap the
 *    box already playing to toggle play/pause.
 *  - **Text list**: the old `ReadingScreen.BlockRow` list, moved over intact,
 *    for anyone who wants the OCR text large and reflowed rather than as a
 *    photo.
 *
 * Accessibility is identical in substance in both modes: one
 * `isTraversalGroup`, one semantic node per block with a stable
 * `contentDescription`/`traversalIndex`/`heading()`/"Read from here" action.
 * The current-block highlight is drawn only -- colour and a subtle pulse --
 * and never changes those semantics properties, per plan section 1.5: a
 * changing `contentDescription` fires an accessibility event, and TalkBack
 * would then talk over the page being read aloud.
 */
@Composable
fun PageScreen(
    page: OcrPage,
    pageImage: ImageBitmap?,
    currentBlockId: Int?,
    isPlaying: Boolean,
    progressFraction: Float,
    talkBackEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onPreviousBlock: () -> Unit,
    onNextBlock: () -> Unit,
    onReadFrom: (TextBlock) -> Unit,
    onNewPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTextList by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(InkBackground)
            .statusBarsPadding(),
    ) {
        LinearProgressIndicator(
            progress = { progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // No liveRegion is set here at all -- the per-block traversal
                // order already carries reading position for TalkBack, and a
                // live-announcing progress bar would talk over the page
                // being read. (This Compose version's LiveRegionMode has no
                // `None`; the default of not declaring the property is the
                // silent state.)
                .clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { showTextList = !showTextList }) {
                Text(if (showTextList) "Show photo" else "Show text list")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (showTextList || pageImage == null) {
                TextListView(
                    page = page,
                    currentBlockId = currentBlockId,
                    onReadFrom = onReadFrom,
                )
            } else {
                PhotoProjection(
                    page = page,
                    pageImage = pageImage,
                    currentBlockId = currentBlockId,
                    isPlaying = isPlaying,
                    talkBackEnabled = talkBackEnabled,
                    onReadFrom = onReadFrom,
                    onToggleCurrent = onTogglePlay,
                )
            }
        }

        ControlBar(
            isPlaying = isPlaying,
            onPreviousBlock = onPreviousBlock,
            onTogglePlay = onTogglePlay,
            onNextBlock = onNextBlock,
            onNewPage = onNewPage,
        )
    }
}

/**
 * The photo projection: fit-center photo, zoom 1x-4x via
 * `detectTransformGestures`, translucent rounded block overlays drawn with
 * the same [toView] transform so a tap lands on the block the user is
 * actually looking at.
 *
 * All positioning math is `PageProjection.toView`/`hitTest`, called with the
 * live `zoom`/`pan` -- the same functions `PageProjectionTest` checks
 * round-trip correctly, so this composable and that test can never silently
 * disagree about where a block is.
 */
@Composable
private fun PhotoProjection(
    page: OcrPage,
    pageImage: ImageBitmap,
    currentBlockId: Int?,
    isPlaying: Boolean,
    talkBackEnabled: Boolean,
    onReadFrom: (TextBlock) -> Unit,
    onToggleCurrent: () -> Unit,
) {
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Faded but present, never a tap target -- matches
    // `BlockLabels.playbackBlocks`, which excludes both from continuous
    // reading. Drawn so a sighted helper can see the running head is there,
    // but never as a jump target: jumping to one would silently do nothing,
    // since `PagePlayer` never loads them as playables.
    val fadedBlocks = remember(page) { page.blocks.filter { it.kind == BlockKind.HEADER_FOOTER } }
    val interactiveBlocks = remember(page) { BlockLabels.playbackBlocks(page) }

    // Only meaningfully animated while a block is both current and playing --
    // a paused current block should not keep breathing, which would look
    // like the app is still working on it.
    val pulseAlpha = if (isPlaying) pulseAlpha() else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics { isTraversalGroup = true }
            .pointerInput(page) {
                detectTransformGestures { _, panDelta, zoomDelta, _ ->
                    zoom = (zoom * zoomDelta).coerceIn(1f, 4f)
                    pan += panDelta
                }
            }
            .then(
                if (talkBackEnabled) {
                    // TalkBack owns single-finger touch here, exactly as
                    // `ExploreScreen` used to: installing a tap recognizer
                    // too means both see every touch, and TalkBack's own
                    // explore-by-touch fights the app's gesture.
                    Modifier
                } else {
                    Modifier.pointerInput(page, viewSize, zoom, pan) {
                        detectTapGestures { position ->
                            val hit = hitTest(
                                interactiveBlocks, position,
                                page.pageWidth, page.pageHeight, viewSize, zoom, pan,
                            )
                            if (hit != null) {
                                if (hit.id == currentBlockId) onToggleCurrent() else onReadFrom(hit)
                            }
                        }
                    }
                }
            ),
    ) {
        if (viewSize.width > 0f && viewSize.height > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pageRect = toView(
                    CvRect(0, 0, page.pageWidth, page.pageHeight),
                    page.pageWidth, page.pageHeight, size, zoom, pan,
                )
                drawImage(
                    image = pageImage,
                    dstOffset = IntOffset(pageRect.left.toInt(), pageRect.top.toInt()),
                    dstSize = IntSize(max(1, pageRect.width.toInt()), max(1, pageRect.height.toInt())),
                )

                (fadedBlocks + interactiveBlocks).forEach { b ->
                    val r = toView(b.bbox, page.pageWidth, page.pageHeight, size, zoom, pan)
                    val isCurrent = b.id == currentBlockId
                    val base = colourFor(b.kind)
                    val faded = b.kind == BlockKind.HEADER_FOOTER
                    val fillAlpha = when {
                        faded -> 0.08f
                        isCurrent -> 0.42f * pulseAlpha
                        else -> 0.16f
                    }
                    val strokeAlpha = when {
                        faded -> 0.25f
                        isCurrent -> 0.95f
                        else -> 0.55f
                    }
                    val topLeft = Offset(r.left, r.top)
                    val boxSize = Size(r.width, r.height)
                    val corner = CornerRadius(BLOCK_CORNER_PX, BLOCK_CORNER_PX)
                    drawRoundRect(color = base.copy(alpha = fillAlpha), topLeft = topLeft, size = boxSize, cornerRadius = corner)
                    drawRoundRect(
                        color = base.copy(alpha = strokeAlpha),
                        topLeft = topLeft,
                        size = boxSize,
                        cornerRadius = corner,
                        style = Stroke(width = if (isCurrent) 5f else 2.5f),
                    )
                }
            }

            // One invisible semantic node per playable block, in BOTH
            // TalkBack states -- only the tap recognizer above is
            // conditional -- because TalkBack's own explore-by-touch and
            // linear swipe navigation both depend on these nodes existing
            // whether or not this screen's own gesture is installed.
            val density = LocalDensity.current
            interactiveBlocks.forEach { b ->
                val r = toView(b.bbox, page.pageWidth, page.pageHeight, viewSize, zoom, pan)
                Box(
                    modifier = Modifier
                        .offset(x = with(density) { r.left.toDp() }, y = with(density) { r.top.toDp() })
                        .size(width = with(density) { r.width.toDp() }, height = with(density) { r.height.toDp() })
                        .semantics {
                            contentDescription = BlockLabels.title(b)
                            traversalIndex = b.order.toFloat()
                            if (b.kind == BlockKind.HEADING) heading()
                            customActions = listOf(
                                CustomAccessibilityAction("Read from here") { onReadFrom(b); true }
                            )
                        },
                )
            }
        }
    }
}

/** Slow, subtle alpha oscillation for the currently-playing block's fill. */
@Composable
private fun pulseAlpha(): Float {
    val infinite = rememberInfiniteTransition(label = "currentBlockPulse")
    val value by infinite.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    return value
}

/**
 * Text-list fallback: the old `ReadingScreen.BlockRow`, moved over intact.
 * For anyone who wants the OCR text itself, large and reflowed, rather than
 * as a photo -- low vision without enough acuity for a photographed page, or
 * a sighted helper who wants to read along quickly.
 */
@Composable
private fun TextListView(
    page: OcrPage,
    currentBlockId: Int?,
    onReadFrom: (TextBlock) -> Unit,
) {
    val blocks = BlockLabels.playbackBlocks(page)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(blocks, key = { it.id }) { block ->
            BlockRow(
                block = block,
                isCurrent = block.id == currentBlockId,
                onReadFrom = { onReadFrom(block) },
            )
        }
    }
}

@Composable
private fun BlockRow(
    block: TextBlock,
    isCurrent: Boolean,
    onReadFrom: () -> Unit,
) {
    val label = BlockLabels.title(block)
    val spoken = if (block.text.isNotBlank()) block.text else label

    Text(
        text = spoken,
        style = if (block.kind == BlockKind.HEADING) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.bodyLarge
        },
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .semantics {
                traversalIndex = block.order.toFloat()
                if (block.kind == BlockKind.HEADING) heading()
                customActions = listOf(
                    CustomAccessibilityAction("Read from here") { onReadFrom(); true }
                )
            },
    )
}

/**
 * Buttons the user asked for, so the app is navigable without knowing any
 * gesture or being able to see a target -- large, labelled, and in the fixed
 * order a physical remote would use.
 */
@Composable
private fun ControlBar(
    isPlaying: Boolean,
    onPreviousBlock: () -> Unit,
    onTogglePlay: () -> Unit,
    onNextBlock: () -> Unit,
    onNewPage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPreviousBlock) { Text("Previous") }
            Button(
                onClick = onTogglePlay,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = onNextBlock) { Text("Next") }
            TextButton(onClick = onNewPage) { Text("New page") }
        }
    }
}

/** Corner radius for the translucent block overlays, in raw canvas pixels. */
private const val BLOCK_CORNER_PX = 14f

/** One full breathe cycle for the current-block pulse while playing. */
private const val PULSE_MS = 900
