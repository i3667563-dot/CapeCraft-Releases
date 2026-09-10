package dev.ggtv.capecraft.mixin

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.render.MinecraftWorldContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.SkinTextures
import net.minecraft.util.AssetInfo
import net.minecraft.util.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Перехват плаща (Minecraft 1.21.11, RenderCommandQueue-пайплайн).
 *
 * Как 1.21.10: ваниль сама считает физику плаща в `submitModel(model, state, ...)`
 * — рендер не отменяем, подменяем `state.skinTextures` (кап-ассет) на наш кадр.
 */
@Mixin(CapeFeatureRenderer::class)
abstract class CapeFeatureRendererMixin {

    @Inject(method = ["render"], at = [At("HEAD")])
    fun onRender(
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
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
        if (defaultTexture == original.cape()?.texturePath()) return

        val packed = state.outlineColor
        val outlineColor = intArrayOf(
            (packed shr 16) and 0xFF,
            (packed shr 8) and 0xFF,
            packed and 0xFF,
            (packed shr 24) and 0xFF,
        )
        val ctx = CapeRenderContext(
            uuid = uuid,
            textureId = defaultTexture,
            matrices = matrices,
            light = light,
            outlineColor = outlineColor,
        )

        val world = MinecraftWorldContext
        val texture = CapeApiHolder.api.renderModifiers.applyBefore(ctx, defaultTexture, world)
        if (texture == original.cape()?.texturePath()) return

        state.skinTextures = SkinTextures(
            original.body(),
            AssetInfo.TextureAssetInfo(texture, texture),
            original.elytra(),
            original.model(),
            original.secure(),
        )

        CapeApiHolder.api.renderModifiers.applyAfter(ctx, world)
    }
}