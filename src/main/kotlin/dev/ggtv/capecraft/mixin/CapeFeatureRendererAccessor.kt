package dev.ggtv.capecraft.mixin

import net.minecraft.client.render.entity.feature.CapeFeatureRenderer
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Доступ к приватному полю `model` CapeFeatureRenderer (для отрисовки своего плаща).
 */
@Mixin(CapeFeatureRenderer::class)
interface CapeFeatureRendererAccessor {
    @Accessor("model")
    fun getModel(): BipedEntityModel<PlayerEntityRenderState>?
}
