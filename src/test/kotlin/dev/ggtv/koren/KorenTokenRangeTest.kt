package dev.ggtv.koren

import dev.ggtv.kjen.Span
import dev.ggtv.kjen.TextRange
import dev.ggtv.kjen.isBefore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Границы токенов `.kn` — то же, что [dev.ggtv.kjen.TokenRangeTest], но своё.
 *
 * Отдельный тест, а не переиспользование kjen-ового, потому что токенизаторы
 * правятся отдельно и расходятся: у koren есть скобки для вызовов функций
 * и своя подстановка окружения. Когда механическая правка одного из них
 * проходит мимо ветки (так было с числом, собранным в тернарнике мимо
 * `emit`), это ловит только свой тест на своём токенизаторе.
 */
class KorenTokenRangeTest {
    private fun tokens(input: String): List<KorenToken> = KorenTokenizer.tokenize(input)

    /**
     * Вырезать из исходника кусок, покрытый диапазоном.
     *
     * Копия хелпера из kjen: `kotlin.test` тут не экспортирует его наружу, а
     * тащить тестовую зависимость ради одной функции смысла нет.
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
    fun `вырезанный по диапазону текст совпадает с токеном`() {
        val input = "port = 8080 # слушать тут\n"
        for (t in tokens(input)) {
            if (t.kind == KorenTokenKind.Newline) continue
            val slice = input.slice(t.range)
            assertTrue(slice.isNotEmpty(), "токен ${t.kind} на ${t.span} вырезался пустым")
            when (val k = t.kind) {
                is KorenTokenKind.Word -> assertEquals(k.w, slice, "слово не совпало с исходником")
                is KorenTokenKind.Str ->
                    assertTrue(slice.startsWith('"') && slice.endsWith('"'), "строка без кавычек: «$slice»")
                is KorenTokenKind.Int -> assertEquals(slice, k.i.toString())
                is KorenTokenKind.Comment -> assertTrue(slice.startsWith('#'), "комментарий без решётки: «$slice»")
                else -> assertTrue(k.toString().isNotEmpty(), "у ${t.kind} нет своего текста")
            }
        }
    }

    @Test
    fun `скобки вызова функции занимают один символ`() {
        val ts = tokens("f(x)").filterNot { it.kind == KorenTokenKind.Newline }
        val lparen = ts.single { it.kind == KorenTokenKind.LParen }
        val rparen = ts.single { it.kind == KorenTokenKind.RParen }
        assertEquals(Span(1, 2), lparen.span)
        assertEquals(Span(1, 3), lparen.end)
        assertEquals(Span(1, 4), rparen.span)
        assertEquals(Span(1, 5), rparen.end)
    }

    /**
     * Подстановка окружения не должна сбивать границы токена.
     *
     * Окружение надо задать явно: koren-токенизатор подставляет значения на
     * лету и падает на незаданной переменной, а не оставляет `$` как есть.
     * Проверяем именно длину исходника, а не результат подстановки: если бы
     * границы считались по подставленному тексту, длина бы разошлась.
     */
    @Test
    fun `подстановка окружения не сбивает границы`() {
        val input = "a = \"\${HOST}/capes/\${PORT}.png\""
        val t = KorenTokenizer.tokenizeWithEnv(input, mapOf("HOST" to "example.com", "PORT" to "8080"))
            .single { it.kind is KorenTokenKind.Str }
        assertEquals(Span(1, 5), t.span, "начало — открывающая кавычка")
        assertEquals(
            input.slice(t.range),
            "\"\${HOST}/capes/\${PORT}.png\"",
            "диапазон должен покрывать исходник с кавычками, а не подставленное значение",
        )
    }

    @Test
    fun `число с дробной частью не пустой диапазон`() {
        val t = tokens("a = 1.5").single { it.kind is KorenTokenKind.Float }
        assertEquals("1.5", "a = 1.5".slice(t.range), "точка тоже входит в диапазон")
    }

    @Test
    fun `отрицательное число`() {
        val t = tokens("a = -12").single { it.kind is KorenTokenKind.Int }
        assertEquals("-12", "a = -12".slice(t.range), "минус входит в диапазон")
    }

    @Test
    fun `слово с дефисом не считается числом`() {
        val t = tokens("my-key = 1").first { it.kind is KorenTokenKind.Word }
        assertEquals("my-key", "my-key = 1".slice(t.range))
    }

    @Test
    fun `многострочная строка помечается как многострочная`() {
        val t = tokens("\"a\nb\"").single()
        assertEquals(Span(1, 1), t.span)
        assertEquals(Span(2, 3), t.end)
        assertTrue(t.isMultiline)
    }

    @Test
    fun `границы не пересекаются`() {
        val ts = tokens("port = 8080 # слушать тут\n").filterNot { it.kind == KorenTokenKind.Newline }
        for ((first, second) in ts.zipWithNext()) {
            assertTrue(
                first.end.isBefore(second.span) || first.end == second.span,
                "токен ${first.kind} заходит на ${second.kind}",
            )
        }
    }

    @Test
    fun `пустая строка не пустой диапазон`() {
        val t = tokens("\"\"").single()
        assertFalse(t.range.isEmpty)
    }
}
