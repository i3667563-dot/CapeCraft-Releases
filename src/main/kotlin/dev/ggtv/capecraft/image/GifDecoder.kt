package dev.ggtv.capecraft.image

/**
 * Декодер GIF (87a/89a) на чистом Kotlin.
 *
 * Скорость: LZW через плоские массивы [prefix]/[suffix]/[stack] без HashMap,
 * битовый ридер без объектов, кадры раскладываются сразу в ARGB.
 *
 * Поддержка: GCT/LCT, интерлейс, прозрачность, GCE-задержки, dispose
 * (none/background/previous), NETSCAPE2.0 loop.
 */
object GifDecoder {

    fun decode(data: ByteArray, source: String? = null): AnimatedImage {
        if (data.size < 13) throw ImageDecodeException("файл слишком короткий", source)
        val magic = String(data, 0, 3, Charsets.US_ASCII)
        if (magic != "GIF") throw ImageDecodeException("не GIF (сигнатура не совпадает)", source)
        val version = String(data, 3, 3, Charsets.US_ASCII)
        if (version != "87a" && version != "89a") {
            throw ImageDecodeException("неизвестная версия GIF «$version»", source)
        }

        val width = leInt(data, 6)
        val height = leInt(data, 8)
        if (width <= 0 || height <= 0) throw ImageDecodeException("неверные размеры $width x $height", source)
        if (width.toLong() * height > MAX_PIXELS) {
            throw ImageDecodeException("изображение $width x $height слишком большое", source)
        }

        val lsd = data[10].toInt() and 0xFF
        val gctFlag = lsd and 0x80 != 0
        val bgIndex = data[11].toInt() and 0xFF
        val gctSize = if (gctFlag) 3 * (1 shl ((lsd and 0x07) + 1)) else 0

        val globalPalette = if (gctSize > 0) readPalette(data, 13, gctSize) else IntArray(0)
        var pos = 13 + gctSize

        // Состояние кадров.
        var canvas = IntArray(width * height)
        val frames = ArrayList<Frame>()
        var pendingGce: Gce? = null
        var pendingRestore: IntArray? = null
        var loopCount = -1 // отсутствие NETSCAPE — без повторов (стандарт: 1 проход)

        while (pos < data.size) {
            when (data[pos].toInt() and 0xFF) {
                0x3B -> break // Trailer
                0x21 -> { // Extension
                    val label = data[pos + 1].toInt() and 0xFF
                    pos += 2
                    when (label) {
                        0xF9 -> { // Graphic Control Extension
                            // block size 4: packed, delay(2), transparent index, terminator
                            if (pos + 6 > data.size) throw ImageDecodeException("GCE обрезан", source)
                            val packed = data[pos + 1].toInt() and 0xFF
                            val delayHundredths = leInt(data, pos + 2)
                            val transparentIndex = data[pos + 4].toInt() and 0xFF
                            pendingGce = Gce(
                                disposal = (packed shr 2) and 0x07,
                                transparent = packed and 0x01 != 0,
                                transparentIndex = transparentIndex,
                                delayMs = if (delayHundredths <= 0) 100 else delayHundredths * 10,
                            )
                            pos += 6 // + terminator
                        }
                        0xFF -> { // Application Extension (NETSCAPE2.0)
                            // block size 11 + "NETSCAPE2.0" + sub-blocks
                            val appName = readString(data, pos + 1, 11)
                            pos += 12
                            if (appName.startsWith("NETSCAPE")) {
                                // sub-block: size(1) data; "03" + loop(2LE)
                                while (pos < data.size) {
                                    val size = data[pos].toInt() and 0xFF
                                    pos++
                                    if (size == 0) break
                                    if (size >= 3 && (data[pos].toInt() and 0xFF) == 0x01) {
                                        loopCount = leInt(data, pos + 1) // 0 = бесконечно
                                    }
                                    pos += size
                                }
                            } else {
                                pos = skipSubBlocks(data, pos, source)
                            }
                        }
                        else -> skipSubBlocks(data, pos, source)
                    }
                }
                0x2C -> { // Image Descriptor
                    if (pos + 10 > data.size) throw ImageDecodeException("Image Descriptor обрезан", source)
                    val ix = leInt(data, pos + 1)
                    val iy = leInt(data, pos + 3)
                    val iw = leInt(data, pos + 5)
                    val ih = leInt(data, pos + 7)
                    val packed = data[pos + 9].toInt() and 0xFF
                    val lctFlag = packed and 0x80 != 0
                    val interlace = packed and 0x40 != 0
                    val lctSize = if (lctFlag) 3 * (1 shl ((packed and 0x07) + 1)) else 0
                    pos += 10

                    val palette = if (lctFlag) {
                        if (pos + lctSize > data.size) throw ImageDecodeException("LCT обрезан", source)
                        readPalette(data, pos, lctSize).also { pos += lctSize }
                    } else {
                        globalPalette
                    }
                    if (palette.isEmpty()) throw ImageDecodeException("нет палитры для кадра", source)

                    val minCodeSize = data[pos].toInt() and 0xFF
                    pos++
                    val imgData = readSubBlocks(data, pos, source)
                    pos = imgData.end

                    // Декодируем индексы и раскладываем в строки.
                    val indices = decodeLzw(imgData.bytes, iw.toLong() * ih, minCodeSize, source)
                    val rowPixels = IntArray(iw * ih)
                    val gce = pendingGce
                    pendingGce = null
                    val transparent = if (gce?.transparent == true) gce.transparentIndex else -1

                    for (y in 0 until ih) {
                        val srcOff = y * iw
                        for (x in 0 until iw) {
                            val idx = indices[srcOff + x]
                            rowPixels[srcOff + x] = if (idx == transparent) 0
                            else palette.getOrElse(idx) { 0 }
                        }
                    }
                    // Может быть idle: индекс вне палитры → 0 (прозрачный).

                    // Интерлейс: переложить строки.
                    val ordered = if (interlace) deinterlace(rowPixels, iw, ih) else rowPixels

                    // Проверка прямоугольника.
                    if (ix < 0 || iy < 0 || ix + iw > width || iy + ih > height) {
                        throw ImageDecodeException("кадр выходит за холст ($ix,$iy $iw x $ih при холсте $width x $height)", source)
                    }

                    // Dispose предыдущего кадра — применяем к canvas перед наложением.
                    // (disposal хранится в GCE кадра; применяется ПОСЛЕ его отрисовки)
                    // Храним restore до наложения.
                    pendingRestore = canvas.copyOf()

                    // Наложение.
                    for (y in 0 until ih) {
                        val dstOff = (iy + y) * width + ix
                        val srcOff = y * iw
                        for (x in 0 until iw) {
                            val p = ordered[srcOff + x]
                            if (p != 0) canvas[dstOff + x] = p // прозрачные пропускаем
                        }
                    }

                    frames += Frame(canvas.copyOf(), gce?.delayMs ?: 100)

                    when (gce?.disposal ?: 0) {
                        2 -> { // background: залить прямоугольник цветом фона/прозрачн.
                            val bg = if (gce?.transparent == true && bgIndex == gce.transparentIndex) 0
                            else globalPalette.getOrNull(bgIndex) ?: 0
                            for (y in 0 until ih) {
                                val off = (iy + y) * width + ix
                                canvas.fill(bg, off, off + iw)
                            }
                        }
                        3 -> pendingRestore?.copyInto(canvas)
                    }
                }
                else -> throw ImageDecodeException("неизвестный блок GIF: 0x${data[pos].toString(16)}", source)
            }
        }

        if (frames.isEmpty()) throw ImageDecodeException("нет кадров", source)
        return AnimatedImage(width, height, frames, if (frames.size > 1) loopCount else -1)
    }

    private class Gce(val disposal: Int, val transparent: Boolean, val transparentIndex: Int, val delayMs: Int)

    /** Чтение RGB-палитры: 3 байта на цвет. */
    private fun readPalette(data: ByteArray, off: Int, byteCount: Int): IntArray {
        val n = byteCount / 3
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = 0xFF000000.toInt() or
                ((data[off + i * 3].toInt() and 0xFF) shl 16) or
                ((data[off + i * 3 + 1].toInt() and 0xFF) shl 8) or
                (data[off + i * 3 + 2].toInt() and 0xFF)
        }
        return out
    }

    private fun readString(data: ByteArray, off: Int, len: Int): String =
        String(data, off, len, Charsets.US_ASCII)

    /** Пропустить sub-blocks до терминатора 0; вернуть позицию после терминатора. */
    private fun skipSubBlocks(data: ByteArray, start: Int, source: String?): Int {
        var p = start
        while (p < data.size) {
            val size = data[p].toInt() and 0xFF
            p++
            if (size == 0) return p
            p += size
        }
        throw ImageDecodeException("sub-blocks обрезаны", source)
    }

    /** Прочитать sub-blocks в один массив; вернуть данные и позицию после терминатора. */
    private fun readSubBlocks(data: ByteArray, start: Int, source: String?): SubBlock {
        val out = java.io.ByteArrayOutputStream()
        var p = start
        while (p < data.size) {
            val size = data[p].toInt() and 0xFF
            p++
            if (size == 0) {
                return SubBlock(out.toByteArray(), p)
            }
            if (p + size > data.size) throw ImageDecodeException("sub-block обрезан", source)
            out.write(data, p, size)
            p += size
        }
        throw ImageDecodeException("sub-blocks обрезаны (нет терминатора)", source)
    }

    private class SubBlock(val bytes: ByteArray, val end: Int)

    /** Переложить строки интерлейса в обычный порядок. */
    private fun deinterlace(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var row = 0
        // pass 0: строки 0, 8, 16...
        var y = 0
        while (y < h) { src.copyInto(out, y * w, row * w, row * w + w); row++; y += 8 }
        // pass 1: 4, 12, 20...
        y = 4
        while (y < h) { src.copyInto(out, y * w, row * w, row * w + w); row++; y += 8 }
        // pass 2: 2, 6, 10...
        y = 2
        while (y < h) { src.copyInto(out, y * w, row * w, row * w + w); row++; y += 4 }
        // pass 3: 1, 3, 5...
        y = 1
        while (y < h) { src.copyInto(out, y * w, row * w, row * w + w); row++; y += 2 }
        return out
    }

    /**
     * Декодер LZW (GIF-вариант: LSB-first битовый поток, словарь до 4096).
     * Возвращает массив цветовых индексов.
     */
    fun decodeLzw(data: ByteArray, pixelCount: Long, minCodeSize: Int, source: String?): IntArray {
        if (minCodeSize !in 1..8) throw ImageDecodeException("неверный min code size $minCodeSize", source)
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1

        val prefix = IntArray(4096) { -1 }
        val suffix = IntArray(4096)
        // Начальные «литеральные» записи словаря: байты 0..clearCode-1.
        for (i in 0 until clearCode) suffix[i] = i
        val stack = IntArray(4096)
        val out = IntArray(pixelCount.toInt())

        var free = clearCode + 2
        var prev = -1
        var outPos = 0

        // Битовый ридер: GIF читает коды LSB-first, биты накапливаются слева.
        var bitPos = 0 // позиция следующего бита в data (с 0)
        fun readCode(): Int {
            if (codeSize > 12) return -1
            var code = 0
            for (i in 0 until codeSize) {
                val bit = (data[bitPos ushr 3].toInt() shr (bitPos and 7)) and 1
                code = code or (bit shl i)
                bitPos++
            }
            return code
        }

        var stackTop = 0

        while (outPos < out.size) {
            if ((bitPos + codeSize + 7) ushr 3 > data.size) break
            val code = readCode()

            if (code == clearCode) {
                free = clearCode + 2
                codeSize = minCodeSize + 1
                prev = -1
                continue
            }
            if (code == eoiCode) break

            if (prev == -1) {
                // Первый код после сброса — одиночный индекс.
                out[outPos++] = code
                prev = code
                continue
            }

            // Распаковка: стек из suffix/prefix-цепочек.
            // Для кода >= free (KwKwK): строка = строка(prev) + первый байт строки(prev).
            stackTop = 0
            var c = code
            if (c >= free) c = prev
            while (c >= 0) {
                stack[stackTop++] = suffix[c]
                c = prefix[c]
            }
            var first = stack[stackTop - 1]
            if (code >= free) {
                // KwKwK: дублируем первый байт строки(prev) в конце.
                stack[stackTop++] = first
            }
            while (stackTop > 0 && outPos < out.size) {
                out[outPos++] = stack[--stackTop]
            }

            // Расширение словаря.
            if (free < 4096) {
                prefix[free] = prev
                suffix[free] = first
                free++
                if (free == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        return out
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private const val MAX_PIXELS = 268_435_456L
}