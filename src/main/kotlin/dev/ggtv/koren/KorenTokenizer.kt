package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span

/**
 * Токенизатор `.kn`. Строка — слово, точка, скобка или функция; всё остальное
 * токенизируется здесь. Семантика базовой грамматики совпадает с Cren.
 */
object KorenTokenizer {

    fun tokenize(input: String): List<KorenToken> = tokenizeWithEnv(input, System.getenv())

    internal fun tokenizeWithEnv(input: String, env: Map<String, String>): List<KorenToken> {
        val tokens = mutableListOf<KorenToken>()
        var line = 1
        var col = 1
        var i = 0
        val len = input.length

        fun peek(): Int = if (i < len) input.codePointAt(i) else -1

        fun nextCp(): Int {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            return cp
        }

        fun cpChar(cp: Int) = String(Character.toChars(cp))

        while (i < len) {
            val start = Span(line, col)
            val c = peek()

            fun advance(codePoint: Int = c) {
                i += Character.charCount(codePoint)
                col += 1
            }

            when (c) {
                ' '.code, '\t'.code, '\r'.code -> advance()

                '\n'.code -> {
                    advance()
                    tokens += KorenToken(KorenTokenKind.Newline, start)
                    line += 1
                    col = 1
                }

                '#'.code -> {
                    advance()
                    val text = StringBuilder()
                    while (i < len && peek() != '\n'.code) {
                        text.appendCodePoint(nextCp())
                        col += 1
                    }
                    tokens += KorenToken(KorenTokenKind.Comment(text.toString().trim()), start)
                }

                '"'.code -> {
                    advance()
                    val value = StringBuilder()
                    while (true) {
                        if (i >= len) {
                            throw CrenError.Parse("незакрытая строка: ожидалось «\"»", start)
                        }
                        when (peek()) {
                            '"'.code -> {
                                advance()
                                break
                            }
                            '\\'.code -> {
                                advance()
                                if (i >= len) {
                                    throw CrenError.Parse("незакрытая строка: ожидалось «\"»", start)
                                }
                                when (val escaped = nextCp()) {
                                    '"'.code -> { value.append('"'); col += 1 }
                                    '\\'.code -> { value.append('\\'); col += 1 }
                                    'n'.code -> { value.append('\n'); col += 1 }
                                    't'.code -> { value.append('\t'); col += 1 }
                                    '\n'.code -> { line += 1; col = 1 }
                                    else -> throw CrenError.Parse(
                                        "неизвестный escape: \\${cpChar(escaped)}",
                                        Span(line, col),
                                    )
                                }
                            }
                            '\n'.code -> {
                                value.append('\n')
                                advance()
                                line += 1
                                col = 1
                            }
                            else -> {
                                value.appendCodePoint(nextCp())
                                col += 1
                            }
                        }
                    }
                    tokens += KorenToken(
                        KorenTokenKind.Str(interpolateEnvironment(value.toString(), env, start)),
                        start,
                    )
                }

                '='.code -> { advance(); tokens += KorenToken(KorenTokenKind.Assign, start) }
                '{'.code -> { advance(); tokens += KorenToken(KorenTokenKind.LBrace, start) }
                '}'.code -> { advance(); tokens += KorenToken(KorenTokenKind.RBrace, start) }
                '['.code -> { advance(); tokens += KorenToken(KorenTokenKind.LBracket, start) }
                ']'.code -> { advance(); tokens += KorenToken(KorenTokenKind.RBracket, start) }
                ':'.code -> { advance(); tokens += KorenToken(KorenTokenKind.Colon, start) }
                ','.code -> { advance(); tokens += KorenToken(KorenTokenKind.Comma, start) }

                '.'.code -> { advance(); tokens += KorenToken(KorenTokenKind.Dot, start) }
                '('.code -> { advance(); tokens += KorenToken(KorenTokenKind.LParen, start) }
                ')'.code -> { advance(); tokens += KorenToken(KorenTokenKind.RParen, start) }

                '-'.code, in '0'.code..'9'.code -> {
                    val number = StringBuilder()
                    if (c == '-'.code) {
                        number.append('-')
                        advance()
                        if (peek() !in '0'.code..'9'.code) {
                            while (i < len) {
                                val next = peek()
                                if (next.isWordChar()) {
                                    number.appendCodePoint(next)
                                    advance()
                                } else {
                                    break
                                }
                            }
                            tokens += KorenToken(KorenTokenKind.Word(number.toString()), start)
                            continue
                        }
                    }

                    var floating = false
                    while (i < len && peek() in '0'.code..'9'.code) {
                        number.appendCodePoint(nextCp())
                        col += 1
                    }
                    if (peek() == '.'.code) {
                        val lookahead = i + 1
                        if (lookahead < len && input.codePointAt(lookahead) in '0'.code..'9'.code) {
                            floating = true
                            number.append('.')
                            advance()
                            while (i < len && peek() in '0'.code..'9'.code) {
                                number.appendCodePoint(nextCp())
                                col += 1
                            }
                        }
                    }

                    var wordSuffix = false
                    while (i < len && peek().isWordChar()) {
                        number.appendCodePoint(nextCp())
                        col += 1
                        wordSuffix = true
                    }
                    if (wordSuffix) {
                        if (floating) {
                            throw CrenError.Parse("после дробного числа ожидался разделитель", start)
                        }
                        tokens += KorenToken(KorenTokenKind.Word(number.toString()), start)
                        continue
                    }

                    val text = number.toString()
                    tokens += if (floating) {
                        val value = text.toDoubleOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)
                        if (!value.isFinite()) {
                            throw CrenError.Parse("число вне диапазона f64: «$text»", start)
                        }
                        KorenToken(KorenTokenKind.Float(value), start)
                    } else {
                        KorenToken(
                            KorenTokenKind.Int(
                                text.toLongOrNull()
                                    ?: throw CrenError.Parse("неверное число: «$text»", start),
                            ),
                            start,
                        )
                    }
                }

                '$'.code -> {
                    val raw = StringBuilder()
                    var depth = 0
                    while (i < len) {
                        val next = peek()
                        val opensSubstitution = depth == 0 && next == '{'.code &&
                            raw.isNotEmpty() && raw[raw.length - 1] == '$'
                        if (!opensSubstitution && depth == 0 &&
                            (Character.isWhitespace(next) || next == ','.code || next == '{'.code ||
                                next == '}'.code || next == '['.code || next == ']'.code ||
                                next == '('.code || next == ')'.code || next == '#'.code ||
                                next == '"'.code || next == '\''.code)
                        ) {
                            break
                        }
                        when {
                            opensSubstitution -> depth += 1
                            next == '}'.code -> depth -= 1
                        }
                        raw.appendCodePoint(next)
                        advance(next)
                    }
                    tokens += KorenToken(
                        KorenTokenKind.Str(interpolateEnvironment(raw.toString(), env, start)),
                        start,
                    )
                }

                else -> {
                    if (c.isWordStart()) {
                        val word = StringBuilder()
                        while (i < len && peek().isWordChar()) {
                            word.appendCodePoint(nextCp())
                            col += 1
                        }
                        val text = word.toString()
                        val kind = when (text) {
                            "true" -> KorenTokenKind.Bool(true)
                            "false" -> KorenTokenKind.Bool(false)
                            else -> KorenTokenKind.Word(text)
                        }
                        tokens += KorenToken(kind, start)
                    } else {
                        throw CrenError.Parse("неожиданный символ: «${cpChar(c)}»", start)
                    }
                }
            }
        }

        return tokens
    }

    private fun Int.isWordChar(): Boolean =
        Character.isLetterOrDigit(this) || this == '_'.code || this == '-'.code

    private fun Int.isWordStart(): Boolean =
        Character.isLetter(this) || this == '_'.code

    private data class EnvironmentDefault(val value: String, val useIfEmpty: Boolean)

    private fun interpolateEnvironment(
        input: String,
        env: Map<String, String>,
        span: Span,
    ): String {
        val output = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val current = input[i]
            if (current != '$') {
                output.append(current)
                i += 1
                continue
            }
            when (val next = input.getOrNull(i + 1)) {
                '$' -> {
                    output.append('$')
                    i += 2
                }
                '{' -> {
                    val end = input.indexOf('}', i + 2)
                    if (end < 0) {
                        throw CrenError.Parse("незакрытая подстановка окружения", span)
                    }
                    val expression = input.substring(i + 2, end)
                    val (name, default) = splitEnvironmentExpression(expression, span)
                    pushEnvironmentValue(output, name, default, env, span)
                    i = end + 1
                }
                else -> {
                    if (next != null && isEnvironmentNameStart(next)) {
                        var end = i + 2
                        while (end < input.length && isEnvironmentNameContinue(input[end])) end += 1
                        val name = input.substring(i + 1, end)
                        pushEnvironmentValue(output, name, null, env, span)
                        i = end
                    } else {
                        output.append('$')
                        if (next != null) {
                            output.append(next)
                            i += 2
                        } else {
                            i += 1
                        }
                    }
                }
            }
        }
        return output.toString()
    }

    private fun splitEnvironmentExpression(
        expression: String,
        span: Span,
    ): Pair<String, EnvironmentDefault?> {
        val separator = expression.indexOf(":-").takeIf { it >= 0 }
            ?: expression.indexOf('-').takeIf { it >= 0 }
        val name = if (separator == null) expression else expression.substring(0, separator)
        val default = if (separator == null) null else EnvironmentDefault(
            value = expression.substring(
                separator + if (expression.startsWith(":-", separator)) 2 else 1,
            ),
            useIfEmpty = expression.startsWith(":-", separator),
        )
        if (name.isEmpty() || !isEnvironmentNameStart(name[0]) ||
            name.drop(1).any { !isEnvironmentNameContinue(it) }
        ) {
            throw CrenError.Parse("неверное имя переменной окружения в «\${$expression}»", span)
        }
        return name to default
    }

    private fun pushEnvironmentValue(
        output: StringBuilder,
        name: String,
        default: EnvironmentDefault?,
        env: Map<String, String>,
        span: Span,
    ) {
        val value = env[name]
        if (value != null) {
            output.append(
                if (default != null && default.useIfEmpty && value.isEmpty()) default.value else value,
            )
            return
        }
        if (default != null) {
            output.append(default.value)
            return
        }
        throw CrenError.Parse("переменная окружения «$name» не задана", span)
    }

    private fun isEnvironmentNameStart(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value == '_'

    private fun isEnvironmentNameContinue(value: Char): Boolean =
        isEnvironmentNameStart(value) || value in '0'..'9'
}

sealed interface KorenTokenKind {
    data class Word(val w: String) : KorenTokenKind
    data class Str(val s: String) : KorenTokenKind
    data class Int(val i: Long) : KorenTokenKind
    data class Float(val f: Double) : KorenTokenKind
    data class Bool(val b: Boolean) : KorenTokenKind
    data object Assign : KorenTokenKind
    data object LBrace : KorenTokenKind
    data object RBrace : KorenTokenKind
    data object LBracket : KorenTokenKind
    data object RBracket : KorenTokenKind
    data object Colon : KorenTokenKind
    data object Comma : KorenTokenKind
    data object Dot : KorenTokenKind
    data object LParen : KorenTokenKind
    data object RParen : KorenTokenKind
    data class Comment(val text: String) : KorenTokenKind
    data object Newline : KorenTokenKind
}

data class KorenToken(val kind: KorenTokenKind, val span: Span) {
    override fun toString(): String = kind.toString()
}
