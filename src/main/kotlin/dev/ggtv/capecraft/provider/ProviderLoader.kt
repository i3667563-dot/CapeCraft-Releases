package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.cren.CrenConfig
import dev.ggtv.capecraft.cren.Value

/**
 * Загрузчик провайдеров из `.crn`.
 *
 * Ожидает конфиг вида:
 * ```
 * capeCraft {
 *     providers [
 *         { name = "trusted", type = "url",  url = ".../{username}.png" }
 *         { name = "local",   type = "file", path = "{root}/capes/{uuid}.png" }
 *         { name = "api",     type = "json", url = "...", extract = "$.data.cape_url" }
 *     ]
 * }
 * ```
 *
 * Пары словаря разделяются запятыми (как и элементы массива).
 *
 * Порядок в списке = порядок fallback: первый провайдер пробуется первым,
 * при ошибке — следующий. Ключевые имена вынесены в константы, чтобы
 * сверять `.crn` и код в одном месте.
 */
object ProviderLoader {
    const val ROOT = "capeCraft.providers"

    object Keys {
        const val NAME = "name"
        const val TYPE = "type"
        const val URL = "url"
        const val PATH = "path"
        const val EXTRACT = "extract"
    }

    /** Типы провайдеров, как в `type = ...`. */
    object Types {
        const val URL = "url"
        const val FILE = "file"
        const val JSON = "json"
    }

    /** Собрать список провайдеров из конфига (в порядке появления, без I/O). */
    fun load(config: CrenConfig): List<Provider> {
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
        val type = str(kv, Keys.TYPE) ?: throw IllegalArgumentException("у провайдера нет «type» (url|file|json)")
        val name = str(kv, Keys.NAME) ?: "provider-${type}"
        val source = when (type) {
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
            else -> throw IllegalArgumentException("провайдер «$name»: неизвестный тип «$type» (ожидалось url|file|json)")
        }
        return Provider(name, source)
    }

    private fun str(kv: Map<String, Value>, key: String): String? =
        (kv[key] as? Value.VStr)?.s
}
