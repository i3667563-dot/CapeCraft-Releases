package dev.ggtv.capecraft.api.render

import net.minecraft.util.Identifier

/**
 * Модификатор рендера плаща: аддон добавляет эффекты (тонирование, вращение,
 * замена текстуры) или применяет условие KoreN для переключения стилей.
 *
 * Методы вызываются с рендер-потока каждый кадр для каждого игрока с плащом.
 * Не бросать исключения — они ловятся и логируются модом.
 */
interface CapeRenderModifier {
    /**
     * Необязательное условие KoreN: модификатор применяется только когда
     * условие выполняется на живом мире. Null = всегда применять.
     *
     * Пример: `CapeCondition(root = WorldRoot.WEATHER, field = "condition", expected = "rain")`
     * применит модификатор только в дождь.
     */
    val condition: CapeCondition? get() = null

    /**
     * Вызывается ДО submitModel. Здесь можно:
     * - трансформировать [ctx.matrices] (push/pop, rotate, scale, translate);
     * - заменить [ctx.textureId] (вернуть другой Identifier);
     * - изменить [ctx.outlineColor].
     *
     * @return заменённый textureId (null = оставить оригинальный).
     */
    fun beforeRender(ctx: CapeRenderContext): Identifier? = null

    /**
     * Вызывается ПОСЛЕ submitModel. Здесь можно:
     * - добавить дополнительные примитивы в очередь;
     * - восстановить трансформации матриц.
     *
     * По умолчанию ничего не делает.
     */
    fun afterRender(ctx: CapeRenderContext) {}
}

/**
 * Простое условие для модификатора: `root.field == expected`.
 *
 * Использует тот же WorldContext, что и условия провайдеров (`when`).
 * Пример:
 * ```
 * CapeCondition(root = WorldRoot.WEATHER, field = "condition", expected = "rain")
 * // Модификатор применится когда weather.condition == "rain"
 * ```
 */
data class CapeCondition(
    val root: dev.ggtv.koren.WorldRoot,
    val field: String,
    val expected: String,
)
