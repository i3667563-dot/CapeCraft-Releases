package dev.ggtv.capecraft.api.placeholder

/**
 * Резолвер плейсхолдера: возвращает строку для текущего контекста.
 * Аналог `{name}` → значение: аддон решает сам, как отдать (достать UUID,
 * имя, случайное значение и т.п.).
 */
fun interface CapePlaceholder {
    fun resolve(context: PlaceholderContext): String
}

/**
 * Данные текущего рендера шаблона, доступные аддон-плейсхолдеру.
 * Поля — те же, что во встроенных плейсхолдерах.
 */
data class PlaceholderContext(
    val username: String,
    val uuid: String,
    val name: String,
    val root: String,
)