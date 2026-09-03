package com.pagereader.android.detect

import org.opencv.core.Mat
import org.opencv.core.Rect

/**
 * Finds the page in a frame.
 *
 * Implementations are called on the camera analysis thread, one frame at a time.
 * They must not retain or release the incoming Mat.
 */
interface PageDetector {
    /**
     * @param roi optional restriction, in [bgr] coordinates. The neural stage
     *   passes its bounding box here so the colour stage solves the much easier
     *   local problem of finding corners inside a known page region.
     */
    fun detect(bgr: Mat, roi: Rect? = null): PageObservation
}
