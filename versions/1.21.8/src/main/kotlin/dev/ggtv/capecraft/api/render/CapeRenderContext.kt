package dev.ggtv.capecraft.api.render

import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier

/**
 * Контекст рендера плаща, передаваемый модификаторам.
 *
 * Содержит всё, что модификатор может изменить:
 * - [matrices] — стек матриц (для трансформаций: вращение, масштаб, сдвиг);
 * - [outlineColor] — цвет обводки (влияет на обводку в F3);
 * - [textureId] — ID текстуры (для замены текстуры);
 * - [light] — уровень освещения.
 *
 * Модификаторы вызываются с рендер-потока (каждый кадр для каждого игрока с плащом).
 * НЕ делать тяжёлую работу (I/O, аллокации) внутри модификаторов.
 */
data class CapeRenderContext(
    val uuid: String,
    val textureId: Identifier,
    val matrices: MatrixStack,
    val light: Int,
    val outlineColor: IntArray,
)
