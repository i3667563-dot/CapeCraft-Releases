package dev.ggtv.capecraft.api.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import net.minecraft.resources.Identifier

/**
 * Реестр модификаторов рендера: хранит [CapeRenderModifier] и проверяет условия.
 *
 * Потокобезопасен: модификаторы добавляются на старте (однопоточно),
 * проверяются/вызываются из рендер-потока (read-only).
 */
class CapeRenderModifierRegistry {
    private val modifiers = mutableListOf<CapeRenderModifier>()

    /** Зарегистрировать модификатор (порядок = порядок вызова). */
    @Synchronized
    fun register(modifier: CapeRenderModifier) {
        modifiers += modifier
    }

    /**
     * Применить модификаторы к контексту рендера.
     * Вызывается из mixin перед submitModel.
     *
     * @return textureId для submitModel (оригинальный или заменённый модификатором).
     */
    fun applyBefore(
        ctx: CapeRenderContext,
        defaultTexture: Identifier,
        world: WorldContext,
    ): Identifier {
        var tex = defaultTexture
        for (mod in modifiers) {
            if (!matchesCondition(mod, world)) continue
            try {
                mod.beforeRender(ctx)?.let { tex = it }
            } catch (e: Exception) {
                dev.ggtv.capecraft.CapeCraftClient.LOGGER.warn(
                    "CapeCraft: модификатор ${mod::class.simpleName} beforeRender упал: ${e.message}", e,
                )
            }
        }
        return tex
    }

    /**
     * Вызвать afterRender для всех подходящих модификаторов.
     * Вызывается из mixin после submitModel.
     */
    fun applyAfter(ctx: CapeRenderContext, world: WorldContext) {
        for (mod in modifiers) {
            if (!matchesCondition(mod, world)) continue
            try {
                mod.afterRender(ctx)
            } catch (e: Exception) {
                dev.ggtv.capecraft.CapeCraftClient.LOGGER.warn(
                    "CapeCraft: модификатор ${mod::class.simpleName} afterRender упал: ${e.message}", e,
                )
            }
        }
    }

    /** Проверить условие модификатора по состоянию мира. */
    private fun matchesCondition(mod: CapeRenderModifier, world: WorldContext): Boolean {
        val cond = mod.condition ?: return true
        return try {
            val value = world.field(cond.root, cond.field, "")
            when (value) {
                is Value.VStr -> value.s == cond.expected
                is Value.VInt -> value.i.toString() == cond.expected
                is Value.VFloat -> value.f.toString() == cond.expected
                is Value.VBool -> value.b.toString() == cond.expected
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Число зарегистрированных модификаторов. */
    val size: Int get() = modifiers.size
}
