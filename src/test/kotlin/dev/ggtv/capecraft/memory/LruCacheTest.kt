package dev.ggtv.capecraft.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LruCacheTest {

    // sizeOf = 1 байт на элемент — упрощённый вес для проверки порядка.
    private fun cache(max: Long) = LruCache<Int, String>(max, { _, _ -> 1L })

    @Test
    fun `keeps items under max bytes`() {
        val c = cache(3)
        c.put(1, "a")
        c.put(2, "b")
        c.put(3, "c")
        assertEquals(3, c.count)
        assertEquals(3, c.size)
        assertNull(c.get(4)) // нет такого
    }

    @Test
    fun `evicts oldest when over limit`() {
        val c = cache(2)
        c.put(1, "a")
        c.put(2, "b")
        c.put(3, "c") // вытеснит 1 (самый старый)
        assertEquals(2, c.count)
        assertEquals("b", c.get(2))
        assertEquals("c", c.get(3))
        assertNull(c.get(1))
    }

    @Test
    fun `get makes item recently used`() {
        val c = cache(2)
        c.put(1, "a")
        c.put(2, "b")
        c.get(1)      // 1 теперь недавно использованный
        c.put(3, "c") // вытеснит 2, не 1
        assertNull(c.get(2))
        assertEquals("a", c.get(1))
        assertEquals("c", c.get(3))
    }

    @Test
    fun `replace key adjusts weight`() {
        val c = cache(3)
        c.put(1, "a")
        c.put(2, "b")
        c.put(1, "aa") // замена, вес тот же (1)
        assertEquals(2, c.count)
        assertEquals(2, c.size)
    }

    @Test
    fun `remove decrements weight`() {
        val c = cache(10)
        c.put(1, "a")
        c.put(2, "b")
        c.remove(1)
        assertEquals(1, c.count)
        assertEquals(1L, c.size)
    }

    @Test
    fun `clear empties`() {
        val c = cache(10)
        c.put(1, "a")
        c.clear()
        assertEquals(0, c.count)
        assertEquals(0, c.size)
    }

    @Test
    fun `respects arbitrary sizeOf`() {
        val c = LruCache<String, IntArray>(10, { _, arr -> arr.size.toLong() })
        c.put("a", IntArray(6))
        c.put("b", IntArray(6)) // 6+6=12>10 → вытеснит "a"
        assertEquals("b", c.keys.first())
        assertTrue(c.count == 1)
        assertEquals(6L, c.size)
    }
}
