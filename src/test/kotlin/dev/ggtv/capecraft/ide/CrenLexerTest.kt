package dev.ggtv.capecraft.ide

import dev.ggtv.kjen.TextRange
import dev.ggtv.kjen.isBefore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты терпимого сканера.
 *
 * Главный тест здесь — [каждая лексема вырезается своим диапазоном]: он
 * проверяет все границы сразу, на любом тексте, и ловит ошибку на единицу в
 * любой ветке сканера. Остальные тесты точечные: подстановки, непарные
 * строки, числа против путей ссылки.
 */
class CrenLexerTest {
    /** Исходник для круговой сверки границ. */
    private val sample = """
        capeCraft {
            "name": "Тестовый ${'$'}{HOST:-localhost}:${'$'}{PORT} сервер",
            "port": 8080,
            "ratio": -1.5,
            "url": "http://a.b.c/x",
            "server.token": "${'$'}{SECRET_TOKEN}",
            "price": 30,
            "enabled": true,
            # комментарий
        }
    """.trimIndent() + "\n"

    /** Вырезать диапазон 1-based из исходника. */
    private fun slice(text: String, range: TextRange): String {
        val starts = ArrayList<Int>()
        starts += 0
        for (k in text.indices) if (text[k] == '\n') starts += k + 1
        val from = starts[range.start.line - 1] + (range.start.col - 1)
        val to = starts[range.end.line - 1] + (range.end.col - 1)
        return text.substring(from, to)
    }

    /** Сверить, что диапазоны лексем не наезжают друг на друга. */
    private fun assertRangesConsistent(text: String, lexemes: List<CrenLexeme>) {
        for (l in lexemes) {
            assertEquals(l.text, slice(text, l.range), "диапазон '${l.text}' не вырезает себя")
        }
        for (k in 1 until lexemes.size) {
            val prev = lexemes[k - 1].range
            val cur = lexemes[k].range
            assertFalse(
                cur.start.isBefore(prev.end),
                "лексемы пересеклись: '${lexemes[k - 1].text}' и '${lexemes[k].text}'",
            )
        }
    }

    @Test
    fun `каждая лексема вырезается своим диапазоном`() {
        assertRangesConsistent(sample, CrenLexer.lex(sample))
    }

    @Test
    fun `пустых лексем не бывает`() {
        assertTrue(CrenLexer.lex(sample).none { it.text.isEmpty() })
    }

    @Test
    fun `подстановка в фигурных скобках с дефолтом`() {
        val text = """name = "X ${'$'}{HOST:-localhost} Y""" + "\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        val sub = str.parts.filterIsInstance<StrPart.Substitution>().single()
        assertEquals("HOST", sub.name)
        assertTrue(sub.braced)
        assertTrue(sub.hasDefault)
        assertEquals("\${HOST:-localhost}", slice(text, sub.range))
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `подстановка без фигурных скобок`() {
        val text = """name = "X ${'$'}PORT Y""" + "\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        val sub = str.parts.filterIsInstance<StrPart.Substitution>().single()
        assertEquals("PORT", sub.name)
        assertFalse(sub.braced)
        assertFalse(sub.hasDefault)
        assertEquals("\$PORT", slice(text, sub.range))
    }

    @Test
    fun `имя переменной включает дефис и подчёркивание`() {
        val text = """a = "${'$'}{BASE-URL}" b = "${'$'}{A_B}" c = "${'$'}A-B""" + "\"\n"
        val subs = CrenLexer.lex(text)
            .filter { it.kind == CrenLexKind.STR }
            .flatMap { it.parts }
            .filterIsInstance<StrPart.Substitution>()
        assertEquals(listOf("BASE-URL", "A_B", "A-B"), subs.map { it.name })
        // Дефис — часть имени, а не разделитель дефолта.
        assertTrue(subs.none { it.hasDefault }, "одиночный дефис дефолтом не считается")
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `имя обрывается точкой и слэшем, хвост остаётся текстом`() {
        val text = """url = "${'$'}BASE/api/cape.png""" + "\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        val sub = str.parts.filterIsInstance<StrPart.Substitution>().single()
        assertEquals("BASE", sub.name)
        assertEquals("/api/cape.png", slice(text, str.parts.filterIsInstance<StrPart.Literal>().single().range))
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `две подстановки подряд дают две части и литерал между ними`() {
        val text = """name = "a${'$'}{A}b${'$'}{B}c""" + "\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        val subs = str.parts.filterIsInstance<StrPart.Substitution>()
        assertEquals(listOf("A", "B"), subs.map { it.name })
        // "a${A}b${B}c" — это три литерала: до, между и после.
        assertEquals(listOf("a", "b", "c"), str.parts.filterIsInstance<StrPart.Literal>().map { slice(text, it.range) })
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `экранированный доллар не подстановка`() {
        val text = "name = \"cost \$\$5\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        assertTrue(
            str.parts.none { it is StrPart.Substitution },
            "доллар не должен считаться подстановкой",
        )
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `одиночный доллар не подстановка`() {
        val text = "name = \"100% \$ ok\"\n"
        val str = CrenLexer.lex(text).last { it.kind == CrenLexKind.STR }
        assertTrue(str.parts.none { it is StrPart.Substitution })
        assertFalse(str.unterminated)
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `незакрытая строка одна лексема до конца файла`() {
        val text = "a = 1\nname = \"ой"
        val lexemes = CrenLexer.lex(text)
        val str = lexemes.last { it.kind == CrenLexKind.STR }
        assertTrue(str.unterminated)
        assertEquals("\"ой", str.text)
        assertEquals(str, lexemes.last(), "незакрытая строка должна быть последней лексемой")
        assertRangesConsistent(text, lexemes)
    }

    @Test
    fun `незакрытая строка не мешает разбирать дальше`() {
        // Мусор после непарной кавычки не должен ломать остальной файл:
        // иначе при правке одной строки пропадёт подсветка всего остального.
        val text = "name = \"ой\nport = 8080\n"
        val lexemes = CrenLexer.lex(text)
        assertTrue(lexemes.any { it.text == "port" }, "ключ после непарной строки потерялся")
        assertTrue(lexemes.any { it.text == "8080" })
        assertRangesConsistent(text, lexemes)
    }

    @Test
    fun `многострочная строка считает строки правильно`() {
        val text = "a = \"one\ntwo\nthree\"\nb = 2"
        val str = CrenLexer.lex(text).first { it.kind == CrenLexKind.STR }
        assertEquals(1, str.range.start.line)
        assertEquals(3, str.range.end.line, "строка закрылась на третьей строке")
        assertEquals("\"one\ntwo\nthree\"", str.text)
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `число со знаком и дробное`() {
        val words = CrenLexer.lex("x = -12 y = 1.5").filter { it.kind == CrenLexKind.WORD }
        assertEquals(listOf("x", "-12", "y", "1.5"), words.map { it.value })
        assertEquals(listOf("-12", "1.5"), words.map { it.value }.filter { it.first() == '-' || it.first().isDigit() })
    }

    @Test
    fun `путь ссылки не путается с дробным числом`() {
        // Точка без цифры после неё — часть слова, иначе `server.token`
        // распалось бы на `server`, `.`, `token`.
        val words = CrenLexer.lex("server.token = 1").filter { it.kind == CrenLexKind.WORD }
        assertEquals(listOf("server.token", "1"), words.map { it.value })
    }

    @Test
    fun `комментарий до перевода строки`() {
        val text = "a = 1 # примечание\nb = 2"
        val c = CrenLexer.lex(text).single { it.kind == CrenLexKind.COMMENT }
        assertEquals("# примечание", c.text)
        assertEquals("примечание", c.value)
        assertRangesConsistent(text, CrenLexer.lex(text))
    }

    @Test
    fun `перевод строки отдельная лексема`() {
        assertEquals(listOf("a", "\n", "b"), CrenLexer.lex("a\nb").map { it.text })
    }

    @Test
    fun `мусор не теряется`() {
        val lexemes = CrenLexer.lex("a = 1 ; b = 2")
        assertTrue(lexemes.any { it.kind == CrenLexKind.UNKNOWN && it.text == ";" })
    }

    @Test
    fun `пустой файл даёт пустой список`() {
        assertTrue(CrenLexer.lex("").isEmpty())
    }

    @Test
    fun `сканер не падает и не теряет границы ни на чём`() {
        val nasty = listOf(
            "", " ", "\n\n\n", "\"", "\"\"", "\"\"\"", "\\", "a = ", "a =", "=",
            "{", "}", "[", "]", ":", ",", ".", "(", ")", "a { b [ c : \"",
            "\u00ABкавычки\u00BB", " ", "a = \";", "#", "# \u00e9",
            "a = \"\${", "a = \"\${}\"", "a = \"\$\"", "a = \"\${:-}\"",
            "a = \"\${A", "-", "-a", "1.", ".5", "a.b.c.d.e", "/path/to/file",
            "%%", "a = 1e5", " capeCraft { \"n\" : \"\${A}\${B}\${C}\" } ",
        )
        for (text in nasty) {
            assertRangesConsistent(text, CrenLexer.lex(text))
        }
    }

    @Test
    fun `границы не едут на длинном файле`() {
        val text = (1..400).joinToString("\n") { "key$it = \"v$it\"" } + "\n"
        val lexemes = CrenLexer.lex(text)
        assertRangesConsistent(text, lexemes)
        assertTrue(lexemes.any { it.text == "key400" })
    }
}
