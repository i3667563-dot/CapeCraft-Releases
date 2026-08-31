package dev.ggtv.capecraft.memory

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryManagerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    // Анимация из frames кадров, каждый кадр 2x2 = 4 пикселя.
    private fun anim(frames: Int, w: Int = 2, h: Int = 2): AnimatedImage {
        val fr = List(frames) { i -> Frame(IntArray(w * h) { argb(255, i, 0, 0) }, 50) }
        return AnimatedImage(w, h, fr)
    }

    @Test
    fun `in-limit image stored unchanged`() {
        val mm = MemoryManager()
        val a = anim(1) // 4 пикселя — влезает
        val r = mm.store("Steve", a)
        assertTrue(r === a) // без копирования
        assertEquals(1, mm.capesCount)
        assertEquals(16L, mm.totalBytes) // 4 px * 4 байта
    }

    @Test
    fun `get returns stored image`() {
        val mm = MemoryManager()
        val a = anim(1)
        mm.store("Steve", a)
        val r = mm.get("Steve")
        assertTrue(r === a)
        assertNull(mm.get("nobody"))
    }

    @Test
    fun `remove deletes cape`() {
        val mm = MemoryManager()
        mm.store("Steve", anim(1))
        assertTrue(mm.remove("Steve"))
        assertEquals(0, mm.capesCount)
        assertTrue(!mm.remove("Steve")) // уже нет
    }

    @Test
    fun `evicts least recently used cape when over total limit`() {
        // Лимит суммы мал: пускай вмещает ~1 капку среднего размера.
        val tight = Limits(maxBytesTotal = 40L) // 10 пикселей по 4 байта
        val mm = MemoryManager(tight)
        mm.store("a", anim(1))   // 4 px = 16 байт
        mm.store("b", anim(1))   // 16 байт, сумма 32
        mm.store("c", anim(1))   // 16 байт → сумма 48 > 40 → вытеснит "a"
        assertNull(mm.get("a"))
        assertTrue(mm.get("b") != null)
        assertTrue(mm.get("c") != null)
    }

    @Test
    fun `large cape is area-averaged down`() {
        // Лимит пикселей на кадр мал — капка должна уменьшиться.
        val tight = Limits(maxPixelsPerFrame = 100L, maxBytesPerCape = 100_000)
        val mm = MemoryManager(tight)
        val a = anim(1, w = 50, h = 50) // 2500 px > 100
        val r = mm.degrade(a)
        assertTrue(r.width.toLong() * r.height <= tight.maxPixelsPerFrame + 1)
        assertTrue(r.width < 50)
    }

    @Test
    fun `too many frames are thinned`() {
        // Много кадров — должны быть прорежены до maxFrames.
        val tight = Limits(maxFrames = 4, maxBytesPerCape = 10_000)
        val mm = MemoryManager(tight)
        val a = anim(20) // 20 кадров, маленькие
        val r = mm.degrade(a)
        assertTrue(r.frameCount <= 4)
    }

    @Test
    fun `always returns a renderable image`() {
        // Жёсткие лимиты не должны ломать: всегда есть хотя бы один кадр.
        val tight = Limits(maxPixelsPerFrame = 1, maxBytesPerCape = 1, maxFrames = 1)
        val mm = MemoryManager(tight)
        val a = anim(10, w = 100, h = 100)
        val r = mm.degrade(a)
        assertTrue(r.frameCount >= 1)
        assertTrue(r.width >= Limits.MIN_DIM && r.height >= Limits.MIN_DIM)
    }
}
