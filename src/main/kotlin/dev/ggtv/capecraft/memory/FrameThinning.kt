package dev.ggtv.capecraft.memory

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.Frame

/**
 * Прореживание кадров анимации (этап 5.4).
 *
 * Если даже после area-average сжатия анимация не влезает в [Limits]
 * (много кадров большого размера), держим не все кадры, а равномерную
 * выборку, масштабируя длительности оставшихся так, чтобы суммарная
 * длительность (и скорость анимации) не изменились.
 *
 * Выборка равномерная: оставляем каждый k-й кадр. Критично, чтобы первый
 * и последний кадр присутствовали (иначе «прыжки» в цикле).
 */
object FrameThinning {

    /**
     * Оставить максимум [maxFrames] кадров, равномерно распределив их по
     * времени. Возвращает новую [AnimatedImage] с теми же размерами.
     * Если кадров уже <= [maxFrames] — вернуть ту же [AnimatedImage].
     */
    fun thin(anim: AnimatedImage, maxFrames: Int): AnimatedImage {
        if (anim.frameCount <= maxFrames || maxFrames < 1) return anim
        if (maxFrames == 1) {
            // Один кадр — берём середину анимации как репрезентативную позу.
            val mid = anim.frameAt(anim.totalDurationMs / 2)
            return AnimatedImage(anim.width, anim.height, listOf(Frame(mid, 100)), 1)
        }

        val src = anim.frames
        val n = src.size
        // Выбираем индексы 0, ..., n-1 так, чтобы получилось ~maxFrames штук.
        // Равномерная сетка: keep j* (n-1)/(maxFrames-1), с гарантией первого и последнего.
        val keptIdx = IntArray(maxFrames)
        val step = (n - 1).toDouble() / (maxFrames - 1)
        for (k in 0 until maxFrames) keptIdx[k] = (k * step).toInt()

        // Длительность выбранного кадра — сумма длительностей исходных кадров,
        // приписанных к нему (каждый исходный кадр идёт к ближайшему выбранному).
        // Это сохраняет суммарную длительность анимации точно.
        val durations = IntArray(maxFrames)
        for (i in 0 until n) {
            // Ближайший выбранный индекс.
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (k in 0 until maxFrames) {
                val d = kotlin.math.abs(i - keptIdx[k])
                if (d < bestDist) { bestDist = d; best = k }
            }
            durations[best] += src[i].durationMs
        }
        for (k in 0 until maxFrames) if (durations[k] <= 0) durations[k] = 1

        val newFrames = ArrayList<Frame>(maxFrames)
        for (k in 0 until maxFrames) newFrames += Frame(src[keptIdx[k]].pixels, durations[k])
        return AnimatedImage(anim.width, anim.height, newFrames, anim.loopCount)
    }
}
