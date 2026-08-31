package dev.ggtv.capecraft.image

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Тесты GIF-декодера. Кадры строятся программно мини-энкодером (LZW),
 * чтобы не зависеть от внешних файлов и покрыть ветки формата:
 * GCT/LCT, интерлейс, прозрачность, задержки, dispose, NETSCAPE2.0 loop.
 */
class GifDecoderTest {

    // ---------------- Мини-энкодер GIF ----------------

    private class GifWriter(val width: Int, val height: Int) {
        private val out = ByteArrayOutputStream()
        private var lct: IntArray? = null

        /** R, G, B -> 0xAARRGGBB. */
        private fun argb(r: Int, g: Int, b: Int): Int =
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

        /** Палитра из IntArray (ARGB) в байты RGB. */
        private fun paletteBytes(p: IntArray): ByteArray =
            ByteArray(p.size * 3) { i ->
                val c = p[i / 3]
                when (i % 3) {
                    0 -> ((c ushr 16) and 0xFF).toByte()
                    1 -> ((c ushr 8) and 0xFF).toByte()
                    else -> (c and 0xFF).toByte()
                }
            }

        private fun subBlocks(data: ByteArray): ByteArray {
            val sb = ByteArrayOutputStream()
            var i = 0
            while (i < data.size) {
                val n = minOf(255, data.size - i)
                sb.write(n)
                sb.write(data, i, n)
                i += n
            }
            sb.write(0)
            return sb.toByteArray()
        }

        /** LZW-сжатие GIF (LSB-first, поток кодов упаковывается на лету). */
        private fun lzw(indices: ByteArray, minCodeSize: Int): ByteArray {
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            val dict = HashMap<String, Int>()
            var next = clear + 2
            var codeSize = minCodeSize + 1

            val out = ByteArrayOutputStream()
            var accumulator = 0
            var bitCount = 0
            // пишем код текущей шириной и при необходимости увеличиваем ширину
            fun writeCode(code: Int) {
                accumulator = accumulator or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    out.write(accumulator and 0xFF)
                    accumulator = accumulator ushr 8
                    bitCount -= 8
                }
            }

            writeCode(clear)

            var w = ""
            fun code(s: String): Int =
                if (s.length == 1) s[0].code else dict[s] ?: -1

            for (b in indices) {
                val c = b.toInt() and 0xFF
                val wc = w + c.toChar()
                if (w.isNotEmpty() && dict.containsKey(wc)) {
                    w = wc
                } else {
                    if (w.isNotEmpty()) {
                        writeCode(code(w))
                        if (next < 4096) {
                            dict[wc] = next
                            next++
                            if (next == (1 shl codeSize) + 1 && codeSize < 12) codeSize++
                        }
                    }
                    w = c.toChar().toString()
                }
            }
            if (w.isNotEmpty()) {
                writeCode(code(w))
            }
            writeCode(eoi)

            if (bitCount > 0) out.write(accumulator and 0xFF)
            return out.toByteArray()
        }

        fun build(): ByteArray {
            val o = ByteArrayOutputStream()
            o.write("GIF89a".toByteArray(Charsets.US_ASCII))
            o.write(byteArrayOf(
                (width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte(),
                (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte(),
            ))
            // packed: GCT flag + color resolution + sort + size
            val gctSizeBits = 1 // 2^2 = 4 цвета в палитре (min 2)
            o.write(0x80 or (gctSizeBits and 0x07))
            o.write(0) // bg color index
            o.write(0) // aspect
            // GCT: 4 цвета: black, white, red, blue
            val gct = IntArray(4) { i -> argb(if (i == 0) 0 else 255, if (i == 1) 255 else 0, if (i == 2) 255 else 0) }
            // проще: [0]=black, [1]=red, [2]=green, [3]=blue
            gct[0] = argb(0, 0, 0)
            gct[1] = argb(255, 0, 0)
            gct[2] = argb(0, 255, 0)
            gct[3] = argb(0, 0, 255)
            o.write(paletteBytes(gct))
            o.write(out.toByteArray())
            o.write(0x3B) // trailer
            return o.toByteArray()
        }

        // ---- API для теста ----

        /** GCE extension (89a). disposal: 0-3, transparent flag + index, delay hundredths. */
        fun gce(delayHundredths: Int, disposal: Int, transparent: Boolean = false, transparentIdx: Int = 0): GifWriter {
            val packed = (disposal shl 2) or (if (transparent) 1 else 0)
            out.write(0x21); out.write(0xF9)
            out.write(4) // block size
            out.write(packed)
            out.write(delayHundredths and 0xFF); out.write((delayHundredths shr 8) and 0xFF)
            out.write(transparentIdx)
            out.write(0)
            return this
        }

        /** NETSCAPE2.0 loop extension. */
        fun loop(iterations: Int): GifWriter {
            out.write(0x21); out.write(0xFF)
            out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3); out.write(0x01)
            out.write(iterations and 0xFF); out.write((iterations shr 8) and 0xFF)
            out.write(0)
            return this
        }

        /**
         * Изображение: прямоугольник [x,y,iw,ih], индексы палитры, LCT (или null = GCT),
         * interlace. Палитра обязана быть степенью двойки (2^k).
         */
        fun frame(x: Int, y: Int, iw: Int, ih: Int, indices: List<Int>, lct: IntArray? = null, interlace: Boolean = false): GifWriter {
            out.write(0x2C) // image descriptor
            out.write(x and 0xFF); out.write((x shr 8) and 0xFF)
            out.write(y and 0xFF); out.write((y shr 8) and 0xFF)
            out.write(iw and 0xFF); out.write((iw shr 8) and 0xFF)
            out.write(ih and 0xFF); out.write((ih shr 8) and 0xFF)
            val hasLct = lct != null
            val lctBits = if (lct != null) (Integer.numberOfTrailingZeros(lct.size) - 1) else 0
            var packed = 0
            if (hasLct) packed = packed or 0x80
            if (interlace) packed = packed or 0x40
            packed = packed or (lctBits and 0x07)
            out.write(packed)
            if (hasLct) out.write(paletteBytes(lct!!))

            val minCodeSize = if (hasLct) lctBits + 1 else 2 // GCT 4 цвета -> индексы 0..3
            out.write(minCodeSize) // min code size
            val lzwData = lzw(ByteArray(indices.size) { indices[it].toByte() }, minCodeSize)
            out.write(subBlocks(lzwData))
            return this
        }

        /** Полный GIF байтами (после trailer). */
        fun bytes(): ByteArray = build()
    }

    // ---------------- Тесты ----------------

    @Test
    fun `decode simple gif with global palette`() {
        val g = GifWriter(4, 2)
        // 4x2, все чёрные, цвета по индексам 0..3.
        g.frame(0, 0, 4, 2, listOf(1, 2, 3, 0, 1, 2, 3, 0))
        val img = GifDecoder.decode(g.bytes())
        assertEquals(4, img.width)
        assertEquals(2, img.height)
        assertTrue(!img.isAnimated)
        val px = img.singlePixels!!
        // палитра: 0=black 1=red 2=green 3=blue
        val expect = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt(),
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt(),
        )
        assertTrue(expect.contentEquals(px), "пиксели не совпали: ${px.joinToString { "0x${Integer.toHexString(it)}" }}")
    }

    @Test
    fun `decode two frames overlay dispose none`() {
        val g = GifWriter(4, 2)
        // кадр 0: весь чёрный
        g.frame(0, 0, 4, 2, List(8) { 0 })
        // кадр 1: красный 2x1 в (2,0), dispose none -> накладывается
        g.gce(10, 0)
        g.frame(2, 0, 2, 1, listOf(1, 1))
        val img = GifDecoder.decode(g.bytes())
        assertEquals(2, img.frameCount)
        assertEquals(100, img.frames[0].durationMs) // без GCE -> 100
        assertEquals(100, img.frames[1].durationMs) // delay 10 * 10
        val f1 = img.frames[1].pixels
        assertEquals(0xFF000000.toInt(), f1[0], "(0,0)")
        assertEquals(0xFFFF0000.toInt(), f1[2], "(2,0)")
        assertEquals(0xFFFF0000.toInt(), f1[3], "(3,0)")
        assertEquals(0xFF000000.toInt(), f1[4], "(0,1)")
    }

    @Test
    fun `decode transparency skips transparent pixels`() {
        val g = GifWriter(4, 1)
        // кадр 0: весь чёрный
        g.frame(0, 0, 4, 1, listOf(0, 0, 0, 0))
        // кадр 1: gce transparent index = 0; рисуем [0]=index2(green) в (0,0),
        // остальные transparent(0) - не перекрывают
        g.gce(5, 0, transparent = true, transparentIdx = 0)
        g.frame(0, 0, 4, 1, listOf(2, 0, 0, 0))
        val f1 = GifDecoder.decode(g.bytes()).frames[1].pixels
        assertEquals(0xFF00FF00.toInt(), f1[0], "(0,0) зелёный")
        assertEquals(0xFF000000.toInt(), f1[1], "(1,0) остался чёрным (transparent)")
        assertEquals(0xFF000000.toInt(), f1[3], "(3,0) остался чёрным (transparent)")
    }

    @Test
    fun `decode local color table`() {
        val g = GifWriter(3, 1)
        // LCT: 2 цвета [0]=cyan, [1]=magenta
        val lct = intArrayOf(
            0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
        )
        g.frame(0, 0, 3, 1, listOf(0, 1, 0), lct)
        val px = GifDecoder.decode(g.bytes()).singlePixels!!
        assertEquals(0xFF00FFFF.toInt(), px[0], "(0,0)")
        assertEquals(0xFFFF00FF.toInt(), px[1], "(1,0)")
        assertEquals(0xFF00FFFF.toInt(), px[2], "(2,0)")
    }

    @Test
    fun `decode interlaced`() {
        // 8-цветная LCT, чтобы индексы 0..6 были в палитре.
        val lct = IntArray(8) { i ->
            val c = i * 37 // детерминированный цвет
            0xFF000000.toInt() or (c shl 16) or ((c * 3 and 0xFF) shl 8) or (c * 5 and 0xFF)
        }
        val g = GifWriter(1, 7)
        // Данные идут в порядке строк интерлейса (порядок проходов декодера):
        // pass0: y=0;  pass1: y=4;  pass2: y=2,6;  pass3: y=1,3,5
        // data = [y0, y4, y2, y6, y1, y3, y5] = [0, 4, 2, 6, 1, 3, 5]
        g.frame(0, 0, 1, 7, listOf(0, 4, 2, 6, 1, 3, 5), lct, interlace = true)
        val px = GifDecoder.decode(g.bytes()).singlePixels!!
        for (y in 0 until 7) {
            assertEquals(lct[y], px[y], "строка $y (ожидали цвет индекса $y)")
        }
    }

    @Test
    fun `decode disposal background clears rect`() {
        val g2 = GifWriter(4, 1)
        g2.gce(10, 2) // disposal background для кадра 0
        g2.frame(0, 0, 4, 1, listOf(1, 1, 1, 1)) // кадр 0: красный весь
        g2.frame(2, 0, 2, 1, listOf(3, 3)) // кадр 1: синий 2x1 в (2,0)
        val f1 = GifDecoder.decode(g2.bytes()).frames[1].pixels
        // Кадр 0 был красный; после показа — dispose background заливает rect bg (index bg=0 -> чёрный)
        // Кадр 1 рисуется поверх: чёрный + синий 2x1
        assertEquals(0xFF000000.toInt(), f1[0], "(0,0) чёрный после dispose bg")
        assertEquals(0xFF0000FF.toInt(), f1[2], "(2,0) синий")
        assertEquals(0xFF0000FF.toInt(), f1[3], "(3,0) синий")
    }

    @Test
    fun `decode netescape loop`() {
        val g = GifWriter(2, 1)
        g.loop(3)
        g.frame(0, 0, 2, 1, listOf(1, 2))
        g.gce(5, 0)
        g.frame(0, 0, 2, 1, listOf(2, 1))
        val img = GifDecoder.decode(g.bytes())
        assertEquals(3, img.loopCount)
    }

    @Test
    fun `bad gif signature is error`() {
        val e = assertFailsWith<ImageDecodeException> { GifDecoder.decode("PNG not a gif!!!".toByteArray()) }
        assertTrue(e.message!!.contains("сигнатура"))
    }

    @Test
    fun `gif too short is error`() {
        val e = assertFailsWith<ImageDecodeException> { GifDecoder.decode(byteArrayOf(0x47, 0x49, 0x46)) }
        assertTrue(e.message!!.contains("короткий"))
    }

    @Test
    fun `no frames is error`() {
        // валидный заголовок + trailer, но без кадров
        val g = GifWriter(2, 1)
        val e = assertFailsWith<ImageDecodeException> { GifDecoder.decode(g.bytes()) }
        assertTrue(e.message!!.contains("нет кадров"))
    }

    @Test
    fun `format detection and unified decode route to gif`() {
        val g = GifWriter(2, 1)
        g.frame(0, 0, 2, 1, listOf(1, 2))
        val bytes = g.bytes()
        assertEquals(ImageFormat.GIF, ImageFormat.detect(bytes))
        // Единая точка входа (ImageDecoder) декодирует GIF и без явного формата.
        val img = ImageDecoder.decode(bytes)
        assertEquals(2, img.width)
        assertEquals(1, img.height)
    }
}
