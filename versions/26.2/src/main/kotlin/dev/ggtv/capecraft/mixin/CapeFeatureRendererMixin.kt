package dev.ggtv.capecraft.mixin

import com.mojang.blaze3d.vertex.PoseStack
import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.render.MinecraftWorldContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.layers.CapeLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Перехват плаща (Minecraft 26.2, submit-пайплайн).
 *
 * В 26.2 ваниль в `CapeLayer.submit` рендерит через
 * `SubmitNodeCollector.submitModel(model, state, ...)` — физику плаща она берёт
 * из state сама. Поэтому рендер НЕ отменяем: подменяем только `state.skin`
 * (кап-ассет) на копию с нашим кадром, и ваниль рисует его со всей физикой.
 *
 * Для игроков без кастомного плаща state не трогаем.
 */
@Mixin(CapeLayer::class)
abstract class CapeFeatureRendererMixin {

    @Inject(
        method = ["submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V"],
        at = [At("HEAD")],
    )
    fun onRender(
        matrices: PoseStack,
        collector: SubmitNodeCollector,
        light: Int,
        state: AvatarRenderState,
        limbAngle: Float,
        limbDistance: Float,
        ci: CallbackInfo,
    ) {
        val client = Minecraft.getInstance()
        val player = client.level?.players()
            ?.firstOrNull { it.id == state.id }
            ?: return
        val uuid = player.getStringUUID()

        val registry = CapeCraftClient.registry
        registry.ensureLoading(uuid, player.getName().getString())

        val defaultTexture: Identifier = registry.textureId(uuid) ?: return

        val original = state.skin
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

        state.skin = PlayerSkin(
            original.body(),
            ClientAsset.DownloadedTexture(texture, ""),
            original.elytra(),
            original.model(),
            original.secure(),
        )

        CapeApiHolder.api.renderModifiers.applyAfter(ctx, world)
    }
}