package dev.ggtv.capecraft.cren

/**
 * Парсер: токены → AST.
 *
 * Рекурсивный спуск. Сложность формата сидит в семантике
 * (нумерация, комментарии, ссылки), а не в грамматике.
 */
object Parser {

    /** Чем заканчивается блок: концом файла или `}`. */
    private enum class Term { EOF, RBRACE }

    /** Собрать AST из токенов. Дубликаты ключей не ругаем — нумерацию даёт [Block.get]. */
    fun parse(tokens: List<Token>): Block = parseBlockContents(Ctx(tokens), 0, Term.EOF).block

    private class Ctx(val tokens: List<Token>) {
        var pos = 0

        fun peek(): Token? = tokens.getOrNull(pos)
        fun peekKind(): TokenKind? = tokens.getOrNull(pos)?.kind

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
            // Пустые строки и комментарии пропускаем; комментарии
            // копим и привязываем к следующей записи.
            while (true) {
                skipNewlines(c)
                val k = c.peekKind()
                if (k is TokenKind.Comment) {
                    c.pos += 1
                    pendingComment = when (val prev = pendingComment) {
                        null -> k.text
                        else -> "$prev\n${k.text}"
                    }
                    continue
                }
                break
            }

            // Конец контейнера?
            when (val k = c.peekKind()) {
                null -> {
                    if (term == Term.RBRACE) {
                        c.err("не закрыт блок: ожидалось «}»")
                    }
                    return ParsedBlock(block, c.pos)
                }
                TokenKind.RBrace -> {
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

        // Ключ.
        val key = when (val k = c.peekKind()) {
            is TokenKind.Word -> { c.pos += 1; k.w }
            else -> c.err("ожидался ключ")
        }

        // Явный тип: `key str = ...`. Неизвестное имя типа — ошибка.
        val ty = when (val k = c.peekKind()) {
            is TokenKind.Word -> {
                val t = Type.fromWord(k.w)
                if (t == null) {
                    c.err("неизвестный тип «${k.w}» (доступны: str, int, float, bool, dict, array, block, ref)")
                }
                c.pos += 1
                t
            }
            else -> null
        }

        // Форма записи: значение, блок или массив.
        when (val k = c.peekKind()) {
            TokenKind.Assign -> {
                c.pos += 1
                val value = parseValue(c)
                // Комментарий после значения важнее комментария перед записью.
                val comment = trailingComment(c) ?: leadingComment
                checkValueType(value, ty, start)
                requireLineEnd(c)
                return Entry(key, ty, value, comment, start)
            }
            TokenKind.LBrace -> {
                val value = Value.VBlock(parseBlock(c, Term.RBRACE).block)
                checkValueType(value, ty, start)
                val comment = trailingComment(c) ?: leadingComment
                requireLineEnd(c)
                return Entry(key, ty, value, comment, start)
            }
            TokenKind.LBracket -> {
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
            is TokenKind.Str -> { c.pos += 1; Value.VStr(k.s) }
            is TokenKind.Int -> { c.pos += 1; Value.VInt(k.i) }
            is TokenKind.Float -> { c.pos += 1; Value.VFloat(k.f) }
            is TokenKind.Bool -> { c.pos += 1; Value.VBool(k.b) }
            TokenKind.LBrace -> parseDict(c)
            TokenKind.LBracket -> parseArray(c)
            // Слово — абсолютный путь (`server.host`); ведущая точка — относительный.
            is TokenKind.Word, TokenKind.Dot -> Value.VRef(parsePath(c))
            else -> c.err("ожидалось значение, найдено: ${k ?: "конец файла"}")
        }
    }

    /** Словарь: `{ key: value, key: value }` — запятые обязательны. */
    private fun parseDict(c: Ctx): Value {
        c.pos += 1 // LBrace
        val pairs = mutableListOf<Pair<String, Value>>()
        while (true) {
            skipNewlinesAndComments(c)
            when (val k = c.peekKind()) {
                TokenKind.RBrace -> { c.pos += 1; return Value.VDict(pairs) }
                TokenKind.Comma -> { c.pos += 1; continue }
                is TokenKind.Word -> {
                    val key = k.w
                    c.pos += 1
                    when (val sep = c.peekKind()) {
                        TokenKind.Colon, TokenKind.Assign -> c.pos += 1
                        else -> c.err("ожидалось «:» или «=» после ключа «$key» в словаре")
                    }
                    val value = parseValue(c)
                    pairs += key to value

                    // После пары обязательна запятая или конец словаря.
                    skipNewlinesAndComments(c)
                    when (val after = c.peekKind()) {
                        TokenKind.Comma -> c.pos += 1
                        TokenKind.RBrace -> {}
                        else -> c.err("ожидалась «,» или «}» после пары словаря, найдено: $after")
                    }
                }
                else -> c.err("ожидался ключ словаря, найдено: ${k ?: "конец файла"}")
            }
        }
    }

    /** Массив: `[ значение, значение ]` — запятые обязательны. */
    private fun parseArray(c: Ctx): Value {
        c.pos += 1 // LBracket
        val items = mutableListOf<Value>()
        while (true) {
            skipNewlinesAndComments(c)
            when (val k = c.peekKind()) {
                TokenKind.RBracket -> { c.pos += 1; return Value.VArray(items) }
                TokenKind.Comma -> { c.pos += 1; continue }
                else -> {}
            }

            items += parseValue(c)

            skipNewlinesAndComments(c)
            when (val after = c.peekKind()) {
                TokenKind.Comma -> c.pos += 1
                TokenKind.RBracket -> {}
                else -> c.err("ожидалась «,» или «]» после элемента массива, найдено: $after")
            }
        }
    }

    /** Пропустить переводы строк и комментарии (в массивах и словарях). */
    private fun skipNewlinesAndComments(c: Ctx) {
        while (c.peekKind() is TokenKind.Newline || c.peekKind() is TokenKind.Comment) c.pos += 1
    }

    private fun skipNewlines(c: Ctx) {
        while (c.peekKind() is TokenKind.Newline) c.pos += 1
    }

    /** Ссылка: `server.token[1]`, `server1.host`, `.shared.host`. */
    private fun parsePath(c: Ctx): Path {
        val segments = mutableListOf<String>()
        val indices = mutableListOf<Int?>()
        var absolute = true

        // Ведущая точка — маркер относительности.
        if (c.peekKind() is TokenKind.Dot) {
            absolute = false
            c.pos += 1
            if (c.peekKind() is TokenKind.Dot) {
                c.err("«..» не поддержан: относительный путь — «.имя», без подъёма на уровень выше")
            }
        }

        while (true) {
            when (val k = c.peekKind()) {
                is TokenKind.Word -> {
                    segments += k.w
                    indices += null
                    c.pos += 1
                }
                TokenKind.Dot -> {
                    if (c.tokens.getOrNull(c.pos + 1)?.kind is TokenKind.Word) {
                        c.pos += 1
                    } else {
                        c.err("после «.» ожидался сегмент пути")
                    }
                }
                TokenKind.LBracket -> {
                    val next = c.tokens.getOrNull(c.pos + 1)?.kind
                    if (next is TokenKind.Int && next.i > 0) {
                        c.pos += 2
                        if (c.peekKind() is TokenKind.RBracket) {
                            // Номер относится к последнему сегменту.
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

        // `server[1].port` — номер применился бы к последнему сегменту.
        if (c.peekKind() is TokenKind.Dot) {
            c.err("номер [n] в середине пути: используйте форму «server1.port» (номер после имени)")
        }
        if (segments.isEmpty()) c.err("пустой путь ссылки")

        return Path(segments, indices, absolute)
    }

    /** Комментарий сразу после значения (до конца строки). */
    private fun trailingComment(c: Ctx): String? {
        val k = c.peekKind()
        if (k is TokenKind.Comment) {
            c.pos += 1
            return k.text
        }
        return null
    }

    /** После записи допустимы только: конец строки, конец блока, конец файла. */
    private fun requireLineEnd(c: Ctx) {
        when (val k = c.peekKind()) {
            null, is TokenKind.Newline, is TokenKind.RBrace, is TokenKind.RBracket, is TokenKind.Comment -> {}
            else -> c.err("ожидался конец строки, найдено: $k")
        }
    }

    /** Явный тип должен совпадать с фактическим значением. `ref` проверяет резолвер. */
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
        if (!matches) {
            throw CrenError.TypeMismatch(t.word, value.kind, span)
        }
    }
}