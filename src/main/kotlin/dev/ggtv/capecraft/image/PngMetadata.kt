package dev.ggtv.capecraft.image

/**
 * Служебные чанки PNG (`tEXt`/`iTXt`), читаемые БЕЗ распаковки `IDAT`.
 *
 * Нужны, чтобы отличить stacked PNG от обычного: по сигнатуре это один и тот
 * же файл, и ни сигнатура, ни соотношение сторон не дают ответа. Разница
 * объявлена в самом файле — в `tEXt` вида
 *
 * ```
 * Description Stacked PNG: 4 frames 256x128, order=down
 * ```
 *
 * Детект по метаданным, а НЕ по высоте: у обычного плаща 256×512 и у
 * stacked из четырёх кадров 256×512 — одинаковые байты, и любая эвристика
 * по размеру растянула бы обычные плащи в анимацию.
 *
 * Сжатые `zTXt` не разжимаются намеренно: они редки, а распаковка ради
 * необязательной подсказки стоила бы лишней зависимости от inflate.
 */
object PngMetadata {

    /** Разобранное описание stacked PNG; неизвестные поля = `-1`. */
    data class Stacked(
        /** Сколько кадров заявлено автором, `-1` — не объявлено. */
        val frameCount: Int = -1,
        /** Ширина одного кадра, `-1` — не объявлена. */
        val frameWidth: Int = -1,
        /** Высота одного кадра, `-1` — не объявлена. */
        val frameHeight: Int = -1,
        /** Порядок кадров: `down` — друг под другом. */
        val order: String = "down",
    )

    /** Описание означает «это stacked PNG»; иначе `null`. */
    private val STACKED = Regex("""stacked\s+png""", RegexOption.IGNORE_CASE)
    private val FRAMES = Regex("""(\d+)\s*frames?""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""(\d+)\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val ORDER = Regex("""order\s*[=:]\s*(\w+)""", RegexOption.IGNORE_CASE)

    /** Сколько чанков читать максимум — защита от мусора вместо PNG. */
    private const val MAX_CHUNKS = 512

    /**
     * Описание stacked PNG из служебных чанков или `null`.
     *
     * Требуется именно слово «stacked png» — иначе обычный `Description`
     * вроде «шляпа 4 кадра» не превратит плащ в анимацию.
     */
    fun stacked(data: ByteArray): Stacked? {
        for (text in text(data).values) {
            if (!STACKED.containsMatchIn(text)) continue
            val frameHeight = SIZE.find(text)?.groupValues?.get(2)?.toIntOrNull() ?: -1
            val frameWidth = SIZE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            return Stacked(
                frameCount = FRAMES.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                order = ORDER.find(text)?.groupValues?.get(1)?.lowercase() ?: "down",
            )
        }
        return null
    }

    /** Все `tEXt`/`iTXt` как `ключ -> текст`; битый файл даёт пустую карту. */
    fun text(data: ByteArray): Map<String, String> {
        if (data.size < 8 + 12) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var offset = 8
        var seen = 0
        while (offset + 8 <= data.size && seen++ < MAX_CHUNKS) {
            val length = readInt(data, offset)
            if (length < 0 || offset + 12L + length > data.size) break
            val type = String(data, offset + 4, 4, Charsets.US_ASCII)
            val body = offset + 8
            if (type == "tEXt") {
                readLat1(data, body, length)?.let { (key, value) -> out.putIfAbsent(key, value) }
            } else if (type == "iTXt") {
                readIntl(data, body, length)?.let { (key, value) -> out.putIfAbsent(key, value) }
            } else if (type == "IEND") {
                break
            }
            offset += 12 + length
        }
        return out
    }

    /** `tEXt`: `ключ\0текст` в Latin-1. */
    private fun readLat1(data: ByteArray, at: Int, length: Int): Pair<String, String>? {
        val end = at + length
        if (end > data.size) return null
        val split = indexOfZero(data, at, end)
        if (split < 0) return null
        val key = String(data, at, split - at, Charsets.ISO_8859_1)
        val value = String(data, split + 1, end - split - 1, Charsets.ISO_8859_1)
        if (key.isEmpty()) return null
        return key to value
    }

    /** `iTXt`: `ключ\0 флаг метод язык\0 перевод\0 текст`; сжатый пропускаем. */
    private fun readIntl(data: ByteArray, at: Int, length: Int): Pair<String, String>? {
        val end = at + length
        if (end > data.size || length < 2) return null
        val keyEnd = indexOfZero(data, at, end)
        if (keyEnd < 0) return null
        val key = String(data, at, keyEnd - at, Charsets.ISO_8859_1)
        if (key.isEmpty()) return null
        // Флаг сжатия стоит за NUL ключа, а не в начале тела.
        val flag = keyEnd + 1
        if (flag + 1 >= end) return null
        if (data[flag] != 0.toByte()) return null // сжатый iTXt не читаем
        var cursor = flag + 2 // пропускаем флаг и метод сжатия
        val languageEnd = indexOfZero(data, cursor, end)
        if (languageEnd < 0) return null
        cursor = languageEnd + 1
        val translatedEnd = indexOfZero(data, cursor, end)
        if (translatedEnd < 0) return null
        val value = String(data, translatedEnd + 1, end - translatedEnd - 1, Charsets.UTF_8)
        return key to value
    }

    private fun indexOfZero(data: ByteArray, from: Int, until: Int): Int {
        for (i in from until until) if (data[i].toInt() == 0) return i
        return -1
    }

    private fun readInt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 24) or
            (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or
            (data[at + 3].toInt() and 0xFF)
}
