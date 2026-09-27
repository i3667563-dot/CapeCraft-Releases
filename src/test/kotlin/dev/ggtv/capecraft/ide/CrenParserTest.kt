package dev.ggtv.capecraft.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты терпимого разбора.
 *
 * Главное здесь — что разбор **не бросает** и **не теряет** то, что идёт
 * после ошибки. Всё остальное вторично: в редакторе файл, в котором треть
 * подсвечивается неправильно из-за опечатки в первой строке, хуже, чем
 * файл без подсветки вовсе.
 */
class CrenParserTest {
    private fun parse(text: String): CrenTree = CrenParser.parse(CrenDocument(text))

    /** Компактное представление дерева, чтобы ожидания читались как конфиг. */
    private fun CrenNode.render(): String = when (this) {
        is CrenEntry -> buildString {
            append(keyText)
            when {
                form == EntryForm.BLOCK ->
                    append(" {").append(children.joinToString(", ") { it.render() }).append("}")

                else -> value?.let {
                    append(" = ").append(it.render())
                }
            }
        }

        is CrenDict -> "{" + entries.joinToString(", ") { it.render() } + "}"
        is CrenArray -> "[" + items.joinToString(", ") { it.render() } + "]"
        is CrenLeaf -> lexeme.text
        is CrenRef -> parts.joinToString("")
        is CrenKey -> text
    }

    @Test
    fun `простой конфиг`() {
        val t = parse(
            """
            name = "Тест"
            port = 8080
            enabled = true
            """.trimIndent(),
        )
        assertEquals(
            listOf("name = \"Тест\"", "port = 8080", "enabled = true"),
            t.root.map { it.render() },
        )
        assertTrue(t.problems.isEmpty(), "проблемы: ${t.problems.map { it.message }}")
    }

    @Test
    fun `словарь в фигурных скобках`() {
        val t = parse("""settings: { "a": 1, "b": 2 }""")
        assertEquals(listOf("""settings = {a = 1, b = 2}"""), t.root.map { it.render() })
        assertTrue(t.problems.isEmpty(), "проблемы: ${t.problems.map { it.message }}")
    }

    @Test
    fun `массив словарей как providers`() {
        val t = parse(
            """
            "providers": [
                { "name": "a", "type": "url" },
                { "name": "b", "type": "file" },
            ]
            """.trimIndent(),
        )
        val providers = t.root.single().value
        assertTrue(providers is CrenArray, "ожидался массив, получено ${providers?.render()}")
        assertEquals(2, (providers as CrenArray).items.size)
        assertEquals("""{name = "a", type = "url"}""", providers.items[0].render())
    }

    @Test
    fun `именованный блок держит содержимое в children`() {
        val t = parse(
            """
            server {
                "token": "abc"
                port = 1
            }
            """.trimIndent(),
        )
        val server = t.root.single()
        assertEquals(EntryForm.BLOCK, server.form)
        assertEquals(listOf("token = \"abc\"", "port = 1"), server.children.map { it.render() })
        assertEquals(server.children, server.body, "body у блока — это children")
    }

    @Test
    fun `ссылка разбирается по частям с точными диапазонами`() {
        val text = "token = server.token[1]"
        val t = parse(text)
        val ref = t.root.single().value
        assertTrue(ref is CrenRef, "ожидалась ссылка, получено ${ref?.render()}")
        val parts = (ref as CrenRef).parts
        assertEquals(
            listOf("server", "token"),
            parts.filterIsInstance<PathPart.Name>().map { it.name },
        )
        assertEquals(listOf(1), parts.filterIsInstance<PathPart.Index>().map { it.index })
        val doc = CrenDocument(text)
        val expected = listOf("server", "token", "[1]")
        for ((p, want) in parts.zip(expected)) {
            assertEquals(want, doc.slice(p.range), "диапазон части пути вырезает не себя")
        }
    }

    @Test
    fun `обычное слово не считается ссылкой`() {
        val t = parse("a = true")
        assertTrue(t.root.single().value is CrenLeaf, "true — это лист, а не ссылка")
    }

    @Test
    fun `незакрытая скобка не съедает остаток файла`() {
        // Ключевая проверка: потерянная скобка не должна прятать следующие
        // записи, иначе подсказки пропадут ровно там, где человек их ждёт.
        //
        // Куда именно попадёт `c` — вопрос открытый: по незакрытой скобке
        // нельзя понять, где файл должен кончиться, поэтому `c` вложится в
        // `a`. Проверяем поэтому не уровень, а **достижимость**: запись
        // должна найтись в дереве, потому что анализатор обходит и вложенные.
        val t = parse(
            """
            a {
                b = 1
            c = 2
            """.trimIndent(),
        )
        assertTrue(
            t.problems.any { "не хватает" in it.message },
            "ожидалась ошибка о скобке, получили: ${t.problems.map { it.message }}",
        )
        val all = ArrayList<String>()
        fun collect(entries: List<CrenEntry>) {
            for (e in entries) {
                all += e.keyText
                collect(e.children)
            }
        }
        collect(t.root)
        assertTrue(all.containsAll(listOf("a", "b", "c")), "потеряли записи: $all")
    }

    @Test
    fun `незакрытая строка не мешает следующим строкам`() {
        val t = parse("name = \"ой\nport = 8080")
        assertTrue(t.root.any { it.keyText == "port" }, "port потерялся: ${t.root.map { it.keyText }}")
    }

    @Test
    fun `лишняя закрывающая скобка не ломает разбор`() {
        val t = parse("a = 1 }\nb = 2")
        assertTrue(t.problems.any { "лишняя" in it.message })
        assertEquals(listOf("a", "b"), t.root.map { it.keyText })
    }

    @Test
    fun `пропущенный разделитель даёт понятную ошибку`() {
        val t = parse("a 1\nb = 2")
        assertTrue(
            t.problems.any { "нужно значение" in it.message },
            "проблемы: ${t.problems.map { it.message }}",
        )
        assertTrue(t.root.any { it.keyText == "b" }, "разбор встал после ошибки")
    }

    @Test
    fun `явный тип ключа не становится отдельной записью`() {
        val t = parse("key str = \"x\"")
        assertEquals(1, t.root.size, "тип не должен превратиться в запись")
        val e = t.root.single()
        assertEquals("key", e.keyText)
        assertEquals("str", e.key.typeText)
        assertEquals("\"x\"", e.value?.render())
        assertTrue(t.problems.isEmpty(), "проблемы: ${t.problems.map { it.message }}")
    }

    @Test
    fun `комментарии и пустые строки не мешают`() {
        val t = parse(
            """
            # сверху
            a = 1

            # между
            b = 2
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b"), t.root.map { it.keyText })
        assertTrue(t.problems.isEmpty())
    }

    @Test
    fun `хвостовой комментарий без перевода строки`() {
        assertTrue(parse("a = 1 # конец").problems.isEmpty())
    }

    @Test
    fun `пустой файл и пустые контейнеры`() {
        assertTrue(parse("").root.isEmpty())
        assertEquals("{}", parse("a = {}").root.single().value?.render())
        assertEquals("[]", parse("a = []").root.single().value?.render())
        assertTrue(parse("").problems.isEmpty())
    }

    @Test
    fun `незакрытый массив`() {
        val t = parse("providers = [\n  { name = \"a\" }\n")
        assertTrue(t.problems.any { "не хватает" in it.message })
        assertTrue(t.root.isNotEmpty())
    }

    @Test
    fun `запятая без значения в массиве не ломает разбор`() {
        val t = parse("a = [1, , 2]")
        assertTrue(t.root.isNotEmpty(), "разбор встал на пустом элементе")
    }

    @Test
    fun `массив без разделителя после ключа`() {
        // Так записывают `providers` в README и в парсере мода: `[` сразу после
        // ключа. Без этой формы разбор терял бы весь массив, а с ним и все
        // провайдеры.
        val t = parse("""capeCraft { providers [ { name = "a" } ] }""")
        val providers = t.root.single().body.first { it.keyText == "providers" }
        val array = providers.value as? CrenArray
        assertNotNull(array, "ожидался массив, а не «${providers.value}»")
        assertEquals(1, array.items.size)
        val name = (array.items.single() as CrenDict).entries.first { it.keyText == "name" }
        assertEquals("a", (name.value as? CrenLeaf)?.lexeme?.value)
        assertTrue(t.problems.isEmpty(), "лишние проблемы: ${t.problems}")
    }

    @Test
    fun `get находит запись по имени`() {
        val t = parse("a = 1\nb = 2")
        assertEquals(2, t.root[1].value?.let { (it as? CrenLeaf)?.lexeme?.value }?.toInt())
        assertNull(t.root[1].dict?.get("нет"))
        assertNotNull(parse("x = { k = 1 }").root.single().dict?.get("k"))
    }

    @Test
    fun `повторный ключ берётся первый`() {
        // Так же, как при разборе модом: последнее значение выигрывает, но
        // для подсказок первое — то, что человек видит в начале.
        val t = parse("a = 1\na = 2")
        assertEquals(2, t.root.size)
        assertEquals("1", t.root.first().value?.render())
    }

    @Test
    fun `body одинаков для блока и словаря`() {
        val block = parse("server { a = 1 }").root.single()
        val dict = parse("server: { a = 1 }").root.single()
        assertEquals(listOf("a"), block.body.map { it.keyText })
        assertEquals(listOf("a"), dict.body.map { it.keyText })
    }

    @Test
    fun `разбор не бросает ни на чём`() {
        val nasty = listOf(
            "", " ", "\n", "\"", "=", "{", "}", "[", "]", ":", ",", ".",
            "a", "a =", "a = ", "= 1", "a = = 1", "{ }", "{ { } }",
            "a { b { c = 1 }", "a = [ [ ] ]", "a = [1", "a = { b = [1, 2 }",
            "server.token[", "a = \"${'$'}{", "a = \"${'$'}{}\"", "a = \"\\\"",
            "# только комментарий", "a = 1\n}\n}\nb = 2", "ключ = значение",
            "a = 1 b = 2", "a = [1 2 3]", "\u00ABчто-то\u00BB", "-1.5e3",
        )
        for (text in nasty) {
            val t = parse(text)
            for (p in t.problems) {
                assertFalse(p.message.isEmpty(), "пустое сообщение на '$text'")
            }
        }
    }

    @Test
    fun `проблемы указывают на реальные места`() {
        val doc = CrenDocument("a = 1\nb {\nc = 2\n")
        val t = CrenParser.parse(doc)
        for (p in t.problems) {
            val from = doc.offsetOf(p.range.start)
            val to = doc.offsetOf(p.range.end)
            assertTrue(from >= 0 && to <= doc.text.length, "диапазон вышел за файл: $from..$to")
            assertTrue(to >= from, "диапазон вывернут: $from..$to")
        }
    }
}
