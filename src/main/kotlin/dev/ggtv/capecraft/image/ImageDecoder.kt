package dev.ggtv.capecraft.image

/**
 * Единая точка входа декодирования изображений.
 *
 * Формат определяется по сигнатуре (а не по расширению — капы качаются
 * с URL без расширений). Если формат известен заранее — его можно
 * передать явно ([format]), пропуская детект.
 */
object ImageDecoder {

    fun decode(data: ByteArray, source: String? = null, format: ImageFormat? = null): AnimatedImage {
        val f = format ?: ImageFormat.detect(data)
            ?: throw ImageDecodeException("неизвестный формат изображения", source)
        return when (f) {
            ImageFormat.PNG -> PngDecoder.decode(data, source)
            ImageFormat.GIF -> GifDecoder.decode(data, source)
            ImageFormat.WEBP -> WebpDecoder.decode(data, source)
        }
    }
}