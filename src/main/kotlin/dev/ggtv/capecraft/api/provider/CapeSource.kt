package dev.ggtv.capecraft.api.provider

/** Канонические значения, которые аддон-провайдер видит из записи конфига. */
data class CapeValues(
    val name: String,
    val type: String,
    val entries: Map<String, Any>,
)

/**
 * «Источник» аддон-провайдера: по [CapeValues] в [fetch] возвращает байты
 * плаща либо бросает исключение (значит — провайдер не сработал, fallback
 * пробует следующего). Аналог внутреннего [dev.ggtv.capecraft.provider.CapeFetcher],
 * но без типа [Resolved] — аддон сам решает, как взять байты.
 */
fun interface CapeSource {
    fun fetch(values: CapeValues): ByteArray
}