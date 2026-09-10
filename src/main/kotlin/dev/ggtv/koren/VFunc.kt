package dev.ggtv.koren

import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Value

/**
 * Вызов функции: `clamp(maxFrames, 0, 100)`, `hash(uuid)`.
 *
 * Аргументы хранятся "сырыми" (значения, ссылки или вложенные функции)
 * и вычисляются в [KorenResolver] при каждом разрешении — поэтому
 * переоценка динамическая, без пересборки AST.
 */
data class VFunc(
    val name: String,
    val args: List<Value>,
    val span: Span,
) : Value {
    override val kind get() = "func"
}