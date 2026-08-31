package dev.ggtv.capecraft.image

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты WebP-декодера. Фикстуры сгенерированы Pillow (lossless VP8L) и лежат
 * в src/test/resources/webp/. Эталонное изображение — рядом PNG, сверяем пиксели.
 */
class WebpDecoderTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun enableDebug() {
            System.setProperty("webp.debug", "1")
        }
    }

    private fun resource(name: String): ByteArray {
        val stream: InputStream = javaClass.getResourceAsStream("/webp/$name")
            ?: error("нет фикстуры /webp/$name")
        return stream.use { it.readBytes() }
    }

    /** Декодировать любой формат через единую точку входа. */
    private fun decodeWebP(name: String): AnimatedImage =
        ImageDecoder.decode(resource(name), source = name)

    /** Эталонный ARGB-массив из PNG-фикстуры. */
    private fun refPng(name: String): IntArray {
        val img = PngDecoder.decode(resource(name), source = name)
        return img.frames[0].pixels
    }

    private fun assertEqualsNonzeroAlpha(expected: AnimatedImage, actual: AnimatedImage, label: String) {
        assertEquals(expected.width, actual.width, "$label: width")
        assertEquals(expected.height, actual.height, "$label: height")
        assertEquals(expected.frames.size, actual.frames.size, "$label: frames")
    }

    @Test
    fun `серый непрозрачный градиент RGB`() {
        val img = decodeWebP("solid_rgb.webp")
        val ref = refPng("solid_rgb_ref.png")
        assertEquals(32, img.width)
        assertEquals(64, img.height)
        assertEquals(1, img.frames.size)
        val px = img.frames[0].pixels
        assertEquals(ref.size, px.size)
        for (i in ref.indices) {
            assertEquals(ref[i], px[i], "пиксель $i")
        }
    }

    @Test
    fun `RGBA с прозрачностью`() {
        val img = decodeWebP("rgba_alpha.webp")
        val ref = refPng("rgba_alpha_ref.png")
        assertEquals(32, img.width)
        assertEquals(64, img.height)
        val px = img.frames[0].pixels
        assertEquals(ref.size, px.size)
        for (i in ref.indices) {
            assertEquals(ref[i], px[i], "пиксель $i")
        }
    }

    @Test
    fun `палитра 256 цветов (color indexing)`() {
        val img = decodeWebP("palette_256.webp")
        val ref = refPng("palette_256_ref.png")
        assertEquals(1, img.frames.size)
        val px = img.frames[0].pixels
        assertEquals(ref.size, px.size)
        for (i in ref.indices) {
            assertEquals(ref[i], px[i], "пиксель $i")
        }
    }

    @Test
    fun `малая палитра с бандлингом (несколько пикселей в канале)`() {
        val img = decodeWebP("palette_small.webp")
        val ref = refPng("palette_small_ref.png")
        assertEquals(1, img.frames.size)
        val px = img.frames[0].pixels
        assertEquals(ref.size, px.size)
        for (i in ref.indices) {
            assertEquals(ref[i], px[i], "пиксель $i")
        }
    }

    @Test
    fun `анимация из нескольких кадров`() {
        val webp = ImageDecoder.decode(resource("anim.webp"), source = "anim.webp")
        assertTrue(webp.frames.size >= 2, "ожидалось несколько кадров, было ${webp.frames.size}")
        // хотя бы первый кадр должен совпасть с эталоном
        val ref = refPng("anim_frame0_ref.png")
        val frame0 = webp.frames[0].pixels
        assertEquals(ref.size, frame0.size)
        for (i in ref.indices) {
            assertEquals(ref[i], frame0[i], "пиксель $i кадра 0")
        }
    }

    @Test
    fun `чисто-белое изображение`() {
        val img = decodeWebP("white.webp")
        assertEquals(8, img.width)
        assertEquals(8, img.height)
        val px = img.frames[0].pixels
        for (i in px.indices) {
            assertEquals(0xFFFFFFFF.toInt(), px[i], "пиксель $i")
        }
    }

    @Test
    fun `детект формата по сигнатуре`() {
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(resource("solid_rgb.webp")))
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(resource("anim.webp")))
    }
}
