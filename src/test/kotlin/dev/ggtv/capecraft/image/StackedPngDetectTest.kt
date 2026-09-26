package dev.ggtv.capecraft.image

import dev.ggtv.capecraft.image.PngFixture.argb
import dev.ggtv.capecraft.image.PngFixture.png
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Регресс: stacked PNG приходил с сервера, но мод отдавал его как обычный PNG.
 *
 * `ImageFormat.detect` смотрел только на сигнатуру, а stacked PNG — это тот же
 * PNG. Файл разбирался как одна картинка 256×512 и натягивалась на плащ целиком
 * вместо четырёх кадров по 256×128.
 *
 * Различать форматы приходится по служебному чанку, который пишет сам сервис:
 *
 * ```
 * Description Stacked PNG: 4 frames 256x128, order=down
 * ```
 */
class StackedPngDetectTest {

    /** Ровно то описание, которое отдаёт skins.ggshnikk.online. */
    private val realDescription = listOf("Description" to "Stacked PNG: 4 frames 256x128, order=down")

    /** Кадр красится в свой цвет, чтобы кадры нельзя было спутать. */
    private fun frames(width: Int, frameHeight: Int, count: Int): IntArray {
        val colors = listOf(
            argb(255, 255, 0, 0), argb(255, 0, 255, 0),
            argb(255, 0, 0, 255), argb(255, 255, 255, 0),
        )
        return IntArray(width * frameHeight * count) { y ->
            colors[(y / frameHeight) % colors.size] + (y % 7)
        }
    }

    @Test
    @DisplayName("стековый PNG определяется по служебному чанку")
    fun stackedPngIsDetected() {
        val bytes = png(256, 512, frames(256, 128, 4), text = realDescription)

        assertEquals(ImageFormat.STACKED_PNG, ImageFormat.detect(bytes))
    }

    @Test
    @DisplayName("обычный PNG тех же размеров остаётся обычным")
    fun plainPngOfSameSizeStaysPng() {
        // Ровно те же 256×512, но без описания. Если бы детект смотрел на
        // высоту, обычный плащ превратился бы в анимацию из 4 кадров.
        val bytes = png(256, 512, frames(256, 128, 4))

        assertEquals(ImageFormat.PNG, ImageFormat.detect(bytes))
        val image = ImageDecoder.decode(bytes)
        assertEquals(1, image.frameCount)
        assertEquals(512, image.height)
    }

    @Test
    @DisplayName("чужой Description не превращает плащ в анимацию")
    fun unrelatedDescriptionIsNotStacked() {
        val bytes = png(
            64, 128, IntArray(64 * 128) { argb(255, 1, 2, 3) },
            text = listOf("Description" to "шляпа, 4 кадра по мотивам"),
        )

        assertEquals(ImageFormat.PNG, ImageFormat.detect(bytes))
        assertNull(PngMetadata.stacked(bytes))
    }

    @Test
    @DisplayName("ImageDecoder сам разбирает стек без явного формата — боевой путь")
    fun imageDecoderSplitsStackedAutomatically() {
        val width = 256
        val frameHeight = 128
        val pixels = frames(width, frameHeight, 4)
        val bytes = png(width, frameHeight * 4, pixels, text = realDescription)

        val image = ImageDecoder.decode(bytes)

        assertEquals(4, image.frameCount)
        assertEquals(width, image.width)
        assertEquals(frameHeight, image.height)
        assertTrue(image.isAnimated)
        // Кадры — куски исходной картинки, а не растянутые полосы.
        for (i in 0 until 4) {
            val from = i * frameHeight * width
            assertTrue(
                image.frames[i].pixels.contentEquals(pixels.copyOfRange(from, from + frameHeight * width)),
                "кадр $i должен совпадать со своим куском картинки",
            )
        }
    }

    @Test
    @DisplayName("кадры не растянуты: у всех один размер")
    fun framesAreNotStretched() {
        val image = ImageDecoder.decode(png(256, 512, frames(256, 128, 4), text = realDescription))

        assertTrue(image.frames.all { it.pixels.size == 256 * 128 })
        assertTrue(image.frames.all { it.durationMs == StackedPngDecoder.FRAME_DURATION_MS })
        // Пиксели соседних кадров разные — значит это не копии одной полосы.
        assertFalse(image.frames[0].pixels.contentEquals(image.frames[1].pixels))
    }

    @Test
    @DisplayName("метаданные читаются: размер, количество и порядок кадров")
    fun metadataIsParsed() {
        val meta = PngMetadata.stacked(png(256, 512, frames(256, 128, 4), text = realDescription))

        requireNotNull(meta)
        assertEquals(4, meta.frameCount)
        assertEquals(256, meta.frameWidth)
        assertEquals(128, meta.frameHeight)
        assertEquals("down", meta.order)
    }

    @Test
    @DisplayName("высота кадра берётся из метаданных, а не угадывается как ширина/2")
    fun declaredFrameHeightWins() {
        // 3 кадра по 64×64: ширина/2 дало бы 32 и обрезало бы половину кадра.
        val text = listOf("Description" to "Stacked PNG: 3 frames 64x64, order=down")
        val image = ImageDecoder.decode(png(64, 192, frames(64, 64, 3), text = text))

        assertEquals(3, image.frameCount)
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    @DisplayName("кадров больше, чем помещается — понятная ошибка, а не мусор")
    fun tooManyFramesFails() {
        val text = listOf("Description" to "Stacked PNG: 9 frames 256x128, order=down")
        val error = assertFailsWith<ImageDecodeException> {
            ImageDecoder.decode(png(256, 512, frames(256, 128, 4), text = text))
        }

        assertTrue(error.message!!.contains("заявлено кадров: 9"), error.message)
    }

    @Test
    @DisplayName("высота не делится на объявленный кадр — понятная ошибка")
    fun undeclaredGeometryFails() {
        val text = listOf("Description" to "Stacked PNG: 4 frames 100x100, order=down")
        val error = assertFailsWith<ImageDecodeException> {
            ImageDecoder.decode(
                png(100, 512, IntArray(100 * 512) { argb(255, it, 0, 0) }, text = text),
            )
        }

        assertTrue(error.message!!.contains("не делится"), error.message)
    }

    @Test
    @DisplayName("порядок кадров вбок не поддержан и не рисуется молча")
    fun unsupportedOrderFails() {
        val text = listOf("Description" to "Stacked PNG: 4 frames 256x128, order=right")
        val error = assertFailsWith<ImageDecodeException> {
            ImageDecoder.decode(png(256, 512, frames(256, 128, 4), text = text))
        }

        assertTrue(error.message!!.contains("right"), error.message)
    }

    @Test
    @DisplayName("описание в iTXt тоже читается")
    fun intlTextIsRead() {
        val bytes = png(
            64, 192, frames(64, 64, 3),
            intl = listOf("Description" to "Stacked PNG: 3 frames 64x64, order=down"),
        )

        assertEquals(ImageFormat.STACKED_PNG, ImageFormat.detect(bytes))
        assertEquals(3, ImageDecoder.decode(bytes).frameCount)
    }

    @Test
    @DisplayName("сжатый iTXt не читается и не превращает плащ в анимацию")
    fun compressedIntlIsIgnored() {
        val bytes = png(
            64, 128, IntArray(64 * 128) { argb(255, 4, 4, 4) },
            intlCompressed = listOf("Description" to "Stacked PNG: 2 frames 64x64, order=down"),
        )

        assertNull(PngMetadata.stacked(bytes))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(bytes))
    }

    @Test
    @DisplayName("битый служебный чанк не роняет детект")
    fun garbageMetadataDoesNotBreakDetect() {
        // Обрезанный tEXt, мусор в длине чанка, файл без IHDR.
        val truncated = png(8, 16, IntArray(128) { argb(255, 1, 1, 1) })
            .copyOfRange(0, 8 + 12 + 3)
        assertEquals(ImageFormat.PNG, ImageFormat.detect(truncated))

        val garbage = PngFixture.SIGNATURE + byteArrayOf(
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // длина-мусор
            'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(),
        )
        assertNull(PngMetadata.stacked(garbage))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(garbage))
    }

    @Test
    @DisplayName("GIF и WebP не ломаются от новой проверки")
    fun otherFormatsUnaffected() {
        assertNull(ImageFormat.detect(ByteArray(0)))
        assertNull(ImageFormat.detect(byteArrayOf(1, 2, 3)))
        assertEquals(ImageFormat.GIF, ImageFormat.detect("GIF89a".toByteArray(Charsets.US_ASCII)))
    }
}
