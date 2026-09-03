package dev.ggtv.capecraft.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Тесты таймлайна [AnimatedImage]: [AnimatedImage.frameAt]/[frameIndexAt]
 * с бинарным поиском по precomputed длительностям. Проверяем границы кадров,
 * поведение одноразовой анимации и статичный путь.
 */
class AnimatedImageTest {

    private fun anim(durations: List<Int>): AnimatedImage {
        // Пиксели кадра = индекс кадра (по одному пикселю достаточно).
        val frames = durations.mapIndexed { i, d -> Frame(intArrayOf(i), d) }
        return AnimatedImage(1, 1, frames, loopCount = 0)
    }

    @Test
    fun `static returns single frame at any time`() {
        val a = AnimatedImage(1, 1, listOf(Frame(intArrayOf(42), 100)), -1)
        assertSame(a.frames[0].pixels, a.frameAt(0))
        assertSame(a.frames[0].pixels, a.frameAt(999999))
        assertEquals(0, a.frameIndexAt(0))
        assertEquals(0, a.frameIndexAt(999999))
    }

    @Test
    fun `frame boundaries map to correct index`() {
        // длительности 100, 100, 100 -> границы на 0, 100, 200.
        val a = anim(listOf(100, 100, 100))
        assertEquals(0, a.frameIndexAt(0))
        assertEquals(1, a.frameIndexAt(100))
        assertEquals(2, a.frameIndexAt(200))
        assertEquals(0, a.frameIndexAt(300)) // цикл
    }

    @Test
    fun `uneven durations map correctly`() {
        // 50, 20, 30 -> сумма 100.
        val a = anim(listOf(50, 20, 30))
        assertEquals(0, a.frameIndexAt(0))
        assertEquals(0, a.frameIndexAt(49))
        assertEquals(1, a.frameIndexAt(50))
        assertEquals(1, a.frameIndexAt(69))
        assertEquals(2, a.frameIndexAt(70))
        assertEquals(2, a.frameIndexAt(99))
        assertEquals(0, a.frameIndexAt(100)) // цикл
    }

    @Test
    fun `frameAt returns matching pixel array`() {
        val a = anim(listOf(50, 50))
        assertSame(a.frames[0].pixels, a.frameAt(0))
        assertSame(a.frames[1].pixels, a.frameAt(50))
        assertSame(a.frames[0].pixels, a.frameAt(100))
    }

    @Test
    fun `one-shot animation holds last frame after end`() {
        // loopCount == 1 — проигрывается один раз, в конце держится последний кадр.
        val oneShot = anim(listOf(100, 100)).let { it.copyOneShot() }
        assertEquals(0, oneShot.frameIndexAt(99))
        assertEquals(1, oneShot.frameIndexAt(100))
        assertEquals(1, oneShot.frameIndexAt(500)) // не цикл, держим последний
    }

    @Test
    fun `totalDuration is precomputed sum`() {
        assertEquals(100L, anim(listOf(40, 60)).totalDurationMs)
        assertEquals(0L, anim(emptyList()).totalDurationMs)
    }

    /** Вернуть анимацию той же длительности, но одноразовую (loopCount=1). */
    private fun AnimatedImage.copyOneShot(): AnimatedImage =
        AnimatedImage(width, height, frames, loopCount = 1)
}