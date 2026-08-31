package dev.ggtv.capecraft.image

/**
 * Ошибка декодирования изображения.
 *
 * Несёт человеческое сообщение (по-русски), файл/источник и конкретную
 * причину — чтобы пользователь по логу мог понять, что не так с его плащом.
 */
class ImageDecodeException(
    message: String,
    /** Источник: URL, имя файла или «встроенные данные». */
    val source: String? = null,
    cause: Throwable? = null,
) : Exception(
    if (source != null) "не удалось декодировать изображение «$source»: $message" else message,
    cause,
)

/** Ошибка ввода-вывода при загрузке изображения (сеть/файл). */
class ImageLoadException(
    message: String,
    val source: String? = null,
    cause: Throwable? = null,
) : Exception(
    if (source != null) "не удалось загрузить изображение «$source»: $message" else message,
    cause,
)