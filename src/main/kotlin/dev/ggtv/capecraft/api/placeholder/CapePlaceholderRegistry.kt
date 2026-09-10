package dev.ggtv.capecraft.api.placeholder

import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.capecraft.api.CapePlaceholderSpec

/**
 * Реестр плейсхолдеров, добавляемых аддонами.
 *
 * Аддон регистрирует свой `{placeholder}`: при сборке шаблона URL/пути
 * [dev.ggtv.capecraft.schema.Placeholders] сначала смотрит встроенные
 * (username/uuid/name/root), затем этот реестр. Имя не должно содержать
 * скобок; повторная регистрация перезаписывает.
 */
class CapePlaceholderRegistry {
    private val resolvers = LinkedHashMap<String, CapePlaceholderSpec>()

    @Synchronized
    fun register(spec: CapePlaceholderSpec) {
        require(spec.name.isNotBlank()) { "имя плейсхолдера не может быть пустым" }
        require(!spec.name.contains('{') && !spec.name.contains('}')) {
            "имя плейсхолдера не может содержать скобки: «${spec.name}»"
        }
        resolvers[spec.name] = spec
    }

    /** Резолвер по имени (без фигурных скобок); null — не аддон-плейсхолдер. */
    @Synchronized
    operator fun get(name: String): CapePlaceholderSpec? = resolvers[name]

    @Synchronized
    fun names(): List<String> = resolvers.keys.toList()

    fun apiVersion(): Int = CAPE_RUNTIME_API_VERSION
}