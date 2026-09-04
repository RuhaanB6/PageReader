package com.pagereader.android.testutil

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import java.nio.ByteBuffer

/**
 * Minimal [ImageProxy] test double for a single-plane (JPEG) capture.
 *
 * There is no mocking library on this project's test classpath (see
 * `app/build.gradle.kts`), so exercising [com.pagereader.android.capture.StillCapture]
 * -- which only reads `planes.firstOrNull()` and `imageInfo.rotationDegrees` --
 * means implementing the handful of interface members it actually touches and
 * failing loudly on anything else this test never expected to be called.
 */
class FakeImageProxy(
    private val jpegBytes: ByteArray,
    private val rotationDegrees: Int,
) : ImageProxy {

    var closed = false
        private set

    override fun close() {
        closed = true
    }

    override fun getCropRect(): Rect = Rect(0, 0, 0, 0)
    override fun setCropRect(rect: Rect?) = Unit
    override fun getFormat(): Int = android.graphics.ImageFormat.JPEG
    override fun getHeight(): Int = 0
    override fun getWidth(): Int = 0

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
        object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = jpegBytes.size
            override fun getPixelStride(): Int = 1
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(jpegBytes)
        }
    )

    override fun getImageInfo(): ImageInfo = object : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
        override fun getTimestamp(): Long = 0L
        override fun getRotationDegrees(): Int = rotationDegrees
        override fun populateExifData(builder: ExifData.Builder) = Unit
    }

    @ExperimentalGetImage
    override fun getImage(): Image? = null
}
