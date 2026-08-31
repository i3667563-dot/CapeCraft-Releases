package dev.ggtv.capecraft.memory

/**
 * Площадное сжатие (area-average) ARGB-изображений (этап 5.3).
 *
 * Используется, когда плащ слишком велик для отведённой памяти: вместо
 * грубого прореживания пикселей берём среднее по всем исходным пикселям,
 * попавшим в один целевой — так мелкие детали капки не теряются и не
 * появляются «дырки»/мерцание.
 *
 * Альфа усредняется как обычный канал, но с весами по непрозрачности,
 * чтобы полупрозрачные края не «темнели» из-за сложения с прозрачностью.
 *
 * Операция чистая: входной [IntArray] ARGB не мутируется, возвращается новый.
 */
object Scale {

    /**
     * Уменьшить изображение так, чтобы его размер не превышал [maxPixels],
     * сохраняя пропорции. Если [width]*[height] уже <= [maxPixels] и размер
     * равен оригиналу — вернуть исходный массив без изменений (дешёвый путь).
     *
     * @return пару (масштабированные пиксели, новая ширина, новая высота)
     */
    fun fitWithin(
        src: IntArray,
        width: Int,
        height: Int,
        maxPixels: Long,
        minDim: Int = Limits.MIN_DIM,
    ): Triple<IntArray, Int, Int> {
        require(src.size == width * height) { "src.size=${src.size} != ${width}x$height" }
        val area = width.toLong() * height
        if (area <= maxPixels) {
            // Не превышает лимит — возврат как есть (никакого копирования пикселей).
            return Triple(src, width, height)
        }

        // Масштаб по площади, с защитой от вырождения до 1 пикселя.
        val factor = kotlin.math.sqrt(area.toDouble() / maxPixels)
        var nw = (width / factor).toInt().coerceAtLeast(minDim)
        var nh = (height / factor).toInt().coerceAtLeast(minDim)
        // Страхуемся: из-за coerceAtLeast площадь может снова превысить maxPixels
        // при сильно вытянутых картинках — тогда ужмим по длинной стороне.
        while (nw.toLong() * nh > maxPixels && (nw > minDim || nh > minDim)) {
            if (nw >= nh) nw = (nw * 9 / 10).coerceAtLeast(minDim)
            else nh = (nh * 9 / 10).coerceAtLeast(minDim)
        }

        return Triple(scale(src, width, height, nw, nh), nw, nh)
    }

    /**
     * Общий area-average сжатие [src] размером width×height до newW×newH.
     * Возвращает новый массив пикселей newW*newH.
     */
    fun scale(src: IntArray, width: Int, height: Int, newW: Int, newH: Int): IntArray {
        require(src.size == width * height) { "src.size=${src.size} != ${width}x$height" }
        require(newW > 0 && newH > 0) { "целевой размер должен быть > 0: ${newW}x$newH" }

        val out = IntArray(newW * newH)
        // Коэффициенты: сколько исходных пикселей на одну строку/колонку цели.
        val scaleX = width.toDouble() / newW
        val scaleY = height.toDouble() / newH

        // Для каждого целевого ряда y аккумулируем область [y0, y1) исходных строк.
        var srcY = 0
        for (dy in 0 until newH) {
            val y0 = srcY
            val y1 = (((dy + 1) * scaleY)).toInt().coerceAtMost(height)
            if (y1 <= y0) continue // вырожденный ряд (крайне редко при целых)
            srcY = y1

            var dOut = dy * newW
            var srcX = 0
            for (dx in 0 until newW) {
                val x0 = srcX
                val x1 = (((dx + 1) * scaleX)).toInt().coerceAtMost(width)
                if (x1 <= x0) {
                    out[dOut] = 0
                    dOut++
                    continue
                }
                srcX = x1

                var aSum = 0L; var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
                for (y in y0 until y1) {
                    var p = y * width + x0
                    val end = y * width + x1
                    while (p < end) {
                        val px = src[p]
                        val a = (px ushr 24) and 0xFF
                        // Альфа-взвешивание: вклад пикселя умножается на его альфу,
                        // чтобы прозрачные пиксели не «разбавляли» цвет непрозрачных.
                        aSum += a
                        rSum += ((px ushr 16) and 0xFF) * a
                        gSum += ((px ushr 8) and 0xFF) * a
                        bSum += (px and 0xFF) * a
                        count++
                        p++
                    }
                }
                if (count == 0) {
                    out[dOut] = 0
                } else {
                    val aAvg = (aSum / count).toInt()
                    out[dOut] = if (aAvg == 0) {
                        0
                    } else {
                        val r = ((rSum / aSum).coerceIn(0, 255)).toInt()
                        val g = ((gSum / aSum).coerceIn(0, 255)).toInt()
                        val b = ((bSum / aSum).coerceIn(0, 255)).toInt()
                        (aAvg shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                dOut++
            }
        }
        return out
    }
}
