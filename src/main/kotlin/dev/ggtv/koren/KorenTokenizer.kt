package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span

/**
 * Токенизатор `.kn`: тот же, что у `.crn` в Kjen, плюс круглые скобки
 * для вызова функций: `clamp(x, 0, 100)`.
 *
 * Синтаксис .crn — строгое подмножество .kn (см. SPEC.md), поэтому
 * любой валидный .crn токенизируется идентично Kjen.
 */
object KorenTokenizer {

    /** Разобрать входной текст на токены. */
    fun tokenize(input: String): List<KorenToken> {
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

            fun advance() {
                i += Character.charCount(c)
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
                    tokens += KorenToken(KorenTokenKind.Str(s.toString()), start)
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
                            tokens += KorenToken(KorenTokenKind.Word(num.toString()), start)
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
                        tokens += KorenToken(KorenTokenKind.Word(num.toString()), start)
                        continue
                    }
                    val text = num.toString()
                    tokens += if (floating) {
                        KorenToken(KorenTokenKind.Float(text.toDoubleOrNull()
                            ?: throw CrenError.Parse("неверное число: «$text»", start)), start)
                    } else {
                        KorenToken(KorenTokenKind.Int(text.toLongOrNull()
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
                            "true" -> KorenTokenKind.Bool(true)
                            "false" -> KorenTokenKind.Bool(false)
                            else -> KorenTokenKind.Word(w)
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

    // Unicode-семантика как в Rust (is_alphanumeric / is_alphabetic),
    // а не ASCII-only — `ключ` и `привет-мир` валидные слова.
    private fun Int.isWordChar(): Boolean =
        Character.isLetterOrDigit(this) || this == '_'.code || this == '-'.code

    private fun Int.isWordStart(): Boolean =
        Character.isLetter(this) || this == '_'.code
}

/** Виды токенов .kn: как .crn + скобки для вызовов функций. */
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

/** Токен с позицией в исходнике. */
data class KorenToken(val kind: KorenTokenKind, val span: Span) {
    override fun toString(): String = kind.toString()
}