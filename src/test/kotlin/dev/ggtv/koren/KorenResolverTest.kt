package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Path
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
        assertEquals("и после", c.getComment("port"))
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
}