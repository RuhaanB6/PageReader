package com.pagereader.android.ocr

import org.opencv.core.Mat

/**
 * Recognises text on a dewarped page.
 *
 * Mirrors `PageDetector`, and for the same reason: the recogniser is the part
 * of this pipeline most likely to be replaced (PP-OCR is the standing
 * candidate), and the reading UI above it should not have to notice.
 *
 * Implementations hold native memory and are not thread-safe -- one instance,
 * one background thread.
 */
interface PageOcr {
    /**
     * @param page the dewarped page in space **G**. Not released by the callee.
     */
    fun recognise(page: Mat): OcrPage

    /** Releases native resources. The instance is unusable afterwards. */
    fun close()
}
