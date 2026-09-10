package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.image.CapeDecoder
import dev.ggtv.capecraft.api.placeholder.CapePlaceholder
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues

/**
 * Тип провайдера для конфига (значение `type = "..."`) и его логика получения
 * байтов. Аддон регистрирует через [dev.ggtv.capecraft.api.CapeApi.sourceTypes].
 */
data class CapeSourceType(
    val id: String,
    val source: (CapeValues) -> CapeSource,
)

/**
 * Тип декодера изображения: сигнатура определяет, какие байты декодирует аддон.
 * Аддон регистрирует через [dev.ggtv.capecraft.api.CapeApi.decoders].
 */
data class CapeDecoderSpec(
    val id: String,
    val detect: (ByteArray) -> Boolean,
    val decoder: CapeDecoder,
)

/** Тип плейсхолдера: имя в шаблоне (`{name}`) и резолвер.
 * Аддон регистрирует через [dev.ggtv.capecraft.api.CapeApi.placeholders]. */
data class CapePlaceholderSpec(
    val name: String,
    val resolver: CapePlaceholder,
)