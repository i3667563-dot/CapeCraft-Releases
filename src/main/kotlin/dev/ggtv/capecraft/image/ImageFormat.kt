package dev.ggtv.capecraft.image

/** Формат изображения, определённый по сигнатуре. */
enum class ImageFormat {
    PNG, GIF, WEBP;

    companion object {
        private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val GIF_SIG = byteArrayOf(0x47, 0x49, 0x46) // "GIF"
        private val RIFF_SIG = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        private val WEBP_SIG = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"

        /** Определить формат по первым байтам; null — неизвестный формат. */
        fun detect(data: ByteArray): ImageFormat? {
            if (data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIG)) return PNG
            if (data.size >= 6 && data.copyOfRange(0, 3).contentEquals(GIF_SIG)) return GIF
            // WebP: RIFF..WEBP (контейнер) либо сырой VP8L (0x2F)
            if (data.size >= 12 && data.copyOfRange(0, 4).contentEquals(RIFF_SIG) &&
                data.copyOfRange(8, 12).contentEquals(WEBP_SIG)
            ) return WEBP
            if (data.size >= 1 && (data[0].toInt() and 0xFF) == 0x2F) return WEBP
            return null
        }
    }
}