package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Type
import dev.ggtv.kjen.Value
import dev.ggtv.kjen.Value.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Обратная совместимость парсинга + вызовы функций. Порт ParserTest из Kjen. */
class KorenParserTest {

    private fun parseStr(input: String): Block = KorenParser.parse(KorenTokenizer.tokenize(input))

    private fun parseErr(input: String, messagePart: String) {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize(input)) }
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
    fun `function call is parsed as VFunc`() {
        val v = valueOf(parseStr("x = clamp(a, 0, 100)\n"), "x")
        assertTrue(v is VFunc)
        assertEquals("clamp", v.name)
        assertEquals(3, v.args.size)
        assertTrue(v.args[0] is VRef)
        assertEquals(VInt(0), v.args[1])
        assertEquals(VInt(100), v.args[2])
    }

    @Test
    fun `nested function calls`() {
        val v = valueOf(parseStr("x = lerp(0, 1, clamp(t, 0, 1))\n"), "x") as VFunc
        assertEquals("lerp", v.name)
        val inner = v.args[2] as VFunc
        assertEquals("clamp", inner.name)
    }

    @Test
    fun `function call with string arg`() {
        val v = valueOf(parseStr("x = hash(uuid)\n"), "x") as VFunc
        assertEquals("hash", v.name)
        assertTrue(v.args[0] is VRef)
    }

    @Test
    fun `leading and trailing comments`() {
        val root = parseStr("# перед записью\nport = 8080 # после значения\n")
        val port = root.get("port", 1)!!
        assertEquals("перед записью\nпосле значения", port.comment)
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
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("}\n")) }
        assertTrue(e.message!!.contains("лишняя «}»"))
    }

    @Test
    fun `error unclosed block`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("server {\nhost = \"x\"\n")) }
        assertTrue(e.message!!.contains("не закрыт блок"))
    }

    @Test
    fun `error expected key`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("= \"x\"\n")) }
        assertTrue(e.message!!.contains("ожидался ключ"))
    }

    @Test
    fun `error expected value`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("a =\n")) }
        assertTrue(e.message!!.contains("ожидалось значение"))
    }

    @Test
    fun `error garbage after value`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("a = 1 42\n")) }
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
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("a = b[1].x\n")) }
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
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("ports [8080 9090]\n")) }
        assertTrue(e.message!!.contains("«,» или «]»"))
    }

    @Test
    fun `dict without commas is error`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("db = { name: \"x\" port: 5432 }\n")) }
        assertTrue(e.message!!.contains("«,» или «}»"))
    }

    @Test
    fun `dotted dict keys keep full path`() {
        val d = valueOf(parseStr("when = { biome.temperature: 0.5 }\n"), "when") as VDict
        assertEquals(1, d.pairs.size)
        assertEquals("biome.temperature" to VFloat(0.5), d.pairs[0])
    }

    @Test
    fun `dotted dict key with bad segment is error`() {
        val e = assertFailsWith<CrenError.Parse> { KorenParser.parse(KorenTokenizer.tokenize("when = { biome.: 1 }\n")) }
        assertTrue(e.message!!.contains("после «.»"))
    }

    @Test
    fun `multiple leading comments all kept`() {
        val root = parseStr("# первый\n# второй\nkey = 1\n")
        assertEquals("первый\nвторой", root.get("key", 1)!!.comment)
    }

    @Test
    fun `unknown function passes parse and fails at resolve`() {
        val root = parseStr("x = unknownfunc(a, b)\n")
        val v = valueOf(root, "x")
        assertTrue(v is VFunc)
    }

    @Test
    fun `unclosed block reports opening brace span`() {
        val e = assertFailsWith<CrenError.Parse> { parseStr("server {\n  host = \"x\"\n") }
        assertEquals(Span(1, 8), e.span)
    }

    @Test
    fun `error at eof uses last token span`() {
        val e = assertFailsWith<CrenError.Parse> { parseStr("a = [1") }
        assertEquals(Span(1, 6), e.span)
    }

    @Test
    fun `explicit ref type requires reference syntax`() {
        val e = assertFailsWith<CrenError.TypeMismatch> { parseStr("x ref = 42\n") }
        assertEquals("ref", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `leading and repeated commas are rejected`() {
        for (input in listOf("a = [,]\n", "a = [1,,2]\n", "a = {x: 1,,}\n", "a = f(1,,2)\n")) {
            val e = assertFailsWith<CrenError.Parse> { parseStr(input) }
            assertTrue(e.messageText.contains("лишняя «,»"))
        }
    }

    @Test
    fun `trailing commas are accepted`() {
        val root = parseStr("a = [1,]\nb = {x: 2,}\nc = f(1,)\n")
        assertEquals(VArray(listOf(VInt(1))), valueOf(root, "a"))
        assertEquals(VDict(listOf("x" to VInt(2))), valueOf(root, "b"))
        assertEquals(1, (valueOf(root, "c") as VFunc).args.size)
    }

    @Test
    fun `excessive nesting returns error`() {
        val input = "a = ${"[".repeat(129)}${"]".repeat(129)}\n"
        val e = assertFailsWith<CrenError.Parse> { parseStr(input) }
        assertTrue(e.messageText.contains("максимум 128"))
        assertEquals(Span(1, 133), e.span)
    }

    @Test
    fun `oversized path index is rejected`() {
        val e = assertFailsWith<CrenError.Parse> {
            parseStr("a = key[9223372036854775807]\n")
        }
        assertTrue(e.messageText.contains("слишком велик"))
    }

    @Test
    fun `environment variables are interpolated`() {
        val config = KorenConfig.fromStringWithEnv(
            """
            host = "${'$'}{KOREN_HOST}"
            port = ${'$'}KOREN_PORT
            url = "jdbc://${'$'}{KOREN_HOST}:${'$'}{KOREN_PORT}/db"
            fallback = "${'$'}{KOREN_MISSING:-local}"
            hyphen_name = "${'$'}{KOREN-A-B}"
            empty_default = "${'$'}{KOREN_EMPTY:-fallback}"
            empty_literal = "${'$'}{KOREN_EMPTY}"
            escaped = "${'$'}${'$'}{KOREN_HOST}"
            currency = "cost ${'$'}5"
            trailing = "value${'$'}"
            bare_url = ${'$'}KOREN_URL
            function_env = hash(${'$'}KOREN_FUNCTION_ARG)
            complex = "${'$'}KOREN_COMPLEX"
            """.trimIndent(),
            mapOf(
                "KOREN_HOST" to "localhost",
                "KOREN_PORT" to "9000",
                "KOREN_EMPTY" to "",
                "KOREN-A-B" to "hyphenated",
                "KOREN_URL" to "https://example.com/a?x=1&y=2",
                "KOREN_FUNCTION_ARG" to "42",
                "KOREN_COMPLEX" to "raw=${'$'}{VALUE};quote=\";brace={}",
            ),
        )

        assertEquals("localhost", config.getStr("host"))
        assertEquals("9000", config.getStr("port"))
        assertEquals("jdbc://localhost:9000/db", config.getStr("url"))
        assertEquals("local", config.getStr("fallback"))
        assertEquals("hyphenated", config.getStr("hyphen_name"))
        assertEquals("fallback", config.getStr("empty_default"))
        assertEquals("", config.getStr("empty_literal"))
        assertEquals("${'$'}{KOREN_HOST}", config.getStr("escaped"))
        assertEquals("cost ${'$'}5", config.getStr("currency"))
        assertEquals("value${'$'}", config.getStr("trailing"))
        assertEquals("https://example.com/a?x=1&y=2", config.getStr("bare_url"))
        assertEquals(config.getInt("function_env"), KorenConfig.fromString("x = hash(42)\n").getInt("x"))
        assertEquals("raw=${'$'}{VALUE};quote=\";brace={}", config.getStr("complex"))
    }

    @Test
    fun `bare substitution is a value, not a broken token`() {
        val config = KorenConfig.fromStringWithEnv(
            """
            bare_braced = ${'$'}{KOREN_HOST}
            bare_default = ${'$'}{KOREN_MISSING:-fallback}
            bare_hyphen = ${'$'}{KOREN-MIXED-Name}
            bare_middle = ${'$'}{KOREN_HOST}/capes/${'$'}{KOREN_PORT}/a.png
            bare_bool = ${'$'}{KOREN_FLAG}
            """.trimIndent(),
            mapOf(
                "KOREN_HOST" to "cdn.example.com",
                "KOREN_PORT" to "8443",
                "KOREN_FLAG" to "true",
                "KOREN-MIXED-Name" to "mixed-1",
            ),
        )

        assertEquals("cdn.example.com", config.getStr("bare_braced"))
        assertEquals("fallback", config.getStr("bare_default"))
        assertEquals("mixed-1", config.getStr("bare_hyphen"))
        assertEquals("cdn.example.com/capes/8443/a.png", config.getStr("bare_middle"))
        assertEquals("true", config.getStr("bare_bool"))
    }

    @Test
    fun `unclosed substitution in a bare value is a parse error`() {
        val e = assertFailsWith<CrenError.Parse> {
            KorenConfig.fromStringWithEnv("a = ${'$'}{OPEN\n", mapOf("OPEN" to "x"))
        }
        assertTrue(e.messageText.contains("незакрытая подстановка окружения"))
    }
    @Test
    fun `unset variable is empty and reported, not an error`() {
        val unset = mutableListOf<String>()
        val config = KorenConfig.fromStringWithEnv(
            "host = \"${'$'}{KOREN_MISSING}\"\nbare = ${'$'}KOREN_ALSO_MISSING\n",
            emptyMap(),
        ) { unset += it }

        assertEquals("", config.getStr("host"))
        assertEquals("", config.getStr("bare"))
        assertEquals(listOf("KOREN_MISSING", "KOREN_ALSO_MISSING"), unset)
    }

    @Test
    fun `name ends at punctuation and the tail is kept`() {
        val config = KorenConfig.fromStringWithEnv(
            """
            base = ${'$'}BASE
            url = ${'$'}BASE/api/cape.png
            file = "${'$'}BASE/a.png"
            comma = "a,${'$'}BASE,b"
            host_port = "${'$'}HOST:${'$'}PORT"
            underscore = "${'$'}{BASE_URL}"
            hyphen = "${'$'}{BASE-URL}"
            trailing = "${'$'}BASE."
            literal_digit = "${'$'}1BASE"
            """.trimIndent(),
            mapOf(
                "BASE" to "localhost:8080",
                "HOST" to "cdn.example.com",
                "PORT" to "8443",
                "BASE_URL" to "u",
                "BASE-URL" to "h",
            ),
        )

        assertEquals("localhost:8080", config.getStr("base"))
        assertEquals("localhost:8080/api/cape.png", config.getStr("url"))
        assertEquals("localhost:8080/a.png", config.getStr("file"))
        assertEquals("a,localhost:8080,b", config.getStr("comma"))
        assertEquals("cdn.example.com:8443", config.getStr("host_port"))
        assertEquals("u", config.getStr("underscore"))
        assertEquals("h", config.getStr("hyphen"))
        assertEquals("localhost:8080.", config.getStr("trailing"))
        // `$1BASE` — не переменная: имя не может начинаться с цифры, и `$`
        // остаётся текстом, как и в `cost $5`.
        assertEquals("${'$'}1BASE", config.getStr("literal_digit"))
    }

    @Test
    fun `malformed environment reference is still an error`() {
        for (input in listOf("a = \"${'$'}{1BAD}\"\n", "a = \"${'$'}{OPEN\"\n")) {
            val malformed = assertFailsWith<CrenError.Parse> {
                KorenConfig.fromStringWithEnv(input, emptyMap())
            }
            assertTrue(malformed.messageText.contains("окружения"))
        }
    }
}
