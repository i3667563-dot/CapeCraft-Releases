package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Встроенные функции `.kn`: hash, clamp, lerp, seq, min, max, abs. */
class KorenFunctionsTest {

    private fun cfg(input: String): KorenConfig = KorenConfig.fromString(input.trimIndent())

    // ── hash ──────────────────────────────────────────────────────────

    @Test
    fun `hash is deterministic`() {
        val c = cfg("a = hash(\"привет\")\nb = hash(\"привет\")\nc = hash(\"другое\")\n")
        val a = c.getInt("a")
        assertEquals(a, c.getInt("b"))
        assertTrue(a != c.getInt("c"))
    }

    @Test
    fun `hash of number and string differ`() {
        val c = cfg("a = hash(42)\nb = hash(43)\n")
        assertTrue(c.getInt("a") != c.getInt("b"))
    }

    @Test
    fun `hash works on ref`() {
        val c = cfg("uuid = \"abc-123\"\nh = hash(uuid)\n")
        val direct = KorenConfig.fromString("h = hash(\"abc-123\")\n").getInt("h")
        assertEquals(direct, c.getInt("h"))
    }

    @Test
    fun `hash wrong type is error`() {
        val e = assertFailsWith<CrenError.TypeMismatch> {
            cfg("h = hash([1, 2])\n").get("h")
        }
        assertTrue(e.message!!.contains("несоответствие"))
    }

    // ── clamp ─────────────────────────────────────────────────────────

    @Test
    fun `clamp inside range`() {
        val c = cfg("x = clamp(5, 0, 10)\n")
        assertEquals(5L, c.getInt("x"))
    }

    @Test
    fun `clamp low and high`() {
        val c = cfg("a = clamp(-3, 0, 10)\nb = clamp(27, 0, 10)\n")
        assertEquals(0L, c.getInt("a"))
        assertEquals(10L, c.getInt("b"))
    }

    @Test
    fun `clamp with float`() {
        val c = cfg("a = clamp(0.5, 0.0, 1.0)\nb = clamp(-0.2, 0.0, 1.0)\n")
        assertEquals(0.5, c.getFloat("a"))
        assertEquals(0.0, c.getFloat("b"))
    }

    @Test
    fun `clamp on ref`() {
        val c = cfg("speed = 15\nx = clamp(speed, 0, 10)\n")
        assertEquals(10L, c.getInt("x"))
    }

    @Test
    fun `clamp minimum bigger than maximum is error`() {
        assertFailsWith<CrenError.Parse> { cfg("x = clamp(5, 10, 0)\n").get("x") }
    }

    // ── lerp ──────────────────────────────────────────────────────────

    @Test
    fun `lerp endpoints and middle`() {
        val c = cfg("a = lerp(0, 10, 0)\nb = lerp(0, 10, 1)\nc = lerp(0, 10, 0.5)\n")
        assertEquals(0.0, c.getFloat("a"))
        assertEquals(10.0, c.getFloat("b"))
        assertEquals(5.0, c.getFloat("c"))
    }

    @Test
    fun `lerp with ref and func arg`() {
        val c = cfg("t = 0.25\na = lerp(0, 100, t)\nb = lerp(0, 100, clamp(t, 0, 1))\n")
        assertEquals(25.0, c.getFloat("a"))
        assertEquals(25.0, c.getFloat("b"))
    }

    @Test
    fun `lerp out of range t is error`() {
        assertFailsWith<CrenError.Parse> { cfg("x = lerp(0, 10, 2)\n").get("x") }
    }

    // ── seq ───────────────────────────────────────────────────────────

    @Test
    fun `seq ascending`() {
        val c = cfg("x = seq(1, 4)\n")
        assertEquals(listOf<Value>(Value.VInt(1), Value.VInt(2), Value.VInt(3), Value.VInt(4)), c.getArray("x"))
    }

    @Test
    fun `seq single`() {
        val c = cfg("x = seq(5, 5)\n")
        assertEquals(listOf<Value>(Value.VInt(5)), c.getArray("x"))
    }

    @Test
    fun `seq empty when end before start`() {
        val c = cfg("x = seq(3, 1)\n")
        assertEquals(emptyList<Value>(), c.getArray("x"))
    }

    // ── min / max ─────────────────────────────────────────────────────

    @Test
    fun `min and max`() {
        val c = cfg("a = min(3, 1, 2)\nb = max(3, 1, 2)\n")
        assertEquals(1L, c.getInt("a"))
        assertEquals(3L, c.getInt("b"))
    }

    @Test
    fun `min and max with int and float`() {
        val c = cfg("a = min(2.5, 2)\nb = max(2.5, 3)\n")
        assertEquals(2.0, c.getFloat("a"))
        assertEquals(3.0, c.getFloat("b"))
    }

    @Test
    fun `min max need at least two args`() {
        assertFailsWith<CrenError.Parse> { cfg("x = min(1)\n").get("x") }
        assertFailsWith<CrenError.Parse> { cfg("x = max(1)\n").get("x") }
    }

    // ── abs ───────────────────────────────────────────────────────────

    @Test
    fun `abs`() {
        val c = cfg("a = abs(-7)\nb = abs(7)\nc = abs(-1.5)\n")
        assertEquals(7L, c.getInt("a"))
        assertEquals(7L, c.getInt("b"))
        assertEquals(1.5, c.getFloat("c"))
    }

    // ── arity и неизвестные ───────────────────────────────────────────

    @Test
    fun `wrong arity is error`() {
        assertFailsWith<CrenError.Parse> { cfg("x = hash(1, 2)\n").get("x") }
        assertFailsWith<CrenError.Parse> { cfg("x = clamp(1, 2)\n").get("x") }
        assertFailsWith<CrenError.Parse> { cfg("x = seq(1)\n").get("x") }
        assertFailsWith<CrenError.Parse> { cfg("x = abs()\n").get("x") }
    }

    @Test
    fun `unknown function is error at resolve`() {
        val e = assertFailsWith<CrenError.Parse> { cfg("x = nope(1)\n").get("x") }
        assertTrue(e.messageText.contains("неизвестная функция"))
    }

    @Test
    fun `function result is revaluated each get`() {
        val direct = cfg("x = hash(\"значение\")\n")
        val v1 = direct.getInt("x")
        val v2 = direct.getInt("x")
        assertEquals(v1, v2)
    }
}