package dev.ggtv.capecraft.mixin

import dev.ggtv.capecraft.CapeCraftClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Перехват рендера плаща (Minecraft 1.21.10, EntityRenderState-пайплайн).
 *
 * В 1.21.10 `FeatureRenderer.render` больше не получает живую [PlayerEntity] и
 * [net.minecraft.client.render.VertexConsumerProvider] — вместо них приходят
 * [PlayerEntityRenderState] и [OrderedRenderCommandQueue]. Соответственно фичи
 * рендерят модель через `RenderCommandQueue.submitModel(...)`, а не через
 * `VertexConsumerProvider.submitModel`.
 *
 * Когда у игрока есть кастомный плащ в реестре — отменяем ванильный рендер и
 * рисуем наш текущий кадр (включая анимацию APNG/GIF/WebP) на той же модели,
 * что использует ваниль, через `submitModel` поверх прозрачного слоя.
 *
 * ## Горячий путь (рендер) — только лёгкие операции
 *
 * Рендер НИКОГДА не делает декод, деградацию, создание/upload текстуры и даже
 * texture-manager lookup. Он лишь:
 *  1. дёргает [dev.ggtv.capecraft.CapeRegistry.ensureLoading] — идемпотентный
 *     планировщик фоновой загрузки (возвращается сразу, без блокировок);
 *  2. читает готовый id текстуры из кэша.
 *
 * Пока плащ грузится в фоне — mixin НЕ отменяет ванильный рендер (без белого
 * плаща и без FPS-спайков), а подхватывает наш плащ, как только тикер создаст
 * текстуру.
 */
@Mixin(CapeFeatureRenderer::class)
abstract class CapeFeatureRendererMixin {

    @Inject(method = ["render"], at = [At("HEAD")], cancellable = true)
    fun onRender(
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        light: Int,
        state: PlayerEntityRenderState,
        limbAngle: Float,
        limbDistance: Float,
        ci: CallbackInfo,
    ) {
        // В RenderState нет UUID — добираем живого игрока мира по id сущности
        // (только для нужного нам пути; в RenderState UUID не хранится).
        val client = MinecraftClient.getInstance()
        val player = client.world?.players
            ?.firstOrNull { it.id == state.id }
            ?: return
        val uuid = player.uuidAsString

        // Идемпотентно планируем фоновую загрузку (не блокирует рендер).
        val registry = CapeCraftClient.registry
        registry.ensureLoading(uuid, player.name.string)

        // Готовой текстуры нет — плащ ещё грузится/отсутствует.
        // НЕ отменяем ванильный рендер: показываем штатный плащ.
        val texture: Identifier = registry.textureId(uuid) ?: return
        if (ci.isCancellable) ci.cancel()

        // Та же модель, что рендерит ваниль (поле `model` CapeFeatureRenderer).
        val model = (this as CapeFeatureRendererAccessor).getModel() ?: return
        // Solid-слой и outlineColor как у ванили — прозрачный слой с -1 давал
        // белый плащ из-за смешивания/отсутствия привязанной текстуры.
        val layer = RenderLayer.getEntitySolid(texture)
        val overlay = OverlayTexture.DEFAULT_UV
        matrices.push()
        queue.submitModel(model, state, matrices, layer, light, overlay, state.outlineColor, null)
        matrices.pop()
    }
}