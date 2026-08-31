package dev.ggtv.capecraft.memory

/**
 * Лимиты памяти для плащей (этап 5 «умная память»).
 *
 * Плащ подгружается по сети/диску и раскладывается в [IntArray] ARGB —
 * по 4 байта на пиксель. Анимация из N кадров 1024x1024 — это N * 4 МБ
 * только под пиксели. Чтобы мод не съел всю память клиента, вводятся
 * жёсткие лимиты: на один плащ и на суммарно все плащи (кэш).
 *
 * Лимиты применяются в [MemoryManager] многоступенчатой деградацией:
 * сначала area-average сжатие, потом прореживание кадров, потом выброс
 * из LRU-кэша.
 */
data class Limits(
    /** Максимум пикселей в одном кадре (ширина*высота). */
    val maxPixelsPerFrame: Long = 4_000_000L,        // ~2000x2000
    /** Максимум кадров в одной анимации. */
    val maxFrames: Int = 100,
    /** Максимум байт под пиксели одного плаща (все кадры). */
    val maxBytesPerCape: Long = 64L * 1024 * 1024,   // 64 МБ = 16 кадров 1024x1024
    /** Максимум суммарных байт под пиксели всех закэшированных плащей. */
    val maxBytesTotal: Long = 128L * 1024 * 1024,    // 128 МБ
) {
    companion object {
        const val BYTES_PER_PIXEL = 4

        /** Превышение лимита кадра — сжатие до этого количества пикселей. */
        const val FRAME_TARGET_PIXELS = 1_000_000L    // 1000x1000
        /** Минимальная ширина/высота, ниже которой не сжимаем (плащ не выродится). */
        const val MIN_DIM = 8
    }

    /** Байты под все кадры изображения. */
    fun bytesOf(width: Int, height: Int, frames: Int): Long =
        width.toLong() * height * frames * BYTES_PER_PIXEL

    /** Байты под пиксели кадра-канваса width x height. */
    fun frameBytes(width: Int, height: Int): Long =
        width.toLong() * height * BYTES_PER_PIXEL
}
