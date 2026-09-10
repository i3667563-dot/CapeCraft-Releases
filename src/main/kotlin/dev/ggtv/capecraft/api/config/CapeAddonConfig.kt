package dev.ggtv.capecraft.api.config

import dev.ggtv.kjen.Value

/**
 * Схема конфига аддона: набор ключей со значениями по умолчанию.
 *
 * Аддон объявляет свою секцию в `.kn`:
 * ```
 * capeCraft {
 *     addons {
 *         my-addon {
 *             apiKey = "default"
 *             refreshMs = 30000
 *         }
 *     }
 * }
 * ```
 *
 * Путь к секции = `capeCraft.addons.<addonId>`. Ключи читаются через
 * [AddonConfig.get], значения по умолчанию используются при отсутствии
 * в конфиге.
 */
class CapeAddonConfig(
    /** Идентификатор аддона (совпадает с `id` в fabric.mod.json). */
    val addonId: String,
    /** Ключи со значениями по умолчанию. Тип значения определяет ожидаемый тип в конфиге. */
    val defaults: Map<String, Value>,
) {
    /** Путь к секции аддона в конфиге. */
    val sectionPath: String get() = "capeCraft.addons.$addonId"
}
