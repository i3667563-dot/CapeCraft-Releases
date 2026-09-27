package dev.ggtv.kjen

/**
 * Токенизатор: текст конфига → список токенов.
 *
 * Токенизатор не знает контекст: слово, точка, скобка — атомы.
 * Ключ это, тип, бул или ссылка — решает парсер.
 * Всё, что однозначно (число, строка, бул, коммент), токенизируется здесь.
 */
object Tokenizer {

    /** Разобрать входной текст на токены. */
    fun tokenize(input: String): List<Token> = tokenizeWithEnv(input, System.getenv())

    internal fun tokenizeWithEnv(input: String, env: Map<String, String>): List<Token> {
        val tokens = mutableListOf<Token>()
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

            /**
             * Добавить токен, начало которого — [start], а конец — текущая
             * позиция после сканирования.
             *
             * Раньше токены создавались вразнобой как `Token(kind, start)`, и
             * позиция конца терялась: токенизатор её знает (`line`/`col` уже
             * смотрят на следующий символ), но сохранять было некуда. Один
             * `emit` вместо пятнадцати прямых вызовов — заодно не даёт
             * забыть про конец в новом виде токена.
             */
            fun emit(kind: TokenKind) {
                tokens += Token(kind, start, Span(line, col))
            }

            val c = peek()

            fun advance(codePoint: Int = c) {
                i += Character.charCount(codePoint)
                col += 1
            }

            when (c) {
                ' '.code, '\t'.code, '\r'.code -> advance()

                '\n'.code -> {
                    advance()
                    emit(TokenKind.Newline)
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
                    emit(TokenKind.Comment(text.toString().trim()))
                }

                '"'.code -> {
                    advance()
                    val s = StringBuilder()
                    while (true) {
                        if (i >= len) {
                            throw CrenError.Parse("незакрытая строка: ожидалось «\"»", start)
                        }
                        val ch = peek()
                        when (ch) {
                            '"'.code -> {
                                advance()
                                break
                            }
                            '\\'.code -> {
                                advance()
                                if (i >= len) {
                                    throw CrenError.Parse("незакрытая строка: ожидалось «\"»", start)
                                }
                                when (val esc = nextCp()) {
                                    '"'.code -> { s.append('"'); col += 1 }
                                    '\\'.code -> { s.append('\\'); col += 1 }
                                    'n'.code -> { s.append('\n'); col += 1 }
                                    't'.code -> { s.append('\t'); col += 1 }
                                    '\n'.code -> { line += 1; col = 1 }
                                    else -> throw CrenError.Parse("неизвестный escape: \\${cpChar(esc)}", Span(line, col))
                                }
                            }
                            '\n'.code -> {
                                s.append('\n'); advance(); line += 1; col = 1
                            }
                            else -> {
                                s.appendCodePoint(nextCp()); col += 1
                            }
                        }
                    }
                    val value = interpolateEnvironment(s.toString(), env, start)
                    emit(TokenKind.Str(value))
                }

                '='.code -> { advance(); emit(TokenKind.Assign) }
                '{'.code -> { advance(); emit(TokenKind.LBrace) }
                '}'.code -> { advance(); emit(TokenKind.RBrace) }
                '['.code -> { advance(); emit(TokenKind.LBracket) }
                ']'.code -> { advance(); emit(TokenKind.RBracket) }
                ':'.code -> { advance(); emit(TokenKind.Colon) }
                ','.code -> { advance(); emit(TokenKind.Comma) }
                '.'.code -> { advance(); emit(TokenKind.Dot) }

                '-'.code, in '0'.code..'9'.code -> {
                    val num = StringBuilder()
                    if (c == '-'.code) {
                        num.append('-')
                        advance()
                        // Не число — дочитываем слово с дефисом (ключ `my-key`).
                        if (peek() !in '0'.code..'9'.code) {
                            while (i < len) {
                                val c2 = peek()
                                if (c2.isWordChar()) {
                                    num.appendCodePoint(c2)
                                    advance()
                                } else break
                            }
                            emit(TokenKind.Word(num.toString()))
                            continue
                        }
                    }
                    var floating = false
                    while (i < len && peek() in '0'.code..'9'.code) {
                        num.appendCodePoint(nextCp()); col += 1
                    }
                    // Дробная часть — только если за точкой идёт цифра.
                    if (peek() == '.'.code) {
                        val lookahead = i + 1
                        if (lookahead < len && input.codePointAt(lookahead) in '0'.code..'9'.code) {
                            floating = true
                            num.append('.')
                            advance() // точка
                            while (i < len && peek() in '0'.code..'9'.code) {
                                num.appendCodePoint(nextCp()); col += 1
                            }
                        }
                    }
                    // Число — только если за ним НЕ идёт символ слова (`2fa` — слово).
                    var wordSuffix = false
                    while (i < len && peek().isWordChar()) {
                        num.appendCodePoint(nextCp()); col += 1
                        wordSuffix = true
                    }
                    if (wordSuffix) {
                        if (floating) {
                            throw CrenError.Parse("после дробного числа ожидался разделитель", start)
                        }
                        emit(TokenKind.Word(num.toString()))
                        continue
                    }
                    val text = num.toString()
                    if (floating) {
                        val value = text.toDoubleOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)
                        if (!value.isFinite()) {
                            throw CrenError.Parse("число вне диапазона f64: «$text»", start)
                        }
                        emit(TokenKind.Float(value))
                    } else {
                        emit(TokenKind.Int(text.toLongOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)))
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
                                next == '#'.code || next == '"'.code || next == '\''.code)
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
                    val value = interpolateEnvironment(raw.toString(), env, start)
                    emit(TokenKind.Str(value))
                }

                else -> {
                    if (c.isWordStart()) {
                        val word = StringBuilder()
                        while (i < len && peek().isWordChar()) {
                            word.appendCodePoint(nextCp()); col += 1
                        }
                        val w = word.toString()
                        val kind = when (w) {
                            "true" -> TokenKind.Bool(true)
                            "false" -> TokenKind.Bool(false)
                            else -> TokenKind.Word(w)
                        }
                        emit(kind)
                    } else {
                        throw CrenError.Parse("неожиданный символ: «${cpChar(c)}»", start)
                    }
                }
            }
        }

        return tokens
    }

    // Unicode-семантика как в Rust (is_alphanumeric / is_alphabetic),
    // а не ASCII-only — `ключ` и `привет-мир` валидные слова.
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
            value = expression.substring(separator + if (expression.startsWith(":-", separator)) 2 else 1),
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
            output.append(if (default != null && default.useIfEmpty && value.isEmpty()) default.value else value)
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

/** Виды токенов. */
sealed interface TokenKind {
    /** Слово: ключ, имя типа, сегмент пути. */
    data class Word(val w: String) : TokenKind
    /** Строка в кавычках; `#` внутри — часть строки. */
    data class Str(val s: String) : TokenKind
    data class Int(val i: Long) : TokenKind
    data class Float(val f: Double) : TokenKind
    data class Bool(val b: Boolean) : TokenKind
    data object Assign : TokenKind
    data object LBrace : TokenKind
    data object RBrace : TokenKind
    data object LBracket : TokenKind
    data object RBracket : TokenKind
    data object Colon : TokenKind
    data object Comma : TokenKind
    data object Dot : TokenKind
    /** Комментарий `# ... до конца строки` — сохраняется. */
    data class Comment(val text: String) : TokenKind
    data object Newline : TokenKind
}

/**
 * Токен с позицией в исходнике — для человеческих ошибок.
 *
 * @property span начало токена; ради совместимости остаётся точкой
 * @property end позиция сразу за последним символом, без неё — [span]
 */
data class Token(val kind: TokenKind, val span: Span, val end: Span = span) {
    /**
     * Диапазон токена целиком — то, что редактору нужно для подчёркивания.
     *
     * У точки его нет: односимвольный токен вроде `{` даёт диапазон в один
     * символ, а многострочная строка — от открывающей кавычки до закрывающей.
     */
    val range: TextRange get() = TextRange(span, end)

    /** Длина токена в строках, если он многострочный. */
    val isMultiline: Boolean get() = end.line != span.line

    override fun toString(): String = kind.toString()
}
