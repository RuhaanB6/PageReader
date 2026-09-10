package com.pagereader.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.opencv.core.Rect

class PageProjectionTest {

    private val pageWidth = 1000
    private val pageHeight = 1400
    private val view = Size(500f, 1000f)

    @Test
    fun `toView round-trips a bbox at 1x`() {
        val bbox = Rect(100, 200, 300, 150)
        val viewRect = toView(bbox, pageWidth, pageHeight, view)
        val back = fromView(viewRect, pageWidth, pageHeight, view)
        assertEquals(bbox.x, back.x)
        assertEquals(bbox.y, back.y)
        assertEquals(bbox.width, back.width)
        assertEquals(bbox.height, back.height)
    }

    @Test
    fun `toView round-trips a bbox at 3x zoom with pan`() {
        val bbox = Rect(50, 900, 400, 120)
        val zoom = 3f
        val pan = Offset(-40f, 15f)
        val viewRect = toView(bbox, pageWidth, pageHeight, view, zoom, pan)
        val back = fromView(viewRect, pageWidth, pageHeight, view, zoom, pan)
        // Rounding to the nearest page pixel through a 3x scale can be off by
        // a pixel or two; a couple of pixels of page space is invisible on
        // screen at this zoom, so a tight tolerance rather than exact equality.
        assertEquals(bbox.x.toFloat(), back.x.toFloat(), 2f)
        assertEquals(bbox.y.toFloat(), back.y.toFloat(), 2f)
        assertEquals(bbox.width.toFloat(), back.width.toFloat(), 2f)
        assertEquals(bbox.height.toFloat(), back.height.toFloat(), 2f)
    }

    private fun block(id: Int, order: Int, bbox: Rect, kind: BlockKind = BlockKind.BODY) =
        TextBlock(
            id = id,
            order = order,
            text = "text $id",
            bbox = bbox,
            confidence = 0.9f,
            kind = kind,
            medianWordHeight = 20f,
        )

    /** A large block with a small one nested inside it, e.g. a figure with a caption. */
    private val outer = block(1, 0, Rect(0, 0, 1000, 1000), BlockKind.FIGURE)
    private val inner = block(2, 1, Rect(400, 400, 100, 100), BlockKind.CAPTION)
    private val blocks = listOf(outer, inner)

    @Test
    fun `hitTest picks the smallest containing block at 1x`() {
        val innerCenter = toView(inner.bbox, pageWidth, pageHeight, view)
        val point = Offset(innerCenter.left + innerCenter.width / 2, innerCenter.top + innerCenter.height / 2)
        val hit = hitTest(blocks, point, pageWidth, pageHeight, view)
        assertNotNull(hit)
        assertEquals(inner.id, hit!!.id)
    }

    @Test
    fun `hitTest picks the smallest containing block at 3x zoom`() {
        val zoom = 3f
        val pan = Offset(-200f, -300f)
        val innerRect = toView(inner.bbox, pageWidth, pageHeight, view, zoom, pan)
        val point = Offset(innerRect.left + innerRect.width / 2, innerRect.top + innerRect.height / 2)
        val hit = hitTest(blocks, point, pageWidth, pageHeight, view, zoom, pan)
        assertNotNull(hit)
        assertEquals(inner.id, hit!!.id)
    }

    @Test
    fun `hitTest falls back to the larger block outside the nested region`() {
        // Derived from the projected rect, not written as a raw screen point:
        // the page is letterboxed inside the view, so a literal offset near
        // the screen origin lands in the margin and hits no block at all.
        val outerRect = toView(outer.bbox, pageWidth, pageHeight, view)
        val outerOnlyPoint = Offset(
            outerRect.left + outerRect.width * 0.02f,
            outerRect.top + outerRect.height * 0.02f,
        )
        val hit = hitTest(blocks, outerOnlyPoint, pageWidth, pageHeight, view)
        assertNotNull(hit)
        assertEquals(outer.id, hit!!.id)
    }
}
