package dev.ggtv.kjen

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Тесты путей — порт tests/path.rs из Rust-проекта Cren. */
class PathTest {

    @Test
    fun `path simple`() {
        val p = Path.parse("server.token")
        assertEquals(listOf("server", "token"), p.segments)
        assertEquals(listOf(null, null), p.indices)
        assertTrue(p.absolute)
    }

    @Test
    fun `path with index`() {
        val p = Path.parse("server.token[1]")
        assertEquals(listOf("server", "token"), p.segments)
        assertEquals(listOf(null, 1), p.indices)
    }

    @Test
    fun `path numbered suffix stays in name`() {
        val p = Path.parse("server1.host")
        assertEquals(listOf("server1", "host"), p.segments)
        assertEquals(listOf(null, null), p.indices)
        assertEquals("server1.host", p.toString())
    }

    @Test
    fun `path relative with leading dot`() {
        val p = Path.parse(".shared.host")
        assertEquals(listOf("shared", "host"), p.segments)
        assertEquals(false, p.absolute)
        assertEquals("shared.host", p.toString())

        assertFailsWith<CrenError.Parse> { Path.parse("..shared") }
        assertFailsWith<CrenError.Parse> { Path.parse("..") }
        assertFailsWith<CrenError.Parse> { Path.parse(".") }
    }

    @Test
    fun `path with spaces`() {
        val p = Path.parse("server. token ")
        assertEquals(listOf("server", "token"), p.segments)
    }

    @Test
    fun `path invalid index`() {
        assertFailsWith<CrenError.Parse> { Path.parse("server.token[0]") }
        assertFailsWith<CrenError.Parse> { Path.parse("server.token[abc]") }
    }

    @Test
    fun `path index only at the end`() {
        assertFailsWith<CrenError.Parse> { Path.parse("server[1].port") }
        assertFailsWith<CrenError.Parse> { Path.parse("[1]") }
        assertFailsWith<CrenError.Parse> { Path.parse("server.[1]") }
        assertFailsWith<CrenError.Parse> { Path.parse("a[1][2]") }
        assertFailsWith<CrenError.Parse> { Path.parse("a]b") }

        val p = Path.parse("server.token[2]")
        assertEquals(listOf("server", "token"), p.segments)
        assertEquals(listOf(null, 2), p.indices)
    }

    @Test
    fun `path empty is error`() {
        assertFailsWith<CrenError.Parse> { Path.parse("") }
    }

    @Test
    fun `path empty segment is error`() {
        assertFailsWith<CrenError.Parse> { Path.parse("a..b") }
        assertFailsWith<CrenError.Parse> { Path.parse("a..") }
        assertFailsWith<CrenError.Parse> { Path.parse("a.") }
        assertFailsWith<CrenError.Parse> { Path.parse("server..host") }
    }
}