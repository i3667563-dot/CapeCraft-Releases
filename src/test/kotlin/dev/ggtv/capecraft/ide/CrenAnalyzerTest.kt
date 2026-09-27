package dev.ggtv.capecraft.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты анализатора: диагностики, подсказки и наведение.
 *
 * Проверяется не «есть ли хоть какая-то диагностика», а конкретные свойства:
 * что на корректном файле **молчание**, а на опечатке — точное сообщение.
 * Ложное срабатывание хуже отсутствия проверки: человек закрывает подсветку
 * и перестаёт смотреть на неё вообще.
 */
class CrenAnalyzerTest {
    /** Конфиг из README — эталон, на котором не должно быть ни одной ошибки. */
    private val good = """
        capeCraft {
            providers [
                { name = "example", type = "url", url = "https://example.com/capes/{username}.png" },
                { name = "api", type = "json", url = "https://api.example.com/cape?u={username}", extract = "${'$'}.data.cape_url" },
                { name = "local", type = "file", path = "{root}/capes/{uuid}.png" }
            ]
        }
    """.trimIndent() + "\n"

    private fun diags(text: String) = CrenAnalyzer.diagnostics(CrenDocument(text))

    private fun codes(text: String) = diags(text).map { it.code }

    private fun offsetOf(text: String, marker: String): Int {
        val i = text.indexOf(marker)
        assertTrue(i >= 0, "маркер «$marker» не найден в:\n$text")
        return i + marker.length
    }

    // ------------------------------------------------------ корректный файл

    @Test
    fun `эталонный конфиг из README не даёт ошибок`() {
        val d = diags(good)
        val errors = d.filter { it.severity == CrenSeverity.ERROR }
        assertTrue(
            errors.isEmpty(),
            "эталон должен быть чистым, а нашлись: ${errors.map { it.message }}",
        )
    }

    @Test
    fun `условия when из README не дают ошибок`() {
        val text = """
            capeCraft {
                providers [
                    { name = "rain", type = "url", url = "https://e/r.png",
                      when = { weather: "rain" } },
                    { name = "night", type = "url", url = "https://e/n.png",
                      when = { time.period: "night", dimension: "overworld" } },
                    { name = "deep", type = "url", url = "https://e/d.png",
                      when = { location.y: ">-20" }, priority = 10 }
                ]
            }
        """.trimIndent()
        val errors = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(errors.isEmpty(), "нашлись ошибки: ${errors.map { it.message }}")
    }

    // ------------------------------------------------------------ неизвестные ключи

    @Test
    fun `опечатка в ключе провайдера`() {
        val d = diags("""capeCraft { providers [ { name = "a", type = "url", ur = "x" } ] }""")
        val err = d.single { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }
        assertTrue(err.message.contains("ur"), err.message)
        assertTrue(err.message.contains("url"), "ожидалась подсказка похожего: ${err.message}")
    }

    @Test
    fun `опечатка в корневом разделе`() {
        val d = diags("""capeCraft { limit { "maxEntities": 1 } }""")
        val err = d.single { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }
        assertTrue(err.message.contains("limits"), err.message)
    }

    @Test
    fun `исправление предлагает похожий ключ`() {
        val d = diags("""capeCraft { providers [ { name = "a", type = "url", ur = "x" } ] }""")
        val fix = d.single { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }.fixes.first()
        assertEquals("url", fix.newText)
    }

    @Test
    fun `неизвестный ключ подсвечивает только сам ключ`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", ur = "x" } ] }"""
        val d = diags(text).single { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }
        assertEquals("ur", CrenDocument(text).slice(d.range))
    }

    @Test
    fun `второй провайдер проверяется так же как первый`() {
        // Ключевая проверка на путь: у элемента массива индекс в пути, и без
        // него второй провайдер выглядел бы как неизвестный раздел.
        val text = """
            capeCraft {
                providers [
                    { name = "a", type = "url", url = "x" },
                    { name = "b", type = "url", nope = "x" }
                ]
            }
        """.trimIndent()
        val err = diags(text).single { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }
        assertTrue(err.message.contains("nope"), err.message)
    }

    @Test
    fun `словарь в провайдере тоже проверяется`() {
        val d = diags("""capeCraft { providers [ { name = "a", type = "url", "x": { "bad": 1 } } ] }""")
        assertTrue(d.any { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY }, "пропущено: ${d.map { it.code }}")
    }

    // ---------------------------------------------------------------- when

    @Test
    fun `неизвестный корень условия`() {
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when = { weater: "rain" } } ] }""",
        )
        val err = d.single { it.code == CrenAnalyzer.CODE_WHEN }
        assertTrue(err.message.contains("weater"), err.message)
        assertTrue(err.message.contains("weather"), "ожидался список корней: ${err.message}")
    }

    @Test
    fun `неизвестное поле корня условия`() {
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when = { time.periodd: "night" } } ] }""",
        )
        val err = d.single { it.code == CrenAnalyzer.CODE_WHEN }
        assertTrue(err.message.contains("periodd"), err.message)
    }

    @Test
    fun `короткая запись с чужим синонимом`() {
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when = { weather: "snow" } } ] }""",
        )
        val w = d.single { it.code == CrenAnalyzer.CODE_WHEN }
        assertTrue(w.message.contains("snow"), w.message)
        assertTrue(w.message.contains("rain"), "ожидался список синонимов: ${w.message}")
    }

    @Test
    fun `у location нет короткой записи и это не ругается`() {
        // У `location` поля по умолчанию нет, писать надо `location.y`. Если
        // бы мы проверяли синонимы вслепую, то ругались бы на правильный ключ.
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when = { location.y: 5 } } ] }""",
        )
        assertTrue(
            d.none { it.severity == CrenSeverity.ERROR },
            "location.y должен быть валиден: ${d.map { it.message }}",
        )
    }

    // --------------------------------------------------------- значения и типы

    @Test
    fun `значение не из списка`() {
        val d = diags("""capeCraft { providers [ { name = "a", type = "wobble" } ] }""")
        val w = d.single { it.code == CrenAnalyzer.CODE_VALUE }
        assertTrue(w.message.contains("wobble"), w.message)
    }

    @Test
    fun `неизвестный тип провайдера не ошибка а предупреждение`() {
        // Аддоны регистрируют свои типы, и об этом есть договорённость в
        // ConfigSchema: чужой тип — предупреждение, а не ошибка.
        val d = diags("""capeCraft { providers [ { name = "a", type = "мой-аддон" } ] }""")
        assertTrue(
            d.none { it.severity == CrenSeverity.ERROR },
            "свой тип провайдера не должен быть ошибкой: ${d.map { it.message }}",
        )
    }

    @Test
    fun `явный тип не совпадает со значением`() {
        val text = """name str = 5"""
        val d = diags(text)
        assertTrue(codes(text).contains(CrenAnalyzer.CODE_TYPE), codes(text).toString())
        assertTrue(d.single { it.code == CrenAnalyzer.CODE_TYPE }.severity == CrenSeverity.ERROR)
    }

    @Test
    fun `float принимает целое как и у мода`() {
        val text = """name float = 1"""
        assertFalse(codes(text).contains(CrenAnalyzer.CODE_TYPE), codes(text).toString())
    }

    @Test
    fun `неизвестное имя типа`() {
        val text = """name text = "x""""
        assertTrue(codes(text).contains(CrenAnalyzer.CODE_UNKNOWN_TYPE), codes(text).toString())
    }

    @Test
    fun `подсветка типа указывает только на имя типа`() {
        val text = """name str = 5"""
        val d = diags(text).single { it.code == CrenAnalyzer.CODE_TYPE }
        assertEquals("str", CrenDocument(text).slice(d.range))
    }

    @Test
    fun `url у провайдера типа file`() {
        val d = diags("""capeCraft { providers [ { name = "a", type = "file", url = "x" } ] }""")
        val w = d.single { it.code == CrenAnalyzer.CODE_APPLIES }
        assertTrue(w.message.contains("file"), w.message)
    }

    @Test
    fun `url у провайдера типа url не ругается`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "x" } ] }"""
        assertFalse(codes(text).contains(CrenAnalyzer.CODE_APPLIES), codes(text).toString())
    }

    // ------------------------------------------------------ обязательные ключи

    @Test
    fun `пропущенный type`() {
        val d = diags("""capeCraft { providers [ { name = "a", url = "x" } ] }""")
        val w = d.firstOrNull { it.code == CrenAnalyzer.CODE_MISSING }
        assertNotNull(w, "ожидалось «type» обязателен: ${d.map { it.message }}")
        assertTrue(w.message.contains("type"), w.message)
    }

    @Test
    fun `обязательный ключ на месте ошибки не даёт`() {
        // `url` обязателен для типа `url`, без него провайдер не загрузится.
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "x" } ] }"""
        assertFalse(codes(text).contains(CrenAnalyzer.CODE_MISSING), codes(text).toString())
    }

    // ------------------------------------------------------------- синтаксис

    @Test
    fun `незакрытая строка помечается`() {
        val text = "capeCraft {\n  providers [\n    { name = \"ой\n"
        assertTrue(codes(text).contains(CrenAnalyzer.CODE_SYNTAX), codes(text).toString())
    }

    @Test
    fun `пропущенный разделитель в словаре`() {
        val d = diags("""capeCraft { providers [ { name "x" } ] }""")
        assertTrue(codes("""capeCraft { providers [ { name "x" } ] }""").contains(CrenAnalyzer.CODE_NO_SEPARATOR))
        assertNotNull(d.firstOrNull { it.code == CrenAnalyzer.CODE_NO_SEPARATOR })
    }

    // ------------------------------------------------------------- подсказки

    @Test
    fun `подсказки внутри providers дают ключи провайдера`() {
        val text = """capeCraft { providers [ {| } ] }"""
        val doc = CrenDocument(text)
        val items = CrenAnalyzer.complete(doc, text.indexOf('|'))
        val labels = items.map { it.label }
        assertTrue(labels.containsAll(listOf("name", "type", "url")), "подсказки: $labels")
    }

    @Test
    fun `подсказки на месте типа`() {
        val text = "capeCraft { providers [ { name | } ] }"
        val items = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|'))
        val labels = items.map { it.label }
        assertTrue(labels.containsAll(listOf("str", "int", "bool")), "подсказки типов: $labels")
    }

    @Test
    fun `подсказки не повторяют уже написанные ключи`() {
        val text = """capeCraft { providers [ { name = "a", ty| } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertFalse(labels.contains("name"), "уже написанный ключ нельзя предлагать: $labels")
    }

    @Test
    fun `подсказка после равно предлагает значения`() {
        val text = """capeCraft { providers [ { name = "a", type = "" } ] }"""
        val at = offsetOf(text, "type = \"")
        val items = CrenAnalyzer.complete(CrenDocument(text), at)
        val labels = items.map { it.label }
        assertTrue(labels.containsAll(listOf("url", "json", "file")), "подсказки: $labels")
        assertTrue(items.all { it.insertText.startsWith("\"") }, "значение вставляется в кавычках")
    }

    @Test
    fun `подсказка сразу после равно без значения даёт значения поля`() {
        // `type = |` и `enabled = |` — самый частый момент ввода: человек
        // набрал ключ и разделитель, а значения ещё нет. Раньше запись без
        // значения не находилась вовсе, и подсказки уезжали в соседние ключи
        // блока: список предлагал вставить ключ туда, где пишется значение.
        val enum = """capeCraft { providers [ { name = "a", type = | } ] }"""
        val atEnum = enum.indexOf('|')
        val labels = CrenAnalyzer.complete(CrenDocument(enum), atEnum).map { it.label }
        assertTrue(
            labels.containsAll(listOf("url", "json", "file")),
            "у type значения-перечисления, а не ключи провайдера: $labels",
        )

        val bool = "capeCraft { serverSync { enabled = | } }"
        val atBool = bool.indexOf('|')
        val boolLabels = CrenAnalyzer.complete(CrenDocument(bool), atBool).map { it.label }
        assertEquals(
            listOf("true", "false"),
            boolLabels,
            "у enabled ровно два значения, без кавычек",
        )
    }

    @Test
    fun `на пустой строке между записями предлагаются ключи а не значения`() {
        // Обратная сторона: заканчивается запятой — значит запись закрыта, и
        // курсор уже не в её значении. Здесь нужны ключи, иначе подсказка
        // значений встанет не туда.
        val text = "capeCraft { providers [ {\n    name = \"a\",\n    |\n} ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertTrue(
            labels.containsAll(listOf("type", "url", "path")),
            "после запятой нужны ключи записи провайдера: $labels",
        )
        assertFalse(
            labels.contains("json"),
            "json — это значение type, а не ключ; тут он лишний: $labels",
        )
    }

    @Test
    fun `подсказки в when дают корни условий`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { | } } ] }"
        val items = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|'))
        val labels = items.map { it.label }
        assertTrue(labels.containsAll(listOf("biome", "weather", "time")), "подсказки: $labels")
    }

    @Test
    fun `подсказки значения when дают синонимы и операторы`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { weather: \"\" } } ] }"
        val at = offsetOf(text, "weather: \"")
        val items = CrenAnalyzer.complete(CrenDocument(text), at)
        val labels = items.map { it.label }
        assertTrue(labels.contains("rain"), "ожидался синоним rain: $labels")
        assertTrue(labels.any { it.startsWith(">") }, "ожидался оператор: $labels")
    }

    @Test
    fun `подсказки не путают позицию типа с позицией после запятой`() {
        val text = """capeCraft { providers [ { name = "a", | } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertTrue(labels.contains("type"), "в пустом провайдере ждём ключи, а не типы: $labels")
    }

    @Test
    fun `на верхнем уровне предлагаются разделы а не ключи провайдера`() {
        // В пустом файле подсказать разделы полезно, а вот ключи провайдера
        // на верхнем уровне — выдумка: там их быть не может.
        val empty = CrenAnalyzer.complete(CrenDocument(""), 0).map { it.label }
        assertTrue(empty.containsAll(listOf("providers", "limits", "serverSync")), "подсказки: $empty")
        assertFalse(empty.contains("url"), "ключ провайдера на верхнем уровне: $empty")

        val junk = CrenAnalyzer.complete(CrenDocument("какой-то мусор"), 0).map { it.label }
        assertFalse(junk.contains("url"), "ключ провайдера на верхнем уровне: $junk")
    }

    @Test
    fun `подсказки внутри limits дают ключи limits`() {
        val text = "capeCraft { limits { | } }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertTrue(labels.isNotEmpty(), "у limits должны быть подсказки")
        assertFalse(labels.contains("providers"), "чужие ключи: $labels")
    }

    // ------------------------------------------------------------- наведение

    @Test
    fun `наведение на ключ показывает документацию`() {
        val text = """capeCraft { providers [ { url = "x" } ] }"""
        val at = text.indexOf("url")
        val h = CrenAnalyzer.hover(CrenDocument(text), at)
        assertNotNull(h)
        assertTrue(h.text.contains("url"), h.text)
        assertTrue(h.text.isNotBlank())
    }

    @Test
    fun `наведение на условие when показывает его поля`() {
        val text = """capeCraft { providers [ { when = { time.period: "night" } } ] }"""
        val h = CrenAnalyzer.hover(CrenDocument(text), text.indexOf("period"))
        assertNotNull(h)
        assertTrue(h.text.contains("period"), h.text)
    }

    @Test
    fun `наведение на неизвестный ключ молчит`() {
        val text = """capeCraft { providers [ { zz = "x" } ] }"""
        assertNull(CrenAnalyzer.hover(CrenDocument(text), text.indexOf("zz")))
    }

    @Test
    fun `анализатор не падает на кривом тексте`() {
        val nasty = listOf(
            "", " ", "=", "{", "providers [", "when { }", "when = { biome }",
            "providers [ { type = } ]", "providers [ { \"a\" b c } ]",
            "when = { time. = 1 }", "when = { .period = 1 }", "when = { biome. }",
            "providers [ [ [ ] ] ]", "capeCraft { providers [ { when = { when = 1 } } ] }",
            "capeCraft { providers [ { when = { location: 5 } } ] }",
            "a = 1\n".repeat(500), "providers [ " + "{ a = 1 } ".repeat(200) + "]",
        )
        for (text in nasty) {
            val doc = CrenDocument(text)
            CrenAnalyzer.diagnostics(doc)
            CrenAnalyzer.hover(doc, text.length / 2)
            for (o in listOf(0, text.length / 3, text.length / 2, text.length)) {
                CrenAnalyzer.complete(doc, o.coerceIn(0, text.length))
            }
        }
    }

    @Test
    fun `диагностики отсортированы и не пересекаются по позиции`() {
        val text = """
            capeCraft {
                providers [ { name = "a", type = "wobble", ur = "x", "n" int = "s" } ]
                limit { "x": 1 }
            }
        """.trimIndent()
        val d = diags(text)
        for (k in 1 until d.size) {
            val a = d[k - 1]
            val b = d[k]
            val ok = a.range.start.line < b.range.start.line ||
                (a.range.start.line == b.range.start.line && a.range.start.col <= b.range.start.col)
            assertTrue(ok, "порядок нарушен: $a затем $b")
        }
    }
}
