package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues

/**
 * Виды результата, который умеет отдавать провайдер.
 *
 * (пар. Этапа 4 — «виды результата: прямая ссылка, локальный файл/директория,
 * JSON-схема»). Шаблоны с плейсхолдерами (`{username}`, ...) подставляются
 * в [Provider.resolve], давая конкретный [Resolved].
 */
sealed interface Source {
    /** Прямая ссылка на картинку: `type = url`. */
    data class Url(val template: String) : Source

    /** Локальный файл/директория: `type = file`. */
    data class File(val template: String) : Source

    /** Извлечь URL из вложенного JSON по path-инструкции: `type = json`. */
    data class Json(val template: String, val extract: String) : Source
}

/**
 * Провайдер плаща — один элемент списка `capeCraft.providers[]`.
 *
 * Встроенные провайдеры (url/file/json) хранят шаблон в [source].
 * Аддон-провайдеры (зарегистрированные через addon-API) хранят [addonSource]:
 * резолвер и фетчер определены аддоном, а конкретные параметры — в [values].
 *
 * [condition] — необязательное условие выбора (`when { ... }` в `.kn`):
 * провайдер становится активным только когда условие выполняется на живом
 * мире. Без условия провайдер активен всегда (default). [priority] задаёт
 * приоритет среди совпадших провайдеров: выше — ближе к началу fallback-цепи.
 *
 * @param condition условие, при котором провайдер активен (null = всегда).
 * @param priority приоритет выбора — по умолчанию 0 (порядок в списке).
 * @param addonSource non-null для аддон-провайдера (fetch определён аддоном).
 * @param values параметры из словаря .kn для аддон-провайдера (null для встроенного).
 */
class Provider(
    val name: String,
    val source: Source,
    val condition: Condition? = null,
    val priority: Int = 0,
    val addonSource: CapeSource? = null,
    val values: CapeValues? = null,
) {

    /**
     * Подставить плейсхолдеры и получить конкретный источник.
     * Чистая функция — без I/O, тестируется без сети.
     */
    fun resolve(ctx: Placeholders.Context, root: String): Resolved {
        val c = ctx.copy(root = root)
        if (addonSource != null) {
            val rendered = Placeholders.render("addon://${name}", c)
            return Resolved.Addon(addonSource, values ?: CapeValues(name, "addon", emptyMap()), rendered)
        }
        return when (val s = source) {
            is Source.Url -> Resolved.Url(Placeholders.render(s.template, c))
            is Source.File -> Resolved.File(Placeholders.render(s.template, c))
            is Source.Json -> Resolved.Json(
                Placeholders.render(s.template, c),
                s.extract,
            )
        }
    }
}

/** Конкретный источник после подстановки плейсхолдеров. */
sealed interface Resolved {
    data class Url(val url: String) : Resolved
    data class File(val path: String) : Resolved
    data class Json(val url: String, val extract: String) : Resolved
    /** Аддон-провайдер: fetch определён аддоном через [source]. */
    data class Addon(val source: CapeSource, val values: CapeValues, val debugUrl: String) : Resolved
}
