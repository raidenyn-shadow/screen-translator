package com.raidenyn.screentranslator.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RgbaFrameConverterTest {

    /** One 0xAARRGGBB color as its four RGBA bytes. */
    private fun rgba(argb: Int): ByteArray = byteArrayOf(
        (argb ushr 16).toByte(),
        (argb ushr 8).toByte(),
        argb.toByte(),
        (argb ushr 24).toByte(),
    )

    /** Two 4-byte pixels plus 4 padding bytes: a 12-byte row for a 2-pixel-wide image. */
    private fun row(c0: Int, c1: Int): ByteArray =
        rgba(c0) + rgba(c1) + byteArrayOf(0x55, 0x55, 0x55, 0x55)

    @Test
    fun paddedRowsPreserveOnlyVisiblePixels() {
        val buffer = ByteBuffer.wrap(
            row(0xFF00FF00.toInt(), 0xFF0000FF.toInt()) +
                row(0xFFFF0000.toInt(), 0xFFFFFF00.toInt()),
        )

        val bitmap = rgbaToBitmap(buffer, width = 2, height = 2, pixelStride = 4, rowStride = 12)

        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(0xFF00FF00.toInt(), bitmap.getPixel(0, 0))
        assertEquals(0xFF0000FF.toInt(), bitmap.getPixel(1, 0))
        assertEquals(0xFFFF0000.toInt(), bitmap.getPixel(0, 1))
        assertEquals(0xFFFFFF00.toInt(), bitmap.getPixel(1, 1))
        assertEquals(0, buffer.position())   // the caller's buffer position is left untouched
    }

    @Test
    fun nonzeroBufferPositionIsRespected() {
        // Two frames in one buffer; position 24 is where the second frame starts.
        val buffer = ByteBuffer.wrap(
            row(0xFF010203.toInt(), 0xFF040506.toInt()) +
                row(0xFF050607.toInt(), 0xFF08090A.toInt()) +
                row(0xFF102030.toInt(), 0xFF405060.toInt()) +
                row(0xFF708090.toInt(), 0xFFA0B0C0.toInt()),
        ).apply { position(24) }

        val bitmap = rgbaToBitmap(buffer, 2, 2, 4, 12)

        assertEquals(0xFF102030.toInt(), bitmap.getPixel(0, 0))
        assertEquals(0xFF405060.toInt(), bitmap.getPixel(1, 0))
        assertEquals(0xFF708090.toInt(), bitmap.getPixel(0, 1))
        assertEquals(0xFFA0B0C0.toInt(), bitmap.getPixel(1, 1))
        assertEquals(24, buffer.position())
    }

    @Test
    fun invalidGeometryFailsBeforeAllocation() {
        val valid = ByteBuffer.wrap(
            row(0xFF111111.toInt(), 0xFF222222.toInt()) +
                row(0xFF333333.toInt(), 0xFF444444.toInt()),
        )

        assertThrows(IllegalArgumentException::class.java) { rgbaToBitmap(valid, 0, 2, 4, 12) }   // zero width
        assertThrows(IllegalArgumentException::class.java) { rgbaToBitmap(valid, 2, 0, 4, 12) }   // zero height
        assertThrows(IllegalArgumentException::class.java) { rgbaToBitmap(ByteBuffer.wrap(ByteArray(8)), 2, 2, 4, 12) }   // buffer shorter than the last pixel
        assertThrows(IllegalArgumentException::class.java) { rgbaToBitmap(valid, 2, 2, 4, 4) }    // row stride below the visible row length 8
    }
}