package dev.ggtv.capecraft.api.config

import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig

/**
 * Реестр конфигов аддонов: хранит схемы и выдаёт [AddonConfig] после загрузки.
 *
 * Потокобезопасен: схемы добавляются на старте (однопоточно), значения
 * читаются из любого потока (immutable).
 */
class CapeAddonConfigRegistry {
    private val schemas = LinkedHashMap<String, CapeAddonConfig>()
    private val configs = HashMap<String, AddonConfig>()

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
        configs.clear()
        for ((id, schema) in schemas) {
            val section = try {
                val block = cfg.getBlock(schema.sectionPath)
                block.entries.associate { it.key to it.value }
            } catch (_: Exception) {
                emptyMap()
            }
            configs[id] = MapAddonConfig(schema, section)
        }
    }

    /** Получить конфиг аддона по ID (null, если аддон не зарегистрировал схему). */
    operator fun get(addonId: String): AddonConfig? = configs[addonId]

    /** Все зарегистрированные ID. */
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
