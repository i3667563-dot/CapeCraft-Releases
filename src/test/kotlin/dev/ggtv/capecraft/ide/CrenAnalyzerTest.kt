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

    @Test
    fun `when блоком без знака в словаре провайдера`() {
        // `when { ... }` внутри словаря — самая частая форма, которую пишут по
        // привычке от верхнего `capeCraft { ... }`. Терпимый разбор её берёт,
        // а мод — нет: KorenParser требует «:» или «=» после ключа в словаре.
        // Без этой проверки человек узнаёт о своём файле только из лога игры.
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when { location.y: "<= -20" } } ] }""",
        )
        val err = d.single { it.code == CrenAnalyzer.CODE_NO_SEPARATOR }
        assertTrue(err.severity == CrenSeverity.ERROR, err.message)
        assertTrue(err.message.contains("="), err.message)
    }

    @Test
    fun `пустой when блоком тоже ругается`() {
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when { } } ] }""",
        )
        assertTrue(
            d.any { it.code == CrenAnalyzer.CODE_NO_SEPARATOR },
            "пустой `when { }` мод тоже не прочитает: ${d.map { it.code }}",
        )
    }

    @Test
    fun `тот же when со знаком не ругается`() {
        val d = diags(
            """capeCraft { providers [ { name = "a", type = "url", when = { location.y: "<= -20" } } ] }""",
        )
        assertTrue(
            d.none { it.severity == CrenSeverity.ERROR },
            "правильная форма не должна ругаться: ${d.map { it.message }}",
        )
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

    // --------------------------------------------------------------------- if

    @Test
    fun `условие if из всех форм не даёт ошибок`() {
        val text = """
            capeCraft {
                providers [
                    { name = "me", type = "url", url = "https://e/a.png",
                      if = { username: "Eonixx" } },
                    { name = "tier", type = "url", url = "https://e/b.png",
                      if = { ${'$'}TIER: "gold" } },
                    { name = "port", type = "url", url = "https://e/c.png",
                      if = { ${'$'}PORT: ">8000", ${'$'}HOST: "localhost" } },
                    { name = "add", type = "url", url = "https://e/d.png",
                      if = { myAddon.level: ">=10" } },
                    { name = "both", type = "url", url = "https://e/e.png",
                      when = { weather: "rain" }, if = { username: "Eonixx" } }
                ]
            }
        """.trimIndent()
        val errors = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(errors.isEmpty(), "на if не должно быть ошибок: ${errors.map { it.message }}")
    }

    @Test
    fun `корень мира в if ругается и говорит чем заменить`() {
        // Самая частая ошибка: рабочий `when` скопирован в `if`. Молча такое
        // имя не переменная, и провайдер тихо ушёл бы в fallback.
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { weather: "rain" } } ] }"""
        val d = diags(text).filter { it.code == CrenAnalyzer.CODE_IF }
        assertEquals(1, d.size, "ожидалась одна диагностика bad-if, а пришло: ${diags(text).map { it.code to it.message }}")
        assertTrue(d.single().message.contains("корень мира"), "сообщение должно называть причину: ${d.single().message}")
    }

    @Test
    fun `корень мира с полем в if тоже ругается`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { location.y: ">10" } } ] }"""
        val d = diags(text).filter { it.code == CrenAnalyzer.CODE_IF }
        assertEquals(1, d.size, "корень распознаётся по первому сегменту: ${diags(text).map { it.message }}")
    }

    @Test
    fun `имя с точкой после $ в if ругается как игра`() {
        // Игра отвергает такое имя при загрузке, и молчать тут нельзя: файл
        // дойдёт до запуска и упадёт уже без редактора рядом.
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { ${'$'}my.var: "x" } } ] }"""
        val d = diags(text).filter { it.code == CrenAnalyzer.CODE_IF }
        assertEquals(1, d.size, "ожидалась одна диагностика bad-if: ${diags(text).map { it.code to it.message }}")
        assertTrue(d.single().message.contains("не имя переменной окружения"), "${d.single().message}")
    }

    @Test
    fun `на живое имя переменной окружения в if не ругаемся`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { ${'$'}TIER: "gold" } } ] }"""
        val d = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(d.isEmpty(), "${'$'}TIER — законное имя: ${d.map { it.message }}")
    }

    @Test
    fun `в if не ругаемся на имена которые заранее неизвестны`() {
        // Аддонных плейсхолдеров и произвольных env нет в списке модных имён,
        // и ругаться на них как на опечатки нельзя: подсветка сломала бы
        // конфиг с аддоном, а человек закрыл бы её и не вернулся.
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { my.deeply.nested: "1" } } ] }"""
        val errors = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(errors.isEmpty(), "чужие имена в if — не ошибка: ${errors.map { it.message }}")
    }

    @Test
    fun `в if не путаем переменную с корнем того же слова`() {
        // `root` — это {root} в пути к файлу, настоящая переменная, а не
        // «корень мира». Отвергать её было бы ложной ошибкой.
        val text = """capeCraft { providers [ { name = "a", type = "file", path = "{root}/c.png", if = { root: "/tmp" } } ] }"""
        val errors = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(errors.isEmpty(), "root в if — переменная, а не корень мира: ${errors.map { it.message }}")
    }

    @Test
    fun `подсказки внутри if предлагают имена переменных`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = {| } } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertTrue(
            labels.containsAll(listOf("username", "uuid", "root")),
            "подсказки в if: $labels",
        )
    }

    @Test
    fun `подсказки в if не предлагают непечатаемое имя`() {
        // `$*` в списке известных имён нужен для текста ошибки, но вставить
        // его в конфиг нельзя — подсказка обязана состоять из печатных имён.
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = {| } } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertFalse(labels.any { it.contains("*") }, "в подсказках не должно быть масок: $labels")
    }

    @Test
    fun `подсказки в if не повторяют уже написанные имена`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if = { username: "x", | } } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertFalse(labels.contains("username"), "уже написанное имя нельзя предлагать: $labels")
    }

    @Test
    fun `подсказки в providers предлагают ключ if`() {
        val text = """capeCraft { providers [ {| } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }
        assertTrue(labels.contains("if"), "if должен предлагаться среди ключей провайдера: $labels")
        assertTrue(labels.contains("when"), "when должен остаться среди ключей: $labels")
    }

    @Test
    fun `if без разделителя ругается как и when`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", url = "u", if { username: "x" } } ] }"""
        val d = diags(text).filter { it.severity == CrenSeverity.ERROR }
        assertTrue(
            d.any { it.message.contains("разделител") || it.message.contains("«:»") || it.message.contains("«=") },
            "блок-форма `if { }` без знака не разбирается: ${d.map { it.message }}",
        )
    }

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
    fun `подсказки значения when дают синонимы и равенство`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { weather: \"\" } } ] }"
        val at = offsetOf(text, "weather: \"")
        val items = CrenAnalyzer.complete(CrenDocument(text), at)
        val labels = items.map { it.label }
        assertTrue(labels.contains("rain"), "ожидался синоним rain: $labels")
        assertTrue(labels.contains("=…"), "ожидался оператор равенства: $labels")
    }

    @Test
    fun `строковому полю when не предлагают сравнение числа`() {
        // `>` у строки не сработает никогда: [dev.ggtv.capecraft.condition.Op]
        // приводит фактическое значение к числу, и `VStr` даёт `null`.
        // Предлагать такое — значит подсовывать условие, которое молча
        // не выполнится.
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { weather: \"\" } } ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "weather: \"")).map { it.label }
        assertTrue(
            labels.none { it.startsWith(">") || it.startsWith("<") },
            "у строкового поля не должно быть сравнений числа: $labels",
        )
        assertTrue(labels.none { it == "от..до" }, "диапазон числовому полю не подходит: $labels")
    }

    @Test
    fun `числовому полю when предлагают сравнение и диапазон`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location.y: \"\" } } ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "location.y: \"")).map { it.label }
        assertTrue(labels.contains(">…"), "ожидалось сравнение числа: $labels")
        assertTrue(labels.contains("от..до"), "ожидался диапазон: $labels")
    }

    @Test
    fun `подсказки значения when предлагают канонические значения поля`() {
        val cases = mapOf(
            "time.period" to listOf("day", "sunrise", "sunset", "night"),
            "weather" to listOf("clear", "rain", "thunder"),
            "biome.precipitation" to listOf("none", "rain", "snow"),
            "dimension.type" to listOf("overworld", "nether", "end"),
        )
        for ((key, values) in cases) {
            val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { $key: \"\" } } ] }"
            val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "$key: \"")).map { it.label }
            assertTrue(
                labels.containsAll(values),
                "для $key ожидались $values, а подсказано $labels",
            )
        }
    }

    @Test
    fun `после точки в when предлагают поле а не тип`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location. } } ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "location.")).map { it.label }
        assertEquals(listOf("location.x", "location.y", "location.z"), labels, "после location. ждём поля координат")
    }

    @Test
    fun `поле after-точки вставляется вместе с корнем`() {
        // Подсказка заменяет диапазон `location.` целиком, поэтому в тексте
        // должен быть полный ключ: вставка одного лишь `y` дала бы `y` без
        // корня, а `location.y` поверх `location.` — `location.location.y`.
        // Правильный вариант один — целиком `location.y`, и именно его
        // накрывает textEdit.
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location. } } ] }"
        val item = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "location."))
            .single { it.label == "location.y" }
        assertEquals("location.y", item.insertText)
    }

    @Test
    fun `подсказки when предлагают поля без точки для координат`() {
        // У `location` нет поля по умолчанию: `when { location: 0 }` не
        // развернётся ни во что. Поэтому предлагаем `location.y` явно.
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { } } ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "{ }")).map { it.label }
        assertTrue(labels.containsAll(listOf("location.x", "location.y", "location.z")), "подсказки: $labels")
        assertTrue(labels.contains("time.period"), "подсказки: $labels")
    }

    @Test
    fun `внутри when не предлагают типы значения`() {
        // Внутри `when` условие пишется `ключ: значение`; знак `=` там
        // невозможен, поэтому предложение типов — заведомо неверная запись.
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location.  } } ] }"
        val at = offsetOf(text, "location.")
        val labels = CrenAnalyzer.complete(CrenDocument(text), at).map { it.label }
        assertTrue(
            labels.containsAll(listOf("location.x", "location.y", "location.z")),
            "после `location.` ждём поля: $labels",
        )
        assertTrue(!labels.contains("str"), "внутри when типов быть не должно: $labels")
    }

    @Test
    fun `пробел после точки не сбрасывает подсказку в корни`() {
        // Курсор ставят в конец строки, а не в конец ключа: после пробела
        // человек всё ещё дописывает `location.y`.
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location.  } } ] }"
        val at = offsetOf(text, "location.  ")
        val labels = CrenAnalyzer.complete(CrenDocument(text), at).map { it.label }
        assertEquals(
            listOf("location.x", "location.y", "location.z"),
            labels,
            "после `location. ` ждём поля координат: $labels",
        )
    }

    @Test
    fun `набранное поле after-точки сужает список`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { location.y  } } ] }"
        val at = offsetOf(text, "location.y")
        val labels = CrenAnalyzer.complete(CrenDocument(text), at).map { it.label }
        assertEquals(listOf("location.y"), labels, "после `location.y` подходит только location.y: $labels")
    }

    @Test
    fun `переименовываемый ключ остаётся в подсказках`() {
        // `enabled` в блоке уже написан, но человек стоит внутри него и
        // правит букву: повторно предложить этот же ключ — единственное,
        // что тут полезно.
        val text = """capeCraft { serverSync { enabled = true } }"""
        val at = text.indexOf("enabled") + "ena".length
        val labels = CrenAnalyzer.complete(CrenDocument(text), at).map { it.label }
        assertTrue(labels.contains("enabled"), "ключ под курсором нельзя выбрасывать из подсказок: $labels")
    }

    @Test
    fun `подсказки фильтруются по набранному началу`() {
        val text = "capeCraft { providers [ { name = \"a\", type = \"url\", when = { loc } } ] }"
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "loc")).map { it.label }
        assertTrue(labels.contains("location"), "подсказки: $labels")
        assertTrue(
            labels.none { it.startsWith("weather") || it.startsWith("biome") },
            "на `loc` biome и weather не подходят: $labels",
        )
    }

    @Test
    fun `подсказки не повторяют уже написанные условия`() {
        val text = """capeCraft { providers [ { name = "a", type = "url", when = { location.x: 1, | } } ] }"""
        val labels = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "|")).map { it.label }
        assertTrue(!labels.contains("location.x"), "location.x уже написан: $labels")
        assertTrue(labels.contains("location.y"), "соседние поля ещё нужны: $labels")
    }

    @Test
    fun `в пустом providers предлагают готовую запись`() {
        val text = "capeCraft { providers [ ] }"
        val items = CrenAnalyzer.complete(CrenDocument(text), offsetOf(text, "[ ]"))
        assertEquals(1, items.size, "в пустом массиве один осмысленный вариант: ${items.map { it.label }}")
        assertTrue(
            items.single().insertText.contains("type ="),
            "нужна готовая запись с типом, а не пустая скобка: ${items.single().insertText}",
        )
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
