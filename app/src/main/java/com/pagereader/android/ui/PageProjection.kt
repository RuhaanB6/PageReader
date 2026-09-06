package com.pagereader.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.TextBlock
import org.opencv.core.Rect

/**
 * Page-space-G-to-screen mapping shared by `PageScreen`'s photo projection.
 *
 * Lifted from the old `ExploreScreen` (`toView` at :196, `hitTest` at :210,
 * `colourFor` at :231) rather than rewritten, because the mapping itself was
 * already correct -- letterboxed, not stretched, so a rectangle top-left on
 * the paper stays top-left on screen -- and `PageScreen` needs the exact same
 * guarantee for tap-to-jump to land on the block the user actually touched.
 *
 * The one addition is zoom/pan: `PageScreen`'s photo is a real camera photo
 * of a page, not a rendering, so a low-vision user zooming in to read small
 * print is the whole point of projecting the photo instead of just showing
 * OCR text. `ExploreScreen` never needed this because it had no photo to zoom.
 */
data class ViewRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    operator fun contains(p: Offset) =
        p.x >= left && p.x <= left + width && p.y >= top && p.y <= top + height
}

/**
 * The fit-center layout of a [pageWidth]x[pageHeight] page inside [view],
 * before any user zoom/pan is applied. Factored out so [toView] and
 * [fromView] apply the exact same base transform and cannot drift apart.
 */
private class PageFit(pageWidth: Int, pageHeight: Int, view: Size) {
    val scale: Float = if (pageWidth > 0 && pageHeight > 0) {
        minOf(view.width / pageWidth, view.height / pageHeight)
    } else 0f
    val offsetX: Float = (view.width - pageWidth * scale) / 2f
    val offsetY: Float = (view.height - pageHeight * scale) / 2f
}

/**
 * Maps a page-space-G rectangle onto the view, at a given [zoom] (1x-4x per
 * `PageScreen`'s clamp) and [pan] (screen-pixel translation applied after the
 * base fit-center layout and after scaling by zoom, matching how
 * `Modifier.graphicsLayer`'s `scaleX/Y` + `translationX/Y` compose -- so the
 * math here agrees with whatever transform the zoom/pan gesture actually
 * applies to the photo it draws under).
 */
fun toView(
    bbox: Rect,
    pageWidth: Int,
    pageHeight: Int,
    view: Size,
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
): ViewRect {
    val fit = PageFit(pageWidth, pageHeight, view)
    val scale = fit.scale * zoom
    val offsetX = fit.offsetX * zoom + pan.x
    val offsetY = fit.offsetY * zoom + pan.y
    return ViewRect(
        left = offsetX + bbox.x * scale,
        top = offsetY + bbox.y * scale,
        width = bbox.width * scale,
        height = bbox.height * scale,
    )
}

/**
 * Inverse of [toView]. Rounds to the nearest pixel because [Rect] is integer
 * page-space, so a round trip through screen space cannot be exact to
 * sub-pixel precision -- `PageProjectionTest` asserts round-tripping within a
 * couple of pixels, not bit-for-bit.
 */
fun fromView(
    r: ViewRect,
    pageWidth: Int,
    pageHeight: Int,
    view: Size,
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
): Rect {
    val fit = PageFit(pageWidth, pageHeight, view)
    val scale = fit.scale * zoom
    if (scale <= 0f) return Rect(0, 0, 0, 0)
    val offsetX = fit.offsetX * zoom + pan.x
    val offsetY = fit.offsetY * zoom + pan.y
    return Rect(
        Math.round((r.left - offsetX) / scale),
        Math.round((r.top - offsetY) / scale),
        Math.round(r.width / scale),
        Math.round(r.height / scale),
    )
}

/**
 * Topmost region under [point], or null.
 *
 * Smallest-area-wins, unchanged from `ExploreScreen`: a caption inside a
 * figure has to be reachable rather than swallowed by the larger region
 * behind it, which a first-match or largest-match rule would do wrong.
 */
fun hitTest(
    blocks: List<TextBlock>,
    point: Offset,
    pageWidth: Int,
    pageHeight: Int,
    view: Size,
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
): TextBlock? {
    if (view.width <= 0f || view.height <= 0f) return null
    return blocks
        .filter { point in toView(it.bbox, pageWidth, pageHeight, view, zoom, pan) }
        .minByOrNull { it.bbox.width.toLong() * it.bbox.height }
}

/**
 * Colour per kind, reused verbatim from `ExploreScreen.colourFor`: already
 * WCAG-AA against [com.pagereader.android.ui.theme.Paper] and already paired
 * one-to-one with the spoken labels in `BlockLabels.title`. `PageScreen`
 * restyles *how* these are drawn -- translucent rounded fills instead of a
 * hard 3dp stroke -- never this mapping.
 */
fun colourFor(kind: BlockKind): Color = when (kind) {
    BlockKind.HEADING -> com.pagereader.android.ui.theme.BlockHeading
    BlockKind.BODY -> com.pagereader.android.ui.theme.BlockBody
    BlockKind.CAPTION -> com.pagereader.android.ui.theme.BlockCaption
    BlockKind.SIDEBAR -> com.pagereader.android.ui.theme.BlockSidebar
    BlockKind.FIGURE -> com.pagereader.android.ui.theme.BlockFigure
    BlockKind.HEADER_FOOTER -> com.pagereader.android.ui.theme.BlockHeaderFooter
    BlockKind.SEPARATOR -> com.pagereader.android.ui.theme.BlockSeparator
}
