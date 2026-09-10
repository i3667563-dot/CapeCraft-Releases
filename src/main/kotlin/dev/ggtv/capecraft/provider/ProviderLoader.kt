package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues
import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig

/**
 * Загрузчик провайдеров из `.kn` (KoreN, надмножество `.crn`).
 *
 * Ожидает конфиг вида:
 * ```
 * capeCraft {
 *     providers [
 *         { name = "trusted", type = "url",  url = ".../{username}.png" }
 *         { name = "local",   type = "file", path = "{root}/capes/{uuid}.png" }
 *         { name = "api",     type = "json", url = "...", extract = "$.data.cape_url" }
 *         { name = "snow",    type = "url",  url = ".../snow.png",
 *           when: { biome.precipitation: "snow" }, priority = 20 } }
 *     ]
 * }
 * ```
 *
 * Пары словаря разделяются запятыми (как и элементы массива).
 *
 * Порядок выбора (см. [ProviderSelector.select]): сначала провайдеры, чьё
 * [Condition] выполняется на живом мире, по убыванию `priority` (при
 * равном приоритете — в порядке списка); затем провайдеры без условия
 * (default) в порядке списка. При ошибке пробуем следующего по этому
 * порядку. Ключевые имена вынесены в константы, чтобы сверять `.kn`
 * и код в одном месте.
 */
object ProviderLoader {
    const val ROOT = "capeCraft.providers"

    object Keys {
        const val NAME = "name"
        const val TYPE = "type"
        const val URL = "url"
        const val PATH = "path"
        const val EXTRACT = "extract"
        const val WHEN = "when"
        const val PRIORITY = "priority"
    }

    /** Типы провайдеров, как в `type = ...`. */
    object Types {
        const val URL = "url"
        const val FILE = "file"
        const val JSON = "json"
    }

    /** Собрать список провайдеров из конфига (в порядке появления, без I/O). */
    fun load(config: KorenConfig): List<Provider> {
        val configRoot = config.getArray(ROOT)
        val out = mutableListOf<Provider>()
        for (v in configRoot) {
            out += parseProvider(v)
        }
        return out
    }

    private fun parseProvider(v: Value): Provider {
        val dict = v as? Value.VDict
            ?: throw IllegalArgumentException("провайдер должен быть словарём {name, type, ...}, найдено «${v.kind}»")
        val kv = dict.pairs.toMap()
        val type = str(kv, Keys.TYPE) ?: throw IllegalArgumentException("у провайдера нет «type» (url|file|json|...)")
        val name = str(kv, Keys.NAME) ?: "provider-${type}"

        // 1. Встроенные типы (url/file/json).
        val builtSource: Source? = when (type) {
            Types.URL -> Source.Url(
                str(kv, Keys.URL) ?: throw IllegalArgumentException("провайдер «$name» типа url: нужен «url»"),
            )
            Types.FILE -> Source.File(
                str(kv, Keys.PATH) ?: throw IllegalArgumentException("провайдер «$name» типа file: нужен «path»"),
            )
            Types.JSON -> {
                val url = str(kv, Keys.URL)
                    ?: throw IllegalArgumentException("провайдер «$name» типа json: нужен «url»")
                val extract = str(kv, Keys.EXTRACT)
                    ?: throw IllegalArgumentException("провайдер «$name» типа json: нужен «extract» (например «$.data.cape_url»)")
                Source.Json(url, extract)
            }
            else -> null
        }
        // 2. Аддон-тип: ищем в реестре CapeApiHolder, строим CapeSource.
        val addonSource: CapeSource? = if (builtSource == null) {
            val typeSpec = CapeApiHolder.api.sourceTypes[type]
                ?: throw IllegalArgumentException("провайдер «$name»: неизвестный тип «$type» (ожидалось url|file|json или аддон-тип)")
            val capeValues = CapeValues(
                name = name,
                type = type,
                entries = kv.mapValues { (_, v) -> v.toAny() },
            )
            typeSpec.source(capeValues)
        } else null

        val condition = (kv[Keys.WHEN] as? Value.VDict)?.let { Condition.parse(it) }
        val priority = (kv[Keys.PRIORITY] as? Value.VInt)?.i?.toInt() ?: 0

        val capeValues = if (addonSource != null) CapeValues(
            name = name,
            type = type,
            entries = kv.mapValues { (_, v) -> v.toAny() },
        ) else null

        return Provider(
            name = name,
            source = builtSource ?: Source.Url("addon://${type}/${name}"),
            condition = condition,
            priority = priority,
            addonSource = addonSource,
            values = capeValues,
        )
    }

    /** Привести Value к платформе для CapeValues.entries (Map<String, Any>). */
    private fun Value.toAny(): Any = when (this) {
        is Value.VStr -> s
        is Value.VInt -> i
        is Value.VFloat -> f
        is Value.VBool -> b
        is Value.VArray -> items.map { it.toAny() }
        is Value.VDict -> mapOf("…" to "dict(${pairs.size} pairs)")
        is Value.VBlock -> "block"
        is Value.VRef -> path.toString()
        else -> kind
    }

    private fun str(kv: Map<String, Value>, key: String): String? =
        (kv[key] as? Value.VStr)?.s
}
