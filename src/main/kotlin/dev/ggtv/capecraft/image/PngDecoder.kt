package dev.ggtv.capecraft.image

import java.util.zip.Inflater

/**
 * Декодер PNG (включая APNG) на чистом Kotlin.
 *
 * Скорость:
 * - inflate отдаётся нативному zlib через [Inflater] — единственный тяжёлый
 *   шаг выполняется нативно, без аллокаций на пиксель;
 * - фильтры строк — проходы по [ByteArray] без объектов, выход пишется
 *   в отдельный буфер (предыдущая строка не портится — Paeth корректен);
 * - пиксели раскладываются сразу в [IntArray] ARGB (0xAARRGGBB);
 * - CRC не проверяется (скорость; повреждённые файлы отсекает inflate).
 *
 * Поддержка: bit depth 1/2/4/8/16, color types 0/2/3/4/6, Adam7,
 * APNG (acTL/fcTL/fdAT, dispose/blank, blend source/over, loop).
 */
object PngDecoder {

    private const val TYPE_IHDR = 0x49484452 // "IHDR"
    private const val TYPE_PLTE = 0x504C5445 // "PLTE"
    private const val TYPE_TRNS = 0x74524E53 // "tRNS"
    private const val TYPE_IDAT = 0x49444154 // "IDAT"
    private const val TYPE_ACTL = 0x6163544C // "acTL"
    private const val TYPE_FCTL = 0x6663544C // "fcTL"
    private const val TYPE_FDAT = 0x66644154 // "fdAT"
    private const val TYPE_IEND = 0x49454E44 // "IEND"

    /** Управляющие данные кадра APNG (fcTL). */
    private class FrameControl(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val delayNum: Int, val delayDen: Int,
        val disposeOp: Int, val blendOp: Int,
    ) {
        fun delayMs(): Int {
            var ms = if (delayDen == 0) 100 else delayNum * 1000 / delayDen
            if (ms <= 0) ms = 100
            return ms
        }
    }

    /** Оттенок серого в ARGB (непрозрачный). */
    private inline fun gray(v: Int): Int = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v

    fun decode(data: ByteArray, source: String? = null): AnimatedImage {
        if (data.size < 8) throw ImageDecodeException("файл слишком короткий", source)
        if (data[0] != 0x89.toByte() || data[1] != 0x50.toByte() || data[2] != 0x4E.toByte() || data[3] != 0x47.toByte() ||
            data[4] != 0x0D.toByte() || data[5] != 0x0A.toByte() || data[6] != 0x1A.toByte() || data[7] != 0x0A.toByte()
        ) {
            throw ImageDecodeException("не PNG (сигнатура не совпадает)", source)
        }

        // ---- Чанки ----
        var ihdr: ByteArray? = null
        var plte: ByteArray? = null
        var trns: ByteArray? = null
        var apng = false
        var numPlays = 0
        var width = 0
        var height = 0
        val controls = ArrayList<FrameControl>()
        val idatParts = ArrayList<ByteArray>()
        val fdatParts = ArrayList<ByteArray>()

        var pos = 8
        while (pos + 8 <= data.size) {
            val len = beInt(data, pos)
            val type = beInt(data, pos + 4)
            if (pos + 8 + len > data.size) {
                throw ImageDecodeException("чанк обрезан (len=$len)", source)
            }
            if (len > MAX_CHUNK) {
                throw ImageDecodeException("чанк слишком большой ($len байт)", source)
            }
            val chunk = data.copyOfRange(pos + 8, pos + 8 + len)
            when (type) {
                TYPE_IHDR -> {
                    ihdr = chunk
                    width = beInt(chunk, 0)
                    height = beInt(chunk, 4)
                    if (width <= 0 || height <= 0) {
                        throw ImageDecodeException("неверные размеры $width x $height", source)
                    }
                    if (width.toLong() * height > MAX_PIXELS) {
                        throw ImageDecodeException(
                            "изображение $width x $height слишком большое (больше $MAX_PIXELS пикселей)", source,
                        )
                    }
                }
                TYPE_PLTE -> plte = chunk
                TYPE_TRNS -> trns = chunk
                TYPE_IDAT -> idatParts += chunk
                TYPE_ACTL -> {
                    if (chunk.size < 8) throw ImageDecodeException("acTL слишком короткий", source)
                    apng = true
                    // num_frames = beInt(chunk, 0); num_plays = beInt(chunk, 4)
                    numPlays = beInt(chunk, 4)
                }
                TYPE_FCTL -> {
                    if (chunk.size < 26) throw ImageDecodeException("fcTL слишком короткий", source)
                    controls += FrameControl(
                        x = beInt(chunk, 0), y = beInt(chunk, 4),
                        w = beInt(chunk, 8), h = beInt(chunk, 12),
                        delayNum = beInt(chunk, 16), delayDen = beInt(chunk, 20),
                        disposeOp = chunk[24].toInt() and 0xFF,
                        blendOp = chunk[25].toInt() and 0xFF,
                    )
                }
                TYPE_FDAT -> {
                    // 4 байта seqno + данные (продолжение того же deflate-потока).
                    if (chunk.size <= 4) throw ImageDecodeException("fdAT слишком короткий", source)
                    fdatParts += chunk.copyOfRange(4, chunk.size)
                }
            }
            pos += 12 + len
        }

        val hdr = ihdr ?: throw ImageDecodeException("нет IHDR", source)
        if (idatParts.isEmpty()) throw ImageDecodeException("нет IDAT — изображение без данных", source)

        val bitDepth = hdr[8].toInt() and 0xFF
        val colorType = hdr[9].toInt() and 0xFF
        val interlace = hdr[12].toInt() and 0xFF
        validateDepth(colorType, bitDepth, source)
        if (interlace !in 0..1) throw ImageDecodeException("неверный метод чересстрочности $interlace", source)

        val channels = when (colorType) {
            0, 3 -> 1
            2 -> 3
            4 -> 2
            else -> 4
        }
        val bytesPerSample = if (bitDepth == 16) 2 else 1
        val bpp = if (bitDepth < 8) 1 else channels * bytesPerSample

        val palette = buildPalette(colorType, plte, trns, source)

        // Полный deflate-поток: IDAT + все fdAT (данные продолжают друг друга).
        val stream = ByteArray(idatParts.sumOf { it.size } + fdatParts.sumOf { it.size })
        var off = 0
        for (p in idatParts) { p.copyInto(stream, off); off += p.size }
        for (p in fdatParts) { p.copyInto(stream, off); off += p.size }

        val raw = inflate(stream, source)
        if (raw.isEmpty()) throw ImageDecodeException("пустые данные изображения", source)

        // Параметры сканирования.
        val raster = Raster(raw, palette, bitDepth, colorType, bpp, width, height, source)

        if (!apng) {
            val pixels = if (interlace == 0) raster.decodeNonInterlaced(0)
            else raster.decodeAdam7(0)
            return AnimatedImage(width, height, listOf(Frame(pixels, 100)))
        }

        // ---- APNG: композиция кадров ----
        val canvas = IntArray(width * height)
        val frames = ArrayList<Frame>(controls.size.coerceAtLeast(1))

        // Кадр 0 = основное изображение (IDAT), прямоугольник — fcTL[0] или весь холст.
        val f0 = controls.getOrNull(0)
        val f0w = f0?.w ?: width
        val f0h = f0?.h ?: height
        val f0x = f0?.x ?: 0
        val f0y = f0?.y ?: 0

        if (f0Blend(f0) == 0) {
            // SOURCE
            val main = if (interlace == 0) raster.decodeNonInterlaced(0) else raster.decodeAdam7(0)
            for (y in 0 until f0h) {
                val dst = (f0y + y) * width + f0x
                val src = y * width
                main.copyInto(canvas, dst, src, src + f0w)
            }
        } else {
            // OVER (на пустом холсте — как замена, но с учётом прозрачности)
            val main = if (interlace == 0) raster.decodeNonInterlaced(0) else raster.decodeAdam7(0)
            for (y in 0 until f0h) {
                val dst = (f0y + y) * width + f0x
                val src = y * width
                for (x in 0 until f0w) canvas[dst + x] = blendOver(canvas[dst + x], main[src + x])
            }
        }
        frames += Frame(canvas.copyOf(), f0?.delayMs() ?: 100)

        var pendingDispose = f0?.disposeOp ?: 0
        var pendingRect = Rect(f0x, f0y, f0w, f0h)
        var restoreState = canvas.copyOf()

        var rawPos = raster.endPos
        for (i in 1 until controls.size) {
            val fc = controls[i]
            if (fc.w <= 0 || fc.h <= 0) {
                throw ImageDecodeException("кадр $i: неверный прямоугольник ${fc.w}x${fc.h}", source)
            }
            if (fc.x < 0 || fc.y < 0 || fc.x + fc.w > width || fc.y + fc.h > height) {
                throw ImageDecodeException("кадр $i: прямоугольник выходит за холст", source)
            }

            // Dispose предыдущего кадра.
            when (pendingDispose) {
                1 -> fill(canvas, width, pendingRect, 0) // BACKGROUND
                2 -> restoreState.copyInto(canvas) // PREVIOUS
            }
            restoreState = canvas.copyOf()

            // Данные кадра в продолжении потока.
            val region = Raster(raw, palette, bitDepth, colorType, bpp, fc.w, fc.h, source)
            val px = if (fc.blendOp == 0) {
                // SOURCE — но регион надо декодировать как есть
                region.decodeNonInterlaced(rawPos)
            } else {
                region.decodeNonInterlaced(rawPos)
            }
            rawPos = region.endPos

            if (fc.blendOp == 0) {
                for (y in 0 until fc.h) {
                    val dst = (fc.y + y) * width + fc.x
                    val src = y * fc.w
                    px.copyInto(canvas, dst, src, src + fc.w)
                }
            } else {
                for (y in 0 until fc.h) {
                    val dst = (fc.y + y) * width + fc.x
                    val src = y * fc.w
                    for (x in 0 until fc.w) canvas[dst + x] = blendOver(canvas[dst + x], px[src + x])
                }
            }

            frames += Frame(canvas.copyOf(), fc.delayMs())
            pendingDispose = fc.disposeOp
            pendingRect = Rect(fc.x, fc.y, fc.w, fc.h)
        }

        // Одноразовая анимация, если нет loop-маркера (numPlays 0 = бесконечно,
        // 1 — один раз, отсутствие acTL-игры — не анимация).
        val loop = when {
            numPlays == 0 -> 0 // бесконечно
            controls.size > 1 -> numPlays
            else -> 1
        }

        return AnimatedImage(width, height, frames, loop)
    }

    private fun f0Blend(f0: FrameControl?): Int = f0?.blendOp ?: 0

    private class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun fill(canvas: IntArray, width: Int, r: Rect, value: Int) {
        for (y in r.y until r.y + r.h) {
            val off = y * width + r.x
            canvas.fill(value, off, off + r.w)
        }
    }

    // ---- Распаковка растров ----

    /** Растр: распаковка фильтрованных строк в ARGB. */
    private class Raster(
        private val raw: ByteArray,
        private val palette: Palette,
        private val bitDepth: Int,
        private val colorType: Int,
        private val bpp: Int,
        val width: Int,
        val height: Int,
        private val source: String?,
    ) {
        var endPos = 0

        fun rowLen(): Int = when {
            bitDepth < 8 -> (width * bitDepth + 7) / 8
            else -> width * bpp
        }

        /** Обычный растр (без интерлейса), с [startPos]. */
        fun decodeNonInterlaced(startPos: Int): IntArray {
            val out = IntArray(width * height)
            val rl = rowLen()
            if (startPos + (rl + 1) * height > raw.size) {
                throw ImageDecodeException("данные изображения обрезаны", source)
            }
            var prev = ByteArray(rl)
            var cur = ByteArray(rl)
            var p = startPos
            for (y in 0 until height) {
                val filter = raw[p].toInt() and 0xFF
                p++
                if (filter > 4) throw ImageDecodeException("неверный фильтр строки $filter", source)
                unfilter(raw, p, prev, cur, rl, filter, bpp)
                unpackRow(cur, out, y * width, width)
                // prev = только что восстановленная строка (для фильтров Up/Average/Paeth).
                val t = prev; prev = cur; cur = t
                p += rl
            }
            endPos = p
            return out
        }

        /** Adam7: семь проходов, раскладка по холсту. */
        fun decodeAdam7(startPos: Int): IntArray {
            val out = IntArray(width * height)
            // (startX, startY, stepX, stepY) для проходов 0..6
            val passX = intArrayOf(0, 4, 0, 2, 0, 1, 0)
            val passY = intArrayOf(0, 0, 4, 0, 2, 0, 1)
            val stepX = intArrayOf(8, 8, 4, 4, 2, 2, 1)
            val stepY = intArrayOf(8, 8, 8, 4, 4, 2, 2)

            var p = startPos
            for (pass in 0 until 7) {
                val pw = ((width - passX[pass] + stepX[pass] - 1) / stepX[pass]).coerceAtLeast(0)
                val ph = ((height - passY[pass] + stepY[pass] - 1) / stepY[pass]).coerceAtLeast(0)
                if (pw == 0 || ph == 0) continue
                val rl = when {
                    bitDepth < 8 -> (pw * bitDepth + 7) / 8
                    else -> pw * bpp
                }
                if (p + (rl + 1) * ph > raw.size) {
                    throw ImageDecodeException("данные Adam7 обрезаны (проход $pass)", source)
                }
                var prev = ByteArray(rl)
                var cur = ByteArray(rl)
                for (iy in 0 until ph) {
                    val filter = raw[p].toInt() and 0xFF
                    p++
                    if (filter > 4) throw ImageDecodeException("неверный фильтр строки $filter", source)
                    unfilter(raw, p, prev, cur, rl, filter, bpp)
                    val ay = passY[pass] + iy * stepY[pass]
                    // Разложить unpacked пасс-строку в холст.
                    val row = IntArray(pw)
                    unpackRow(cur, row, 0, pw)
                    var d = ay * width + passX[pass]
                    for (x in 0 until pw) {
                        out[d] = row[x]
                        d += stepX[pass]
                    }
                    // prev = только что восстановленная строка.
                    val t = prev; prev = cur; cur = t
                    p += rl
                }
            }
            endPos = p
            return out
        }

        /** Разложить одну отфильтрованную строку в ARGB. [pixelCount] — сколько пикселей распаковать. */
        private fun unpackRow(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            when (colorType) {
                0 -> unpackGray(src, dst, dstOff, pixelCount)
                2 -> unpackRgb(src, dst, dstOff, pixelCount)
                3 -> unpackIndexed(src, dst, dstOff, pixelCount)
                4 -> unpackGrayAlpha(src, dst, dstOff, pixelCount)
                else -> unpackRgba(src, dst, dstOff, pixelCount)
            }
        }

        private fun unpackGray(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            if (bitDepth == 8) {
                var i = 0
                var d = dstOff
                val n = dstOff + pixelCount
                while (d < n) {
                    val v = src[i].toInt() and 0xFF
                    dst[d] = palette.grayWithAlpha(v)
                    i++; d++
                }
            } else if (bitDepth == 16) {
                var i = 0
                var d = dstOff
                val n = dstOff + pixelCount
                while (d < n) {
                    val v = src[i].toInt() and 0xFF
                    dst[d] = palette.grayWithAlpha(v)
                    i += 2; d++
                }
            } else {
                val mask = (1 shl bitDepth) - 1
                val max = mask
                val perByte = 8 / bitDepth
                var i = 0
                var d = dstOff
                val n = dstOff + pixelCount
                while (d < n) {
                    val byte = src[i].toInt() and 0xFF
                    for (k in 0 until perByte) {
                        if (d >= n) break
                        val v = (byte shr (8 - bitDepth * (k + 1))) and mask
                        // sub-8-bit grayscale масштабируется до полной шкалы.
                        val scaled = v * 255 / max
                        dst[d] = palette.grayWithAlpha(scaled)
                        d++
                    }
                    i++
                }
            }
        }

        private fun unpackRgb(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            val step = if (bitDepth == 16) 6 else 3
            var i = 0
            var d = dstOff
            val n = dstOff + pixelCount
            while (d < n) {
                val r = src[i].toInt() and 0xFF
                val g = src[i + (if (bitDepth == 16) 2 else 1)].toInt() and 0xFF
                val b = src[i + (if (bitDepth == 16) 4 else 2)].toInt() and 0xFF
                dst[d] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                i += step; d++
            }
        }

        private fun unpackIndexed(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            if (bitDepth == 8) {
                var i = 0
                var d = dstOff
                val n = dstOff + pixelCount
                while (d < n) {
                    dst[d] = palette.indexed(src[i].toInt() and 0xFF)
                    i++; d++
                }
            } else {
                val mask = (1 shl bitDepth) - 1
                val perByte = 8 / bitDepth
                var i = 0
                var d = dstOff
                val n = dstOff + pixelCount
                while (d < n) {
                    val byte = src[i].toInt() and 0xFF
                    for (k in 0 until perByte) {
                        if (d >= n) break
                        val v = (byte shr (8 - bitDepth * (k + 1))) and mask
                        dst[d] = palette.indexed(v)
                        d++
                    }
                    i++
                }
            }
        }

        private fun unpackGrayAlpha(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            val step = if (bitDepth == 16) 4 else 2
            var i = 0
            var d = dstOff
            val n = dstOff + pixelCount
            while (d < n) {
                val g = src[i].toInt() and 0xFF
                val a = src[i + (if (bitDepth == 16) 2 else 1)].toInt() and 0xFF
                dst[d] = (a shl 24) or (g shl 16) or (g shl 8) or g
                i += step; d++
            }
        }

        private fun unpackRgba(src: ByteArray, dst: IntArray, dstOff: Int, pixelCount: Int) {
            val step = if (bitDepth == 16) 8 else 4
            var i = 0
            var d = dstOff
            val n = dstOff + pixelCount
            while (d < n) {
                val r = src[i].toInt() and 0xFF
                val g = src[i + (if (bitDepth == 16) 2 else 1)].toInt() and 0xFF
                val b = src[i + (if (bitDepth == 16) 4 else 2)].toInt() and 0xFF
                val a = src[i + (if (bitDepth == 16) 6 else 3)].toInt() and 0xFF
                dst[d] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i += step; d++
            }
        }
    }

    // ---- фильтры строк ----
    // prev — предыдущая (уже обработанная) строка; cur — текущая строка.
    // cur пишется отдельно, prev не мутируется до конца строки — Paeth корректен.

    private fun unfilter(
        raw: ByteArray, srcOff: Int, prev: ByteArray, cur: ByteArray,
        rowLen: Int, filter: Int, bpp: Int,
    ) {
        when (filter) {
            0 -> raw.copyInto(cur, 0, srcOff, srcOff + rowLen)
            1 -> { // Sub
                for (i in 0 until bpp) cur[i] = raw[srcOff + i]
                for (i in bpp until rowLen) {
                    cur[i] = (raw[srcOff + i].toInt() + (cur[i - bpp].toInt() and 0xFF)).toByte()
                }
            }
            2 -> { // Up
                for (i in 0 until rowLen) {
                    cur[i] = (raw[srcOff + i].toInt() + (prev[i].toInt() and 0xFF)).toByte()
                }
            }
            3 -> { // Average
                for (i in 0 until bpp) {
                    cur[i] = (raw[srcOff + i].toInt() + ((prev[i].toInt() and 0xFF) ushr 1)).toByte()
                }
                for (i in bpp until rowLen) {
                    val a = cur[i - bpp].toInt() and 0xFF
                    val b = prev[i].toInt() and 0xFF
                    cur[i] = (raw[srcOff + i].toInt() + ((a + b) ushr 1)).toByte()
                }
            }
            else -> { // Paeth
                for (i in 0 until bpp) {
                    cur[i] = (raw[srcOff + i].toInt() + paeth(0, prev[i].toInt() and 0xFF, 0)).toByte()
                }
                for (i in bpp until rowLen) {
                    val a = cur[i - bpp].toInt() and 0xFF // left (новая строка)
                    val b = prev[i].toInt() and 0xFF      // up
                    val c = prev[i - bpp].toInt() and 0xFF // upLeft (старая строка)
                    cur[i] = (raw[srcOff + i].toInt() + paeth(a, b, c)).toByte()
                }
            }
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /** Альфа-наложение (blend OVER) в 8-битных каналах. */
    private fun blendOver(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0xFF) return src
        if (sa == 0) return dst
        val da = (dst ushr 24) and 0xFF
        val outA = sa + (da * (255 - sa) + 127) / 255
        if (outA == 0) return 0
        fun ch(s: Int, d: Int): Int {
            val v = (s * sa * 255 + d * da * (255 - sa)) / (outA * 255)
            return v.coerceIn(0, 255)
        }
        return (outA shl 24) or
            (ch((src ushr 16) and 0xFF, (dst ushr 16) and 0xFF) shl 16) or
            (ch((src ushr 8) and 0xFF, (dst ushr 8) and 0xFF) shl 8) or
            ch(src and 0xFF, dst and 0xFF)
    }

    private class Palette(val rgb: IntArray, private val alpha: ByteArray?) {

        fun indexed(i: Int): Int {
            if (i < 0 || i >= rgb.size) return 0 // вне палитры — прозрачный
            val a = alpha?.getOrNull(i)?.toInt()?.and(0xFF) ?: 0xFF
            return (a shl 24) or (rgb[i] and 0x00FFFFFF)
        }

        fun grayWithAlpha(v: Int): Int {
            // tRNS для grayscale задаёт прозрачный оттенок.
            val a = alpha?.getOrNull(v)?.toInt()?.and(0xFF) ?: 0xFF
            return (a shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun buildPalette(
        colorType: Int, plte: ByteArray?, trns: ByteArray?, source: String?,
    ): Palette {
        if (colorType == 3) {
            val p = plte ?: throw ImageDecodeException("indexed-изображение без PLTE", source)
            if (p.size % 3 != 0) throw ImageDecodeException("PLTE повреждена (размер ${p.size})", source)
            val n = p.size / 3
            val rgb = IntArray(n)
            for (i in 0 until n) {
                rgb[i] = 0xFF000000.toInt() or
                    ((p[i * 3].toInt() and 0xFF) shl 16) or
                    ((p[i * 3 + 1].toInt() and 0xFF) shl 8) or
                    (p[i * 3 + 2].toInt() and 0xFF)
            }
            var alpha: ByteArray? = null
            if (trns != null) {
                alpha = ByteArray(n) { 0xFF.toByte() }
                val m = minOf(n, trns.size)
                for (i in 0 until m) alpha[i] = trns[i]
            }
            return Palette(rgb, alpha)
        }
        if (colorType == 0 && trns != null && trns.size >= 2) {
            // grayscale: tRNS = 2-байтный оттенок, который становится прозрачным.
            val v = trns[1].toInt() and 0xFF
            val alpha = ByteArray(256) { 0xFF.toByte() }
            alpha[v] = 0
            return Palette(IntArray(0), alpha)
        }
        // Остальные типы без палитры/tRNS-прозрачности.
        return Palette(IntArray(0), null)
    }

    private fun validateDepth(colorType: Int, bitDepth: Int, source: String?) {
        val ok = when (colorType) {
            0 -> bitDepth in intArrayOf(1, 2, 4, 8, 16)
            2 -> bitDepth in intArrayOf(8, 16)
            3 -> bitDepth in intArrayOf(1, 2, 4, 8)
            4, 6 -> bitDepth in intArrayOf(8, 16)
            else -> false
        }
        if (!ok) {
            throw ImageDecodeException("color type $colorType не поддерживает битовую глубину $bitDepth", source)
        }
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun inflate(stream: ByteArray, source: String?): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(stream)
            val out = java.io.ByteArrayOutputStream(minOf(stream.size * 4, 16 shl 20))
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsDictionary()) {
                        throw ImageDecodeException("zlib требует словарь (не поддерживается)", source)
                    }
                    if (inflater.needsInput()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw ImageDecodeException("повреждённые сжатые данные (zlib): ${e.message}", source, e)
        } finally {
            inflater.end()
        }
    }

    /** Защита от гигантских битмапов при битых заголовках. */
    const val MAX_PIXELS = 268_435_456L // 16384x16384

    /** Максимальный размер чанка (защита от мусора). */
    private const val MAX_CHUNK = 1 shl 28 // 256 МБ
}