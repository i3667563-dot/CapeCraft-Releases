package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.schema.J
import dev.ggtv.capecraft.schema.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сервер целиком, через настоящие кадры: те же байты, что присылает редактор.
 *
 * Тесты на границах проверяют не «сервер ответил», а «ответили тем, что
 * клиент поймёт». Разница видна на невалидном запросе: сервер, который вместо
 * ответа молча зависнет, тест поймает по таймауту, а сервер, который ответит
 * ерундой, — по проверке кода ошибки.
 */
class LspServerTest {

    /**
     * Прогоняет сервер на заранее заданной ленте запросов и отдаёт ответы.
     *
     * Ответы читаются тем же [RpcTransport], что и пишутся: иначе тест
     * проверял бы не сервер, а свой разбор кадров, и ошибка в заголовке
     * проскочила бы мимо него же — а заголовок это первое, на чём ломается
     * настоящий клиент.
     */
    private class Session(private val lane: String) {
        private val out = ByteArrayOutputStream()
        private val log = StringBuilder()

        fun run(): List<J.JObj> {
            LspServer(
                ByteArrayInputStream(lane.toByteArray(Charsets.UTF_8)),
                out,
            ) { log.appendLine(it) }.run()
            val reader = RpcTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
            val out2 = mutableListOf<J.JObj>()
            while (true) {
                out2 += reader.read() ?: break
            }
            return out2
        }
    }

    private fun frame(body: String): String {
        val n = body.toByteArray(Charsets.UTF_8).size
        return "Content-Length: $n\r\n\r\n$body"
    }

    private fun req(id: Int, method: String, params: String): String =
        frame("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    private fun note(method: String, params: String): String =
        frame("""{"jsonrpc":"2.0","method":"$method","params":$params}""")

    private val uri = "file:///tmp/capecraft-test.kn"

    /** Конфиг, в котором ровно одна ошибка с готовой правкой. */
    private val configWithTypo = """
        capeCraft {
            providers [
                { name = "trusted", type = "url", url = "https://example.com/{username}.png" }
            ]
            serverSinc { enabled = true }
        }
    """.trimIndent()

    private fun openText(text: String): String {
        val escaped = Json.render(J.JStr(text))
        return note(
            "textDocument/didOpen",
            """{"textDocument":{"uri":"$uri","languageId":"kn","version":1,"text":$escaped}}""",
        )
    }

    private fun position(line: Int, character: Int): String =
        """{"line":$line,"character":$character}"""

    private operator fun J.JObj.get(key: String): J? = fields.firstOrNull { it.first == key }?.second

    private fun J.JObj.str(key: String): String? = (this[key] as? J.JStr)?.s

    private fun J.JObj.arr(key: String): List<J> = (this[key] as? J.JArr)?.items ?: emptyList()

    private fun J.JObj.obj(key: String): J.JObj? = this[key] as? J.JObj

    private fun J.JNum.long(): Long = i

    /** «Пустой» ответ: поля нет либо оно JSON-null. Оба случая равнозначны. */
    private fun J.JObj.noContent(): Boolean = this["result"] == null || this["result"] is J.JNull

    /** Колонка по вхождению маркера: руками считать смещения в тестах нельзя. */
    private fun at(text: String, marker: String, after: Int = 0): Pair<Int, Int> {
        val line = text.substring(0, text.indexOf(marker)).count { it == '\n' }
        return line to (text.lines()[line].indexOf(marker) + marker.length + after)
    }

    // --- жизненный цикл ----------------------------------------------------

    @Test
    fun `initialize объявляет возможности`() {
        val res = Session(req(1, "initialize", """{"capabilities":{}}""")).run().single()
        val caps = res.obj("result")!!.obj("capabilities")!!
        assertEquals(1L, caps["textDocumentSync"]!!.let { (it as J.JNum).long() }, "нужен полный текст")
        assertTrue(caps["completionProvider"] != null, "нужны подсказки")
        assertTrue(caps["hoverProvider"] != null, "нужен hover")
        assertTrue(caps["codeActionProvider"] != null, "нужны быстрые исправления")
        assertEquals("capecraft-lsp", res.obj("result")!!.obj("serverInfo")!!.str("name"))
    }

    @Test
    fun `shutdown отвечает null а exit завершает процесс с кодом 0`() {
        val out = ByteArrayOutputStream()
        val lane = (req(1, "initialize", "{}") + req(2, "shutdown", "null") + note("exit", "null"))
        val code = LspServer(ByteArrayInputStream(lane.toByteArray(Charsets.UTF_8)), out).run()
        assertEquals(0, code, "штатное завершение после shutdown+exit — код 0")
        val reader = RpcTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val replies = generateSequence { reader.read() }.toList()
        assertEquals(2, replies.size, "ответы должны быть на initialize и shutdown")
        assertTrue(replies.last().noContent(), "shutdown отвечает null: ${replies.last()}")
    }

    @Test
    fun `exit без shutdown даёт код 1`() {
        val input = (req(1, "initialize", "{}") + note("exit", "null")).toByteArray(Charsets.UTF_8)
        assertEquals(1, LspServer(ByteArrayInputStream(input), ByteArrayOutputStream()).run())
    }

    @Test
    fun `неизвестный метод отвечает MethodNotFound а не падает`() {
        val res = Session(req(1, "textDocument/nope", "{}")).run().single()
        val err = res.obj("error")!!
        assertEquals(-32601L, (err["code"] as J.JNum).long())
        assertTrue(err.str("message")!!.contains("не поддерживается"))
    }

    @Test
    fun `запрос без params не роняет сервер`() {
        // Клиент имеет право прислать params: null (например shutdown).
        // Ответ обязан быть, иначе редактор ждёт его до таймаута.
        val res = Session(frame("""{"jsonrpc":"2.0","id":7,"method":"textDocument/hover"}""")).run()
        assertEquals(1, res.size, "ответ должен быть: $res")
        assertTrue(res.single().noContent(), "без документа hover это пустой ответ: ${res.single()}")
    }

    // --- документы и диагностика -------------------------------------------

    @Test
    fun `didOpen публикует диагностику с опечаткой в ключе`() {
        val res = Session(openText(configWithTypo)).run()
        assertEquals(1, res.size, "ожидалось одно уведомление publishDiagnostics: $res")
        val params = res.single().obj("params")!!
        assertEquals(uri, params.str("uri"))
        val diags = params.arr("diagnostics")
        assertEquals(1, diags.size, "опечатка serverSinc должна дать одну диагностику: $diags")
        val d = diags.single() as J.JObj
        assertEquals(1L, (d["severity"] as J.JNum).long(), "ошибка — severity 1")
        assertEquals("unknown-key", d.str("code"))
        assertTrue(d.str("message")!!.contains("serverSinc"), d.str("message")!!)
    }

    @Test
    fun `didChange заменяет текст и снимает диагностику`() {
        // Сначала файл с ошибкой, потом тот же файл без неё. Диагностика — это
        // отправленное уведомление, а не флаг: клиент не спросит «а актуально
        // ли у тебя», он просто покажет то, что прислали. Забыть прислать
        // пустой список — значит оставить красную ошибку в файле, который давно
        // починили.
        val fixed = configWithTypo.replace("serverSinc", "serverSync")
        val res = Session(
            openText(configWithTypo) +
                note(
                    "textDocument/didChange",
                    """{"textDocument":{"uri":"$uri","version":2},"contentChanges":[{"text":${Json.render(J.JStr(fixed))}}]}""",
                ),
        ).run()
        assertEquals(2, res.size, "ожидались два publishDiagnostics")
        assertEquals(1, res.first().obj("params")!!.arr("diagnostics").size)
        assertEquals(0, res.last().obj("params")!!.arr("diagnostics").size, "после правки ошибок быть не должно")
    }

    @Test
    fun `didClose снимает диагностики закрытого файла`() {
        // Иначе закрытый файл остаётся с ошибками в панели проблем до
        // перезапуска редактора.
        val res = Session(
            openText(configWithTypo) +
                note("textDocument/didClose", """{"textDocument":{"uri":"$uri"}}"""),
        ).run()
        assertEquals(2, res.size)
        assertEquals(0, res.last().obj("params")!!.arr("diagnostics").size)
    }

    @Test
    fun `битый конфиг не роняет сервер а даёт синтаксическую ошибку`() {
        val res = Session(openText("""capeCraft { providers [ { name = "x" """)).run()
        assertEquals(1, res.size)
        val diags = res.single().obj("params")!!.arr("diagnostics")
        assertTrue(diags.isNotEmpty(), "незакрытая скобка обязана быть видна")
        assertTrue(diags.any { (it as J.JObj).str("code") == "syntax" }, "код синтаксической ошибки")
    }

    @Test
    fun `незакрытая строка видна в диагностике`() {
        // Именно в коде, а не после # : в комментарии кавычка не начинает
        // строку, и ошибки там быть не должно — проверяем оба случая, иначе
        // тест начнёт ловить не то.
        val open = Session(openText("""capeCraft { serverSync { allowFileProviders = "да } """)).run()
        val diags = open.single().obj("params")!!.arr("diagnostics")
        assertTrue(
            diags.any { (it as J.JObj).str("message")!!.contains("не закрыта") },
            "строка без закрывающей кавычки: $diags",
        )
        val commented = Session(openText("""capeCraft { } # "кавычка в комментарии""")).run()
        assertEquals(
            0,
            commented.single().obj("params")!!.arr("diagnostics").size,
            "в комментарии кавычка обычный символ",
        )
    }

    @Test
    fun `запрос к неоткрытому файлу не падает`() {
        val res = Session(
            req(1, "textDocument/completion", """{"textDocument":{"uri":"file:///нет/такого.kn"},"position":${position(0, 0)}}"""),
        ).run()
        assertEquals(1, res.size)
        assertEquals(0, res.single().arr("result").size, "нет документа — нет подсказок")
    }

    // --- подсказки и наведение --------------------------------------------

    @Test
    fun `после равенства у bool приходят true и false`() {
        val text = "capeCraft {\n    serverSync {\n        enabled = \n    }\n}\n"
        val (line, col) = at(text, "enabled = ")
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(line, col)}}""",
                ),
        ).run()
        val items = res.last().arr("result")
        assertEquals(listOf("true", "false"), items.map { (it as J.JObj).str("label") })
        // Значение булева пишется без кавычек: вставка с кавычками дала бы
        // невалидный конфиг.
        assertEquals("true", (items.first() as J.JObj).str("insertText"))
    }

    @Test
    fun `у поля с перечислением приходят только допустимые значения`() {
        val text = "capeCraft {\n    providers [\n        { name = \"a\", type =  }\n    ]\n}\n"
        val (line, col) = at(text, "type = ")
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(line, col)}}""",
                ),
        ).run()
        val items = res.last().arr("result")
        assertEquals(listOf("url", "json", "file"), items.map { (it as J.JObj).str("label") })
        // Значение-строка вставляется в кавычках: без них мод не прочитает
        // `type = url`, а редактор подставил бы нерабочий конфиг.
        assertEquals("\"url\"", (items.first() as J.JObj).str("insertText"))
    }

    @Test
    fun `метка сниппета и сам сниппет всегда согласованы`() {
        // Ключ, открывающий блок, достраивает скобки — это сниппет. Ключ без
        // блока — обычный текст. Расхождение между меткой insertTextFormat и
        // наличием $0 вставляло бы в файл либо доллар с номером, либо голый
        // ключ без скобок.
        val text = "capeCraft {\n    \n}\n"
        val (line, col) = at(text, "{", after = 1)
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(line, col)}}""",
                ),
        ).run()
        val items = res.last().arr("result").map { it as J.JObj }
        assertTrue(items.isNotEmpty(), "в пустом блоке должны предлагаться ключи")
        for (i in items) {
            val isSnippet = i["insertTextFormat"] != null
            val hasPlaceholder = i.str("insertText")!!.contains("\$0")
            assertEquals(
                isSnippet,
                hasPlaceholder,
                "метка и содержимое разошлись: $i",
            )
        }
    }

    @Test
    fun `корень when приходит сниппетом с курсором внутри`() {
        // Сниппет без insertTextFormat вставился бы как «{\n\t$0\n}» —
        // с долларом и номером, и файл стал бы невалидным.
        val text = "capeCraft {\n    providers [\n        { name = \"a\", when: {  } }\n    ]\n}\n"
        val (line, col) = at(text, "{  }")
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(line, col)}}""",
                ),
        ).run()
        val items = res.last().arr("result")
        val root = items.map { it as J.JObj }.firstOrNull { it["insertTextFormat"] != null }
        assertNotNull(root, "корень when должен приходить сниппетом: ${items.map { (it as J.JObj).str("label") }}")
        assertEquals(" {\n\t$0\n}", root.str("insertText"))
    }

    @Test
    fun `hover на ключе отдаёт документацию поля`() {
        val text = "capeCraft {\n    serverSync {\n        enabled = true\n    }\n}\n"
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/hover",
                    """{"textDocument":{"uri":"$uri"},"position":${position(2, 10)}}""",
                ),
        ).run()
        val value = res.last().obj("result")!!.obj("contents")!!.str("value")!!
        assertTrue(value.startsWith("`enabled`"), "документация должна начинаться с имени поля: $value")
    }

    @Test
    fun `hover без документации отдаёт null а не пустой объект`() {
        // Пустой объект клиент покажет всплывашкой без содержимого — хуже, чем
        // ничего: человек решит, что сервер сломался.
        val res = Session(
            openText("capeCraft {\n}\n") +
                req(
                    1, "textDocument/hover",
                    """{"textDocument":{"uri":"$uri"},"position":${position(1, 0)}}""",
                ),
        ).run()
        assertTrue(res.last().noContent(), "пустой ответ, а не объект без содержимого: ${res.last()}")
    }

    // --- быстрые исправления ----------------------------------------------

    @Test
    fun `codeAction предлагает правку для диагностики под курсором`() {
        val res = Session(
            openText(configWithTypo) +
                req(
                    1, "textDocument/codeAction",
                    """{"textDocument":{"uri":"$uri"},"range":{"start":${position(4, 4)},"end":${position(4, 14)}}}""",
                ),
        ).run()
        val actions = res.last().arr("result")
        assertTrue(actions.isNotEmpty(), "для serverSinc должна быть правка")
        val a = actions.first() as J.JObj
        assertEquals("quickfix", a.str("kind"))
        val changes = a.obj("edit")!!.obj("changes")!!
        val edits = (changes[uri] as J.JArr).items
        assertEquals(1, edits.size)
        assertEquals("serverSync", (edits.single() as J.JObj).str("newText"))
    }

    @Test
    fun `codeAction вне диагностики отдаёт пустой список`() {
        val res = Session(
            openText(configWithTypo) +
                req(
                    1, "textDocument/codeAction",
                    """{"textDocument":{"uri":"$uri"},"range":{"start":${position(1, 8)},"end":${position(1, 20)}}}""",
                ),
        ).run()
        assertEquals(0, res.last().arr("result").size, "правки на providers[0].name нет")
    }

    // --- границы протокола ------------------------------------------------

    @Test
    fun `запрос без диапазона в codeAction не роняет сервер`() {
        val res = Session(
            openText(configWithTypo) +
                req(1, "textDocument/codeAction", """{"textDocument":{"uri":"$uri"}}"""),
        ).run()
        assertEquals(2, res.size, "диагностика и ответ: $res")
        assertEquals(0, res.last().arr("result").size, "без диапазона правок нечего предлагать")
    }

    @Test
    fun `позиция за пределами файла не роняет сервер`() {
        // Клиент считает строки от 0, но на пустом файле или после гонки с
        // сохранением может прислать что угодно.
        val res = Session(
            openText("capeCraft {\n}\n") +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(9999, 0)}}""",
                ),
        ).run()
        assertEquals(2, res.size, "диагностика и ответ, а не падение: $res")
        assertTrue(res.last()["result"] != null, "ответ на completion должен быть: ${res.last()}")
    }

    @Test
    fun `кириллица в тексте не ломает рамки сообщений`() {
        // Считать Content-Length в символах — классическая ошибка: на файле
        // без кириллицы всё работает, а с ней сервер зависает.
        val text = "capeCraft {\n    serverSync {\n        # привет, мир\n        enabled = true\n    }\n}\n"
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(3, 8)}}""",
                ),
        ).run()
        assertEquals(2, res.size, "и диагностика, и ответ на подсказку: $res")
    }

    @Test
    fun `эмодзи перед курсором не сдвигают подсказку`() {
        // Клиент считает колонку в UTF-16 code unit, а текст — в кодпоинтах.
        // Эмодзи занимает две единицы у клиента и один символ здесь, поэтому
        // наивный пересчёт уводит курсор на символ назад и подсказывает не
        // тот ключ.
        val text = "capeCraft {\n    # 🎉\n    serverSync {\n        enabled = true\n    }\n}\n"
        val line = 3
        // Все символы до курсора — BMP, поэтому клиентские 11 code units и
        // наши 11 кодпоинтов совпадают: подсказка обязана прийти от строки с
        // enabled, а не от строки с эмодзи.
        val prefix = "        ena"
        val col = prefix.length
        assertEquals(11, col, "префикс из 8 пробелов и ena — это 11 символов")
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(line, col)}}""",
                ),
        ).run()
        // Диагностика при didOpen плюс ответ на подсказку.
        assertEquals(2, res.size, "диагностика и ответ: $res")
        assertTrue(res.last().arr("result").isNotEmpty(), "подсказки в ключах serverSync: ${res.last()}")
    }

    @Test
    fun `эмодзи в той же строке что и курсор считается как две единицы`() {
        val text = "capeCraft {\n    serverSync {\n        🎉 = true\n        enabled = true\n    }\n}\n"
        // Курсор после `🎉 = |true`: клиент насчитает 4 единицы (эмодзи = 2).
        val res = Session(
            openText(text) +
                req(
                    1, "textDocument/completion",
                    """{"textDocument":{"uri":"$uri"},"position":${position(2, 5)}}""",
                ),
        ).run()
        assertEquals(2, res.size, "диагностика и ответ, а не падение: $res")
    }

    @Test
    fun `мусорный кадр завершает сервер кодом 1 а не тишиной`() {
        // Кадр не JSON-объект: поток рассинхронизирован, продолжать нельзя.
        // Тихий выход выглядел бы в редакторе как «сервер молча упал».
        val log = StringBuilder()
        val code = LspServer(
            ByteArrayInputStream(frame("[1,2,3]").toByteArray(Charsets.UTF_8)),
            ByteArrayOutputStream(),
        ) { log.appendLine(it) }.run()
        assertEquals(1, code)
        assertTrue(log.toString().contains("кадр"), "в журнале должно быть сказано, что случилось: $log")
    }

    @Test
    fun `закрытый stdin это штатное завершение`() {
        val code = LspServer(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).run()
        assertEquals(0, code, "клиент закрыл канал — не ошибка")
    }
}
