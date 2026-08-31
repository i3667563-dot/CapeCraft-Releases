package dev.ggtv.capecraft.image

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Сжатие zlib — для генерации IDAT/fdAT в тестах. */
private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    deflater.setInput(data)
    deflater.finish()
    val buf = ByteArray(8192)
    val out = ByteArrayOutputStream()
    while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        out.write(buf, 0, n)
    }
    deflater.end()
    return out.toByteArray()
}

/**
 * Тесты PNG-декодера. Изображения генерируются программно (мини-энкодер ниже),
 * чтобы не зависеть от внешних файлов и покрыть все ветки формата.
 */
class PngDecoderTest {

    // ---------- генератор PNG ----------

    private class PngWriter {
        val chunks = mutableListOf<ByteArray>()
        val raw = ByteArrayOutputStream()

        fun chunk(type: String, data: ByteArray) {
            val out = ByteArrayOutputStream()
            val len = byteArrayOf(
                (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(), data.size.toByte(),
            )
            out.write(len)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(data)
            val crc = CRC32()
            crc.update(type.toByteArray(Charsets.US_ASCII))
            crc.update(data)
            val c = crc.value.toInt()
            out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
            chunks += out.toByteArray()
        }

        fun ihdr(w: Int, h: Int, bitDepth: Int, colorType: Int, interlace: Int = 0): ByteArray = byteArrayOf(
            (w ushr 24).toByte(), (w ushr 16).toByte(), (w ushr 8).toByte(), w.toByte(),
            (h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte(),
            bitDepth.toByte(), colorType.toByte(), 0, 0, interlace.toByte(),
        )

        /** Фильтр: обратимое преобразование исходной строки в отфильтрованную (Filt = Rov - Predictor). */
        private fun filterRow(row: ByteArray, prev: ByteArray, type: Int, bpp: Int): ByteArray {
            val out = ByteArray(row.size)
            when (type) {
                0 -> row.copyInto(out)
                1 -> for (i in row.indices) {
                    val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                    out[i] = (row[i].toInt() - a).toByte()
                }
                2 -> for (i in row.indices) {
                    val b = prev[i].toInt() and 0xFF
                    out[i] = (row[i].toInt() - b).toByte()
                }
                3 -> for (i in row.indices) {
                    val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    out[i] = (row[i].toInt() - ((a + b) / 2)).toByte()
                }
                else -> for (i in row.indices) {
                    val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val p = a + b - c
                    val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                    val pred = when {
                        pa <= pb && pa <= pc -> a
                        pb <= pc -> b
                        else -> c
                    }
                    out[i] = (row[i].toInt() - pred).toByte()
                }
            }
            return out
        }

        /** Записать строки с фильтрами (prev сбрасывается на каждом вызове — так надо для Adam7). */
        fun addScanlines(rows: List<ByteArray>, bpp: Int, filterPerRow: (Int) -> Int = { 0 }) {
            var prev = ByteArray(rows[0].size)
            for ((y, row) in rows.withIndex()) {
                val f = filterPerRow(y)
                raw.write(f)
                raw.write(filterRow(row, prev, f, bpp))
                prev = row
            }
        }

        /** Собрать PNG: сигнатура + чанки + IDAT(deflate(raw)) + IEND. */
        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(SIG)
            for (c in chunks) out.write(c)
            chunk("IDAT", deflate(raw.toByteArray()))
            out.write(chunks.last())
            chunk("IEND", ByteArray(0))
            out.write(chunks.last())
            return out.toByteArray()
        }
    }

    /** Собрать APNG: оба кадра сжимаются одним deflate-потоком, который разрезается на IDAT|fdAT. */
    private fun buildApng(
        ihdr: ByteArray,
        raw0: ByteArray, raw1: ByteArray,
        fctl0: ByteArray, fctl1: ByteArray,
        acTl: ByteArray = byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0), // 2 кадра, бесконечный цикл
    ): ByteArray {
        val stream = deflate(raw0 + raw1)
        val mid = stream.size / 2
        val out = ByteArrayOutputStream()
        out.write(SIG)
        fun chunk(type: String, data: ByteArray) {
            val len = byteArrayOf(
                (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(), data.size.toByte(),
            )
            out.write(len)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(data)
            val crc = CRC32()
            crc.update(type.toByteArray(Charsets.US_ASCII))
            crc.update(data)
            val c = crc.value.toInt()
            out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
        }
        chunk("IHDR", ihdr)
        chunk("acTL", acTl)
        chunk("fcTL", fctl0)
        chunk("IDAT", stream.copyOfRange(0, mid))
        chunk("fcTL", fctl1)
        chunk("fdAT", byteArrayOf(0, 0, 0, 0) + stream.copyOfRange(mid, stream.size)) // seqno 0
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun fctl(x: Int, y: Int, w: Int, h: Int, delayNum: Int, dispose: Int, blend: Int): ByteArray = byteArrayOf(
        (x ushr 24).toByte(), (x ushr 16).toByte(), (x ushr 8).toByte(), x.toByte(),
        (y ushr 24).toByte(), (y ushr 16).toByte(), (y ushr 8).toByte(), y.toByte(),
        (w ushr 24).toByte(), (w ushr 16).toByte(), (w ushr 8).toByte(), w.toByte(),
        (h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte(),
        (delayNum ushr 24).toByte(), (delayNum ushr 16).toByte(), (delayNum ushr 8).toByte(), delayNum.toByte(),
        0, 0, 3, 232.toByte(), // delayDen = 1000
        dispose.toByte(), blend.toByte(),
    )

    /** Строка RGB с детерминированными значениями (x, y задают цвет). */
    private fun rgbRow(w: Int, y: Int, step: Int): ByteArray = ByteArray(w * 3) { i ->
        when (i % 3) {
            0 -> ((y * step + i / 3 * 7) % 256).toByte()
            1 -> ((i / 3 * 13 + 5) % 256).toByte()
            else -> ((y * 29 + i / 3 * 3) % 256).toByte()
        }
    }

    @Test
    fun `decode simple RGB png`() {
        val w = 4
        val h = 3
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 8, 2))
        p.addScanlines(List(h) { y -> rgbRow(w, y, 3) }, 3)
        val img = PngDecoder.decode(p.build())
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertTrue(!img.isAnimated, "статичный PNG не должен быть анимацией")
        assertEquals(-1, img.loopCount)
        val px = img.singlePixels!!
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (y * 3 + x * 7) % 256
                val g = (x * 13 + 5) % 256
                val b = (y * 29 + x * 3) % 256
                assertEquals(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b, px[y * w + x], "пиксель $x,$y")
            }
        }
    }

    @Test
    fun `decode with all filter types`() {
        val w = 5
        val h = 8
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 8, 2))
        val rows = List(h) { y -> rgbRow(w, y, 5) }
        p.addScanlines(rows, 3) { y -> y % 5 } // фильтры 0..4 по кругу
        val px = PngDecoder.decode(p.build()).singlePixels!!
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (y * 5 + x * 7) % 256
                val g = (x * 13 + 5) % 256
                val b = (y * 29 + x * 3) % 256
                assertEquals(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b, px[y * w + x], "пиксель $x,$y (фильтр ${y % 5})")
            }
        }
    }

    @Test
    fun `decode rgba with alpha`() {
        val w = 3
        val h = 2
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 8, 6))
        val rows = List(h) { y ->
            ByteArray(w * 4) { i ->
                when (i % 4) {
                    0 -> (100 + y * 10).toByte()   // r
                    1 -> (50 + i / 4 * 20).toByte() // g
                    2 -> 200.toByte()                // b
                    else -> (y * 128).toByte()       // a
                }
            }
        }
        p.addScanlines(rows, 4)
        val px = PngDecoder.decode(p.build()).singlePixels!!
        // Пиксель (0,1): r=110, g=50, b=200, a=128.
        assertEquals((128 shl 24) or (110 shl 16) or (50 shl 8) or 200, px[w + 0])
    }

    @Test
    fun `decode indexed with trns`() {
        val w = 4
        val h = 1
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 8, 3))
        p.chunk("PLTE", byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte())) // 0=red 1=green 2=blue
        p.chunk("tRNS", byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())) // индекс 2 полупрозрачный
        p.addScanlines(listOf(byteArrayOf(0, 1, 2, 3)), 1) // индекс 3 — вне палитры
        val px = PngDecoder.decode(p.build()).singlePixels!!
        assertEquals(0xFFFF0000.toInt(), px[0])
        assertEquals(0xFF00FF00.toInt(), px[1])
        assertEquals((0x80 shl 24) or 0x000000FF, px[2])
        assertEquals(0, px[3]) // вне палитры — прозрачный
    }

    @Test
    fun `decode grayscale 1-bit`() {
        val w = 8
        val h = 1
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 1, 0))
        p.addScanlines(listOf(byteArrayOf(0b10101010.toByte())), 1) // пиксели: 1,0,1,0...
        val px = PngDecoder.decode(p.build()).singlePixels!!
        for (x in 0 until w) {
            val expect = if (x % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            assertEquals(expect, px[x], "пиксель $x")
        }
    }

    @Test
    fun `decode 16-bit png takes high bytes`() {
        val w = 2
        val h = 1
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 16, 2))
        // r g b r g b, каждая компонента 2 байта (старший берётся)
        p.addScanlines(listOf(byteArrayOf(
            0x12, 0x34, 0xAB.toByte(), 0xCD.toByte(), 0x01, 0x02,
            0x99.toByte(), 0x88.toByte(), 0x77, 0x66, 0x55, 0x44,
        )), 6)
        val px = PngDecoder.decode(p.build()).singlePixels!!
        assertEquals(0xFF12AB01.toInt(), px[0])
        assertEquals(0xFF997755.toInt(), px[1])
    }

    @Test
    fun `decode adam7 interlaced`() {
        val w = 6
        val h = 5
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(w, h, 8, 2, interlace = 1))

        val passX = intArrayOf(0, 4, 0, 2, 0, 1, 0)
        val passY = intArrayOf(0, 0, 4, 0, 2, 0, 1)
        val stepX = intArrayOf(8, 8, 4, 4, 2, 2, 1)
        val stepY = intArrayOf(8, 8, 8, 4, 4, 2, 2)

        val expected = IntArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            val r = (yy * 5 + xx * 7) % 256
            val g = (xx * 13 + 5) % 256
            val b = (yy * 29 + xx * 3) % 256
            expected[yy * w + xx] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }

        // Строки проходов пишутся в порядке Adam7 — декодер раскладывает сам.
        for (pass in 0 until 7) {
            val pw = ((w - passX[pass] + stepX[pass] - 1) / stepX[pass]).coerceAtLeast(0)
            val ph = ((h - passY[pass] + stepY[pass] - 1) / stepY[pass]).coerceAtLeast(0)
            if (pw == 0 || ph == 0) continue
            val rows = ArrayList<ByteArray>(ph)
            for (iy in 0 until ph) {
                val row = ByteArray(pw * 3)
                for (ix in 0 until pw) {
                    val x = passX[pass] + ix * stepX[pass]
                    val y = passY[pass] + iy * stepY[pass]
                    val px = expected[y * w + x]
                    row[ix * 3] = ((px ushr 16) and 0xFF).toByte()
                    row[ix * 3 + 1] = ((px ushr 8) and 0xFF).toByte()
                    row[ix * 3 + 2] = (px and 0xFF).toByte()
                }
                rows += row
            }
            p.addScanlines(rows, 3)
        }
        val px = PngDecoder.decode(p.build()).singlePixels!!
        assertTrue(expected.contentEquals(px), "Adam7 пиксели не совпали")
    }

    /** Красный RGBA-кадр 4x2. */
    private fun redFrame(w: Int, h: Int): List<ByteArray> =
        List(h) { ByteArray(w * 4) { i -> when (i % 4) { 0 -> 255.toByte(); 3 -> 255.toByte(); else -> 0 } } }

    /** Синий RGBA-прямоугольник 2x1. */
    private fun blueRect(): List<ByteArray> =
        listOf(ByteArray(2 * 4) { i -> when (i % 4) { 2 -> 255.toByte(); 3 -> 255.toByte(); else -> 0 } })

    @Test
    fun `apng two frames with split idat fdat`() {
        val w = 4
        val h = 2
        val p = PngWriter()
        val ihdr = p.ihdr(w, h, 8, 6)
        p.raw.reset()
        p.addScanlines(redFrame(w, h), 4)
        val raw0 = p.raw.toByteArray()
        p.raw.reset()
        p.addScanlines(blueRect(), 4)
        val raw1 = p.raw.toByteArray()

        // Кадр 0: весь холст, 100 мс, dispose none, blend source.
        // Кадр 1: синий 2x1 в (2,0), 50 мс, dispose none, blend source.
        val bytes = buildApng(ihdr, raw0, raw1, fctl(0, 0, w, h, 100, 0, 0), fctl(2, 0, 2, 1, 50, 0, 0))
        val img = PngDecoder.decode(bytes)
        assertEquals(2, img.frameCount)
        assertEquals(100, img.frames[0].durationMs)
        assertEquals(50, img.frames[1].durationMs)
        assertEquals(0, img.loopCount, "numPlays=0 — бесконечный цикл")

        val f0 = img.frames[0].pixels
        for (i in f0.indices) assertEquals(0xFFFF0000.toInt(), f0[i], "кадр 0, пиксель $i")

        // Кадр 1: красный холст + синий прямоугольник поверх (dispose none).
        val f1 = img.frames[1].pixels
        assertEquals(0xFFFF0000.toInt(), f1[0], "пиксель (0,0)")
        assertEquals(0xFF0000FF.toInt(), f1[2], "пиксель (2,0)")
        assertEquals(0xFF0000FF.toInt(), f1[3], "пиксель (3,0)")
        assertEquals(0xFFFF0000.toInt(), f1[4], "пиксель (0,1)")
    }

    @Test
    fun `apng dispose background clears previous frame`() {
        val w = 4
        val h = 2
        val p = PngWriter()
        val ihdr = p.ihdr(w, h, 8, 6)
        p.raw.reset()
        p.addScanlines(redFrame(w, h), 4)
        val raw0 = p.raw.toByteArray()
        p.raw.reset()
        p.addScanlines(blueRect(), 4)
        val raw1 = p.raw.toByteArray()

        // Кадр 0: dispose BACKGROUND — после показа холст очищается прозрачным,
        // кадр 1 рисуется уже на чистом холсте.
        val bytes = buildApng(ihdr, raw0, raw1, fctl(0, 0, w, h, 100, 1, 0), fctl(2, 0, 2, 1, 50, 0, 0))
        val f1 = PngDecoder.decode(bytes).frames[1].pixels
        assertEquals(0, f1[0], "пиксель (0,0) — очищен")
        assertEquals(0xFF0000FF.toInt(), f1[2], "пиксель (2,0) — синий")
        assertEquals(0xFF0000FF.toInt(), f1[3], "пиксель (3,0) — синий")
        assertEquals(0, f1[4], "пиксель (0,1) — очищен")
    }

    @Test
    fun `apng blend over semi-transparent`() {
        val w = 4
        val h = 2
        val p = PngWriter()
        val ihdr = p.ihdr(w, h, 8, 6)
        p.raw.reset()
        p.addScanlines(redFrame(w, h), 4)
        val raw0 = p.raw.toByteArray()
        // Полупрозрачный зелёный (a=128) прямоугольник 2x1 в (0,0).
        p.raw.reset()
        p.addScanlines(listOf(ByteArray(2 * 4) { i -> when (i % 4) { 1 -> 255.toByte(); 3 -> 128.toByte(); else -> 0 } }), 4)
        val raw1 = p.raw.toByteArray()

        val bytes = buildApng(ihdr, raw0, raw1, fctl(0, 0, w, h, 100, 0, 0), fctl(0, 0, 2, 1, 50, 0, 1))
        val f1 = PngDecoder.decode(bytes).frames[1].pixels
        // Зелёный a=128 поверх красного: r=127, g=128, b=0, a=255 (по формуле blendOver).
        val over = (255 shl 24) or (127 shl 16) or (128 shl 8) or 0
        assertEquals(over, f1[0], "пиксель (0,0)")
        assertEquals(over, f1[1], "пиксель (1,0)")
        assertEquals(0xFFFF0000.toInt(), f1[2], "пиксель (2,0) не тронут")
        assertEquals(0xFFFF0000.toInt(), f1[4], "пиксель (0,1) не тронут")
    }

    @Test
    fun `bad signature is error`() {
        val e = assertFailsWith<ImageDecodeException> { PngDecoder.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        assertTrue(e.message!!.contains("сигнатура"))
    }

    @Test
    fun `truncated chunks are error`() {
        val p = PngWriter()
        p.chunk("IHDR", p.ihdr(4, 4, 8, 2))
        val data = p.build()
        // Режем внутри данных IDAT (сигнатура 8 + IHDR-чанк 25 = IDAT data с 41).
        val cut = 45
        val e = assertFailsWith<ImageDecodeException> { PngDecoder.decode(data.copyOfRange(0, cut)) }
        assertTrue(e.message!!.contains("чанк"), "ожидали 'чанк', получили: ${e.message}")
    }

    companion object {
        /** Сигнатура PNG. */
        val SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}