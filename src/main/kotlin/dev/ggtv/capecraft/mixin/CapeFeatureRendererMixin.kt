package dev.ggtv.capecraft.mixin

import dev.ggtv.capecraft.CapeCraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Перехват рендера плаща.
 *
 * Когда у игрока есть кастомный плащ в реестре — отменяем стандартный рендер
 * и рисуем наш кадр (включая анимацию APNG/GIF/WebP) поверх, используя ту же
 * модель (`model` из аксессора) и `VertexConsumerProvider.submitModel` поверх
 * `RenderLayer.getEntityTranslucent`. Если плаща нет — ванильный рендер не трогаем.
 *
 * NOTE: итоговая картинка проверяется в runClient (этап 7).
 */
@Mixin(CapeFeatureRenderer::class)
abstract class CapeFeatureRendererMixin {

    @Inject(method = ["render"], at = [At("HEAD")], cancellable = true)
    fun onRender(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        entity: PlayerEntity,
        limbAngle: Float,
        limbDistance: Float,
        ci: CallbackInfo,
    ) {
        val uuid = try { entity.uuid } catch (e: Exception) { return }
        val anim = CapeCraftClient.registry.get(uuid.toString(), entity.name.string) ?: return
        if (ci.isCancellable) ci.cancel()

        // Кадр по игровому времени (анимация), регистрируем как динамическую текстуру.
        val texture: Identifier = CapeCraftClient.registry.dynamicTexture(uuid.toString(), anim) ?: return

        // Та же модель, что рендерит ваниль (поле `model` CapeFeatureRenderer).
        val model = (this as CapeFeatureRendererAccessor).getModel() ?: return
        val layer = RenderLayer.getEntityTranslucent(texture)
        val overlay = OverlayTexture.DEFAULT_UV
        // submitModel есть у VertexConsumerProvider.Immediate — фактически всегда
        // так в FeatureRenderer#render. Белый цвет (ARGB): плащ не тонируется.
        (vertexConsumers as VertexConsumerProvider.Immediate)
            .submitModel(model, entity, matrices, layer, light, overlay, -1, null)
    }
}
