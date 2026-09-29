package dev.ggtv.capecraft.api.config

import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig

/**
 * Реестр конфигов аддонов: хранит схемы и выдаёт [AddonConfig] после загрузки.
 *
 * **Значения публикуются снимком, а не правкой на месте.** Раньше [loadFrom]
 * делал `configs.clear()` и заново наполнял тот же `HashMap`, а [get] читал его
 * без синхронизации: между `clear()` и первым `put` читатель с другого потока
 * видел `null` для аддона, у которого конфиг только что загрузился, а на ARM
 * без happens-before — ещё и небезопасно опубликованный `HashMap`. Теперь
 * [loadFrom] собирает новый `LinkedHashMap` и присваивает его [configs] через
 * `@Volatile`: читатель видит либо прежний снимок целиком, либо новый целиком.
 */
class CapeAddonConfigRegistry {
    private val schemas = LinkedHashMap<String, CapeAddonConfig>()

    @Volatile
    private var configs: Map<String, AddonConfig> = emptyMap()

    /** Зарегистрировать схему конфига аддона. */
    @Synchronized
    fun register(schema: CapeAddonConfig) {
        schemas[schema.addonId] = schema
    }

    /**
     * Прочитать значения аддонов из загруженного конфига.
     * Вызывается [dev.ggtv.capecraft.CapeConfig.reload] после парсинга `.kn`.
     * Пропускает аддонов, чья секция отсутствует в конфиге (используются defaults).
     */
    @Synchronized
    fun loadFrom(cfg: KorenConfig) {
        val next = LinkedHashMap<String, AddonConfig>(schemas.size)
        for ((id, schema) in schemas) {
            val section = try {
                val block = cfg.getBlock(schema.sectionPath)
                block.entries.associate { it.key to it.value }
            } catch (_: Exception) {
                emptyMap()
            }
            next[id] = MapAddonConfig(schema, section)
        }
        configs = next
    }

    /** Получить конфиг аддона по ID (null, если аддон не зарегистрировал схему). */
    operator fun get(addonId: String): AddonConfig? = configs[addonId]

    /** Все зарегистрированные ID. */
    @Synchronized
    fun ids(): List<String> = schemas.keys.toList()
}

/** Реализация [AddonConfig] поверх словаря из `.kn` + defaults из схемы. */
private class MapAddonConfig(
    private val schema: CapeAddonConfig,
    private val section: Map<String, Value>,
) : AddonConfig {

    override fun getString(key: String): String = when (val v = getValue(key)) {
        is Value.VStr -> v.s
        else -> (schema.defaults[key] as? Value.VStr)?.s ?: ""
    }

    override fun getLong(key: String): Long = when (val v = getValue(key)) {
        is Value.VInt -> v.i
        else -> (schema.defaults[key] as? Value.VInt)?.i ?: 0L
    }

    override fun getBool(key: String): Boolean = when (val v = getValue(key)) {
        is Value.VBool -> v.b
        else -> (schema.defaults[key] as? Value.VBool)?.b ?: false
    }

    override fun getDouble(key: String): Double = when (val v = getValue(key)) {
        is Value.VFloat -> v.f
        is Value.VInt -> v.i.toDouble()
        else -> (schema.defaults[key] as? Value.VFloat)?.f
            ?: (schema.defaults[key] as? Value.VInt)?.i?.toDouble() ?: 0.0
    }

    override fun getValue(key: String): Value? = section[key] ?: schema.defaults[key]

    override fun keys(): Set<String> = schema.defaults.keys + section.keys
}
