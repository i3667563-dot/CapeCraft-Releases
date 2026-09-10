package dev.ggtv.capecraft.mixin

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.render.MinecraftWorldContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.SkinTextures
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Перехват плаща (Minecraft 1.21.8, EntityRenderState-пайплайн).
 *
 * Как 1.21.4: ваниль сама считает физику плаща (`copyTransforms` + `setAngles`
 * + `model.render`), поэтому рендер не отменяем — подменяем только
 * `state.skinTextures`, ваниль рисует наш кадр со всей физикой.
 */
@Mixin(CapeFeatureRenderer::class)
abstract class CapeFeatureRendererMixin {

    @Inject(method = ["render"], at = [At("HEAD")])
    fun onRender(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        state: PlayerEntityRenderState,
        limbAngle: Float,
        limbDistance: Float,
        ci: CallbackInfo,
    ) {
        val client = MinecraftClient.getInstance()
        val player = client.world?.players
            ?.firstOrNull { it.id == state.id }
            ?: return
        val uuid = player.uuidAsString

        val registry = CapeCraftClient.registry
        registry.ensureLoading(uuid, player.name.string)

        val defaultTexture: Identifier = registry.textureId(uuid) ?: return

        val original = state.skinTextures
        if (defaultTexture == original.capeTexture()) return

        val ctx = CapeRenderContext(
            uuid = uuid,
            textureId = defaultTexture,
            matrices = matrices,
            light = light,
            outlineColor = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF),
        )

        val world = MinecraftWorldContext
        val texture = CapeApiHolder.api.renderModifiers.applyBefore(ctx, defaultTexture, world)
        if (texture == original.capeTexture()) return

        state.skinTextures = SkinTextures(
            original.texture(),
            original.textureUrl(),
            texture,
            original.elytraTexture(),
            original.model(),
            original.secure(),
        )

        CapeApiHolder.api.renderModifiers.applyAfter(ctx, world)
    }
}