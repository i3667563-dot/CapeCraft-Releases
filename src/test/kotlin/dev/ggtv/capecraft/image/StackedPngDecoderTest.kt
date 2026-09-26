package dev.ggtv.capecraft.image

import dev.ggtv.capecraft.image.PngFixture.argb
import dev.ggtv.capecraft.image.PngFixture.png
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackedPngDecoderTest {

    @Test
    fun `splits static png into vertical frames`() {
        val width = 4
        val frameHeight = 2
        val height = frameHeight * 3
        val pixels = IntArray(width * height) { y ->
            when (y / frameHeight) {
                0 -> argb(255, 255, 0, 0)
                1 -> argb(128, 0, 255, 0)
                else -> argb(0, 0, 0, 255)
            }
        }

        val image = StackedPngDecoder.decode(PngFixture.png(width, height, pixels))

        assertEquals(width, image.width)
        assertEquals(frameHeight, image.height)
        assertEquals(3, image.frameCount)
        assertTrue(image.isAnimated)
        assertEquals(0, image.loopCount)
        assertTrue(image.frames.all { it.durationMs == 100 })
        assertTrue(image.frames[0].pixels.contentEquals(pixels.copyOfRange(0, width * frameHeight)))
        assertTrue(image.frames[1].pixels.contentEquals(pixels.copyOfRange(width * frameHeight, width * frameHeight * 2)))
        assertTrue(image.frames[2].pixels.contentEquals(pixels.copyOfRange(width * frameHeight * 2, pixels.size)))
    }

    @Test
    fun `uses integer frame height and drops trailing rows`() {
        val width = 5
        val height = 5
        val pixels = IntArray(width * height) { argb(255, it, 0, 0) }

        val image = StackedPngDecoder.decode(PngFixture.png(width, height, pixels))

        assertEquals(2, image.height)
        assertEquals(2, image.frameCount)
        assertEquals(width * 2, image.frames[0].pixels.size)
        assertEquals(width * 2, image.frames[1].pixels.size)
        assertTrue(image.frames[1].pixels.contentEquals(pixels.copyOfRange(10, 20)))
    }

    @Test
    fun `single frame remains static`() {
        val image = StackedPngDecoder.decode(PngFixture.png(4, 2, IntArray(8) { argb(255, 1, 2, 3) }))

        assertFalse(image.isAnimated)
        assertEquals(-1, image.loopCount)
        assertEquals(1, image.frameCount)
    }

    @Test
    fun `single frame drops trailing rows`() {
        val width = 4
        val pixels = IntArray(width * 3) { argb(255, it, 0, 0) }

        val image = StackedPngDecoder.decode(PngFixture.png(width, 3, pixels))

        assertEquals(1, image.frameCount)
        assertFalse(image.isAnimated)
        assertTrue(image.frames[0].pixels.contentEquals(pixels.copyOfRange(0, width * 2)))
    }

    @Test
    fun `explicit format routes through image decoder`() {
        val bytes = png(4, 4, IntArray(16) { argb(255, 7, 8, 9) })

        assertEquals(ImageFormat.PNG, ImageFormat.detect(bytes))
        val image = ImageDecoder.decode(bytes, source = "stacked", format = ImageFormat.STACKED_PNG)

        assertEquals(2, image.frameCount)
        assertEquals(2, image.height)
    }

    @Test
    fun `autodetect leaves stacked shaped png as regular png`() {
        val image = ImageDecoder.decode(PngFixture.png(4, 4, IntArray(16) { argb(255, 7, 8, 9) }))

        assertEquals(1, image.frameCount)
        assertEquals(4, image.height)
    }

    @Test
    fun `rejects apng input`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(
                PngFixture.png(4, 2, IntArray(8) { argb(255, 1, 2, 3) }, apng = true),
            )
        }

        assertTrue(error.message!!.contains("статичным"))
    }

    @Test
    fun `rejects width below two`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(PngFixture.png(1, 1, intArrayOf(argb(255, 1, 1, 1))))
        }

        assertTrue(error.message!!.contains("не меньше 2"))
    }

    @Test
    fun `rejects image shorter than one frame`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(PngFixture.png(4, 1, IntArray(4) { argb(255, 1, 1, 1) }))
        }

        assertTrue(error.message!!.contains("меньше высоты кадра"))
    }

}
