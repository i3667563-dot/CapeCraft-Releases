package dev.ggtv.capecraft.schema

/**
 * Мини-парсер JSON без внешних зависимостей (движок — чистый Kotlin).
 *
 * Нужен для «JSON-схем»: провайдер тянет JSON с сервера и по инструкции
 * (`$.a.b[0].url`) вытаскивает из него URL плаща. Полноценный JSON не нужен —
 * хватает объектов, массивов, строк, чисел, булов и null для навигации.
 *
 * Значения иммутабельны — парсер строит дерево один раз, дальше по нему ходят.
 */
sealed interface J {
    data class JObj(val fields: List<Pair<String, J>>) : J
    data class JArr(val items: List<J>) : J
    data class JStr(val s: String) : J
    data class JNum(val d: Double, val isInt: Boolean, val i: Long) : J
    data class JBool(val b: Boolean) : J
    data object JNull : J
}

/** Ошибка разбора или навигации по JSON. */
class JsonError(message: String) : Exception(message)

/**
 * Однопроходный рекурсивный парсер. JSON небольшой (ответ сервера),
 * поэтому рекурсия глубокая тут не опасна.
 */
object Json {
    fun parse(text: String): J {
        val p = P(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonError("лишние символы после JSON на позиции ${p.pos}")
        return v
    }

    private class P(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun parseValue(): J {
            skipWs()
            if (atEnd()) throw JsonError("неожиданный конец JSON")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> J.JStr(parseString())
                't' -> { expect("true"); J.JBool(true) }
                'f' -> { expect("false"); J.JBool(false) }
                'n' -> { expect("null"); J.JNull }
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonError("неожиданный символ '${s[pos]}' на позиции $pos")
            }
        }

        fun expect(word: String) {
            if (s.startsWith(word, pos)) pos += word.length
            else throw JsonError("ожидалось «$word» на позиции $pos")
        }

        fun parseString(): String {
            pos++ // открывающая кавычка
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonError("незакрытая строка JSON")
                val c = s[pos]
                when (c) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw JsonError("незаконченный escape-символ")
                        val e = s[pos]
                        sb.append(
                            when (e) {
                                '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                                'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                'u' -> {
                                    if (pos + 4 >= s.length) throw JsonError("короткий \\u escape")
                                    val hex = s.substring(pos + 1, pos + 5)
                                    val code = hex.toIntOrNull(16)
                                        ?: throw JsonError("неверный \\u escape: «$hex»")
                                    pos += 4
                                    code.toChar()
                                }
                                else -> throw JsonError("неизвестный escape '\\$e'")
                            }
                        )
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        fun parseObject(): J {
            pos++ // '{'
            val fields = mutableListOf<Pair<String, J>>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return J.JObj(fields) }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonError("ожидался ключ объекта на позиции $pos")
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonError("ожидалось ':' после ключа «$key»")
                pos++
                val value = parseValue()
                fields += key to value
                skipWs()
                if (atEnd()) throw JsonError("незакрытый объект JSON")
                when (s[pos]) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return J.JObj(fields) }
                    else -> throw JsonError("ожидалось ',' или '}' на позиции $pos")
                }
            }
        }

        fun parseArray(): J {
            pos++ // '['
            val items = mutableListOf<J>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return J.JArr(items) }
            while (true) {
                items += parseValue()
                skipWs()
                if (atEnd()) throw JsonError("незакрытый массив JSON")
                when (s[pos]) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return J.JArr(items) }
                    else -> throw JsonError("ожидалось ',' или ']' на позиции $pos")
                }
            }
        }

        fun parseNumber(): J {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            var isFloat = false
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9') { pos++ }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isFloat = true; pos++ }
                else break
            }
            val raw = s.substring(start, pos)
            if (raw.isEmpty() || raw == "-") throw JsonError("неверное число JSON «$raw»")
            val d = raw.toDoubleOrNull() ?: throw JsonError("неверное число JSON «$raw»")
            val intVal = raw.toLongOrNull()
            return if (!isFloat && intVal != null) J.JNum(d, true, intVal) else J.JNum(d, false, 0)
        }
    }

    /**
     * Обратное действие: дерево в текст JSON.
     *
     * Нужно редактору: LSP — это JSON-RPC поверх stdin/stdout, то есть
     * наружу уходит только текст, и собрать его можно лишь сериализатором.
     * Симметрия с [parse] заодно даёт бесплатный тест: `parse(render(x))` обязан
     * вернуть `x`.
     *
     * Без отступов и без экранирования `/`: ответы LSP короткие, а читать их
     * приходится в отладчике, и лишние пробелы только мешают.
     */
    fun render(value: J): String = StringBuilder().also { render(value, it) }.toString()

    private fun render(value: J, sb: StringBuilder) {
        when (value) {
            is J.JNull -> sb.append("null")
            is J.JBool -> sb.append(if (value.b) "true" else "false")
            // Целое печатается как Long, иначе 3 превратится в 3.0, а JSON-RPC
            // ждёт целые там, где стоит int (например номера диагностик нет,
            // но длины и позиции — да).
            is J.JNum -> if (value.isInt) sb.append(value.i) else sb.append(value.d)
            is J.JStr -> renderString(value.s, sb)
            is J.JArr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    render(item, sb)
                }
                sb.append(']')
            }
            is J.JObj -> {
                sb.append('{')
                value.fields.forEachIndexed { i, (key, v) ->
                    if (i > 0) sb.append(',')
                    renderString(key, sb)
                    sb.append(':')
                    render(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun renderString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                // Остальные управляющие обязаны уходить как \u00XX: сырой
                // символ в JSON недопустим, и это молча ломает кадр у клиента.
                c < ' ' || c == '\u007F' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
