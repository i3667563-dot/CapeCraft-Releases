package dev.ggtv.capecraft.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Сборка PNG-байтов для тестов: IHDR + произвольные чанки + IDAT + IEND.
 *
 * Живой IDAT нужен, чтобы [PngDecoder] действительно распаковал пиксели,
 * а служебные чанки (`tEXt`) — чтобы проверить различение форматов.
 */
object PngFixture {
    val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * @param text пары `ключ -> значение` для чанков `tEXt`.
     * @param intl пары `ключ -> значение` для чанков `iTXt` (UTF-8, без сжатия).
     * @param apng добавить `acTL`/`fcTL`, чтобы файл считался APNG.
     */
    fun png(
        width: Int,
        height: Int,
        pixels: IntArray,
        text: List<Pair<String, String>> = emptyList(),
        intl: List<Pair<String, String>> = emptyList(),
        intlCompressed: List<Pair<String, String>> = emptyList(),
        apng: Boolean = false,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                raw.write((pixel ushr 16) and 0xFF)
                raw.write((pixel ushr 8) and 0xFF)
                raw.write(pixel and 0xFF)
                raw.write((pixel ushr 24) and 0xFF)
            }
        }
        val ihdr = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        )
        val chunks = mutableListOf(chunk("IHDR", ihdr))
        if (apng) {
            chunks += chunk("acTL", intBytes(1, 1))
            chunks += chunk("fcTL", concat(intBytes(0, width, height, 0, 0), byteArrayOf(0, 1, 1, 0, 0, 0)))
        }
        val nul = "\u0000"
        for ((key, value) in text) {
            // Формат tEXt: keyword, NUL-байт, текст в Latin-1.
            chunks += chunk("tEXt", (key + nul + value).toByteArray(Charsets.ISO_8859_1))
        }
        for ((key, value) in intl) {
            // Формат iTXt: keyword, NUL, флаг сжатия, метод, язык, NUL,
            // перевод, NUL, текст в UTF-8. Флаг сжатия = 0.
            chunks += chunk("iTXt", (key + nul + nul + nul + nul + nul + value).toByteArray(Charsets.UTF_8))
        }
        for ((key, value) in intlCompressed) {
            // То же, но флаг сжатия = 1: такой чанк мод читать не должен.
            chunks += chunk("iTXt", (key + nul + 1.toChar() + nul + nul + value).toByteArray(Charsets.UTF_8))
        }
        chunks += chunk("IDAT", deflate(raw.toByteArray()))
        chunks += chunk("IEND", byteArrayOf())
        return concat(SIGNATURE, *chunks.toTypedArray())
    }

    private fun intBytes(vararg values: Int): ByteArray {
        val output = ByteArray(values.size * 4)
        for (index in values.indices) {
            val value = values[index]
            val offset = index * 4
            output[offset] = (value ushr 24).toByte()
            output[offset + 1] = (value ushr 16).toByte()
            output[offset + 2] = (value ushr 8).toByte()
            output[offset + 3] = value.toByte()
        }
        return output
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            output.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val value = crc.value.toInt()
        return concat(
            byteArrayOf(
                (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(), data.size.toByte(),
            ),
            typeBytes,
            data,
            byteArrayOf(
                (value ushr 24).toByte(), (value ushr 16).toByte(),
                (value ushr 8).toByte(), value.toByte(),
            ),
        )
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        for (part in parts) output.write(part)
        return output.toByteArray()
    }
}
