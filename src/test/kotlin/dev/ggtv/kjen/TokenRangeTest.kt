package dev.ggtv.kjen

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Границы токенов — то, ради чего в [Token] добавлен [Token.end].
 *
 * Позиция конца нужна редактору: точку [Span] можно только показать в
 * сообщении, а подчёркивание требует диапазона. Раньше токенизатор конец
 * знал (`line`/`col` смотрят на следующий символ), но выбрасывал, и
 * «вы здесь накосячили» приходилось показывать курсором без подсветки.
 *
 * Соглашение проверяется здесь целиком: [TextRange.end] — позиция **сразу
 * после** последнего символа, как `substring` в Kotlin, а не позиция
 * последнего символа. Если это перепутать, подчёркивание уедет на один
 * символ вправо, и на односимвольных токенах это будет выглядеть
 * правильно — ошибка обнаружится только на многосимвольных.
 */
class TokenRangeTest {
    private fun tokens(input: String): List<Token> = Tokenizer.tokenize(input)

    /**
     * Вырезать из исходника кусок, покрытый диапазоном.
     *
     * Позиции 1-based, как в [Span], а [String.substring] ждёт индексы с нуля,
     * поэтому здесь обратное преобразование. Считается по строкам, а не по
     * одному общему смещению: многострочные токены иначе не вырезаются.
     */
    private fun String.slice(range: TextRange): String {
        val lines = split('\n')
        if (range.start.line == range.end.line) {
            val line = lines[range.start.line - 1]
            return line.substring(range.start.col - 1, range.end.col - 1)
        }
        val sb = StringBuilder()
        for (lineNum in range.start.line..range.end.line) {
            val line = lines[lineNum - 1]
            val from = if (lineNum == range.start.line) range.start.col - 1 else 0
            val to = if (lineNum == range.end.line) range.end.col - 1 else line.length
            sb.append(line, from, to)
        }
        return sb.toString()
    }

    @Test
    fun `односимвольный токен занимает ровно один символ`() {
        val t = tokens("=").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 2), t.end, "конец — сразу за символом, не на нём")
        assertFalse(t.range.isEmpty)
    }

    @Test
    fun `слово занимает от первой буквы до последней`() {
        val t = tokens("server").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 7), t.end)
    }

    /**
     * Настоящий инвариант: текст, вырезанный по диапазону токена, обязан быть
     * самим токеном.
     *
     * Сначала здесь стоял другой тест — «токены идут подряд, конец одного равен
     * началу следующего». Он падал на `port = 8080`, и падал правильно: между
     * токенами пробел, и проверка была бессмысленной. Этот сильнее: если
     * токенизатор потеряет символ при подсчёте колонок, тест это поймает,
     * потому что вырезанный текст перестанет совпадать.
     */
    @Test
    fun `вырезанный по диапазону текст совпадает с токеном`() {
        val input = "port = 8080 # слушать тут\n"
        for (t in tokens(input)) {
            if (t.kind == TokenKind.Newline) continue
            val slice = input.slice(t.range)
            assertTrue(
                slice.isNotEmpty(),
                "токен ${t.kind} на ${t.span} вырезался пустым",
            )
            when (val k = t.kind) {
                is TokenKind.Word -> assertEquals(k.w, slice, "слово не совпало с исходником")
                is TokenKind.Str -> assertTrue(slice.startsWith('"') && slice.endsWith('"'), "строка без кавычек: «$slice»")
                is TokenKind.Int -> assertEquals(slice, k.i.toString())
                is TokenKind.Comment -> assertTrue(slice.startsWith('#'), "комментарий без решётки: «$slice»")
                else -> assertTrue(
                    k.toString().isNotEmpty(),
                    "у ${t.kind} нет своего текста для проверки",
                )
            }
        }
    }

    @Test
    fun `границы не пересекаются`() {
        val ts = tokens("port = 8080 # слушать тут\n")
            .filterNot { it.kind == TokenKind.Newline }
        for (a in ts.zipWithNext()) {
            val (first, second) = a
            assertTrue(
                first.end.isBefore(second.span) || first.end == second.span,
                "токен ${first.kind} заходит на ${second.kind}",
            )
        }
    }

    @Test
    fun `строка вместе с кавычками`() {
        val t = tokens("\"ab\"").single()
        assertEquals(Span(1, 1), t.span, "начало — открывающая кавычка")
        assertEquals(Span(1, 5), t.end, "конец — сразу за закрывающей кавычкой")
    }

    @Test
    fun `пустая строка не пустой диапазон`() {
        val t = tokens("\"\"").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 3), t.end)
        assertFalse(t.range.isEmpty, "две кавычки — это два символа")
    }

    @Test
    fun `комментарий до конца строки`() {
        val t = tokens("# привет\n").single { it.kind is TokenKind.Comment }
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 9), t.end, "«# привет» — восемь символов, конец на девятом")
    }

    @Test
    fun `многострочная строка помечается как многострочная`() {
        val t = tokens("\"a\nb\"").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(2, 3), t.end, "пять символов: кавычка, a, перевод, b, кавычка")
        assertTrue(t.isMultiline)
    }

    @Test
    fun `комментарий не залезает на следующую строку`() {
        val t = tokens("# раз\n# два\n").first { it.kind is TokenKind.Comment }
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 6), t.end, "«# раз» — пять символов, конец на шестом")
    }

    @Test
    fun `после перевода строки колонка начинается заново`() {
        val ts = tokens("a\nbb")
        val second = ts.last { it.kind is TokenKind.Word }
        assertEquals(Span(2, 1), second.span)
        assertEquals(Span(2, 3), second.end)
    }

    @Test
    fun `экранированная кавычка не сбивает границы`() {
        val t = tokens("\"a\\\"b\"").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(1, 7), t.end, "шесть символов: \" a \\\" b \"")
    }

    @Test
    fun `merge двух диапазонов покрывает оба`() {
        val a = TextRange(Span(1, 5), Span(1, 8))
        val b = TextRange(Span(1, 2), Span(1, 4))
        assertEquals(TextRange(Span(1, 2), Span(1, 8)), a.merge(b))
    }

    @Test
    fun `merge учитывает перевод строки, а не только колонку`() {
        val a = TextRange(Span(1, 5), Span(1, 8))
        val b = TextRange(Span(2, 1), Span(2, 3))
        val m = a.merge(b)
        assertEquals(Span(1, 5), m.start, "начало осталось на первой строке")
        assertEquals(Span(2, 3), m.end, "конец уехал на вторую строку")
    }

    @Test
    fun `merge с самим собой ничего не меняет`() {
        val a = TextRange(Span(3, 4), Span(3, 9))
        assertEquals(a, a.merge(a))
    }

    @Test
    fun `пустой диапазон в точке`() {
        val e = TextRange.emptyAt(Span(2, 7))
        assertTrue(e.isEmpty)
        assertEquals(Span(2, 7), e.span)
    }
}
