package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Entry
import dev.ggtv.kjen.Path
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Type
import dev.ggtv.kjen.Value
import dev.ggtv.kjen.Value.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Обратная совместимость резолвинга `.crn`. Порт ResolverTest из Kjen. */
class KorenResolverTest {

    private fun cfg(input: String): KorenConfig = KorenConfig.fromString(input.trimIndent())

    @Test
    fun `simple ref`() {
        val c = cfg("""
            server {
                token = "abc"
            }
            client {
                token = server.token
            }
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
            b {
                port = 8080
            }
        """)
        assertEquals(8080L, c.getInt("a"))
    }

    @Test
    fun `chain of refs`() {
        val c = cfg("""
            a = b
            b = c
            c = "конец"
        """)
        assertEquals("конец", c.getStr("a"))
    }

    @Test
    fun `cycle is an error`() {
        val c = cfg("""
            a = b
            b = a
        """)
        val e = assertFailsWith<CrenError.Cycle> { c.get("a") }
        assertEquals("a", e.path)
    }

    @Test
    fun `self cycle is an error`() {
        val c = cfg("a = a")
        assertFailsWith<CrenError.Cycle> { c.get("a") }
    }

    @Test
    fun `ref to block`() {
        val c = cfg("""
            default {
                port = 9090
            }
            server = default
        """)
        val block = c.getBlock("server")
        assertEquals(VInt(9090), block.get("port", 1)!!.value)
    }

    @Test
    fun `ref inside block`() {
        val c = cfg("""
            shared {
                host = "localhost"
            }
            server {
                host = shared.host
            }
        """)
        assertEquals("localhost", c.getStr("server.host"))
    }

    @Test
    fun `ref typed check`() {
        val c = cfg("""
            target = "значение"
            x ref = target
        """)
        assertEquals("значение", c.getStr("x"))
    }

    @Test
    fun `not found`() {
        val c = cfg("a = 1")
        val e = assertFailsWith<CrenError.NotFound> { c.get("b") }
        assertEquals("b", e.path)

        assertFailsWith<CrenError.NotFound> { c.get("a[5]") }

        val c2 = cfg("x = nope.deep")
        assertFailsWith<CrenError.NotFound> { c2.get("x") }
    }

    @Test
    fun `typed getters`() {
        val c = cfg("""
            s = "текст"
            i = 42
            f = 1.5
            b = true
        """)
        assertEquals("текст", c.getStr("s"))
        assertEquals(42L, c.getInt("i"))
        assertEquals(1.5, c.getFloat("f"))
        assertEquals(42.0, c.getFloat("i")) // int → float
        assertTrue(c.getBool("b"))
    }

    @Test
    fun `typed getter mismatch`() {
        val e = assertFailsWith<CrenError.TypeMismatch> { cfg("i = 42").getStr("i") }
        assertEquals("str", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `get comment works`() {
        val c = cfg("""
            # коммент к порту
            port = 8080 # и после
        """)
        assertEquals("коммент к порту\nи после", c.getComment("port"))
        assertFailsWith<CrenError.NotFound> { c.getComment("nope") }
    }

    @Test
    fun `keys works`() {
        val c = cfg("""
            server {
                host = "x"
                port = 1
                host = "y"   # дубликат — в keys попадёт один раз
                ssl = true
            }
        """)
        assertEquals(listOf("host", "port", "ssl"), c.keys("server"))
    }

    @Test
    fun `full pipeline`() {
        val c = cfg("""
            # Корень
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
    fun `empty path is not found not panic`() {
        val root = Block()
        val res = KorenResolver(root, EmptyWorldContext)
        val e = assertFailsWith<CrenError.NotFound> {
            res.resolve(Path(segments = emptyList(), indices = emptyList(), absolute = true))
        }
        assertEquals("", e.path)
    }

    @Test
    fun `numbered suffix disambiguates multi blocks`() {
        val c = cfg("""
            server { host = "один" }
            server { host = "два" }
        """)
        assertEquals("один", c.getStr("server1.host"))
        assertEquals("два", c.getStr("server2.host"))
        assertFailsWith<CrenError.Ambiguous> { c.get("server.host") }
    }

    @Test
    fun `plain ref to duplicate key is ambiguous error`() {
        val e = assertFailsWith<CrenError.Ambiguous> { cfg("token = \"a\"\ntoken = \"b\"").getStr("token") }
        assertEquals("token", e.path)
        assertEquals(2, e.count)
    }

    @Test
    fun `unique key plain and numbered both work`() {
        val c = cfg("server { host = \"x\" }")
        assertEquals("x", c.getStr("server.host"))
        assertEquals("x", c.getStr("server1.host"))
        assertFailsWith<CrenError.NotFound> { c.get("server2.host") }
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
            server {
                token = "c"
            }
        """)
        assertEquals("b", c.getStr("server1.token[2]"))
        assertEquals("c", c.getStr("server2.token[1]"))
        assertFailsWith<CrenError.Ambiguous> { c.get("server1.token") }
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
        assertFailsWith<CrenError.Ambiguous> { c.keys("server") }
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
        val c = cfg("x = \"корень\"\ny = .x")
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
        assertFailsWith<CrenError.Cycle> { c.get("server.x") }
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
    fun `get array and dict`() {
        val c = cfg("""
            ports = [8080, 9090]
            db = { name: "x", port: 5432 }
        """)
        assertEquals(listOf(VInt(8080), VInt(9090)), c.getArray("ports"))
        assertEquals(
            listOf("name" to VStr("x"), "port" to VInt(5432)),
            c.getDict("db").mapNotNull { p ->
                p.second.let { p.first to it }
            },
        )
    }

    @Test
    fun `load from file`() {
        val tmp = java.nio.file.Files.createTempFile("koren", ".kn")
        java.nio.file.Files.writeString(tmp, "title = \"из файла\"\n")
        assertEquals("из файла", KorenConfig.load(tmp).getStr("title"))
        tmp.toFile().delete()
    }

    @Test
    fun `load missing file is io error`() {
        val e = assertFailsWith<CrenError.Io> { KorenConfig.load("/nonexistent/путь/x.kn") }
        assertTrue(e.message!!.contains("не могу прочитать"))
    }

    @Test
    fun `reference suffix does not resolve unselected siblings`() {
        val c = cfg("""
            source {
                good = 1
                bad = missing
            }
            alias = source
        """)
        assertEquals(1L, c.getInt("source.good"))
        assertEquals(1L, c.getInt("alias.good"))
        val e = assertFailsWith<CrenError.NotFound> { c.get("alias") }
        assertEquals("missing", e.path)
    }

    @Test
    fun `reference suffix skips unselected cycle`() {
        val c = cfg("""
            source {
                good = 1
                bad = source.bad
            }
            alias = source
        """)
        assertEquals(1L, c.getInt("alias.good"))
        assertFailsWith<CrenError.Cycle> { c.get("source.bad") }
        assertFailsWith<CrenError.Cycle> { c.get("alias.bad") }
    }

    @Test
    fun `alias with mismatched indices does not panic`() {
        val root = KorenParser.parse(KorenTokenizer.tokenize("source { good = 1 }\nalias = source\n"))
        val path = Path(listOf("alias", "good"), emptyList(), true)
        assertEquals(VInt(1), KorenResolver(root).resolve(path))
    }

    @Test
    fun `refs inside containers and returned blocks are resolved`() {
        val c = cfg("""
            source = "value"
            array = [source]
            dict = { value: source }
            server {
                value = "local"
                array = [.value]
            }
        """)
        assertEquals(VArray(listOf(VStr("value"))), c.get("array"))
        assertEquals(VDict(listOf("value" to VStr("value"))), c.get("dict"))
        assertEquals(VArray(listOf(VStr("local"))), c.get("server.array"))
        assertEquals(
            VArray(listOf(VStr("local"))),
            c.getBlock("server").get("array", 1)!!.value,
        )
    }

    @Test
    fun `whole config is a dynamic resolved value`() {
        val c = KorenConfig.fromStringWithEnv(
            """
            host = "${'$'}{KOREN_HOST}"
            port = 1
            port = 2
            count int = 3 # количество
            server {
                address = "https://${'$'}{KOREN_HOST}"
                values = [1, 2]
            }
            server {
                local = "second"
                address = .local
            }
            client {
                address = server2.address
                endpoints = [server1.address, {url: server1.address}]
            }
            """.trimIndent(),
            mapOf("KOREN_HOST" to "example.com"),
        )
        val root = (c.toValue() as VBlock).block
        assertEquals(VStr("example.com"), root.get("host", 1)!!.value)
        assertEquals(VInt(2), root.get("port", 2)!!.value)
        val count = root.get("count", 1)!!
        assertEquals(Type.INT, count.ty)
        assertEquals("количество", count.comment)
        assertEquals(Span(4, 1), count.span)
        val client = root.get("client", 1)!!.value as VBlock
        assertEquals(VStr("second"), client.block.get("address", 1)!!.value)
        assertEquals(
            VArray(listOf(
                VStr("https://example.com"),
                VDict(listOf("url" to VStr("https://example.com"))),
            )),
            client.block.get("endpoints", 1)!!.value,
        )
    }

    @Test
    fun `container ref cycles are detected`() {
        for (input in listOf("a = [b]\nb = [a]\n", "a = {value: a}\n")) {
            assertFailsWith<CrenError.Cycle> { cfg(input).get("a") }
        }
    }

    @Test
    fun `reference depth is bounded`() {
        fun chain(last: Int): String {
            val out = StringBuilder("v$last = \"ok\"\n")
            for (i in last - 1 downTo 0) out.append("v$i = v${i + 1}\n")
            return out.toString()
        }
        assertEquals("ok", cfg(chain(256)).getStr("v0"))
        val e = assertFailsWith<CrenError.Parse> { cfg(chain(300)).getStr("v0") }
        assertTrue(e.messageText.contains("глубина ссылок: максимум 256"))
    }

    @Test
    fun `path length is bounded`() {
        fun makeRoot(count: Int): Block {
            var block = Block().apply {
                entries += Entry("leaf", null, VInt(1), null, Span(1, 1))
            }
            for (i in count - 1 downTo 0) {
                block = Block().apply {
                    entries += Entry("s$i", null, VBlock(block), null, Span(1, 1))
                }
            }
            return block
        }

        fun makePath(count: Int) = Path(
            (0 until count).map { "s$it" } + "leaf",
            List(count + 1) { null },
            true,
        )

        assertEquals(VInt(1), KorenResolver(makeRoot(255)).resolve(makePath(255)))
        val e = assertFailsWith<CrenError.Parse> {
            KorenResolver(makeRoot(256)).resolve(makePath(256))
        }
        assertTrue(e.messageText.contains("длина пути: максимум 256"))
    }

    @Test
    fun `exponential container expansion is bounded and resolver resets budget`() {
        val input = buildString {
            appendLine("v18 = 1")
            for (i in 17 downTo 0) appendLine("v$i = [v${i + 1}, v${i + 1}]")
        }
        val resolver = KorenResolver(KorenParser.parse(KorenTokenizer.tokenize(input)))
        val e = assertFailsWith<CrenError.Parse> { resolver.resolve(Path.parse("v0")) }
        assertTrue(e.messageText.contains("превышен предел раскрытия: максимум 65536"))
        assertEquals(VInt(1), resolver.resolve(Path.parse("v18")))
    }

    @Test
    fun `expansion budget boundary is enforced`() {
        fun root(size: Int) = Block().apply {
            entries += Entry(
                "values",
                null,
                VArray(List(size) { VInt(it.toLong()) }),
                null,
                Span(1, 1),
            )
        }

        val path = Path.parse("values")
        assertEquals(
            (0 until 65_535).map { VInt(it.toLong()) },
            (KorenResolver(root(65_535)).resolve(path) as VArray).items,
        )
        val e = assertFailsWith<CrenError.Parse> { KorenResolver(root(65_536)).resolve(path) }
        assertTrue(e.messageText.contains("превышен предел раскрытия: максимум 65536"))
    }

    @Test
    fun `manually built nested values are depth limited`() {
        var value: Value = VInt(1)
        repeat(300) { value = VArray(listOf(value)) }
        val root = Block().apply {
            entries += Entry("deep", Type.ARRAY, value, null, Span(1, 1))
        }
        val e = assertFailsWith<CrenError.Parse> {
            KorenResolver(root).resolve(Path.parse("deep"))
        }
        assertTrue(e.messageText.contains("вложенность значений: максимум 256"))
    }

    @Test
    fun `not found keeps requested path through a reference`() {
        val c = cfg("a = 1\nb = a\nx = b.c\n")
        val e = assertFailsWith<CrenError.NotFound> { c.get("x") }
        assertEquals("b.c", e.path)
    }

    @Test
    fun `typed function result is checked after evaluation`() {
        val ok = cfg("value int = hash(42)\n")
        assertEquals(cfg("value = hash(42)\n").getInt("value"), ok.getInt("value"))

        val e = assertFailsWith<CrenError.TypeMismatch> { cfg("value str = hash(42)\n").get("value") }
        assertEquals("str", e.expected)
        assertEquals("int", e.found)
    }

    @Test
    fun `function cannot be traversed as a block`() {
        val c = cfg("value = hash(42)\n")
        assertFailsWith<CrenError.NotFound> { c.get("value.field") }
    }
}
