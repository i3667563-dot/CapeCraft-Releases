package dev.ggtv.capecraft.api.provider

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

    /**
     * Проверить совместимость аддона с этой сборкой мода.
     *
     * Возвращает описание состояния, а не «здоровье» в булевом виде: вызывающий
     * сам решает, что делать со строкой (в `debug`-лог это уходит как есть).
     * Проверка настоящая: сверяет [CAPE_RUNTIME_API_VERSION] с [SUPPORTED_API_VERSIONS]
     * и, если версия не поддерживается, первым делом называет расхождение —
     * иначе аддон увидит красивую строку «всё хорошо» и сломается позже, на
     * вызове отсутствующего метода.
     */
    @Synchronized
    fun checkCompatibility(): String {
        val count = types.size
        if (CAPE_RUNTIME_API_VERSION !in SUPPORTED_API_VERSIONS) {
            return "НЕСОВМЕСТИМО: аддон-типов $count, а сборка мода имеет API " +
                "$CAPE_RUNTIME_API_VERSION, поддерживаются $SUPPORTED_API_VERSIONS"
        }
        return "совместимо: аддон-типов $count, API $CAPE_RUNTIME_API_VERSION"
    }

    companion object {
        /** Версии runtime-API, с которыми совместим этот мод. */
        val SUPPORTED_API_VERSIONS: Set<Int> = setOf(CAPE_RUNTIME_API_VERSION)
    }
}