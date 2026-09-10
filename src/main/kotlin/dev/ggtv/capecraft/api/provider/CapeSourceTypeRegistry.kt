package dev.ggtv.capecraft.api.provider

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.capecraft.api.CapeSourceType

/**
 * Реестр типов провайдеров, добавляемых аддонами.
 *
 * Аддон регистрирует новый `type = "..."` для `.kn`: при загрузке конфига
 * [dev.ggtv.capecraft.provider.ProviderLoader] находит неизвестный встроенный
 * тип и спрашивает этот реестр. Зарегистрированная [CapeSourceType.source]
 * строит [CapeSource], который добывает байты плаща. Если аддон не ответил —
 * ошибка конфига (как сейчас для неизвестного типа).
 */
class CapeSourceTypeRegistry {
    private val types = LinkedHashMap<String, CapeSourceType>()

    /** Зарегистрировать тип провайдера (повторная регистрация перезаписывает). */
    @Synchronized
    fun register(type: CapeSourceType) {
        types[type.id] = type
    }

    /** Найти тип; null — не аддон-тип. */
    @Synchronized
    operator fun get(id: String): CapeSourceType? = types[id]

    @Synchronized
    fun ids(): List<String> = types.keys.toList()

    /** Проверка совместимости аддона/клиента. */
    fun checkCompatibility(): String {
        return "аддон-типов ${types.size}, API $CAPE_RUNTIME_API_VERSION"
    }
}