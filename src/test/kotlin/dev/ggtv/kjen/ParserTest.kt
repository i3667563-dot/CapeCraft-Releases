package dev.ggtv.kjen

import dev.ggtv.kjen.Value.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Тесты парсера — порт tests/parser.rs из Rust-проекта Cren. */
class ParserTest {

    private fun parseStr(input: String): Block = Parser.parse(Tokenizer.tokenize(input))

    private fun parseErr(input: String, messagePart: String) {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize(input)) }
        assertTrue(e.messageText.contains(messagePart), "ожидалось «$messagePart» в «${e.messageText}»")
    }

    private fun valueOf(block: Block, key: String, index: Int = 1): Value =
        block.get(key, index)!!.value

    @Test
    fun `full example from design`() {
        val input = """
            # Это комментарий, и он сохранится при парсинге!
            title = "Мой крутой конфиг"

            # Вложенность через блоки, а не через точки в заголовках
            server {
                host = "localhost"
                port = 8080

                # Массив объектов (то, что в TOML требует [[...]])
                databases [
                    { type = "postgres", url = "jdbc:..." },
                    { type = "redis", url = "redis://..." }
                ]

                # Глубокая вложенность без боли
                security {
                    ssl = true
                    certificates {
                        ca = "/path/to/ca.pem"
                        cert = "/path/to/cert.pem"
                    }
                }
            }
        """.trimIndent()

        val root = parseStr(input)

        val title = root.get("title", 1)
        assertNotNull(title)
        assertEquals("Это комментарий, и он сохранится при парсинге!", title.comment)
        assertEquals(VStr("Мой крутой конфиг"), title.value)

        val server = valueOf(root, "server") as VBlock
        assertEquals(VStr("localhost"), valueOf(server.block, "host"))
        assertEquals(VInt(8080), valueOf(server.block, "port"))

        val databases = valueOf(server.block, "databases") as VArray
        assertEquals(2, databases.items.size)
        val redis = databases.items[1] as VDict
        assertEquals("type" to VStr("redis"), redis.pairs[0])
        assertEquals("url" to VStr("redis://..."), redis.pairs[1])

        val security = valueOf(server.block, "security") as VBlock
        assertEquals(VBool(true), valueOf(security.block, "ssl"))

        val certificates = valueOf(security.block, "certificates") as VBlock
        assertEquals(VStr("/path/to/ca.pem"), valueOf(certificates.block, "ca"))
        assertEquals(VStr("/path/to/cert.pem"), valueOf(certificates.block, "cert"))
    }

    @Test
    fun `multi keys are numbered in order`() {
        val root = parseStr("token = \"a\"\ntoken = \"b\"\ntoken = \"c\"\n")
        assertEquals(VStr("a"), root.get("token", 1)!!.value)
        assertEquals(VStr("b"), root.get("token", 2)!!.value)
        assertEquals(VStr("c"), root.get("token", 3)!!.value)
        assertNull(root.get("token", 4))
    }

    @Test
    fun `ref is built from atoms`() {
        val v = valueOf(parseStr("token = server.token[1]\n"), "token") as VRef
        assertEquals(listOf("server", "token"), v.path.segments)
        assertEquals(listOf(null, 1), v.path.indices)
        assertTrue(v.path.absolute)
    }

    @Test
    fun `ref numbered suffix is plain segment`() {
        val v = valueOf(parseStr("token = server1.host\n"), "token") as VRef
        assertEquals(listOf("server1", "host"), v.path.segments)
        assertEquals(listOf(null, null), v.path.indices)
    }

    @Test
    fun `ref relative starts with dot`() {
        val v = valueOf(parseStr("host = .shared.host\n"), "host") as VRef
        assertEquals(listOf("shared", "host"), v.path.segments)
        assertEquals(listOf(null, null), v.path.indices)
        assertEquals(false, v.path.absolute)
    }

    @Test
    fun `ref double dot is error`() {
        val e = assertFailsWith<CrenError.Parse> { parseStr("a = ..host\n") }
        assertTrue(e.message!!.contains("«..»"))
    }

    @Test
    fun `explicit type is checked`() {
        val root = parseStr("token str = \"bot\"\ncount int = 42\nratio float = 1.5\nflag bool = true\n")
        assertEquals(VStr("bot"), valueOf(root, "token"))
        assertEquals(VInt(42), valueOf(root, "count"))
        assertEquals(VFloat(1.5), valueOf(root, "ratio"))
        assertEquals(VBool(true), valueOf(root, "flag"))
    }

    @Test
    fun `explicit type mismatch is error`() {
        val e = assertFailsWith<CrenError.TypeMismatch> { parseStr("token str = 42\n") }
        assertEquals("str", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `unknown type is error`() {
        val e = assertFailsWith<CrenError.Parse> { parseStr("token magic = \"x\"\n") }
        assertTrue(e.messageText.contains("неизвестный тип"))
    }

    @Test
    fun `leading and trailing comments`() {
        val root = parseStr("# перед записью\nport = 8080 # после значения\n")
        val port = root.get("port", 1)!!
        assertEquals("после значения", port.comment)
    }

    @Test
    fun `dict inline value`() {
        val d = valueOf(parseStr("token = {name: \"bot\", value: \"...\"}\n"), "token") as VDict
        assertEquals(2, d.pairs.size)
        assertEquals("name" to VStr("bot"), d.pairs[0])
        assertEquals("value" to VStr("..."), d.pairs[1])
    }

    @Test
    fun `array value with equal sign`() {
        val a = valueOf(parseStr("ports = [8080, 9090]\n"), "ports") as VArray
        assertEquals(listOf(VInt(8080), VInt(9090)), a.items)
    }

    @Test
    fun `block in one line`() {
        val b = valueOf(parseStr("server { host = \"localhost\" }\n"), "server") as VBlock
        assertEquals(VStr("localhost"), b.block.get("host", 1)!!.value)
    }

    @Test
    fun `empty block and empty array`() {
        val root = parseStr("a {}\nb []\n")
        assertTrue((valueOf(root, "a") as VBlock).block.entries.isEmpty())
        assertTrue((valueOf(root, "b") as VArray).items.isEmpty())
    }

    @Test
    fun `error stray brace`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("}\n")) }
        assertTrue(e.message!!.contains("лишняя «}»"))
    }

    @Test
    fun `error unclosed block`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("server {\nhost = \"x\"\n")) }
        assertTrue(e.message!!.contains("не закрыт блок"))
    }

    @Test
    fun `error expected key`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("= \"x\"\n")) }
        assertTrue(e.message!!.contains("ожидался ключ"))
    }

    @Test
    fun `error expected value`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("a =\n")) }
        assertTrue(e.message!!.contains("ожидалось значение"))
    }

    @Test
    fun `error garbage after value`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("a = 1 42\n")) }
        assertTrue(e.message!!.contains("ожидался конец строки"))
    }

    @Test
    fun `block and array forms keep explicit type`() {
        val server = parseStr("server block { host = \"x\" }\n").get("server", 1)!!
        assertEquals(Type.BLOCK, server.ty)

        val ports = parseStr("ports array [ 1 ]\n").get("ports", 1)!!
        assertEquals(Type.ARRAY, ports.ty)

        val e = assertFailsWith<CrenError.TypeMismatch> { parseStr("server dict { host = \"x\" }\n") }
        assertEquals("dict", e.expected)
        assertEquals("block", e.found)
    }

    @Test
    fun `trailing comment after block and array`() {
        assertEquals("коммент", parseStr("ports [ 1, 2 ] # коммент\n").get("ports", 1)!!.comment)
        assertEquals("за сервером", parseStr("server { host = \"x\" } # за сервером\n").get("server", 1)!!.comment)
    }

    @Test
    fun `block get index zero is none not panic`() {
        val root = parseStr("a = 1\n")
        assertNull(root.get("a", 0))
    }

    @Test
    fun `digit leading key parses`() {
        val root = parseStr("2fa = true\n")
        assertEquals(VBool(true), valueOf(root, "2fa"))
    }

    @Test
    fun `mid path index is parse error`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("a = b[1].x\n")) }
        assertTrue(e.message!!.contains("server1.port"))
    }

    @Test
    fun `negative and float values`() {
        val root = parseStr("a = -5\nb = 1.5\nc = -2.5\n")
        assertEquals(VInt(-5), valueOf(root, "a"))
        assertEquals(VFloat(1.5), valueOf(root, "b"))
        assertEquals(VFloat(-2.5), valueOf(root, "c"))
    }

    @Test
    fun `comments inside array are skipped`() {
        val a = valueOf(parseStr("ports [\n    8080,  # веб\n    9090   # внутренний\n]\n"), "ports") as VArray
        assertEquals(listOf(VInt(8080), VInt(9090)), a.items)

        val a2 = valueOf(parseStr("ports [ # список\n    8080\n]\n"), "ports") as VArray
        assertEquals(listOf(VInt(8080)), a2.items)
    }

    @Test
    fun `comments inside dict are skipped`() {
        val d = valueOf(parseStr("db = { name: \"x\", # имя\n  port: 5432 }\n"), "db") as VDict
        assertEquals(2, d.pairs.size)
        assertEquals("name" to VStr("x"), d.pairs[0])
        assertEquals("port" to VInt(5432), d.pairs[1])
    }

    @Test
    fun `array without commas is error`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("ports [8080 9090]\n")) }
        assertTrue(e.message!!.contains("«,» или «]»"))
    }

    @Test
    fun `dict without commas is error`() {
        val e = assertFailsWith<CrenError.Parse> { Parser.parse(Tokenizer.tokenize("db = { name: \"x\" port: 5432 }\n")) }
        assertTrue(e.message!!.contains("«,» или «}»"))
    }

    @Test
    fun `multiple leading comments all kept`() {
        val root = parseStr("# первый\n# второй\nkey = 1\n")
        assertEquals("первый\nвторой", root.get("key", 1)!!.comment)
    }
}