package dev.ggtv.capecraft.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScaleTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `returns same array when already fits`() {
        val px = IntArray(4) { argb(255, it, 0, 0) } // 2x2
        val (out, w, h) = Scale.fitWithin(px, 2, 2, 100)
        assertTrue(out === px) // тот же объект — без копирования
        assertEquals(2, w)
        assertEquals(2, h)
    }

    @Test
    fun `shrinks large image preserving area constraint`() {
        val w = 2000; val h = 2000
        val px = IntArray(w * h) { argb(255, 10, 20, 30) }
        val maxPixels = 1_000_000L
        val (out, nw, nh) = Scale.fitWithin(px, w, h, maxPixels)
        assertTrue(nw.toLong() * nh <= maxPixels)
        assertTrue(nw < w) // реально уменьшилось
        assertEquals(nw * nh, out.size)
    }

    @Test
    fun `area average blends colors`() {
        // 2x2: левые пиксели чёрные (255,0,0,0)? Нет — сделаем: [чёрный, белый]
        // верхняя строка чёрная-белая, нижняя тоже → средний серый 127.
        val px = intArrayOf(
            argb(255, 0, 0, 0), argb(255, 255, 255, 255),
            argb(255, 0, 0, 0), argb(255, 255, 255, 255),
        )
        val out = Scale.scale(px, 2, 2, 1, 1)
        val v = out[0]
        val r = ((v ushr 16) and 0xFF).toDouble()
        val g = ((v ushr 8) and 0xFF).toDouble()
        val b = (v and 0xFF).toDouble()
        assertEquals(127.0, r, 1.0)
        assertEquals(127.0, g, 1.0)
        assertEquals(127.0, b, 1.0)
    }

    @Test
    fun `fully transparent region stays transparent`() {
        val px = IntArray(4) { 0 } // всё прозрачное
        val out = Scale.scale(px, 2, 2, 1, 1)
        assertEquals(0, out[0])
    }

    @Test
    fun `keeps min dimension`() {
        // Очень маленький допустимый максимум — изображение сжимается до предела,
        // но НЕ ниже MIN_DIM по каждой стороне.
        val w = 400; val h = 400
        val px = IntArray(w * h) { argb(255, 1, 2, 3) }
        val maxPixels = 100L
        val (out, nw, nh) = Scale.fitWithin(px, w, h, maxPixels)
        assertTrue(nw >= Limits.MIN_DIM && nh >= Limits.MIN_DIM)
        // Хоть и не влезаем в maxPixels из-за MIN_DIM, размер уменьшился
        assertTrue(nw < w || nh < h)
        assertEquals(nw * nh, out.size)
    }
}
