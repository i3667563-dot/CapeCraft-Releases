package dev.ggtv.capecraft.cren

import dev.ggtv.capecraft.cren.CrenError.*
import dev.ggtv.capecraft.cren.TokenKind.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Тесты токенизатора — перенос из tests/tokenizer.rs. */
class TokenizerTest {

    private fun tokenize(input: String) = Tokenizer.tokenize(input)

    @Test
    fun `base tokens`() {
        val t = tokenize("a = 1\n")
        assertEquals(listOf(Word("a"), Assign, TokenKind.Int(1), Newline), t.map { it.kind })
    }

    @Test
    fun `string with hash inside`() {
        val t = tokenize("a = \"x#y\"\n")
        assertEquals(TokenKind.Str("x#y"), t[2].kind)
    }

    @Test
    fun `comment is kept`() {
        val t = tokenize("# привет\n")
        assertEquals(listOf(Comment("привет"), Newline), t.map { it.kind })
    }

    @Test
    fun `escapes in string`() {
        val t = tokenize("a = \"\\\"x\\\"\\\\\\n\\t\"\n")
        assertEquals(TokenKind.Str("\"x\"\\\n\t"), t[2].kind)
    }

    @Test
    fun `multiline string`() {
        val t = tokenize("a = \"первая\nвторая\"\n")
        assertEquals(TokenKind.Str("первая\nвторая"), t[2].kind)
    }

    @Test
    fun `unclosed string is error`() {
        val e = assertFailsWith<Parse> { tokenize("a = \"незакрыто\n") }
        assertTrue(e.messageText.contains("незакрытая строка"))
    }

    @Test
    fun `unknown escape is error`() {
        val e = assertFailsWith<Parse> { tokenize("a = \"\\q\"\n") }
        assertTrue(e.messageText.contains("неизвестный escape"))
    }

    @Test
    fun `numbers`() {
        val t = tokenize("a = 42\nb = -7\nc = 3.14\nd = -2.5\n")
        assertEquals(TokenKind.Int(42), t[2].kind)
        assertEquals(TokenKind.Int(-7), t[6].kind)
        assertEquals(TokenKind.Float(3.14), t[10].kind)
        assertEquals(TokenKind.Float(-2.5), t[14].kind)
    }

    @Test
    fun `digit leading word is word not number`() {
        val t = tokenize("2fa = true\n")
        assertEquals(Word("2fa"), t[0].kind)
        assertEquals(TokenKind.Bool(true), t[2].kind)
    }

    @Test
    fun `hyphen word is word not negative number`() {
        val t = tokenize("my-key = 1\n")
        assertEquals(Word("my-key"), t[0].kind)
    }

    @Test
    fun `bool literals`() {
        val t = tokenize("a = true\nb = false\n")
        assertEquals(TokenKind.Bool(true), t[2].kind)
        assertEquals(TokenKind.Bool(false), t[6].kind)
    }

    @Test
    fun `spans track line and column`() {
        val t = tokenize("a = 1\nbb = 2\n")
        assertEquals(Span(1, 1), t[0].span)
        assertEquals(Span(2, 1), t[4].span)
        assertEquals(Span(2, 6), t[6].span) // Int 2 на строке 2, колонка 6
    }

    @Test
    fun `unexpected char is error`() {
        val e = assertFailsWith<Parse> { tokenize("a = @\n") }
        assertTrue(e.messageText.contains("неожиданный символ"))
        assertEquals(Span(1, 5), e.span)
    }

    @Test
    fun `dot outside path is token`() {
        val t = tokenize("a = .\n")
        assertEquals(TokenKind.Dot, t[2].kind)
    }

    @Test
    fun `empty input`() {
        assertEquals(emptyList(), tokenize(""))
    }
}

/** Тесты парсера — перенос из tests/parser.rs. */
class ParserTest {

    private fun parseStr(input: String): Block = Parser.parse(Tokenizer.tokenize(input))
    private fun parseErr(input: String, messagePart: String) {
        val e = assertFailsWith<Parse> { Parser.parse(Tokenizer.tokenize(input)) }
        assertTrue(e.messageText.contains(messagePart), "ожидалось «$messagePart» в «${e.messageText}»")
    }

    private fun valueOf(block: Block, key: String, index: Int = 1) =
        block.get(key, index)!!.value

    @Test
    fun `full example from design`() {
        val root = parseStr("""
            # Это комментарий, и он сохранится при парсинге!
            title = "Мой крутой конфиг"

            server {
                host = "localhost"
                port = 8080

                databases [
                    { type = "postgres", url = "jdbc:..." },
                    { type = "redis", url = "redis://..." }
                ]

                security {
                    ssl = true
                    certificates {
                        ca = "/path/to/ca.pem"
                        cert = "/path/to/cert.pem"
                    }
                }
            }
        """.trimIndent())

        // Комментарий сохранился и привязался к следующей записи.
        val title = root.get("title", 1)!!
        assertEquals("Это комментарий, и он сохранится при парсинге!", title.comment)
        assertEquals(Value.VStr("Мой крутой конфиг"), title.value)

        val server = valueOf(root, "server") as Value.VBlock
        assertEquals(Value.VStr("localhost"), valueOf(server.block, "host"))
        assertEquals(Value.VInt(8080), valueOf(server.block, "port"))

        val databases = valueOf(server.block, "databases") as Value.VArray
        assertEquals(2, databases.items.size)
        assertEquals(
            listOf("type" to Value.VStr("redis"), "url" to Value.VStr("redis://...")),
            (databases.items[1] as Value.VDict).pairs,
        )

        val security = valueOf(server.block, "security") as Value.VBlock
        assertEquals(Value.VBool(true), valueOf(security.block, "ssl"))
        val certs = valueOf(security.block, "certificates") as Value.VBlock
        assertEquals(Value.VStr("/path/to/ca.pem"), valueOf(certs.block, "ca"))
        assertEquals(Value.VStr("/path/to/cert.pem"), valueOf(certs.block, "cert"))
    }

    @Test
    fun `multi keys numbered in order`() {
        val root = parseStr("token = \"a\"\ntoken = \"b\"\ntoken = \"c\"\n")
        assertEquals(Value.VStr("a"), root.get("token", 1)!!.value)
        assertEquals(Value.VStr("b"), root.get("token", 2)!!.value)
        assertEquals(Value.VStr("c"), root.get("token", 3)!!.value)
        assertNull(root.get("token", 4))
    }

    @Test
    fun `ref is built from atoms`() {
        val root = parseStr("token = server.token[1]\n")
        val path = (valueOf(root, "token") as Value.VRef).path
        assertEquals(listOf("server", "token"), path.segments)
        assertEquals(listOf(null, 1), path.indices)
        assertTrue(path.absolute)
    }

    @Test
    fun `ref numbered suffix is plain segment`() {
        val path = (valueOf(parseStr("token = server1.host\n"), "token") as Value.VRef).path
        assertEquals(listOf("server1", "host"), path.segments)
        assertEquals(listOf(null, null), path.indices)
    }

    @Test
    fun `ref relative starts with dot`() {
        val path = (valueOf(parseStr("host = .shared.host\n"), "host") as Value.VRef).path
        assertEquals(listOf("shared", "host"), path.segments)
        assertEquals(false, path.absolute)
    }

    @Test
    fun `ref double dot is error`() { parseErr("a = ..host\n", "«..»") }

    @Test
    fun `explicit types are checked`() {
        val root = parseStr("token str = \"bot\"\ncount int = 42\nratio float = 1.5\nflag bool = true\n")
        assertEquals(Value.VStr("bot"), valueOf(root, "token"))
        assertEquals(Value.VInt(42), valueOf(root, "count"))
        assertEquals(Value.VFloat(1.5), valueOf(root, "ratio"))
        assertEquals(Value.VBool(true), valueOf(root, "flag"))
    }

    @Test
    fun `explicit type mismatch is error`() {
        val e = assertThrows<TypeMismatch> { parseStr("token str = 42\n") }
        assertEquals("str", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `unknown type is error`() { parseErr("token magic = \"x\"\n", "неизвестный тип") }

    @Test
    fun `leading and trailing comments`() {
        val root = parseStr("# перед записью\nport = 8080 # после значения\n")
        assertEquals("после значения", root.get("port", 1)!!.comment)
    }

    @Test
    fun `dict inline value`() {
        val d = valueOf(parseStr("token = {name: \"bot\", value: \"...\"}\n"), "token") as Value.VDict
        assertEquals(listOf("name" to Value.VStr("bot"), "value" to Value.VStr("...")), d.pairs)
    }

    @Test
    fun `array value with equal sign`() {
        val a = valueOf(parseStr("ports = [8080, 9090]\n"), "ports") as Value.VArray
        assertEquals(listOf(Value.VInt(8080), Value.VInt(9090)), a.items)
    }

    @Test
    fun `block in one line`() {
        val b = valueOf(parseStr("server { host = \"localhost\" }\n"), "server") as Value.VBlock
        assertEquals(Value.VStr("localhost"), b.block.get("host", 1)!!.value)
    }

    @Test
    fun `empty block and empty array`() {
        val root = parseStr("a {}\nb []\n")
        assertTrue((valueOf(root, "a") as Value.VBlock).block.entries.isEmpty())
        assertTrue((valueOf(root, "b") as Value.VArray).items.isEmpty())
    }

    @Test
    fun `stray brace is error`() { parseErr("}\n", "лишняя «}»") }

    @Test
    fun `unclosed block is error`() { parseErr("server {\nhost = \"x\"\n", "не закрыт блок") }

    @Test
    fun `expected key is error`() { parseErr("= \"x\"\n", "ожидался ключ") }

    @Test
    fun `expected value is error`() { parseErr("a =\n", "ожидалось значение") }

    @Test
    fun `garbage after value is error`() { parseErr("a = 1 42\n", "ожидался конец строки") }

    @Test
    fun `block and array forms keep explicit type`() {
        val server = parseStr("server block { host = \"x\" }\n").get("server", 1)!!
        assertEquals(Type.BLOCK, server.ty)

        val ports = parseStr("ports array [ 1 ]\n").get("ports", 1)!!
        assertEquals(Type.ARRAY, ports.ty)

        val e = assertThrows<TypeMismatch> { parseStr("server dict { host = \"x\" }\n") }
        assertEquals("dict", e.expected)
        assertEquals("block", e.found)
    }

    @Test
    fun `trailing comment after block and array`() {
        assertEquals("коммент", parseStr("ports [ 1, 2 ] # коммент\n").get("ports", 1)!!.comment)
        assertEquals("за сервером", parseStr("server { host = \"x\" } # за сервером\n").get("server", 1)!!.comment)
    }

    @Test
    fun `block get index zero is null`() {
        assertNull(parseStr("a = 1\n").get("a", 0))
    }

    @Test
    fun `digit leading key parses`() {
        assertEquals(Value.VBool(true), valueOf(parseStr("2fa = true\n"), "2fa"))
    }

    @Test
    fun `mid path index is parse error`() {
        parseErr("a = b[1].x\n", "server1.port")
    }

    @Test
    fun `negative and float values`() {
        val root = parseStr("a = -5\nb = 1.5\nc = -2.5\n")
        assertEquals(Value.VInt(-5), valueOf(root, "a"))
        assertEquals(Value.VFloat(1.5), valueOf(root, "b"))
        assertEquals(Value.VFloat(-2.5), valueOf(root, "c"))
    }

    @Test
    fun `comments inside array are skipped`() {
        val a = valueOf(parseStr("ports [\n    8080,  # веб\n    9090   # внутренний\n]\n"), "ports") as Value.VArray
        assertEquals(listOf(Value.VInt(8080), Value.VInt(9090)), a.items)
    }

    @Test
    fun `comments inside dict are skipped`() {
        val d = valueOf(parseStr("db = { name: \"x\", # имя\n  port: 5432 }\n"), "db") as Value.VDict
        assertEquals(2, d.pairs.size)
        assertEquals("x", (d.pairs[0].second as Value.VStr).s)
        assertEquals(5432L, (d.pairs[1].second as Value.VInt).i)
    }

    @Test
    fun `array without commas is error`() { parseErr("ports [8080 9090]\n", "«,» или «]»") }

    @Test
    fun `dict without commas is error`() { parseErr("db = { name: \"x\" port: 5432 }\n", "«,» или «}»") }

    @Test
    fun `multiple leading comments all kept`() {
        assertEquals("первый\nвторой", parseStr("# первый\n# второй\nkey = 1\n").get("key", 1)!!.comment)
    }
}

/** Тесты путей — перенос из tests/path.rs. */
class PathTest {

    @Test
    fun `parse standard path`() {
        val p = Path.parse("server.token[1]")
        assertEquals(listOf("server", "token"), p.segments)
        assertEquals(listOf(null, 1), p.indices)
        assertTrue(p.absolute)
        assertEquals("server.token[1]", p.toString())
    }

    @Test
    fun `relative path`() {
        val p = Path.parse(".shared.host")
        assertEquals(false, p.absolute)
        assertEquals("shared.host", p.toString())
    }

    @Test
    fun `index zero is error`() {
        val e = assertFailsWith<Parse> { Path.parse("a[0]") }
        assertTrue(e.messageText.contains("с 1"))
    }

    @Test
    fun `mid path index is error`() {
        assertTrue(assertFailsWith<Parse> { Path.parse("a[1].b") }.messageText.contains("середине"))
    }

    @Test
    fun `empty segment is error`() {
        assertTrue(assertFailsWith<Parse> { Path.parse("a..b") }.messageText.contains("пустой сегмент"))
    }

    @Test
    fun `double dot is error`() {
        assertTrue(assertFailsWith<Parse> { Path.parse("..host") }.messageText.contains("«..»"))
    }

    @Test
    fun `empty path is error`() {
        assertFailsWith<Parse> { Path.parse("") }
    }
}

/** Тесты резолвера и API — перенос из tests/resolver.rs. */
class ResolverTest {

    private fun cfg(input: String): CrenConfig = CrenConfig.fromString(input.trimIndent())

    @Test
    fun `simple ref`() {
        val c = cfg("""
            server { token = "abc" }
            client { token = server.token }
        """)
        assertEquals("abc", c.getStr("client.token"))
    }

    @Test
    fun `ref with index to multi key`() {
        val c = cfg("""
            server {
                token = "первый"
                token = "второй"
            }
            a = server.token[1]
            b = server.token[2]
        """)
        assertEquals("первый", c.getStr("a"))
        assertEquals("второй", c.getStr("b"))
    }

    @Test
    fun `forward ref`() {
        val c = cfg("""
            a = b.port
            b { port = 8080 }
        """)
        assertEquals(8080L, c.getInt("a"))
    }

    @Test
    fun `chain of refs`() {
        val c = cfg("a = b\nb = c\nc = \"конец\"\n")
        assertEquals("конец", c.getStr("a"))
    }

    @Test
    fun `cycle is an error`() {
        val c = cfg("a = b\nb = a\n")
        val e = assertFailsWith<Cycle> { c.get("a") }
        assertEquals("a", e.path)
    }

    @Test
    fun `self cycle is an error`() {
        val c = cfg("a = a\n")
        assertFailsWith<Cycle> { c.get("a") }
    }

    @Test
    fun `ref to block`() {
        val c = cfg("""
            default { port = 9090 }
            server = default
        """)
        assertEquals(9090L, c.getBlock("server").get("port", 1)!!.value.let { (it as Value.VInt).i })
    }

    @Test
    fun `ref inside block is absolute`() {
        val c = cfg("""
            shared { host = "localhost" }
            server { host = shared.host }
        """)
        assertEquals("localhost", c.getStr("server.host"))
    }

    @Test
    fun `ref typed check`() {
        val c = cfg("target = \"значение\"\nx ref = target\n")
        assertEquals("значение", c.getStr("x"))
    }

    @Test
    fun `not found`() {
        val c = cfg("a = 1\n")
        assertFailsWith<NotFound> { c.get("b") }
        assertFailsWith<NotFound> { c.get("a[5]") }
        val c2 = cfg("x = nope.deep\n")
        assertFailsWith<NotFound> { c2.get("x") }
    }

    @Test
    fun `typed getters`() {
        val c = cfg("s = \"текст\"\ni = 42\nf = 1.5\nb = true\n")
        assertEquals("текст", c.getStr("s"))
        assertEquals(42L, c.getInt("i"))
        assertEquals(1.5, c.getFloat("f"))
        assertEquals(42.0, c.getFloat("i")) // int → float
        assertEquals(true, c.getBool("b"))
    }

    @Test
    fun `typed getter mismatch`() {
        val e = assertFailsWith<TypeMismatch> { cfg("i = 42\n").getStr("i") }
        assertEquals("str", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `get comment works`() {
        val c = cfg("# коммент к порту\nport = 8080 # и после\n")
        assertEquals("и после", c.getComment("port"))
        assertFailsWith<NotFound> { c.getComment("nope") }
    }

    @Test
    fun `keys works`() {
        val c = cfg("""
            server {
                host = "x"
                port = 1
                host = "y"
                ssl = true
            }
        """)
        assertEquals(listOf("host", "port", "ssl"), c.keys("server"))
    }

    @Test
    fun `full pipeline`() {
        val c = cfg("""
            title = "Мой крутой конфиг"
            server {
                host = "localhost"
                port = 8080
                token = "секрет"
            }
            client {
                address = server.host
                token = server.token
            }
        """)
        assertEquals("Мой крутой конфиг", c.getStr("title"))
        assertEquals(8080L, c.getInt("server.port"))
        assertEquals("localhost", c.getStr("client.address"))
        assertEquals("секрет", c.getStr("client.token"))
    }

    @Test
    fun `numbered suffix disambiguates multi blocks`() {
        val c = cfg("""
            server { host = "один" }
            server { host = "два" }
        """)
        assertEquals("один", c.getStr("server1.host"))
        assertEquals("два", c.getStr("server2.host"))
        assertFailsWith<Ambiguous> { c.get("server.host") }
    }

    @Test
    fun `plain ref to duplicate key is ambiguous error`() {
        val e = assertFailsWith<Ambiguous> { cfg("token = \"a\"\ntoken = \"b\"\n").getStr("token") }
        assertEquals("token", e.path)
        assertEquals(2, e.count)
    }

    @Test
    fun `unique key plain and numbered both work`() {
        val c = cfg("server { host = \"x\" }\n")
        assertEquals("x", c.getStr("server.host"))
        assertEquals("x", c.getStr("server1.host"))
        assertFailsWith<NotFound> { c.get("server2.host") }
    }

    @Test
    fun `literal key wins over numbered form`() {
        val c = cfg("""
            server { host = "первый" }
            server { host = "второй" }
            server1 { host = "литерал" }
        """)
        assertEquals("литерал", c.getStr("server1.host"))
    }

    @Test
    fun `numbered suffix with trailing index`() {
        val c = cfg("""
            server {
                token = "a"
                token = "b"
            }
            server { token = "c" }
        """)
        assertEquals("b", c.getStr("server1.token[2]"))
        assertEquals("c", c.getStr("server2.token[1]"))
        assertFailsWith<Ambiguous> { c.get("server1.token") }
    }

    @Test
    fun `numbered suffix through config getters`() {
        val c = cfg("""
            server { host = "a" }
            server {
                # порт второго
                port = 8080
            }
        """)
        assertEquals(listOf("port"), c.keys("server2"))
        assertEquals("порт второго", c.getComment("server2.port"))
        assertFailsWith<Ambiguous> { c.keys("server") }
    }

    @Test
    fun `relative ref resolves from own block`() {
        val c = cfg("""
            server {
                shared { host = "свой" }
                host = .shared.host
            }
            root_shared { host = "чужой" }
        """)
        assertEquals("свой", c.getStr("server.host"))
    }

    @Test
    fun `relative ref in root resolves from root`() {
        val c = cfg("x = \"корень\"\ny = .x\n")
        assertEquals("корень", c.getStr("y"))
    }

    @Test
    fun `relative ref distinguishes multi blocks`() {
        val c = cfg("""
            server {
                v = "A"
                x = .v
            }
            server {
                v = "B"
                x = .v
            }
        """)
        assertEquals("A", c.getStr("server1.x"))
        assertEquals("B", c.getStr("server2.x"))
    }

    @Test
    fun `relative ref forward`() {
        val c = cfg("""
            server {
                x = .defined_below
                defined_below = "внизу"
            }
        """)
        assertEquals("внизу", c.getStr("server.x"))
    }

    @Test
    fun `relative ref with numbered suffix and index`() {
        val c = cfg("""
            server {
                token = "a"
                token = "b"
                x = .token[2]
                y = .token2
            }
        """)
        assertEquals("b", c.getStr("server.x"))
        assertEquals("b", c.getStr("server.y"))
    }

    @Test
    fun `relative ref cycle is an error`() {
        val c = cfg("""
            server {
                x = .y
                y = .x
            }
        """)
        assertFailsWith<Cycle> { c.get("server.x") }
    }

    @Test
    fun `relative ref into absolute ref`() {
        val c = cfg("""
            real { v = "цепочка" }
            server {
                alias = real.v
                x = .alias
            }
        """)
        assertEquals("цепочка", c.getStr("server.x"))
    }

    @Test
    fun `load from file`() {
        val tmp = java.nio.file.Files.createTempFile("cren", ".crn")
        java.nio.file.Files.writeString(tmp, "title = \"из файла\"\n")
        assertEquals("из файла", CrenConfig.load(tmp).getStr("title"))
        tmp.toFile().delete()
    }

    @Test
    fun `load missing file is io error`() {
        val e = assertFailsWith<Io> { CrenConfig.load("/nonexistent/путь/x.crn") }
        assertTrue(e.message!!.contains("не могу прочитать"))
    }
}