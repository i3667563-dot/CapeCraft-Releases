package dev.ggtv.capecraft.schema

/**
 * Path-язык для извлечения поля из вложенного JSON.
 *
 * Формат: `$.a.b[0].url`
 *  - `$` — корень (обязателен в начале);
 *  - `.имя` — поле объекта;
 *  - `[n]` — n-й элемент массива (с нуля, как в JSON/JS).
 *
 * Применяется в JSON-схеме провайдера: сервер вернул объект, из него
 * по инструкции достаём строку-URL плаща.
 */
object JsonPath {
    /**
     * Вернуть значение по пути. Бросает [JsonError] с человеческим сообщением:
     * путь есть, но тип не сходится (прошли по строке) → «... не объект/массив».
     *
     * @param value корень, из которого извлекаем
     * @param path строка вроде `$.a.b[0].url`
     */
    fun extract(value: J, path: String): J {
        val steps = parse(path)
        var cur = value
        for (step in steps) {
            cur = when (step) {
                is Step.Field -> {
                    val obj = cur as? J.JObj
                        ?: throw JsonError("по пути «$path»: ${cur.kind()} — это не объект, нельзя взять поле «${step.name}»")
                    obj.fields.firstOrNull { it.first == step.name }?.second
                        ?: throw JsonError("по пути «$path»: поле «${step.name}» не найдено")
                }
                is Step.Index -> {
                    val arr = cur as? J.JArr
                        ?: throw JsonError("по пути «$path»: ${cur.kind()} — это не массив, нельзя взять элемент [${step.i}]")
                    arr.items.getOrNull(step.i)
                        ?: throw JsonError("по пути «$path»: индекс [${step.i}] за пределами массива (длина ${arr.items.size})")
                }
            }
        }
        return cur
    }

    /**
     * Вернуть строку по пути. Удобно для URL плаща: путь должен вести к строке.
     */
    fun extractString(value: J, path: String): String {
        val v = extract(value, path)
        return (v as? J.JStr)?.s
            ?: throw JsonError("по пути «$path»: ${v.kind()} — ожидалась строка (URL)")
    }

    private sealed interface Step {
        data class Field(val name: String) : Step
        data class Index(val i: Int) : Step
    }

    private fun J.kind(): String = when (this) {
        is J.JObj -> "объект"
        is J.JArr -> "массив"
        is J.JStr -> "строка"
        is J.JNum -> "число"
        is J.JBool -> "булево"
        is J.JNull -> "null"
    }

    private fun parse(path: String): List<Step> {
        if (!path.startsWith("$")) throw JsonError("путь должен начинаться с «$»: «$path»")
        val steps = mutableListOf<Step>()
        var i = 1 // пропустили '$'
        val n = path.length
        while (i < n) {
            val c = path[i]
            when (c) {
                '.' -> {
                    // имя поля до '.' или '['
                    val start = i + 1
                    var j = start
                    while (j < n && path[j] != '.' && path[j] != '[') j++
                    if (j == start) throw JsonError("пустой сегмент поля в «$path»")
                    steps += Step.Field(path.substring(start, j))
                    i = j
                }
                '[' -> {
                    val close = path.indexOf(']', i)
                    if (close < 0) throw JsonError("незакрытый «[» в «$path»")
                    val idxStr = path.substring(i + 1, close).trim()
                    val idx = idxStr.toIntOrNull()
                        ?: throw JsonError("неверный индекс «$idxStr» в «$path» (ожидалось число)")
                    if (idx < 0) throw JsonError("отрицательный индекс [$idx] в «$path»")
                    steps += Step.Index(idx)
                    i = close + 1
                }
                else -> throw JsonError("неожиданный символ '${c}' в «$path» (ожидался «.» или «[»)")
            }
        }
        return steps
    }
}
