package dev.ggtv.capecraft.schema

/**
 * Подстановка плейсхолдеров в строки-шаблоны URL/путей.
 *
 * Поддерживаются:
 *  - `{username}` — имя профиля игрока;
 *  - `{uuid}` — UUID игрока (нижний регистр, без дефисов);
 *  - `{name}` — имя провайдера;
 *  - `{root}` — корневая папка мода (для локальных файлов).
 *
 * Неизвестный плейсхолдер — ошибка на этапе сборки шаблона:
 * лучше упасть на конфиге, чем молча слать битый URL.
 */
object Placeholders {
    /** Данные для подстановки. */
    data class Context(
        val username: String = "",
        val uuid: String = "",
        val name: String = "",
        val root: String = "",
    )

    /** Заменить все `{...}` в [template] значениями из [ctx]. */
    fun render(template: String, ctx: Context): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i)
                if (close < 0) throw JsonError("незакрытый плейсхолдер в «$template»")
                val key = template.substring(i + 1, close)
                out.append(value(key, ctx, template))
                i = close + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun value(key: String, ctx: Context, template: String): String = when (key) {
        "username" -> ctx.username
        "uuid" -> ctx.uuid
        "name" -> ctx.name
        "root" -> ctx.root
        else -> throw JsonError("неизвестный плейсхолдер «{$key}» в «$template»")
    }
}
