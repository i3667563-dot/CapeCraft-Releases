package dev.ggtv.capecraft.api.config

import dev.ggtv.kjen.Value

/**
 * Текущий конфиг аддона: читает ключи из секции в `.kn` с fallback на
 * значения по умолчанию.
 *
 * Аддон получает экземпляр в [CapeAddon.registerAddonConfig] и хранит
 * как поле. Чтение — лёгкое (in-memory), без I/O.
 */
interface AddonConfig {
    /** Прочитать строковый ключ. */
    fun getString(key: String): String

    /** Прочитать целочисленный ключ. */
    fun getLong(key: String): Long

    /** Прочитать boolean ключ. */
    fun getBool(key: String): Boolean

    /** Прочитать float ключ. */
    fun getDouble(key: String): Double

    /** Прочитать сырое значение (для сложных типов). */
    fun getValue(key: String): Value?

    /** Все ключи, доступные в секции аддона. */
    fun keys(): Set<String>
}
