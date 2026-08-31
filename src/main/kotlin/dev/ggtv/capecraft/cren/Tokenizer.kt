package dev.ggtv.capecraft.cren

/**
 * Токенизатор: текст конфига → список токенов.
 *
 * Токенизатор не знает контекст: слово, точка, скобка — атомы.
 * Ключ это, тип, бул или ссылка — решает парсер.
 * Всё, что однозначно (число, строка, бул, коммент), токенизируется здесь.
 */
object Tokenizer {

    /** Разобрать входной текст на токены. */
    fun tokenize(input: String): List<Token> {
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
            val c = peek()

            fun advance() {
                i += Character.charCount(c)
                col += 1
            }

            when (c) {
                ' '.code, '\t'.code, '\r'.code -> advance()

                '\n'.code -> {
                    advance()
                    tokens += Token(TokenKind.Newline, start)
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
                    tokens += Token(TokenKind.Comment(text.toString().trim()), start)
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
                    tokens += Token(TokenKind.Str(s.toString()), start)
                }

                '='.code -> { advance(); tokens += Token(TokenKind.Assign, start) }
                '{'.code -> { advance(); tokens += Token(TokenKind.LBrace, start) }
                '}'.code -> { advance(); tokens += Token(TokenKind.RBrace, start) }
                '['.code -> { advance(); tokens += Token(TokenKind.LBracket, start) }
                ']'.code -> { advance(); tokens += Token(TokenKind.RBracket, start) }
                ':'.code -> { advance(); tokens += Token(TokenKind.Colon, start) }
                ','.code -> { advance(); tokens += Token(TokenKind.Comma, start) }
                '.'.code -> { advance(); tokens += Token(TokenKind.Dot, start) }

                '-'.code, in '0'.code..'9'.code -> {
                    val num = StringBuilder()
                    var isNegative = false
                    if (c == '-'.code) {
                        num.append('-')
                        advance()
                        isNegative = true
                        // Не число — дочитываем слово с дефисом (ключ `my-key`).
                        if (peek() !in '0'.code..'9'.code) {
                            while (i < len) {
                                val c2 = peek()
                                if (c2.isWordChar()) {
                                    num.appendCodePoint(c2)
                                    advance()
                                } else break
                            }
                            tokens += Token(TokenKind.Word(num.toString()), start)
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
                        tokens += Token(TokenKind.Word(num.toString()), start)
                        continue
                    }
                    // isNegative тут не используется отдельно: знак уже в строке.
                    val text = num.toString()
                    tokens += if (floating) {
                        Token(TokenKind.Float(text.toDoubleOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)), start)
                    } else {
                        Token(TokenKind.Int(text.toLongOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)), start)
                    }
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
                        tokens += Token(kind, start)
                    } else {
                        throw CrenError.Parse("неожиданный символ: «${cpChar(c)}»", start)
                    }
                }
            }
        }

        return tokens
    }

    private fun Int.isWordChar(): Boolean =
        this in '0'.code..'9'.code || this in 'a'.code..'z'.code ||
            this in 'A'.code..'Z'.code || this == '_'.code || this == '-'.code

    private fun Int.isWordStart(): Boolean =
        this in 'a'.code..'z'.code || this in 'A'.code..'Z'.code || this == '_'.code
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

/** Токен с позицией в исходнике — для человеческих ошибок. */
data class Token(val kind: TokenKind, val span: Span) {
    override fun toString(): String = kind.toString()
}