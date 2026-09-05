package com.pagereader.android.ocr

import org.opencv.core.Rect
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/**
 * Turns Tesseract's hOCR into [TextBlock]s.
 *
 * hOCR is used rather than the plain-text output because it is the only thing
 * that carries *structure* — where each block sits, how confident each word
 * was, and what kind of region Tesseract thought it was looking at. All three
 * are needed downstream: position for reading order, confidence for the
 * self-diagnostic that decides whether to retry rotated, and kind so headings
 * can be announced as headings.
 *
 * **Classification happens at block level, not line level.** Tesseract puts the
 * type on the *line* spans (`ocr_header`, `ocr_caption`, `ocr_textfloat`) while
 * the geometry that matters is the enclosing `ocr_carea`. A heading is usually
 * one header line inside a block whose other lines are ordinary, so classifying
 * per line would shatter one heading into fragments. Each block therefore takes
 * the kind of the most specific class any of its lines carries.
 *
 * Parsing is by [XmlPullParser], which is in the platform — no new dependency
 * for what is a well-formed XHTML document. The DOCTYPE references an external
 * DTD that is deliberately never fetched; Tesseract emits only the five
 * standard XML entities, so nothing is lost by ignoring it.
 */
object HocrParser {

    /**
     * @param reader hOCR document. Closed by the caller.
     * @param fallbackWidth used when the document carries no page bbox.
     */
    fun parse(reader: Reader, fallbackWidth: Int = 0, fallbackHeight: Int = 0): ParsedPage {
        val factory = XmlPullParserFactory.newInstance()
        // hOCR uses an xmlns, and the class/id/title attributes we read are
        // unprefixed either way, so namespace processing buys nothing and only
        // adds failure modes.
        factory.isNamespaceAware = false
        val xpp = factory.newPullParser()
        xpp.setInput(reader)

        var pageW = fallbackWidth
        var pageH = fallbackHeight
        val blocks = mutableListOf<TextBlock>()

        // State for the block currently open.
        var inBlock = false
        var blockBbox: Rect? = null
        var blockKind = BlockKind.BODY
        var blockSpecificity = -1
        val words = StringBuilder()
        var confSum = 0.0
        var confCount = 0
        val wordHeights = mutableListOf<Int>()
        var lineCount = 0

        // State for the word currently open, since the confidence lives on the
        // span's title and the text is its content.
        var inWord = false
        var pendingWordConf: Int? = null
        var pendingWordHeight: Int? = null

        fun closeBlock() {
            if (!inBlock) return
            val bbox = blockBbox
            if (bbox == null && words.isNotBlank()) {
                // A carea whose title attribute is malformed has nowhere to be
                // placed, so its text cannot be used -- but losing a paragraph
                // in silence is exactly what this pipeline must never do, so
                // it at least leaves a trace.
                android.util.Log.w(
                    "HocrParser",
                    "discarding ${words.length} chars: block had no usable bbox",
                )
            }
            if (bbox != null) {
                val text = words.toString().trim().replace(WHITESPACE, " ")
                blocks += TextBlock(
                    id = blocks.size,
                    // Document order for now. M6 replaces this with true
                    // reading order, which differs on any multi-column page.
                    order = blocks.size,
                    text = text,
                    bbox = bbox,
                    confidence = if (confCount == 0) 0f else (confSum / confCount / 100.0).toFloat(),
                    kind = blockKind,
                    medianWordHeight = median(wordHeights),
                    lineCount = lineCount,
                )
            }
            inBlock = false
            blockBbox = null
            blockKind = BlockKind.BODY
            blockSpecificity = -1
            words.setLength(0)
            confSum = 0.0
            confCount = 0
            wordHeights.clear()
            lineCount = 0
        }

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val cls = xpp.getAttributeValue(null, "class")
                    val title = xpp.getAttributeValue(null, "title")
                    when {
                        cls == "ocr_page" -> {
                            bboxOf(title)?.let { pageW = it.width; pageH = it.height }
                        }
                        // A separator or a figure is a block in its own right
                        // and has no lines inside it, so it is emitted whole.
                        cls == "ocr_separator" || cls == "ocr_photo" || cls == "ocr_image" -> {
                            closeBlock()
                            bboxOf(title)?.let {
                                blocks += TextBlock(
                                    id = blocks.size,
                                    order = blocks.size,
                                    text = "",
                                    bbox = it,
                                    confidence = 1f,
                                    kind = if (cls == "ocr_separator") {
                                        BlockKind.SEPARATOR
                                    } else {
                                        BlockKind.FIGURE
                                    },
                                    medianWordHeight = 0f,
                                )
                            }
                        }
                        cls == "ocr_carea" -> {
                            closeBlock()
                            blockBbox = bboxOf(title)
                            inBlock = blockBbox != null
                        }
                        cls == "ocr_line" -> lineCount++
                        cls != null && cls in LINE_KINDS -> {
                            lineCount++
                            // Most specific line class wins for the whole block.
                            val kind = LINE_KINDS.getValue(cls)
                            val specificity = SPECIFICITY.getValue(kind)
                            if (specificity > blockSpecificity) {
                                blockSpecificity = specificity
                                blockKind = kind
                            }
                        }
                        cls == "ocrx_word" -> {
                            inWord = true
                            pendingWordConf = wconfOf(title)
                            pendingWordHeight = bboxOf(title)?.height
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inWord) words.append(xpp.text)
                }
                XmlPullParser.END_TAG -> {
                    if (inWord) {
                        inWord = false
                        pendingWordConf?.let { confSum += it; confCount++ }
                        pendingWordHeight?.let { if (it > 0) wordHeights += it }
                        pendingWordConf = null
                        pendingWordHeight = null
                        words.append(' ')
                    }
                }
            }
            event = xpp.next()
        }
        closeBlock()

        val withWords = blocks.filter { it.text.isNotBlank() }
        val mean = if (withWords.isEmpty()) 0f else withWords.map { it.confidence }.average().toFloat()
        return ParsedPage(pageW, pageH, blocks, mean)
    }

    data class ParsedPage(
        val pageWidth: Int,
        val pageHeight: Int,
        val blocks: List<TextBlock>,
        val meanConfidence: Float,
    )

    /**
     * hOCR line classes that name a block type.
     *
     * `ocr_line` is the unmarked default and deliberately absent: a block whose
     * lines are all plain stays [BlockKind.BODY], which is the initial value.
     */
    private val LINE_KINDS = mapOf(
        "ocr_header" to BlockKind.HEADING,
        "ocr_caption" to BlockKind.CAPTION,
        "ocr_textfloat" to BlockKind.SIDEBAR,
    )

    /**
     * Which class wins when a block's lines disagree.
     *
     * Any marked class beats the unmarked default, and a heading beats the rest
     * because announcing a block as a heading when it opens one is more useful
     * than the alternative -- a missed heading costs the listener their place
     * in the document's structure.
     */
    private val SPECIFICITY = mapOf(
        BlockKind.BODY to 0,
        BlockKind.SIDEBAR to 1,
        BlockKind.CAPTION to 2,
        BlockKind.HEADING to 3,
    )

    private val WHITESPACE = Regex("\\s+")

    /** `bbox x0 y0 x1 y1` out of an hOCR title attribute. */
    private fun bboxOf(title: String?): Rect? {
        val m = title?.let { BBOX_RE.find(it) } ?: return null
        val (x0, y0, x1, y1) = m.destructured
        val left = x0.toInt()
        val top = y0.toInt()
        return Rect(left, top, x1.toInt() - left, y1.toInt() - top)
    }

    private fun wconfOf(title: String?): Int? =
        title?.let { WCONF_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val BBOX_RE = Regex("bbox (\\d+) (\\d+) (\\d+) (\\d+)")
    private val WCONF_RE = Regex("x_wconf (\\d+)")

    private fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].toFloat() else (s[mid - 1] + s[mid]) / 2f
    }
}
