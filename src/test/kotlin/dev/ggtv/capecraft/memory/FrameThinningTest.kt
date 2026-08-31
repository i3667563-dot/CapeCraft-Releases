package dev.ggtv.capecraft.memory

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameThinningTest {

    private fun makeAnim(frames: Int, durMs: Int = 50): AnimatedImage {
        val fr = List(frames) { i -> Frame(IntArray(4) { i }, durMs) } // 2x2
        return AnimatedImage(2, 2, fr)
    }

    @Test
    fun `keeps anim when under max`() {
        val a = makeAnim(3)
        val r = FrameThinning.thin(a, 10)
        assertTrue(r === a) // тот же объект
    }

    @Test
    fun `reduces frame count`() {
        val a = makeAnim(10)
        val r = FrameThinning.thin(a, 4)
        assertEquals(4, r.frameCount)
    }

    @Test
    fun `preserves first and last frame`() {
        // Пиксель каждого кадра = его индекс, так мы видим, какие кадры остались.
        val a = makeAnim(10)
        val r = FrameThinning.thin(a, 3)
        assertEquals(3, r.frameCount)
        // Первый кадр сохранён (индекс 0).
        assertEquals(0, r.frames.first().pixels[0])
        // Последний кадр сохранён (индекс 9).
        assertEquals(9, r.frames.last().pixels[0])
    }

    @Test
    fun `preserves total duration`() {
        val a = makeAnim(10, durMs = 100)
        val totalOrig = a.totalDurationMs
        val r = FrameThinning.thin(a, 4)
        // Суммарная длительность не должна сильно измениться (равномерная выборка).
        assertTrue(kotlin.math.abs(r.totalDurationMs - totalOrig) < 200)
    }

    @Test
    fun `single frame uses middle pose`() {
        val a = makeAnim(10, durMs = 10) // суммарно 100 мс
        val r = FrameThinning.thin(a, 1)
        assertEquals(1, r.frameCount)
        // Кадр — из середины (примерно индекс 5).
        val mid = r.frames.first().pixels[0]
        assertTrue(mid in 3..7)
    }

    @Test
    fun `non-animated stays one frame`() {
        val a = makeAnim(1)
        val r = FrameThinning.thin(a, 1)
        assertTrue(r === a)
    }
}
