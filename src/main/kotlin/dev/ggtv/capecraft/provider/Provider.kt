package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.schema.Placeholders

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
 * [source] содержит шаблоны с плейсхолдерами (`{username}`, ...);
 * [resolve] подставляет их и возвращает конкретный [Resolved], который
 * уже можно скачать/прочитать напрямую.
 */
class Provider(val name: String, val source: Source) {

    /**
     * Подставить плейсхолдеры и получить конкретный источник.
     * Чистая функция — без I/O, тестируется без сети.
     */
    fun resolve(ctx: Placeholders.Context, root: String): Resolved {
        val c = ctx.copy(root = root)
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
}
