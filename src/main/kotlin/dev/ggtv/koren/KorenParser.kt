package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Type
import dev.ggtv.kjen.Value
import dev.ggtv.kjen.Value.VArray
import dev.ggtv.kjen.Value.VBlock
import dev.ggtv.kjen.Value.VDict
import dev.ggtv.kjen.Value.VRef
import dev.ggtv.kjen.Block
import dev.ggtv.kjen.Entry
import dev.ggtv.kjen.Path

/**
 * Парсер `.kn`: токены → AST.
 *
 * Тот же рекурсивный спуск, что у Kjen, плюс вызовы функций
 * `clamp(x, 0, 100)` → [Value.VRef]-подобный [VFunc].
 * Любой валидный `.crn` парсится идентично Kjen.
 */
object KorenParser {

    private enum class Term { EOF, RBRACE }

    /** Собрать AST из токенов. */
    fun parse(tokens: List<KorenToken>): Block = parseBlockContents(Ctx(tokens), 0, Term.EOF).block

    private class Ctx(val tokens: List<KorenToken>) {
        var pos = 0

        fun peek(): KorenToken? = tokens.getOrNull(pos)
        fun peekKind(): KorenTokenKind? = tokens.getOrNull(pos)?.kind

        fun err(message: String): Nothing {
            val span = peek()?.span ?: Span.ZERO
            throw CrenError.Parse(message, span)
        }
    }

    private class ParsedBlock(val block: Block, val endPos: Int)

    /** Вложенный блок: текущий токен — `{`, перешагиваем его и читаем до `}`. */
    private fun parseBlock(c: Ctx, term: Term): ParsedBlock {
        c.pos += 1
        return parseBlockContents(c, c.pos, term)
    }

    /** Содержимое блока: записи до `}` (или до конца файла). */
    private fun parseBlockContents(c: Ctx, startPos: Int, term: Term): ParsedBlock {
        c.pos = startPos
        val block = Block()
        var pendingComment: String? = null

        while (true) {
            while (true) {
                skipNewlines(c)
                val k = c.peekKind()
                if (k is KorenTokenKind.Comment) {
                    c.pos += 1
                    pendingComment = when (val prev = pendingComment) {
                        null -> k.text
                        else -> "$prev\n${k.text}"
                    }
                    continue
                }
                break
            }

            when (val k = c.peekKind()) {
                null -> {
                    if (term == Term.RBRACE) {
                        c.err("не закрыт блок: ожидалось «}»")
                    }
                    return ParsedBlock(block, c.pos)
                }
                KorenTokenKind.RBrace -> {
                    if (term == Term.RBRACE) {
                        c.pos += 1
                        return ParsedBlock(block, c.pos)
                    }
                    c.err("лишняя «}» без открывающей «{»")
                }
                else -> {}
            }

            val entry = parseEntry(c, pendingComment)
            pendingComment = null
            block.entries += entry
        }
    }

    /** Запись: `key [type] = value [# коммент]` или контейнер `key { ... }` / `key [ ... ]`. */
    private fun parseEntry(c: Ctx, leadingComment: String?): Entry {
        val start = c.peek()?.span ?: Span.ZERO

        val key = when (val k = c.peekKind()) {
            is KorenTokenKind.Word -> { c.pos += 1; k.w }
            else -> c.err("ожидался ключ")
        }

        val ty = when (val k = c.peekKind()) {
            is KorenTokenKind.Word -> {
                val t = Type.fromWord(k.w)
                if (t == null) {
                    c.err("неизвестный тип «${k.w}» (доступны: str, int, float, bool, dict, array, block, ref)")
                }
                c.pos += 1
                t
            }
            else -> null
        }

        when (val k = c.peekKind()) {
            KorenTokenKind.Assign -> {
                c.pos += 1
                val value = parseValue(c)
                val comment = trailingComment(c) ?: leadingComment
                checkValueType(value, ty, start)
                requireLineEnd(c)
                return Entry(key, ty, value, comment, start)
            }
            KorenTokenKind.LBrace -> {
                val value = VBlock(parseBlock(c, Term.RBRACE).block)
                checkValueType(value, ty, start)
                val comment = trailingComment(c) ?: leadingComment
                requireLineEnd(c)
                return Entry(key, ty, value, comment, start)
            }
            KorenTokenKind.LBracket -> {
                val value = parseArray(c)
                checkValueType(value, ty, start)
                val comment = trailingComment(c) ?: leadingComment
                requireLineEnd(c)
                return Entry(key, ty, value, comment, start)
            }
            else -> c.err("ожидалось «=», «{» или «[» после ключа")
        }
    }

    /** Значение в позиции `= ...` или внутри словаря/массива. */
    private fun parseValue(c: Ctx): Value {
        return when (val k = c.peekKind()) {
            is KorenTokenKind.Str -> { c.pos += 1; Value.VStr(k.s) }
            is KorenTokenKind.Int -> { c.pos += 1; Value.VInt(k.i) }
            is KorenTokenKind.Float -> { c.pos += 1; Value.VFloat(k.f) }
            is KorenTokenKind.Bool -> { c.pos += 1; Value.VBool(k.b) }
            KorenTokenKind.LBrace -> parseDict(c)
            KorenTokenKind.LBracket -> parseArray(c)
            // Слово: имя функции с `(` или абсолютный путь (`server.host`).
            is KorenTokenKind.Word -> {
                if (c.tokens.getOrNull(c.pos + 1)?.kind is KorenTokenKind.LParen) {
                    parseFuncCall(c)
                } else {
                    VRef(parsePath(c))
                }
            }
            // Ведущая точка — относительный путь.
            KorenTokenKind.Dot -> VRef(parsePath(c))
            else -> c.err("ожидалось значение, найдено: ${k ?: "конец файла"}")
        }
    }

    /** Вызов функции: `name(арг, арг, ...)` → [VFunc]. */
    private fun parseFuncCall(c: Ctx): Value {
        val start = c.peek()?.span ?: Span.ZERO
        val name = (c.peekKind() as KorenTokenKind.Word).w
        c.pos += 1 // имя
        c.pos += 1 // LParen

        skipNewlinesAndComments(c)
        val args = mutableListOf<Value>()
        if (c.peekKind() !is KorenTokenKind.RParen) {
            while (true) {
                args += parseValue(c)
                skipNewlinesAndComments(c)
                when (val after = c.peekKind()) {
                    KorenTokenKind.Comma -> {
                        c.pos += 1
                        skipNewlinesAndComments(c)
                        if (c.peekKind() is KorenTokenKind.RParen) {
                            c.err("ожидалось значение после «,» в вызове «$name(...)»")
                        }
                    }
                    KorenTokenKind.RParen -> { c.pos += 1; break }
                    else -> c.err("ожидалась «,» или «)» в вызове «$name(...)», найдено: $after")
                }
            }
        } else {
            c.pos += 1 // RParen
        }
        return VFunc(name, args, start)
    }

    /** Словарь: `{ key: value, key: value }` — запятые обязательны. */
    private fun parseDict(c: Ctx): Value {
        c.pos += 1 // LBrace
        val pairs = mutableListOf<Pair<String, Value>>()
        while (true) {
            skipNewlinesAndComments(c)
            when (val k = c.peekKind()) {
                KorenTokenKind.RBrace -> { c.pos += 1; return VDict(pairs) }
                KorenTokenKind.Comma -> { c.pos += 1; continue }
                is KorenTokenKind.Word -> {
                    // parseDictKeyDot уже сдвинул позицию мимо всех сегментов ключа.
                    val key = buildString { append(k.w); parseDictKeyDot(c, this) }
                    when (val sep = c.peekKind()) {
                        KorenTokenKind.Colon, KorenTokenKind.Assign -> c.pos += 1
                        else -> c.err("ожидалось «:» или «=» после ключа «$key» в словаре")
                    }
                    val value = parseValue(c)
                    pairs += key to value

                    skipNewlinesAndComments(c)
                    when (val after = c.peekKind()) {
                        KorenTokenKind.Comma -> c.pos += 1
                        KorenTokenKind.RBrace -> {}
                        else -> c.err("ожидалась «,» или «}» после пары словаря, найдено: $after")
                    }
                }
                else -> c.err("ожидался ключ словаря, найдено: ${k ?: "конец файла"}")
            }
        }
    }

    /** Дочитать хвост ключа после точки: `biome.temperature` → «biome.temperature». */
    private fun parseDictKeyDot(c: Ctx, acc: StringBuilder): Boolean {
        c.pos += 1 // уже прочитанные Word — счётчик на него
        var more = false
        while (c.peekKind() is KorenTokenKind.Dot) {
            c.pos += 1 // Dot
            when (val seg = c.peekKind()) {
                is KorenTokenKind.Word -> {
                    acc.append('.').append(seg.w)
                    c.pos += 1
                    more = true
                }
                else -> c.err("ожидался сегмент пути после «.» в ключе словаря")
            }
        }
        return more
    }

    /** Массив: `[ значение, значение ]` — запятые обязательны. */
    private fun parseArray(c: Ctx): Value {
        c.pos += 1 // LBracket
        val items = mutableListOf<Value>()
        while (true) {
            skipNewlinesAndComments(c)
            when (val k = c.peekKind()) {
                KorenTokenKind.RBracket -> { c.pos += 1; return VArray(items) }
                KorenTokenKind.Comma -> { c.pos += 1; continue }
                else -> {}
            }

            items += parseValue(c)

            skipNewlinesAndComments(c)
            when (val after = c.peekKind()) {
                KorenTokenKind.Comma -> c.pos += 1
                KorenTokenKind.RBracket -> {}
                else -> c.err("ожидалась «,» или «]» после элемента массива, найдено: $after")
            }
        }
    }

    /** Пропустить переводы строк и комментарии (в массивах и словарях). */
    private fun skipNewlinesAndComments(c: Ctx) {
        while (c.peekKind() is KorenTokenKind.Newline || c.peekKind() is KorenTokenKind.Comment) c.pos += 1
    }

    private fun skipNewlines(c: Ctx) {
        while (c.peekKind() is KorenTokenKind.Newline) c.pos += 1
    }

    /** Ссылка: `server.token[1]`, `server1.host`, `.shared.host`. */
    private fun parsePath(c: Ctx): Path {
        val segments = mutableListOf<String>()
        val indices = mutableListOf<Int?>()
        var absolute = true

        if (c.peekKind() is KorenTokenKind.Dot) {
            absolute = false
            c.pos += 1
            if (c.peekKind() is KorenTokenKind.Dot) {
                c.err("«..» не поддержан: относительный путь — «.имя», без подъёма на уровень выше")
            }
        }

        while (true) {
            when (val k = c.peekKind()) {
                is KorenTokenKind.Word -> {
                    segments += k.w
                    indices += null
                    c.pos += 1
                }
                KorenTokenKind.Dot -> {
                    if (c.tokens.getOrNull(c.pos + 1)?.kind is KorenTokenKind.Word) {
                        c.pos += 1
                    } else {
                        c.err("после «.» ожидался сегмент пути")
                    }
                }
                KorenTokenKind.LBracket -> {
                    val next = c.tokens.getOrNull(c.pos + 1)?.kind
                    if (next is KorenTokenKind.Int && next.i > 0) {
                        c.pos += 2
                        if (c.peekKind() is KorenTokenKind.RBracket) {
                            if (indices.isNotEmpty()) {
                                indices[indices.lastIndex] = next.i.toInt()
                                c.pos += 1
                                break
                            }
                            c.err("номер [n] не к чему применить")
                        }
                        c.err("ожидалась «]» после номера")
                    }
                    c.err("в пути ожидался номер [n] начиная с 1")
                }
                else -> {
                    if (segments.isEmpty()) {
                        c.err("ожидалось слово пути, найдено: ${k ?: "конец файла"}")
                    }
                    break
                }
            }
        }

        if (c.peekKind() is KorenTokenKind.Dot) {
            c.err("номер [n] в середине пути: используйте форму «server1.port» (номер после имени)")
        }
        if (segments.isEmpty()) c.err("пустой путь ссылки")

        return Path(segments, indices, absolute)
    }

    /** Комментарий сразу после значения (до конца строки). */
    private fun trailingComment(c: Ctx): String? {
        val k = c.peekKind()
        if (k is KorenTokenKind.Comment) {
            c.pos += 1
            return k.text
        }
        return null
    }

    /** После записи допустимы только: конец строки, конец блока, конец файла. */
    private fun requireLineEnd(c: Ctx) {
        when (val k = c.peekKind()) {
            null, is KorenTokenKind.Newline, is KorenTokenKind.RBrace, is KorenTokenKind.RBracket, is KorenTokenKind.Comment -> {}
            else -> c.err("ожидался конец строки, найдено: $k")
        }
    }

    /** Явный тип должен совпадать с фактическим значением. `ref` и функции проверяет резолвер. */
    private fun checkValueType(value: Value, ty: Type?, span: Span) {
        val t = ty ?: return
        val matches = when (t) {
            Type.STR -> value is Value.VStr
            Type.INT -> value is Value.VInt
            Type.FLOAT -> value is Value.VFloat || value is Value.VInt
            Type.BOOL -> value is Value.VBool
            Type.DICT -> value is Value.VDict
            Type.ARRAY -> value is Value.VArray
            Type.BLOCK -> value is Value.VBlock
            Type.REF -> true // проверит резолвер
        }
        // Функция считается совместимой с любым типом: результат проверяется в резолвере.
        if (!matches && value is VFunc) return
        if (!matches) {
            throw CrenError.TypeMismatch(t.word, value.kind, span)
        }
    }
}