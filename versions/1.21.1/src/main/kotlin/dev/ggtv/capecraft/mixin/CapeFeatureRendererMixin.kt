package dev.ggtv.capecraft.mixin

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.render.MinecraftWorldContext
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.util.SkinTextures
import net.minecraft.util.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/**
 * Перехват текстуры плаща (Minecraft 1.21.1, классический пайплайн).
 *
 * В 1.21.1 физика плаща (качание, гравитация, попутный ветер) вычисляется
 * внутри `CapeFeatureRenderer.render` и рисуется через `renderCape`. Поэтому
 * вместо отмены рендера подменяем только текстуру — `@Redirect` на
 * `AbstractClientPlayerEntity.getSkinTextures()`. Ваниль сама отрисует плащ
 * с правильной физикой, но с нашим текущим кадром.
 *
 * `getSkinTextures()` вызывается последним в цепочке проверок (после
 * невидимости, части CAPE и элитры), так что дублировать их не нужно.
 */
@Mixin(net.minecraft.client.render.entity.feature.CapeFeatureRenderer::class)
abstract class CapeFeatureRendererMixin {

    @Redirect(
        method = ["render"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getSkinTextures()Lnet/minecraft/client/util/SkinTextures;",
        )],
    )
    fun replaceCapeSkinTextures(entity: AbstractClientPlayerEntity): SkinTextures {
        val original = entity.skinTextures
        val uuid = entity.uuidAsString

        val registry = CapeCraftClient.registry
        registry.ensureLoading(uuid, entity.name.string)

        val defaultTexture: Identifier = registry.textureId(uuid) ?: return original
        if (defaultTexture == original.capeTexture()) return original

        val ctx = CapeRenderContext(
            uuid = uuid,
            textureId = defaultTexture,
            matrices = net.minecraft.client.util.math.MatrixStack(),
            light = 0,
            outlineColor = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF),
        )

        val world = MinecraftWorldContext
        val finalTexture = CapeApiHolder.api.renderModifiers.applyBefore(ctx, defaultTexture, world)
        if (finalTexture == original.capeTexture()) return original

        CapeApiHolder.api.renderModifiers.applyAfter(ctx, world)

        return SkinTextures(
            original.texture(),
            original.textureUrl(),
            finalTexture,
            original.elytraTexture(),
            original.model(),
            original.secure(),
        )
    }
}