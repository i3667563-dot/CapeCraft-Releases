package dev.ggtv.capecraft.image

/**
 * Декодер WebP на чистом Kotlin.
 *
 * Поддержка:
 * - контейнер RIFF/WEBP (VP8X, ANIM, ANMF, ALPH) и «сырой» VP8L;
 * - lossless-изображения VP8L полностью: преобразования (predictor, color,
 *   subtract-green, color-indexing с бандлингом), цветовой кэш, LZ77,
 *   мета-prefix (несколько групп Хаффмана);
 * - анимация WebP (VP8X + ANIM + ANMF) для lossless-кадров с композицией
 *   по рамкам, dispose (NONE/BACKGROUND/PREVIOUS) и blend.
 *
 * Lossy (VP8) намеренно не поддержан: для плащей (иллюстрации, прозрачность)
 * используется lossless, а кривой lossy-декодер хуже честной ошибки.
 */
object WebpDecoder {

    /**
     * Ленивый debug-файл: создаётся только при включённой системной свойстве
     * `webp.debug` и только если каталог /tmp/opencode существует.
     * Не падает на CI или чужих машинах.
     */
    private val debugFile: java.io.File? by lazy {
        if (System.getProperty("webp.debug") == null) return@lazy null
        val f = java.io.File("/tmp/opencode/webp_debug.txt")
        f.parentFile?.mkdirs()
        f
    }

    fun decode(data: ByteArray, source: String? = null): AnimatedImage {
        if (data.size < 12) throw ImageDecodeException("файл слишком короткий", source)
        if (isAscii(data, 0, "RIFF")) {
            if (!isAscii(data, 8, "WEBP")) {
                throw ImageDecodeException("RIFF без WEBP", source)
            }
            return decodeRiff(data, source)
        }
        if ((data[0].toInt() and 0xFF) == 0x2F) {
            return decodeVp8l(data, source)
        }
        throw ImageDecodeException("не WebP (сигнатура не совпадает)", source)
    }

    private fun isAscii(data: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > data.size) return false
        for (i in s.indices) if (data[off + i] != s[i].code.toByte()) return false
        return true
    }

    // ------------------------------------------------------------------
    // Контейнер RIFF/WEBP
    // ------------------------------------------------------------------

    private data class Anmf(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val dur: Int, val dispose: Int, val blend: Int, val payload: ByteArray,
    )

    private fun decodeRiff(data: ByteArray, source: String?): AnimatedImage {
        var pos = 12
        var canvasW = 0
        var canvasH = 0
        var animate = false
        var loopCount = 0
        var bgColor = 0
        val frames = ArrayList<Anmf>()

        while (pos + 8 <= data.size) {
            val tag = String(data, pos, 4, Charsets.US_ASCII)
            val size = le32(data, pos + 4)
            if (pos + 8 + size > data.size) {
                throw ImageDecodeException("чанк $tag обрезан", source)
            }
            val payload = data.copyOfRange(pos + 8, pos + 8 + size)
            when (tag) {
                "VP8X" -> {
                    if (payload.size < 10) throw ImageDecodeException("VP8X повреждён", source)
                    val flags = payload[0].toInt() and 0xFF
                    animate = flags and 0x02 != 0
                    canvasW = 1 + int24(payload, 4)
                    canvasH = 1 + int24(payload, 7)
                    if (canvasW <= 0 || canvasH <= 0) {
                        throw ImageDecodeException("неверные размеры холста $canvasW x $canvasH", source)
                    }
                }
                "VP8L" -> {
                    if (frames.isEmpty()) {
                        val img = decodeVp8l(payload, source)
                        if (canvasW == 0) { canvasW = img.width; canvasH = img.height }
                        return img
                    }
                }
                "ANIM" -> {
                    if (payload.size < 6) throw ImageDecodeException("ANIM повреждён", source)
                    bgColor = le32(payload, 0)
                    loopCount = payload[4].toInt() and 0xFF or ((payload[5].toInt() and 0xFF) shl 8)
                }
                "ANMF" -> {
                    if (payload.size < 16) throw ImageDecodeException("ANMF повреждён", source)
                    val x = int24(payload, 0)
                    val y = int24(payload, 3)
                    val w = int24(payload, 6)
                    val h = int24(payload, 9)
                    val dur = int24(payload, 12)
                    val flags = payload[15].toInt() and 0xFF
                    val disp = (flags shr 1) and 0x01
                    val blend = (flags shr 2) and 0x01
                    val body = payload.copyOfRange(16, payload.size)
                    frames += Anmf(x, y, w, h, dur, disp, blend, body)
                }
                // ALPH/ICCP/EXIF/XMP — игнорируем
            }
            pos += 8 + size
        }

        if (frames.isNotEmpty()) {
            if (canvasW == 0 || canvasH == 0) {
                throw ImageDecodeException("нет размеров холста", source)
            }
            return composeAnimation(canvasW, canvasH, bgColor, loopCount, frames, source)
        }
        throw ImageDecodeException("нет изображения в WebP", source)
    }

    private fun composeAnimation(
        w: Int, h: Int, bgColor: Int, loopCount: Int,
        list: List<Anmf>, source: String?,
    ): AnimatedImage {
        val canvas = IntArray(w * h) { bgColor }
        val outFrames = ArrayList<Frame>(list.size)
        // Состояния «до наложения» каждого кадра по индексу, но только для тех,
        // что объявили PREVIOUS-storage (dispose=2) — остальные null, копия
        // холста не создаётся (типичный случай экономит память/CPU).
        val savedBefore = arrayOfNulls<IntArray>(list.size)

        for ((i, f) in list.withIndex()) {
            // Применяем dispose предыдущего кадра (состояние вывода после кадра i-1 ->
            // состояние для начала отрисовки кадра i).
            if (i > 0) {
                val prev = list[i - 1]
                when (prev.dispose) {
                    1 -> fillRect(canvas, w, prev.x, prev.y, prev.w, prev.h, bgColor)
                    2 -> savedBefore[i - 1]?.copyInto(canvas)
                }
            }
            // Запоминаем состояние до наложения текущего кадра — только если
            // этот кадр объявил PREVIOUS-storage (dispose=2), по его индексу.
            // Для типичных анимаций (dispose=0) копия холста не создаётся.
            if (f.dispose == 2) savedBefore[i] = canvas.copyOf()

            // Тело кадра.
            val body = f.payload
            val framePixels: IntArray
            val fw: Int
            val fh: Int
            // Тело lossless-кадра в ANMF — полноценный вложенный RIFF-чанк "VP8L"
            // (тег + 4-байт размер + данные 0x2F...), а не сырой VP8L-битстрим.
            // Декодируем в естественном размере кадра (у ANMF-прямоугольника бывают
            // расхождения с реальными размером) и вписываем в холст как есть.
            if (body.size >= 8 && isAscii(body, 0, "VP8L")) {
                val img = decodeVp8l(body.copyOfRange(8, body.size), source)
                framePixels = img.singlePixels!!
                fw = img.width; fh = img.height
            } else if (body.isNotEmpty() && (body[0].toInt() and 0xFF) == 0x2F) {
                val img = decodeVp8l(body, source)
                framePixels = img.singlePixels!!
                fw = img.width; fh = img.height
            } else if (body.isNotEmpty() && (body[0].toInt() and 0xFF) == 0x9D) {
                throw ImageDecodeException("WebP-анимация с lossy-кадрами (VP8) не поддержана", source)
            } else if (body.isEmpty()) {
                // пустой кадр — оставить холст как есть
                outFrames += Frame(canvas.copyOf(), if (f.dur > 0) f.dur else 100)
                continue
            } else {
                throw ImageDecodeException("неизвестное тело кадра WebP", source)
            }

            // Кадр вписывается в холст. Используем РЕАЛЬНЫЕ размеры декодированного
            // кадра (fw/fh) как источник — у ANMF-прямоугольника (f.w/f.h) бывают
            // расхождения с фактическим размером данных (некоторые энкодеры пишут
            // в ANMF w/h на единицу меньше). Клипаем к холсту по началу (f.x/f.y).
            val cw = minOf(fw, w - f.x)
            val ch = minOf(fh, h - f.y)
            if (f.x < 0 || f.y < 0 || f.x + cw > w || f.y + ch > h) {
                throw ImageDecodeException("кадр WebP выходит за холст", source)
            }
            if (f.blend == 0) {
                for (yy in 0 until ch) {
                    val dst = (f.y + yy) * w + f.x
                    val src = yy * fw
                    framePixels.copyInto(canvas, dst, src, src + cw)
                }
            } else {
                for (yy in 0 until ch) {
                    val dst = (f.y + yy) * w + f.x
                    val src = yy * fw
                    for (xx in 0 until cw) {
                        canvas[dst + xx] = blendOver(canvas[dst + xx], framePixels[src + xx])
                    }
                }
            }
            outFrames += Frame(canvas.copyOf(), if (f.dur > 0) f.dur else 100)
        }

        if (outFrames.isEmpty()) throw ImageDecodeException("нет кадров WebP", source)
        return AnimatedImage(w, h, outFrames, if (loopCount == 0) 0 else loopCount)
    }

    private fun fillRect(buf: IntArray, w: Int, x: Int, y: Int, rw: Int, rh: Int, color: Int) {
        for (yy in y until y + rh) {
            val off = yy * w + x
            buf.fill(color, off, off + rw)
        }
    }

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

    // ------------------------------------------------------------------
    // VP8L (lossless)
    // ------------------------------------------------------------------

    private fun decodeVp8l(data: ByteArray, source: String?, forcedW: Int = 0, forcedH: Int = 0): AnimatedImage {
        val br = BitReader(data)
        if (br.read(8) != 0x2F) throw ImageDecodeException("не VP8L (сигнатура 0x2F)", source)

        var w = br.read(14) + 1
        var h = br.read(14) + 1
        val alphaUsed = br.read(1)
        val version = br.read(3)
        if (version != 0) throw ImageDecodeException("неверная версия VP8L $version", source)
        if (forcedW != 0) w = forcedW
        if (forcedH != 0) h = forcedH
        if (w <= 0 || h <= 0) throw ImageDecodeException("неверные размеры $w x $h", source)
        if (w.toLong() * h > MAX_PIXELS) {
            throw ImageDecodeException("изображение $w x $h слишком большое", source)
        }

        // Трансформы (порядок появления = порядок применения инверсии в обратном виде).
        val transforms = ArrayList<Transform>()
        var imgW = w
        var imgH = h
        while (br.read(1) == 1) {
            when (br.read(2)) {
                0 -> { // PREDICTOR
                    val sizeBits = br.read(3) + 2
                    val tw = divRoundUp(imgW, 1 shl sizeBits)
                    val th = divRoundUp(imgH, 1 shl sizeBits)
                    val map = decodeEntropyImage(tw, th, br, source)
                    transforms += Transform.Predictor(map, sizeBits)
                }
                1 -> { // COLOR
                    val sizeBits = br.read(3) + 2
                    val tw = divRoundUp(imgW, 1 shl sizeBits)
                    val th = divRoundUp(imgH, 1 shl sizeBits)
                    val map = decodeEntropyImage(tw, th, br, source)
                    transforms += Transform.Color(map, sizeBits)
                }
                2 -> transforms += Transform.SubtractGreen
                3 -> { // COLOR_INDEXING
                    val (widthBits, palette) = readColorTable(br, source)
                    transforms += Transform.IndexBundling(widthBits, palette)
                    // subsample последующих трансформ и основного изображения
                    imgW = divRoundUp(imgW, 1 shl widthBits)
                }
                else -> throw ImageDecodeException("неизвестная трансформа", source)
            }
        }

        debugFile?.appendText(
            "[webp] ${source} ${w}x${h} alpha=$alphaUsed transforms=${transforms.map { it.javaClass.simpleName }} imgW=$imgW imgH=$imgH\n"
        )

        // Основное изображение: color-cache-info + meta-prefix + данные.
        // main.pixels имеют subsampled-размер (после всех трансформ).
        val main = decodeMainImage(imgW, imgH, br, source)
        debugFile?.appendText("[webp] main ${imgW}x${imgH} pixels=${main.pixels.size} first=${main.pixels.take(8).joinToString(",") { Integer.toHexString(it) }}\n")
        var result = main.pixels

        // Применяем обратные трансформы в обратном порядке появления.
        // Размер изображения был уменьшен только colour-indexing трансформами;
        // в обратном проходе восстанавливаем его по пути к первой трансформе.
        var curW = imgW
        var curH = imgH
        for (t in transforms.asReversed()) {
            when (t) {
                is Transform.Predictor -> result = applyPredictor(result, curW, curH, t.map, t.sizeBits)
                is Transform.Color -> result = applyColorTransform(result, curW, curH, t.map, t.sizeBits)
                Transform.SubtractGreen -> result = applySubtractGreen(result)
                is Transform.IndexBundling -> {
                    result = applyColorIndexing(result, curW, curH, t.widthBits, t.palette)
                    curW = curW shl t.widthBits
                }
            }
        }

        if (alphaUsed == 0) {
            for (i in result.indices) result[i] = result[i] or 0xFF000000.toInt()
        }

        return AnimatedImage(w, h, listOf(Frame(result, 100)))
    }

    private class DecodeResult(val pixels: IntArray)

    private sealed class Transform {
        class Predictor(val map: IntArray, val sizeBits: Int) : Transform()
        class Color(val map: IntArray, val sizeBits: Int) : Transform()
        object SubtractGreen : Transform()
        class IndexBundling(val widthBits: Int, val palette: IntArray) : Transform()
    }

    private fun divRoundUp(n: Int, d: Int): Int = (n + d - 1) / d

    // ---- color indexing ----

    /**
     * Читает данные color-indexing трансформы: возвращает (widthBits, палитра).
     *
     * Палитра — это изображение шириной ровно color_table_size (НЕ subsampled!),
     * высотой 1, закодированное обычным entropy-кодом. Цвета палитры
     * subtraction-coded: каждый следующий цвет = сумма предыдущего по каналам.
     * Палитра «расширяется» до final_num_colors = 1 << (8 >> widthBits) записей,
     * незаполненный хвост — чёрный (0x00000000), что даёт прозрачные пиксели
     * при вылете индекса за color_table_size.
     */
    private fun readColorTable(br: BitReader, source: String?): Pair<Int, IntArray> {
        val size = br.read(8) + 1
        val widthBits = when {
            size <= 2 -> 3
            size <= 4 -> 2
            size <= 16 -> 1
            else -> 0
        }
        // Палитра: изображение width=size, height=1, без RIFF/размеров/трансформ.
        val raw = decodeEntropyImage(size, 1, br, source)
        debugFile?.appendText(
            "[palette] pos=${br.pos} size=$size widthBits=$widthBits raw=${raw.take(12).joinToString(",") { Integer.toHexString(it) }}\n")
        val finalColors = 1 shl (8 shr widthBits)
        val palette = IntArray(finalColors)
        if (size > 0) palette[0] = raw[0]
        for (i in 1 until size) {
            val prev = palette[i - 1]
            val cur = raw[i]
            val a = ((cur ushr 24) and 0xFF) + ((prev ushr 24) and 0xFF)
            val r = ((cur ushr 16) and 0xFF) + ((prev ushr 16) and 0xFF)
            val g = ((cur ushr 8) and 0xFF) + ((prev ushr 8) and 0xFF)
            val b = (cur and 0xFF) + (prev and 0xFF)
            palette[i] = ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or
                ((g and 0xFF) shl 8) or (b and 0xFF)
        }
        // Хвост (индексы от size до finalColors) уже 0 (чёрный/прозрачный).
        debugFile?.appendText(
            "[palette-out] pos=${br.pos} pal0..15=${palette.take(16).joinToString(",") { Integer.toHexString(it) }}\n")
        return widthBits to palette
    }

    // ---- inverse transforms ----

    private fun applyPredictor(pixels: IntArray, w: Int, h: Int, map: IntArray, sizeBits: Int): IntArray {
        val tw = divRoundUp(w, 1 shl sizeBits)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                val block = (y shr sizeBits) * tw + (x shr sizeBits)
                val mode = (map[block] ushr 8) and 0xFF
                val pred = predict(pixels, w, x, y, mode)
                if (debugFile != null) {
                    val pp = pixels[p]
                    debugFile!!.appendText(
                        "[pred] y=$y x=$x mode=$mode residual(${(pp ushr 16) and 255},${(pp ushr 8) and 255},${pp and 255}) pred(${(pred ushr 16) and 255},${(pred ushr 8) and 255},${pred and 255}) -> ${addChannels(pp, pred)}\n")
                }
                pixels[p] = addChannels(pixels[p], pred)
            }
        }
        return pixels
    }

    private fun predict(buf: IntArray, w: Int, x: Int, y: Int, mode: Int): Int {
        // Спец. правила границ (спека §4.1): left-top = 0xff000000,
        // верхняя строка — L-пиксель, левая колонка — T-пиксель (независимо от mode).
        if (x == 0 && y == 0) return 0xFF000000.toInt()
        if (y == 0) return buf[x - 1]                        // верхняя строка → L
        if (x == 0) return buf[(y - 1) * w]                  // левая колонка → T
        val L = buf[y * w + x - 1]
        val T = buf[(y - 1) * w + x]
        val TL = buf[(y - 1) * w + x - 1]
        // Спека §4.1: на правой колонке (x == w-1) TR недоступен, вместо него
        // берётся ЛЕВЫЙ КРАЙНИЙ пиксель текущей строки (buf[y*w + 0]), а не L.
        val TR = if (x < w - 1) buf[(y - 1) * w + x + 1] else buf[y * w]
        return when (mode) {
            0 -> 0xFF000000.toInt()
            1 -> L
            2 -> T
            3 -> TR
            4 -> TL
            5 -> average2(average2(L, TR), T)
            6 -> average2(L, TL)
            7 -> average2(L, T)
            8 -> average2(TL, T)
            9 -> average2(T, TR)
            10 -> average2(average2(L, TL), average2(T, TR))
            11 -> select(L, T, TL)
            12 -> clampAddSubtractFull(L, T, TL)
            else -> clampAddSubtractHalf(average2(L, T), TL)
        }
    }

    private fun addChannels(residual: Int, pred: Int): Int {
        val a = ((residual ushr 24) and 0xFF) + ((pred ushr 24) and 0xFF)
        val r = ((residual ushr 16) and 0xFF) + ((pred ushr 16) and 0xFF)
        val g = ((residual ushr 8) and 0xFF) + ((pred ushr 8) and 0xFF)
        val b = (residual and 0xFF) + (pred and 0xFF)
        return ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    private fun cA(v: Int) = (v ushr 24) and 0xFF
    private fun cR(v: Int) = (v ushr 16) and 0xFF
    private fun cG(v: Int) = (v ushr 8) and 0xFF
    private fun cB(v: Int) = v and 0xFF

    private fun average2(x: Int, y: Int): Int {
        val a = (cA(x) + cA(y)) ushr 1
        val r = (cR(x) + cR(y)) ushr 1
        val g = (cG(x) + cG(y)) ushr 1
        val b = (cB(x) + cB(y)) ushr 1
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun select(L: Int, T: Int, TL: Int): Int {
        // pAlpha..pBlue — оценки Prediction(a+b-c) по каналам.
        val pA = cA(L) + cA(T) - cA(TL)
        val pR = cR(L) + cR(T) - cR(TL)
        val pG = cG(L) + cG(T) - cG(TL)
        val pB = cB(L) + cB(T) - cB(TL)
        fun d(a: Int, b: Int) = if (a >= b) a - b else b - a
        val pL = d(pA, cA(L)) + d(pR, cR(L)) + d(pG, cG(L)) + d(pB, cB(L))
        val pT = d(pA, cA(T)) + d(pR, cR(T)) + d(pG, cG(T)) + d(pB, cB(T))
        return if (pL < pT) L else T
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun clampAddSubtractFull(a: Int, b: Int, c: Int): Int {
        val A = clamp(cA(a) + cA(b) - cA(c))
        val R = clamp(cR(a) + cR(b) - cR(c))
        val G = clamp(cG(a) + cG(b) - cG(c))
        val B = clamp(cB(a) + cB(b) - cB(c))
        return (A shl 24) or (R shl 16) or (G shl 8) or B
    }

    private fun clampAddSubtractHalf(a: Int, b: Int): Int {
        val A = clamp(cA(a) + (cA(a) - cA(b)) / 2)
        val R = clamp(cR(a) + (cR(a) - cR(b)) / 2)
        val G = clamp(cG(a) + (cG(a) - cG(b)) / 2)
        val B = clamp(cB(a) + (cB(a) - cB(b)) / 2)
        return (A shl 24) or (R shl 16) or (G shl 8) or B
    }

    private fun applyColorTransform(pixels: IntArray, w: Int, h: Int, map: IntArray, sizeBits: Int): IntArray {
        val tw = divRoundUp(w, 1 shl sizeBits)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                val block = (y shr sizeBits) * tw + (x shr sizeBits)
                val cte = map[block]
                val redToBlue = cR(cte)
                val greenToBlue = cG(cte)
                val greenToRed = cB(cte)
                val px = pixels[p]
                val green = cG(px)
                val red = cR(px)
                val blue = cB(px)
                val tmpRed = (red + colorTransformDelta(greenToRed, green)) and 0xFF
                val tmpBlue = (blue + colorTransformDelta(greenToBlue, green) +
                    colorTransformDelta(redToBlue, tmpRed)) and 0xFF
                pixels[p] = (px and 0xFF00FF00.toInt()) or (tmpRed shl 16) or tmpBlue
            }
        }
        return pixels
    }

    private fun colorTransformDelta(t: Int, c: Int): Int {
        val ts = if (t >= 128) t - 256 else t
        val cs = if (c >= 128) c - 256 else c
        return (ts * cs) shr 5
    }

    private fun applySubtractGreen(pixels: IntArray): IntArray {
        for (i in pixels.indices) {
            val g = cG(pixels[i])
            val r = (cR(pixels[i]) + g) and 0xFF
            val b = (cB(pixels[i]) + g) and 0xFF
            pixels[i] = (pixels[i] and 0xFF00FF00.toInt()) or (r shl 16) or b
        }
        return pixels
    }

    private fun applyColorIndexing(pixels: IntArray, w: Int, h: Int, widthBits: Int, palette: IntArray): IntArray {
        // w,h — subsampled-размеры; распаковываем индексы из green в полный размер.
        val fullW = w shl widthBits
        val fullH = h
        val out = IntArray(fullW * fullH)
        val per = 1 shl widthBits
        for (y in 0 until h) {
            for (x in 0 until w) {
                val packed = (pixels[y * w + x] ushr 8) and 0xFF
                for (k in 0 until per) {
                    val fx = (x shl widthBits) + k
                    if (fx >= fullW) break
                    val idx = (packed shr (k * 8 / per)) and maskFor(widthBits)
                    out[y * fullW + fx] = palette[idx]
                }
            }
        }
        return out
    }

    private fun maskFor(widthBits: Int): Int = when (widthBits) {
        0 -> 0xFF
        1 -> 0x0F
        2 -> 0x03
        else -> 0x01
    }

    // ---- entropy-coded image ----

    private fun decodeEntropyImage(w: Int, h: Int, br: BitReader, source: String?): IntArray {
        val cache = readColorCacheInfo(br, source)
        val group = readPrefixGroup(cache.cacheSize, br, source)
        return decodeData(w, h, br, arrayOf(group), 0, IntArray(0), cache, source)
    }

    private fun decodeMainImage(w: Int, h: Int, br: BitReader, source: String?): DecodeResult {
        val cache = readColorCacheInfo(br, source)
        debugFile?.appendText("[webp] mainImage ${w}x$h cacheUsed=${cache.used} bits=${cache.bits}\n")
        val useMeta = br.read(1) == 1
        debugFile?.appendText("[webp] mainImage useMeta=$useMeta\n")
        if (useMeta) {
            val prefixBits = br.read(3) + 2
            val pw = divRoundUp(w, 1 shl prefixBits)
            val ph = divRoundUp(h, 1 shl prefixBits)
            val entropy = decodeEntropyImage(pw, ph, br, source)
            var maxGroup = 0
            for (i in entropy.indices) {
                val meta = (entropy[i] ushr 8) and 0xFFFF
                if (meta > maxGroup) maxGroup = meta
            }
            val groups = Array(maxGroup + 1) { readPrefixGroup(cache.cacheSize, br, source) }
            val pixels = decodeData(w, h, br, groups, prefixBits, entropy, cache, source)
            return DecodeResult(pixels)
        } else {
            val group = readPrefixGroup(cache.cacheSize, br, source)
            val pixels = decodeData(w, h, br, arrayOf(group), 0, IntArray(0), cache, source)
            return DecodeResult(pixels)
        }
    }

    private class ColorCache internal constructor(val used: Boolean, val bits: Int) {
        val cacheSize: Int get() = if (used) 1 shl bits else 0
        val buf: IntArray = IntArray(cacheSize)

        fun insert(pixel: Int) {
            if (used) buf[(0x1e35a7bd * pixel) ushr (32 - bits)] = pixel
        }

        fun get(symbol: Int): Int = buf[symbol]
    }

    private fun readColorCacheInfo(br: BitReader, source: String?): ColorCache {
        if (br.read(1) == 0) return ColorCache(false, 0)
        val bits = br.read(4)
        if (bits !in 1..11) throw ImageDecodeException("неверный color cache (bits=$bits)", source)
        return ColorCache(true, bits)
    }

    private class PrefixGroup(
        val green: Huffman,
        val red: Huffman,
        val blue: Huffman,
        val alpha: Huffman,
        val distance: Huffman,
    )

    private fun readPrefixGroup(cacheSize: Int, br: BitReader, source: String?): PrefixGroup {
        return PrefixGroup(
            readHuffman(256 + 24 + cacheSize, br, source),
            readHuffman(256, br, source),
            readHuffman(256, br, source),
            readHuffman(256, br, source),
            readHuffman(40, br, source),
        )
    }

    /**
     * Декодирование потока пикселей. [groups] — группы Хаффмана (1 если нет мета).
     * [prefixBits] и [entropy] — параметры мета-prefix (0 и пустой, если нет мета).
     */
    private fun decodeData(
        w: Int, h: Int, br: BitReader, groups: Array<PrefixGroup>,
        prefixBits: Int, entropy: IntArray, cache: ColorCache, source: String?,
    ): IntArray {
        val out = IntArray(w * h)
        val hasMeta = groups.size > 1
        debugFile?.appendText("[webp] decodeData ${w}x$h hasMeta=$hasMeta prefixBits=$prefixBits\n")
        val pw = if (hasMeta) divRoundUp(w, 1 shl prefixBits) else 0
        var pos = 0
        while (pos < out.size) {
            val g = if (hasMeta) {
                val x = pos % w
                val y = pos / w
                val meta = (entropy[(y shr prefixBits) * pw + (x shr prefixBits)] ushr 8) and 0xFFFF
                groups[meta]
            } else {
                groups[0]
            }
            val s = g.green.decode(br)
            val isPal = w * h <= 2000 && !hasMeta && pw == 0
            if (debugFile != null && (pos < 8 || (isPal && pos < 24))) {
                debugFile!!.appendText("[webp] decode pos=$pos symPos=${br.pos} s=$s\n")
            }
            if (s < 256) {
                val green = s
                val red = g.red.decode(br)
                val blue = g.blue.decode(br)
                val alpha = g.alpha.decode(br)
                val px = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                if (debugFile != null && (pos < 45 || (isPal && pos < 24))) {
                    debugFile!!.appendText("[chan] pos=$pos g=$green r=$red b=$blue a=$alpha brpos=${br.pos}\n")
                }
                cache.insert(px)
                out[pos++] = px
            } else if (s < 280) {
                val lengthCode = s - 256
                var length = prefixValue(lengthCode, br)
                val distCode = g.distance.decode(br)
                val dist = distanceValue(distCode, br, w)
                if (debugFile != null && (pos < 90 || (isPal && pos < 24))) {
                    debugFile!!.appendText("[lz77] pos=$pos lenCode=$lengthCode len=$length distCode=$distCode dist=$dist brpos=${br.pos}\n")
                }
                for (i in 0 until length) {
                    val from = pos - dist
                    if (from < 0) throw ImageDecodeException("backward-ссылка за пределы", source)
                    val px = out[from]
                    cache.insert(px)
                    out[pos++] = px
                }
            } else {
                val px = cache.get(s - 280)
                cache.insert(px)
                out[pos++] = px
            }
        }
        return out
    }

    /** Значение LZ77-prefix кода (общая формула для length и distance). */
    private fun prefixValue(code: Int, br: BitReader): Int {
        if (code < 4) return code + 1
        val extra = (code - 2) shr 1
        val offset = (2 + (code and 1)) shl extra
        return offset + br.read(extra) + 1
    }

    /** Преобразование distance-кода в сканлайновое смещение. */
    private fun distanceValue(distCode: Int, br: BitReader, w: Int): Int {
        val code = prefixValue(distCode, br)
        if (code <= 120) {
            val (xi, yi) = DIST_MAP[code - 1]
            var dist = xi + yi * w
            if (dist < 1) dist = 1
            return dist
        }
        return code - 120
    }

    // ---- Huffman ----

    private class Huffman private constructor(
        private val symbols: Array<IntArray>,
        private val counts: IntArray,
        private val firstCodes: IntArray,
        private val maxLen: Int,
        private val singleSymbol: Int,
    ) {
        fun decode(br: BitReader): Int {
            if (singleSymbol >= 0) return singleSymbol
            var code = 0
            for (len in 1..maxLen) {
                code = (code shl 1) or br.read(1)
                val cnt = counts[len]
                if (cnt > 0) {
                    val first = firstCodes[len]
                    if (code - first in 0 until cnt) {
                        return symbols[len][code - first]
                    }
                }
            }
            throw IllegalStateException("не найден код Хаффмана")
        }

        companion object {
            fun fromLengths(lengths: IntArray): Huffman {
                val counts = IntArray(16)
                var maxLen = 0
                var nonZero = 0
                var single = -1
                for ((sym, l) in lengths.withIndex()) {
                    if (l in 1..15) {
                        counts[l]++
                        maxLen = maxOf(maxLen, l)
                        nonZero++
                        single = sym
                    }
                }
                if (nonZero == 0) {
                    // пустой код — одиночный символ 0
                    return Huffman(Array(0) { IntArray(0) }, IntArray(0), IntArray(0), 1, 0)
                }
                if (nonZero == 1) {
                    // одиночный лист, длиной 1
                    return Huffman(Array(0) { IntArray(0) }, IntArray(0), IntArray(0), 1, single)
                }
                val firstCodes = IntArray(16)
                var code = 0
                for (len in 1..15) {
                    code = (code + counts[len - 1]) shl 1
                    firstCodes[len] = code
                }
                val symbols: Array<IntArray> = Array(16) { IntArray(0) }
                val cur = IntArray(16)
                for (len in 1..15) symbols[len] = IntArray(counts[len])
                for ((sym, l) in lengths.withIndex()) {
                    if (l in 1..15) symbols[l][cur[l]++] = sym
                }
                return Huffman(symbols, counts, firstCodes, maxLen, -1)
            }
        }
    }

    private fun readHuffman(alphabetSize: Int, br: BitReader, source: String?): Huffman {
        if (br.read(1) == 1) {
            val numSymbols = br.read(1) + 1
            val isFirst8 = br.read(1)
            val symbol0 = br.read(1 + 7 * isFirst8)
            val lengths = IntArray(alphabetSize)
            lengths[symbol0] = 1
            if (numSymbols == 2) {
                val symbol1 = br.read(8)
                lengths[symbol1] = 1
            }
            return Huffman.fromLengths(lengths)
        }

        val numCodeLengths = 4 + br.read(4)
        val ccl = IntArray(19)
        for (i in 0 until numCodeLengths) {
            ccl[CODE_LENGTH_ORDER[i]] = br.read(3)
        }
        val codeLengthLen = Huffman.fromLengths(ccl)
        debugFile?.appendText("[webp] huffStep pos=${br.pos} alpha=$alphabetSize numCL=$numCodeLengths ccl=${ccl.joinToString(",")}\n")

        val maxSymbol = if (br.read(1) == 0) {
            alphabetSize
        } else {
            val lenNbits = 2 + 2 * br.read(3)
            2 + br.read(lenNbits)
        }
        if (maxSymbol > alphabetSize) {
            throw ImageDecodeException("max_symbol $maxSymbol больше алфавита $alphabetSize", source)
        }

        val lengths = IntArray(alphabetSize)
        var symbol = 0
        // libwebp: цикл идёт `while (symbol < num_symbols)` и в начале КАЖДОЙ
        // итерации делает `if (max_symbol-- == 0) break;`. Т.е. читается ровно
        // max_symbol кодов длин, при этом repeat-коды засчитываются как ОДНА
        // итерация, но заполняют несколько символов. Именно эти семантики (а не
        // `i < maxSymbol` / `i += repeat`) согласуют потребление бит с libwebp.
        var maxSym = maxSymbol
        var prev = DEFAULT_CODE_LENGTH
        while (symbol < alphabetSize) {
            if (maxSym-- == 0) break
            val code = codeLengthLen.decode(br)
            when {
                code < 16 -> {
                    lengths[symbol] = code
                    if (code > 0) prev = code
                    symbol++
                }
                code == 16 -> {
                    val repeat = 3 + br.read(2)
                    // libwebp: prev_code_len инициализируется DEFAULT_CODE_LENGTH (=8),
                    // поэтому если repeat-code 16 встречается ДО первого ненулевого
                    // значения длины, он повторяет 8, а не 0.
                    val v = prev
                    if (alphabetSize == 256 && maxSymbol == 256) debugFile?.appendText("step i=$symbol code=16 rep=$repeat v=$v\n")
                    repeatUntil(lengths, symbol, repeat, alphabetSize) { v }; symbol += repeat
                }
                code == 17 -> {
                    val repeat = 3 + br.read(3)
                    if (alphabetSize == 256 && maxSymbol == 256) debugFile?.appendText("step i=$symbol code=17 rep=$repeat\n")
                    symbol += repeatUntilZero(lengths, symbol, repeat, alphabetSize)
                }
                else -> {
                    val repeat = 11 + br.read(7)
                    if (alphabetSize == 256 && maxSymbol == 256) debugFile?.appendText("step i=$symbol code=18 rep=$repeat\n")
                    symbol += repeatUntilZero(lengths, symbol, repeat, alphabetSize)
                }
            }
            if (alphabetSize == 256 && maxSymbol == 256 && code < 16) {
                debugFile?.appendText("step i=${symbol - 1} code=$code\n")
            }
        }
        if (debugFile != null) {
            val nz = lengths.count { it > 0 }
            val mx = lengths.maxOrNull() ?: 0
            val all = if (maxSymbol == 209 || mx == 8 || mx == 6) lengths.take(maxSymbol).joinToString(",") else ""
            debugFile!!.appendText(
                "[webp] huff alpha=$alphabetSize maxSym=$maxSymbol nz=$nz maxLen=$mx posEnd=${br.pos}\n$all\n"
            )
        }
        return Huffman.fromLengths(lengths)
    }

    private inline fun repeatUntil(lengths: IntArray, start: Int, repeat: Int, max: Int, v: () -> Int) {
        var i = start
        for (k in 0 until repeat) { if (i >= max) break; lengths[i++] = v() }
    }

    private fun repeatUntilZero(lengths: IntArray, start: Int, repeat: Int, max: Int): Int {
        var i = start
        for (k in 0 until repeat) { if (i >= max) break; lengths[i++] = 0 }
        return i - start
    }

    // ---- bit reader ----

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0
        val pos: Int get() = bitPos

        fun read(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val bytePos = bitPos ushr 3
                if (bytePos >= data.size) throw IllegalStateException("выход за пределы битстрима")
                v = v or (((data[bytePos].toInt() shr (bitPos and 7)) and 1) shl i)
                bitPos++
            }
            return v
        }
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun int24(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16)

    private const val MAX_PIXELS = 268_435_456L

    private val DIST_MAP: Array<Pair<Int, Int>> = arrayOf(
        0 to 1, 1 to 0, 1 to 1, -1 to 1, 0 to 2, 2 to 0, 1 to 2,
        -1 to 2, 2 to 1, -2 to 1, 2 to 2, -2 to 2, 0 to 3, 3 to 0,
        1 to 3, -1 to 3, 3 to 1, -3 to 1, 2 to 3, -2 to 3, 3 to 2,
        -3 to 2, 0 to 4, 4 to 0, 1 to 4, -1 to 4, 4 to 1, -4 to 1,
        3 to 3, -3 to 3, 2 to 4, -2 to 4, 4 to 2, -4 to 2, 0 to 5,
        3 to 4, -3 to 4, 4 to 3, -4 to 3, 5 to 0, 1 to 5, -1 to 5,
        5 to 1, -5 to 1, 2 to 5, -2 to 5, 5 to 2, -5 to 2, 4 to 4,
        -4 to 4, 3 to 5, -3 to 5, 5 to 3, -5 to 3, 0 to 6, 6 to 0,
        1 to 6, -1 to 6, 6 to 1, -6 to 1, 2 to 6, -2 to 6, 6 to 2,
        -6 to 2, 4 to 5, -4 to 5, 5 to 4, -5 to 4, 3 to 6, -3 to 6,
        6 to 3, -6 to 3, 0 to 7, 7 to 0, 1 to 7, -1 to 7, 5 to 5,
        -5 to 5, 7 to 1, -7 to 1, 4 to 6, -4 to 6, 6 to 4, -6 to 4,
        2 to 7, -2 to 7, 7 to 2, -7 to 2, 3 to 7, -3 to 7, 7 to 3,
        -7 to 3, 5 to 6, -5 to 6, 6 to 5, -6 to 5, 8 to 0, 4 to 7,
        -4 to 7, 7 to 4, -7 to 4, 8 to 1, 8 to 2, 6 to 6, -6 to 6,
        8 to 3, 5 to 7, -5 to 7, 7 to 5, -7 to 5, 8 to 4, 6 to 7,
        -6 to 7, 7 to 6, -7 to 6, 8 to 5, 7 to 7, -7 to 7, 8 to 6,
        8 to 7,
    )

    private val CODE_LENGTH_ORDER = intArrayOf(
        17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    // libwebp DEFAULT_CODE_LENGTH: значение, которое повторяет code 16, если оно
    // встречается до первого ненулевого code length.
    private const val DEFAULT_CODE_LENGTH = 8
}
