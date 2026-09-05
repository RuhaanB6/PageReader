package com.pagereader.android.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.BlockLabels
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock

/**
 * Touch-explore: feel where things are on the page.
 *
 * Continuous reading is linear, and a page is not. This gives back the thing a
 * glance provides — that there is a headline up there, a figure bottom-left, a
 * sidebar down the right — by mapping the page onto the screen and letting the
 * user find regions with a finger.
 *
 * Two delivery paths over the same rectangles, because TalkBack changes what is
 * possible:
 *
 *  - **TalkBack off** — the finger is tracked directly. Crossing into a new
 *    region ticks haptically and speaks its *label only*, flushing the previous
 *    one. Speaking whole paragraphs while the finger moves would make the page
 *    impossible to survey.
 *  - **TalkBack on** — no touch handler at all, because TalkBack consumes
 *    single-finger touch and the two would fight. Each region becomes an
 *    invisible node with a description and a "Read this region" action, and
 *    TalkBack's own explore-by-touch does the work.
 *
 * Colour is for low-vision users and sighted helpers. Every colour also has a
 * spoken label, and colour is never the only channel.
 */
@Composable
fun ExploreScreen(
    page: OcrPage,
    talkBackEnabled: Boolean,
    onRegionEntered: (TextBlock) -> Unit,
    onReadRegion: (TextBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewSize by remember { mutableStateOf(Size.Zero) }
    val blocks = remember(page) { page.blocks.filter { it.kind != BlockKind.SEPARATOR } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (talkBackEnabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(page, viewSize) {
                        // Follows the finger for the whole gesture rather than
                        // sampling only where it landed. Surveying a page means
                        // sweeping across it, so the announcement has to fire on
                        // *crossing* into a region, not on touching down in one.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastId = hitTest(blocks, down.position, page, viewSize)
                                ?.also(onRegionEntered)?.id
                            var last = hitTest(blocks, down.position, page, viewSize)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val here = hitTest(blocks, change.position, page, viewSize)
                                if (here?.id != lastId) {
                                    lastId = here?.id
                                    last = here
                                    // Label only. Reading whole paragraphs while
                                    // the finger moves would make the page
                                    // impossible to survey.
                                    here?.let(onRegionEntered)
                                }
                            }
                            // Lifting reads the region in full.
                            last?.let(onReadRegion)
                        }
                    }
                }
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (viewSize.width <= 0f || viewSize.height <= 0f) return@Canvas
            val strokePx = with(density) { OUTLINE_DP.dp.toPx() }
            for (b in blocks) {
                val r = toView(b, page, size)
                drawRect(
                    color = colourFor(b.kind),
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = strokePx),
                )
            }
        }

        // TalkBack path: one invisible node per region, described and
        // actionable. Present only when TalkBack is on, so the two input
        // schemes never compete for the same touch.
        if (talkBackEnabled && viewSize.width > 0f) {
            val density = LocalDensity.current
            for (b in blocks) {
                val label = BlockLabels.title(b)
                // Each node sits over its own region. Laying them all out at
                // full size would stack them, and TalkBack's explore-by-touch
                // would report every region at every point on the screen --
                // which is worse than having no touch-explore at all.
                val r = toView(b, page, viewSize)
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { r.left.toDp() },
                            y = with(density) { r.top.toDp() },
                        )
                        .size(
                            width = with(density) { r.width.toDp() },
                            height = with(density) { r.height.toDp() },
                        )
                        .semantics {
                            contentDescription = label
                            traversalIndex = b.order.toFloat()
                            customActions = listOf(
                                CustomAccessibilityAction("Read this region") {
                                    onReadRegion(b); true
                                }
                            )
                        }
                )
            }
        }
    }
}

private data class ViewRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    operator fun contains(p: Offset) =
        p.x >= left && p.x <= left + width && p.y >= top && p.y <= top + height
}

/**
 * Maps a block's page rectangle onto the view.
 *
 * Letterboxed rather than stretched: the page keeps its aspect ratio, so what
 * the finger finds top-left really is top-left on the paper. Stretching would
 * make the spatial sense this screen exists to give subtly wrong.
 */
private fun toView(block: TextBlock, page: OcrPage, view: Size): ViewRect {
    if (page.pageWidth <= 0 || page.pageHeight <= 0) return ViewRect(0f, 0f, 0f, 0f)
    val scale = minOf(view.width / page.pageWidth, view.height / page.pageHeight)
    val offsetX = (view.width - page.pageWidth * scale) / 2f
    val offsetY = (view.height - page.pageHeight * scale) / 2f
    return ViewRect(
        left = offsetX + block.bbox.x * scale,
        top = offsetY + block.bbox.y * scale,
        width = block.bbox.width * scale,
        height = block.bbox.height * scale,
    )
}

/** Topmost region under [point], or null. */
private fun hitTest(
    blocks: List<TextBlock>,
    point: Offset,
    page: OcrPage,
    view: Size,
): TextBlock? {
    if (view.width <= 0f || view.height <= 0f) return null
    // Smallest match wins, so a caption inside a figure is reachable rather
    // than being swallowed by the larger region behind it.
    return blocks
        .filter { point in toView(it, page, view) }
        .minByOrNull { it.bbox.width.toLong() * it.bbox.height }
}

/**
 * Outline colour per kind.
 *
 * Chosen for contrast against a light page at WCAG AA, and paired one-to-one
 * with the spoken labels in [BlockLabels.title] -- a sighted helper and a
 * listener are told the same thing.
 */
private fun colourFor(kind: BlockKind): Color = when (kind) {
    BlockKind.HEADING -> Color(0xFF1B5E20)
    BlockKind.BODY -> Color(0xFF0D47A1)
    BlockKind.CAPTION -> Color(0xFF4A148C)
    BlockKind.SIDEBAR -> Color(0xFF880E4F)
    BlockKind.FIGURE -> Color(0xFFE65100)
    BlockKind.HEADER_FOOTER -> Color(0xFF424242)
    BlockKind.SEPARATOR -> Color(0xFF616161)
}

/** Minimum outline weight. Below 3 dp the regions are hard to see at all. */
private const val OUTLINE_DP = 3
