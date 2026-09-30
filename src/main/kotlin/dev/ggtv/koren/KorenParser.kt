package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Entry
import dev.ggtv.kjen.Path
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Type
import dev.ggtv.kjen.Value

object KorenParser {

    private const val MAX_NESTING_DEPTH = 128

    private sealed interface Term {
        data object EOF : Term
        data class RBrace(val openSpan: Span) : Term
    }

    fun parse(tokens: List<KorenToken>): Block =
        parseBlockContents(Ctx(tokens), 0, Term.EOF, 0).block

    private class Ctx(val tokens: List<KorenToken>) {
        var pos = 0

        fun peek(): KorenToken? = tokens.getOrNull(pos)
        fun peekKind(): KorenTokenKind? = peek()?.kind
        fun currentSpan(): Span = peek()?.span ?: tokens.lastOrNull()?.span ?: Span(1, 1)

        fun err(message: String): Nothing {
            throw CrenError.Parse(message, currentSpan())
        }

        fun errAt(span: Span, message: String): Nothing {
            throw CrenError.Parse(message, span)
        }
    }

    private class ParsedBlock(val block: Block, val endPos: Int)

    private fun parseBlock(c: Ctx, depth: Int): ParsedBlock {
        ensureDepth(c, depth)
        val openSpan = c.currentSpan()
        c.pos += 1
        return parseBlockContents(c, c.pos, Term.RBrace(openSpan), depth)
    }

    private fun parseBlockContents(
        c: Ctx,
        startPos: Int,
        term: Term,
        depth: Int,
    ): ParsedBlock {
        c.pos = startPos
        val block = Block()
        var pendingComment: String? = null

        while (true) {
            while (true) {
                skipNewlines(c)
                val kind = c.peekKind()
                if (kind is KorenTokenKind.Comment) {
                    c.pos += 1
                    pendingComment = when (val previous = pendingComment) {
                        null -> kind.text
                        else -> "$previous\n${kind.text}"
                    }
                    continue
                }
                break
            }

            when (c.peekKind()) {
                null -> {
                    if (term is Term.RBrace) {
                        c.errAt(term.openSpan, "не закрыт блок: ожидалось «}»")
                    }
                    return ParsedBlock(block, c.pos)
                }
                KorenTokenKind.RBrace -> {
                    if (term is Term.RBrace) {
                        c.pos += 1
                        return ParsedBlock(block, c.pos)
                    }
                    c.err("лишняя «}» без открывающей «{»")
                }
                else -> {}
            }

            val entry = parseEntry(c, pendingComment, depth)
            pendingComment = null
            block.entries += entry
        }
    }

    private fun parseEntry(c: Ctx, leadingComment: String?, depth: Int): Entry {
        val start = c.currentSpan()
        val key = when (val kind = c.peekKind()) {
            is KorenTokenKind.Word -> {
                c.pos += 1
                kind.w
            }
            else -> c.err("ожидался ключ")
        }

        val type = when (val kind = c.peekKind()) {
            is KorenTokenKind.Word -> {
                val parsed = Type.fromWord(kind.w)
                    ?: c.err("неизвестный тип «${kind.w}» (доступны: str, int, float, bool, dict, array, block, ref)")
                c.pos += 1
                parsed
            }
            else -> null
        }

        return when (c.peekKind()) {
            KorenTokenKind.Assign -> {
                c.pos += 1
                val value = parseValue(c, depth)
                checkValueType(value, type, start)
                requireLineEnd(c)
                Entry(
                    key = key,
                    ty = type,
                    value = value,
                    comment = mergeComments(leadingComment, trailingComment(c)),
                    span = start,
                )
            }
            KorenTokenKind.LBrace -> {
                val value = Value.VBlock(parseBlock(c, depth + 1).block)
                checkValueType(value, type, start)
                requireLineEnd(c)
                Entry(
                    key = key,
                    ty = type,
                    value = value,
                    comment = mergeComments(leadingComment, trailingComment(c)),
                    span = start,
                )
            }
            KorenTokenKind.LBracket -> {
                val value = parseArray(c, depth + 1)
                checkValueType(value, type, start)
                requireLineEnd(c)
                Entry(
                    key = key,
                    ty = type,
                    value = value,
                    comment = mergeComments(leadingComment, trailingComment(c)),
                    span = start,
                )
            }
            else -> c.err("ожидалось «=», «{» или «[» после ключа")
        }
    }

    private fun parseValue(c: Ctx, depth: Int): Value {
        ensureDepth(c, depth)
        return when (val kind = c.peekKind()) {
            is KorenTokenKind.Str -> {
                c.pos += 1
                Value.VStr(kind.s)
            }
            is KorenTokenKind.Int -> {
                c.pos += 1
                Value.VInt(kind.i)
            }
            is KorenTokenKind.Float -> {
                c.pos += 1
                Value.VFloat(kind.f)
            }
            is KorenTokenKind.Bool -> {
                c.pos += 1
                Value.VBool(kind.b)
            }
            KorenTokenKind.LBrace -> parseDict(c, depth + 1)
            KorenTokenKind.LBracket -> parseArray(c, depth + 1)
            is KorenTokenKind.Word -> {
                if (c.tokens.getOrNull(c.pos + 1)?.kind is KorenTokenKind.LParen) {
                    parseFunction(c, depth)
                } else {
                    Value.VRef(parsePath(c))
                }
            }
            KorenTokenKind.Dot -> Value.VRef(parsePath(c))
            else -> c.err("ожидалось значение, найдено: ${kind ?: "конец файла"}")
        }
    }

    private fun parseFunction(c: Ctx, depth: Int): VFunc {
        ensureDepth(c, depth)
        val span = c.currentSpan()
        val name = (c.peekKind() as KorenTokenKind.Word).w
        c.pos += 2
        val args = mutableListOf<Value>()

        while (true) {
            skipNewlinesAndComments(c)
            when (c.peekKind()) {
                KorenTokenKind.RParen -> {
                    c.pos += 1
                    return VFunc(name, args, span)
                }
                KorenTokenKind.Comma -> c.err("лишняя «,» в аргументах функции")
                else -> {}
            }

            args += parseValue(c, depth)
            skipNewlinesAndComments(c)
            when (val after = c.peekKind()) {
                KorenTokenKind.RParen -> {
                    c.pos += 1
                    return VFunc(name, args, span)
                }
                KorenTokenKind.Comma -> {
                    c.pos += 1
                    skipNewlinesAndComments(c)
                    if (c.peekKind() is KorenTokenKind.RParen) {
                        c.pos += 1
                        return VFunc(name, args, span)
                    }
                    if (c.peekKind() is KorenTokenKind.Comma) {
                        c.err("лишняя «,» в аргументах функции")
                    }
                }
                else -> c.err("ожидалась «,» или «)» после аргумента функции, найдено: $after")
            }
        }
    }

    private fun parseDict(c: Ctx, depth: Int): Value {
        ensureDepth(c, depth)
        c.pos += 1
        val pairs = mutableListOf<Pair<String, Value>>()

        while (true) {
            skipNewlinesAndComments(c)
            when (val kind = c.peekKind()) {
                KorenTokenKind.RBrace -> {
                    c.pos += 1
                    return Value.VDict(pairs)
                }
                KorenTokenKind.Comma -> c.err("лишняя «,» в словаре")
                is KorenTokenKind.EnvRef -> {
                    // Ключ-ссылка на переменную окружения: имя сохраняется
                    // вместе с `$`, и разбирает его уже владелец значения
                    // (см. VarCondition), а не парсер формата.
                    c.pos += 1
                    val key = kind.text
                    when (c.peekKind()) {
                        KorenTokenKind.Colon, KorenTokenKind.Assign -> c.pos += 1
                        else -> c.err(
                            "ожидалось «:» или «=» после ключа «$key» в словаре, найдено: ${c.peekKind()}",
                        )
                    }
                    pairs += key to parseValue(c, depth)

                    skipNewlinesAndComments(c)
                    when (val after = c.peekKind()) {
                        KorenTokenKind.Comma -> {
                            c.pos += 1
                            skipNewlinesAndComments(c)
                            if (c.peekKind() is KorenTokenKind.RBrace) {
                                c.pos += 1
                                return Value.VDict(pairs)
                            }
                            if (c.peekKind() is KorenTokenKind.Comma) {
                                c.err("лишняя «,» в словаре")
                            }
                        }
                        KorenTokenKind.RBrace -> {
                            c.pos += 1
                            return Value.VDict(pairs)
                        }
                        else -> c.err("ожидалась «,» или «}» после пары словаря, найдено: $after")
                    }
                }
                is KorenTokenKind.Word -> {
                    c.pos += 1
                    val key = parseDictKey(c, kind.w)
                    when (c.peekKind()) {
                        KorenTokenKind.Colon, KorenTokenKind.Assign -> c.pos += 1
                        else -> c.err(
                            "ожидалось «:» или «=» после ключа «$key» в словаре, найдено: ${c.peekKind()}",
                        )
                    }
                    pairs += key to parseValue(c, depth)

                    skipNewlinesAndComments(c)
                    when (val after = c.peekKind()) {
                        KorenTokenKind.Comma -> {
                            c.pos += 1
                            skipNewlinesAndComments(c)
                            if (c.peekKind() is KorenTokenKind.RBrace) {
                                c.pos += 1
                                return Value.VDict(pairs)
                            }
                            if (c.peekKind() is KorenTokenKind.Comma) {
                                c.err("лишняя «,» в словаре")
                            }
                        }
                        KorenTokenKind.RBrace -> {
                            c.pos += 1
                            return Value.VDict(pairs)
                        }
                        else -> c.err("ожидалась «,» или «}» после пары словаря, найдено: $after")
                    }
                }
                else -> c.err("ожидался ключ словаря, найдено: ${kind ?: "конец файла"}")
            }
        }
    }

    private fun parseDictKey(c: Ctx, first: String): String {
        val key = StringBuilder(first)
        while (c.peekKind() is KorenTokenKind.Dot) {
            if (c.tokens.getOrNull(c.pos + 1)?.kind !is KorenTokenKind.Word) {
                c.err("после «.» ожидался сегмент пути")
            }
            key.append('.').append((c.tokens[c.pos + 1].kind as KorenTokenKind.Word).w)
            c.pos += 2
        }
        return key.toString()
    }

    private fun parseArray(c: Ctx, depth: Int): Value {
        ensureDepth(c, depth)
        c.pos += 1
        val items = mutableListOf<Value>()

        while (true) {
            skipNewlinesAndComments(c)
            when (c.peekKind()) {
                KorenTokenKind.RBracket -> {
                    c.pos += 1
                    return Value.VArray(items)
                }
                KorenTokenKind.Comma -> c.err("лишняя «,» в массиве")
                else -> {}
            }

            items += parseValue(c, depth)
            skipNewlinesAndComments(c)
            when (val after = c.peekKind()) {
                KorenTokenKind.Comma -> {
                    c.pos += 1
                    skipNewlinesAndComments(c)
                    if (c.peekKind() is KorenTokenKind.RBracket) {
                        c.pos += 1
                        return Value.VArray(items)
                    }
                    if (c.peekKind() is KorenTokenKind.Comma) {
                        c.err("лишняя «,» в массиве")
                    }
                }
                KorenTokenKind.RBracket -> {
                    c.pos += 1
                    return Value.VArray(items)
                }
                else -> c.err("ожидалась «,» или «]» после элемента массива, найдено: $after")
            }
        }
    }

    private fun skipNewlinesAndComments(c: Ctx) {
        while (c.peekKind() is KorenTokenKind.Newline || c.peekKind() is KorenTokenKind.Comment) {
            c.pos += 1
        }
    }

    private fun skipNewlines(c: Ctx) {
        while (c.peekKind() is KorenTokenKind.Newline) c.pos += 1
    }

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
            when (val kind = c.peekKind()) {
                is KorenTokenKind.Word -> {
                    segments += kind.w
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
                        if (c.peekKind() !is KorenTokenKind.RBracket) {
                            c.err("ожидалась «]» после номера")
                        }
                        if (indices.isEmpty()) {
                            c.err("номер [n] не к чему применить")
                        }
                        if (next.i > Int.MAX_VALUE) {
                            c.err("номер в пути слишком велик для этой платформы")
                        }
                        indices[indices.lastIndex] = next.i.toInt()
                        c.pos += 1
                        break
                    }
                    c.err("в пути ожидался номер [n] начиная с 1")
                }
                else -> {
                    if (segments.isEmpty()) {
                        c.err("ожидалось слово пути, найдено: ${kind ?: "конец файла"}")
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

    private fun trailingComment(c: Ctx): String? {
        val kind = c.peekKind()
        if (kind is KorenTokenKind.Comment) {
            c.pos += 1
            return kind.text
        }
        return null
    }

    private fun mergeComments(leading: String?, trailing: String?): String? = when {
        leading != null && trailing != null -> "$leading\n$trailing"
        else -> leading ?: trailing
    }

    private fun requireLineEnd(c: Ctx) {
        when (c.peekKind()) {
            null,
            is KorenTokenKind.Newline,
            is KorenTokenKind.RBrace,
            is KorenTokenKind.RBracket,
            is KorenTokenKind.Comment,
            -> {}
            else -> c.err("ожидался конец строки, найдено: ${c.peekKind()}")
        }
    }

    private fun checkValueType(value: Value, type: Type?, span: Span) {
        if (value is VFunc || type == null) return
        val matches = when (type) {
            Type.STR -> value is Value.VStr
            Type.INT -> value is Value.VInt
            Type.FLOAT -> value is Value.VFloat || value is Value.VInt
            Type.BOOL -> value is Value.VBool
            Type.DICT -> value is Value.VDict
            Type.ARRAY -> value is Value.VArray
            Type.BLOCK -> value is Value.VBlock
            Type.REF -> value is Value.VRef
        }
        if (!matches) {
            throw CrenError.TypeMismatch(type.word, value.kind, span)
        }
    }

    private fun ensureDepth(c: Ctx, depth: Int) {
        if (depth > MAX_NESTING_DEPTH) {
            throw CrenError.Parse(
                "превышен предел вложенности: максимум $MAX_NESTING_DEPTH",
                c.currentSpan(),
            )
        }
    }
}
