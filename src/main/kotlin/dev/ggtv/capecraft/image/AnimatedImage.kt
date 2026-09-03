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
     * Кумулятивные длительности: cumulativeEnds[i] = сумма durations[0..i].
     *
     * Precompute один раз при создании — тогда [frameAt]/[frameIndexAt] делают
     * бинарный поиск за O(log N) вместо пересчёта суммы и линейного прохода
     * по всем кадрам каждый вызов. Важно: [frameAt]/[frameIndexAt] вызываются
     * из игрового тика (~10-30 раз/сек) для каждого анимированного плаща,
     * поэтому это было заметной просадкой для анимаций с многими кадрами.
     */
    private val cumulativeEnds: IntArray = IntArray(frames.size)

    private val _totalDurationMs: Long

    init {
        var sum = 0
        for (i in frames.indices) {
            sum += frames[i].durationMs.coerceAtLeast(1)
            cumulativeEnds[i] = sum
        }
        _totalDurationMs = sum.toLong()
    }

    /**
     * Кадр на момент времени [timeMs] (миллисекунды от начала анимации).
     *
     * Быстрый путь O(1) для статичных изображений; для анимации —
     * бинарный поиск по precomputed длительностям O(log N). Учитывает
     * [loopCount]: одноразовая анимация после конца показывает последний кадр.
     */
    fun frameAt(timeMs: Long): IntArray {
        if (frames.size <= 1) return frames[0].pixels
        val total = _totalDurationMs
        if (total <= 0) return frames[0].pixels

        var t = if (loopCount == 1 && timeMs >= total) {
            total - 1 // последний кадр держится до конца
        } else {
            timeMs % total
        }
        var lo = 0
        var hi = frames.size - 1
        // Библиотечный binarySearch почти тем же: ищем первый кадр, где t < end.
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t < cumulativeEnds[mid]) hi = mid else lo = mid + 1
        }
        return frames[lo].pixels
    }

    /**
     * Индекс кадра (в [frames]) на момент времени [timeMs].
     * Аналог [frameAt], но возвращает индекс, чтобы вызывающий мог
     * проверить, сменился ли кадр, и пропустить лишний upload в текстуру.
     */
    fun frameIndexAt(timeMs: Long): Int {
        if (frames.size <= 1) return 0
        val total = _totalDurationMs
        if (total <= 0) return 0
        var t = if (loopCount == 1 && timeMs >= total) {
            total - 1
        } else {
            timeMs % total
        }
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t < cumulativeEnds[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Суммарная длительность всех кадров, мс (precomputed). */
    val totalDurationMs: Long get() = _totalDurationMs

    /** Суммарная площадь всех кадров в пикселях (для учёта памяти). */
    val totalPixels: Long get() = width.toLong() * height * frames.size
}