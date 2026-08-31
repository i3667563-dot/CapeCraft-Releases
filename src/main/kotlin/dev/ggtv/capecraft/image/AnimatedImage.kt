package dev.ggtv.capecraft.image

/**
 * Один кадр анимации: пиксели ARGB (0xAARRGGBB) + длительность.
 *
 * Пиксели хранятся в плоском [IntArray] размера width*height холста.
 * Все кадры анимации — одного размера (холст), даже если исходный
 * кадр был меньше (прямоугольные кадры APNG/GIF вписаны в холст).
 */
class Frame(
    val pixels: IntArray,
    /** Длительность кадра в миллисекундах (>= 1). */
    val durationMs: Int,
) {
    val size: Int get() = pixels.size
}

/**
 * Полностью декодированное изображение: статичный PNG или анимация.
 *
 * Для статичных изображений [frames] содержит ровно один кадр.
 * Анимация определяется по размеру кадра (>1) — проверять формат
 * исходника не нужно.
 */
class AnimatedImage(
    val width: Int,
    val height: Int,
    val frames: List<Frame>,
    /**
     * Сколько раз проигрывается анимация:
     * 0 — бесконечно, 1 — один раз, -1 — не анимация.
     */
    val loopCount: Int = if (frames.size > 1) 0 else -1,
) {
    val isAnimated: Boolean get() = frames.size > 1
    val frameCount: Int get() = frames.size

    /** Пиксели статичного изображения (быстрый путь для обычных плащей). */
    val singlePixels: IntArray?
        get() = if (frames.size == 1) frames[0].pixels else null

    /**
     * Кадр на момент времени [timeMs] (миллисекунды от начала анимации).
     *
     * Быстрый путь O(1) для статичных изображений; для анимации —
     * один проход по кадрам (их обычно немного). Учитывает [loopCount]:
     * одноразовая анимация после конца показывает последний кадр.
     */
    fun frameAt(timeMs: Long): IntArray {
        if (frames.size <= 1) return frames[0].pixels

        val total = frames.sumOf { it.durationMs }
        if (total <= 0) return frames[0].pixels

        var t = if (loopCount == 1 && timeMs >= total) {
            total.toLong() - 1 // последний кадр держится до конца
        } else {
            timeMs % total
        }
        for (f in frames) {
            if (t < f.durationMs) return f.pixels
            t -= f.durationMs
        }
        return frames.last().pixels
    }

    /** Суммарная длительность всех кадров, мс. */
    val totalDurationMs: Long get() = frames.sumOf { it.durationMs.toLong() }

    /** Суммарная площадь всех кадров в пикселях (для учёта памяти). */
    val totalPixels: Long get() = width.toLong() * height * frames.size
}