package dev.ggtv.capecraft.image

/**
 * Stacked PNG: несколько кадров, сложенных в одну картинку друг под другом.
 *
 * Формат объявляет себя сам — служебным чанком
 * `Description Stacked PNG: 4 frames 256x128, order=down`, который читает
 * [PngMetadata]. Без такого описания разбиение угадывается по высоте
 * (кадр = половина ширины) — так работал мод до того, как формат стал
 * различаться, и обычный плащ 256x512 превращался в «анимацию» из
 * четырёх растянутых кусков.
 */
object StackedPngDecoder {
    const val FRAME_DURATION_MS = 100

    fun decode(data: ByteArray, source: String? = null): AnimatedImage {
        val decoded = PngDecoder.decode(data, source)
        if (decoded.isAnimated || decoded.loopCount != -1) {
            throw ImageDecodeException("stacked PNG должен быть статичным", source)
        }

        val meta = PngMetadata.stacked(data)
        if (meta != null && meta.order != "down") {
            throw ImageDecodeException(
                "порядок кадров «${meta.order}» не поддержан, умею только «down»",
                source,
            )
        }

        val frameHeight = meta?.frameHeight?.takeIf { it > 0 } ?: (decoded.width / 2)
        if (frameHeight <= 0) {
            throw ImageDecodeException("ширина stacked PNG должна быть не меньше 2", source)
        }
        // Сколько кадров реально помещается по высоте.
        val fits = decoded.height / frameHeight
        if (fits <= 0) {
            throw ImageDecodeException("высота stacked PNG меньше высоты кадра", source)
        }
        val frameCount = meta?.frameCount?.takeIf { it > 0 } ?: fits
        // Заявленное автором число кадров обязано помещаться, иначе это битый
        // файл, а не повод молча обрезать анимацию.
        if (frameCount > fits) {
            throw ImageDecodeException(
                "заявлено кадров: $frameCount, а по высоте помещается: $fits",
                source,
            )
        }
        // Неполный последний кадр допускаем только когда высоту кадра мы
        // угадали сами: объявленную автором геометрию неверной быть не может.
        if ((meta?.frameHeight ?: 0) > 0 && decoded.height % frameHeight != 0) {
            throw ImageDecodeException(
                "высота ${decoded.height} не делится на объявленную высоту кадра $frameHeight",
                source,
            )
        }

        val sourcePixels = decoded.frames[0].pixels
        val frames = ArrayList<Frame>(frameCount)
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * frameHeight * decoded.width
            val end = start + frameHeight * decoded.width
            frames += Frame(sourcePixels.copyOfRange(start, end), FRAME_DURATION_MS)
        }
        return AnimatedImage(decoded.width, frameHeight, frames)
    }
}
