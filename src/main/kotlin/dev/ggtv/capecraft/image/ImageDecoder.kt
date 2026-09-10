package dev.ggtv.capecraft.image

/**
 * Единая точка входа декодирования изображений.
 *
 * Формат определяется по сигнатуре (а не по расширению — капы качаются
 * с URL без расширений). Если формат известен заранее — его можно
 * передать явно ([format]), пропуская детект.
 *
 * Встроенные форматы (PNG/GIF/WebP) детектируются нативно; после них
 * пробуются аддон-декодеры, зарегистрированные через [dev.ggtv.capecraft.api.CapeApi.decoders].
 */
object ImageDecoder {

    fun decode(data: ByteArray, source: String? = null, format: ImageFormat? = null): AnimatedImage {
        // 1. Явно заданный формат (тесты, оптимизированный путь).
        if (format != null) return decodeBuiltin(data, source, format)
        // 2. Детект по встроенным сигнатурам.
        ImageFormat.detect(data)?.let { return decodeBuiltin(data, source, it) }
        // 3. Аддон-декодеры: через CapeApiHolder.registry .
        return dev.ggtv.capecraft.api.CapeApiHolder.decode(data, source)
    }

    private fun decodeBuiltin(data: ByteArray, source: String?, format: ImageFormat): AnimatedImage =
        when (format) {
            ImageFormat.PNG -> PngDecoder.decode(data, source)
            ImageFormat.GIF -> GifDecoder.decode(data, source)
            ImageFormat.WEBP -> WebpDecoder.decode(data, source)
        }
}