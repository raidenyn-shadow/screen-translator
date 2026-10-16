package com.raidenyn.screentranslator.capture

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Bytes in one RGBA_8888 pixel. */
private const val RGBA_BYTES = 4

/**
 * Converts the RGBA_8888 plane of a captured frame to a software ARGB bitmap: reads start at
 * the buffer's current position without moving it, row padding is skipped, and channels are
 * reordered from RGBA bytes into Android's ARGB ints. Dimensions and buffer bounds are
 * checked in Long arithmetic before any allocation, so hostile geometry fails cheaply instead
 * of overflowing Int math on the way to an OutOfMemoryError.
 */
internal fun rgbaToBitmap(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int,
): Bitmap {
    require(width > 0) { "width must be positive, was $width" }
    require(height > 0) { "height must be positive, was $height" }
    require(pixelStride >= RGBA_BYTES) { "pixelStride must hold an RGBA pixel, was $pixelStride" }
    require(rowStride.toLong() >= width.toLong() * pixelStride) {
        "rowStride $rowStride is below the visible row length ${width.toLong() * pixelStride}"
    }
    // The last visible pixel must fit in the buffer; the bounds above keep every Int index
    // used below inside `remaining`, so the per-pixel Int arithmetic cannot overflow.
    val lastPixel = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
    require(lastPixel + RGBA_BYTES <= buffer.remaining().toLong()) {
        "buffer holds ${buffer.remaining()} readable bytes, needs ${lastPixel + RGBA_BYTES}"
    }

    val base = buffer.position()
    val pixels = IntArray(width * height)
    var index = 0
    for (row in 0 until height) {
        val rowStart = base + row * rowStride
        for (col in 0 until width) {
            val at = rowStart + col * pixelStride
            val r = buffer.get(at).toInt() and 0xFF
            val g = buffer.get(at + 1).toInt() and 0xFF
            val b = buffer.get(at + 2).toInt() and 0xFF
            val a = buffer.get(at + 3).toInt() and 0xFF
            pixels[index++] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}