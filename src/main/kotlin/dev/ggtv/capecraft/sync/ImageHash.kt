package dev.ggtv.capecraft.sync

import java.security.MessageDigest

/**
 * sha-256 картинки `file`-провайдера — идентификатор картинки в Sync v2.
 *
 * ## Зачем хэш, а не путь
 *
 * Файл лежит на диске **владельца**. В пути «`{root}/capes/pirate.png`» для
 * другого игрока нет ничего полезного (у него нет ни этого каталога, ни этого
 * диска), но есть личная информация о том, как владелец назвал свои файлы.
 * Поэтому в провод уходит только хэш содержимого, а путь остаётся у владельца.
 *
 * Побочная выгода — дедупликация: пятьдесят игроков с одним и тем же
 * Stacked PNG заливают его один раз, а не пятьдесят, и сервер хранит одну копию.
 * Совпали хэши — совпали байты, сверять их при получении не нужно.
 *
 * ## Почему не `value class`
 *
 * `value class` над `ByteArray` нельзя сделать с правильным `equals`: у него
 * сгенерированное сравнение по ссылке на массив, а нужен `contentEquals`.
 * Платим одной аллокацией на хэш (их единицы на провайдер) и получаем
 * корректное поведение в `HashMap`/`Set` — без сюрпризов в тестах.
 */
class ImageHash private constructor(private val bytes: ByteArray) {

    /** Байты для записи в поток. */
    fun toBytes(): ByteArray = bytes

    fun copyInto(target: ByteArray, offset: Int) {
        bytes.copyInto(target, offset)
    }

    override fun equals(other: Any?): Boolean = other is ImageHash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex(this)

    companion object {
        /** Длина sha-256 в байтах. */
        const val SIZE: Int = 32

        val ZERO: ImageHash = ImageHash(ByteArray(SIZE))

        /**
         * Разобрать 32 байта с провода. Ровно [SIZE], иначе — исключение:
         * «почти правильный» хэш означал бы, что мы потеряли или подмешали
         * байты и картинка приклеится не к тому провайдеру.
         */
        fun fromWire(bytes: ByteArray): ImageHash {
            if (bytes.size != SIZE) {
                throw SyncProtocolException("хеш должен быть $SIZE байт, а пришло ${bytes.size}")
            }
            return ImageHash(bytes.copyOf())
        }

        /**
         * Посчитать sha-256 содержимого картинки.
         *
         * Проверяет [SyncProtocol.MAX_IMAGE_BYTES] уже здесь: вызывающий код
         * работает с файлом и не должен сначала прочитать 40 МиБ в память,
         * чтобы потом узнать, что столько нельзя.
         */
        fun compute(data: ByteArray): ImageHash {
            if (data.size > SyncProtocol.MAX_IMAGE_BYTES) {
                throw SyncProtocolException(
                    "картинка ${data.size} байт, максимум ${SyncProtocol.MAX_IMAGE_BYTES}",
                )
            }
            return ImageHash(MessageDigest.getInstance("SHA-256").digest(data))
        }

        /** Из hex-строки (64 символа) — для логов, тестов и ручной сборки. */
        fun fromHex(hex: String): ImageHash {
            val clean = hex.trim().lowercase()
            if (clean.length != SIZE * 2) {
                throw SyncProtocolException("hex-хеш должен быть ${SIZE * 2} символов, а пришло ${clean.length}")
            }
            val out = ByteArray(SIZE)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) {
                    throw SyncProtocolException("не-hex символ в хеше «${hex.take(24)}…»")
                }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return ImageHash(out)
        }

        fun toHex(hash: ImageHash): String = buildString(SIZE * 2) {
            val digits = "0123456789abcdef"
            for (b in hash.toBytes()) {
                val v = b.toInt() and 0xFF
                append(digits[v ushr 4])
                append(digits[v and 0x0F])
            }
        }

        /** Короткая форма для логов: первые 8 hex-символов. */
        fun shortHex(hash: ImageHash): String = toHex(hash).take(8)
    }
}
