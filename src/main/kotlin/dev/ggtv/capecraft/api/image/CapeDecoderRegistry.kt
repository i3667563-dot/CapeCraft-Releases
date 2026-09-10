package dev.ggtv.capecraft.api.image

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.capecraft.api.CapeDecoderSpec
import dev.ggtv.capecraft.image.AnimatedImage

/**
 * Декодер изображения: байты → [AnimatedImage]. Бросает исключение
 * ([dev.ggtv.capecraft.image.ImageDecodeException]) если байты не распознаны/битые.
 */
fun interface CapeDecoder {
    fun decode(data: ByteArray, source: String?): AnimatedImage
}

/**
 * Реестр декодеров изображений (по сигнатуре байтов, не по расширению).
 *
 * Аддон добавляет поддержку нового формата: [CapeDecoderSpec.detect] должен
 * быстро и надёжно определить «мои ли это байты», а [CapeDecoderSpec.decoder]
 * вернуть кадры. Встроенные форматы (PNG/GIF/WebP) регистрируются модом
 * первыми; аддон-декодеры пробуются после них, в порядке регистрации.
 */
class CapeDecoderRegistry {
    private val decoders = ArrayList<CapeDecoderSpec>()

    @Synchronized
    fun register(spec: CapeDecoderSpec) {
        decoders += spec
    }

    /** Байты → декодер, чья сигнатура распознала их; null — никто не узнал. */
    @Synchronized
    fun decoderFor(data: ByteArray): CapeDecoderSpec? =
        decoders.firstOrNull { runCatching { it.detect(data) }.getOrDefault(false) }

    @Synchronized
    fun ids(): List<String> = decoders.map { it.id }

    fun apiVersion(): Int = CAPE_RUNTIME_API_VERSION
}