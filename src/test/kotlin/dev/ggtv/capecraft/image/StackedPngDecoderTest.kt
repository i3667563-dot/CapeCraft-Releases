package dev.ggtv.capecraft.image

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
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

        val image = StackedPngDecoder.decode(png(width, height, pixels))

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

        val image = StackedPngDecoder.decode(png(width, height, pixels))

        assertEquals(2, image.height)
        assertEquals(2, image.frameCount)
        assertEquals(width * 2, image.frames[0].pixels.size)
        assertEquals(width * 2, image.frames[1].pixels.size)
        assertTrue(image.frames[1].pixels.contentEquals(pixels.copyOfRange(10, 20)))
    }

    @Test
    fun `single frame remains static`() {
        val image = StackedPngDecoder.decode(png(4, 2, IntArray(8) { argb(255, 1, 2, 3) }))

        assertFalse(image.isAnimated)
        assertEquals(-1, image.loopCount)
        assertEquals(1, image.frameCount)
    }

    @Test
    fun `single frame drops trailing rows`() {
        val width = 4
        val pixels = IntArray(width * 3) { argb(255, it, 0, 0) }

        val image = StackedPngDecoder.decode(png(width, 3, pixels))

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
        val image = ImageDecoder.decode(png(4, 4, IntArray(16) { argb(255, 7, 8, 9) }))

        assertEquals(1, image.frameCount)
        assertEquals(4, image.height)
    }

    @Test
    fun `rejects apng input`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(
                png(4, 2, IntArray(8) { argb(255, 1, 2, 3) }, apng = true),
            )
        }

        assertTrue(error.message!!.contains("статичным"))
    }

    @Test
    fun `rejects width below two`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(png(1, 1, intArrayOf(argb(255, 1, 1, 1))))
        }

        assertTrue(error.message!!.contains("не меньше 2"))
    }

    @Test
    fun `rejects image shorter than one frame`() {
        val error = assertFailsWith<ImageDecodeException> {
            StackedPngDecoder.decode(png(4, 1, IntArray(4) { argb(255, 1, 1, 1) }))
        }

        assertTrue(error.message!!.contains("меньше высоты кадра"))
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun png(width: Int, height: Int, pixels: IntArray, apng: Boolean = false): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                raw.write((pixel ushr 16) and 0xFF)
                raw.write((pixel ushr 8) and 0xFF)
                raw.write(pixel and 0xFF)
                raw.write((pixel ushr 24) and 0xFF)
            }
        }
        val ihdr = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        )
        val chunks = mutableListOf(chunk("IHDR", ihdr))
        if (apng) {
            chunks += chunk("acTL", intBytes(1, 1))
            chunks += chunk("fcTL", concat(intBytes(0, width, height, 0, 0), byteArrayOf(0, 1, 1, 0, 0, 0)))
        }
        chunks += chunk("IDAT", deflate(raw.toByteArray()))
        chunks += chunk("IEND", byteArrayOf())
        return concat(SIGNATURE, *chunks.toTypedArray())
    }

    private fun intBytes(vararg values: Int): ByteArray {
        val output = ByteArray(values.size * 4)
        for (index in values.indices) {
            val value = values[index]
            val offset = index * 4
            output[offset] = (value ushr 24).toByte()
            output[offset + 1] = (value ushr 16).toByte()
            output[offset + 2] = (value ushr 8).toByte()
            output[offset + 3] = value.toByte()
        }
        return output
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            output.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val value = crc.value.toInt()
        return concat(
            byteArrayOf(
                (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(), data.size.toByte(),
            ),
            typeBytes,
            data,
            byteArrayOf(
                (value ushr 24).toByte(), (value ushr 16).toByte(),
                (value ushr 8).toByte(), value.toByte(),
            ),
        )
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        for (part in parts) output.write(part)
        return output.toByteArray()
    }

    private companion object {
        val SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
