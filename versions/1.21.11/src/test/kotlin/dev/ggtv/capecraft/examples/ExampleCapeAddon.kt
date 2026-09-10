package dev.ggtv.capecraft.examples

import dev.ggtv.capecraft.api.CapeAddon
import dev.ggtv.capecraft.api.CapeApi
import dev.ggtv.capecraft.api.CapeDecoderSpec
import dev.ggtv.capecraft.api.CapePlaceholderSpec
import dev.ggtv.capecraft.api.CapeSourceType
import dev.ggtv.capecraft.api.config.CapeAddonConfig
import dev.ggtv.capecraft.api.event.CapeEvent
import dev.ggtv.capecraft.api.image.CapeDecoder
import dev.ggtv.capecraft.api.placeholder.CapePlaceholder
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.render.CapeCondition
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.api.render.CapeRenderModifier
import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.Frame
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldRoot
import net.minecraft.util.Identifier

/**
 * Демонстрационный аддон: показывает все возможности addon API.
 *
 * Реальный аддон объявил бы этот класс в своём `fabric.mod.json`:
 * ```
 * {
 *   "entrypoints": {
 *     "capecraft:addons": ["com.example.MyAddon"]
 *   }
 * }
 * ```
 *
 * Пример регистрирует:
 *  - провайдер `type = "example"` (байты из память-массива, в реальности — HTTP);
 *  - декодер формата `example` (сигнатура "EX" в начале байтов);
 *  - плейсхолдер `{player-color}`;
 *  - схему конфига `capeCraft.addons.example-addon { ... }`;
 *  - модификатор рендера (подмена текстуры в дождь, условие KoreN);
 *  - слушатель события загрузки плаща.
 */
class ExampleCapeAddon : CapeAddon {

    override fun register(api: CapeApi) {
        registerProvider(api)
        registerDecoder(api)
        registerPlaceholder(api)
        registerConfig(api)
        registerRenderModifier(api)
        subscribeToEvents(api)
    }

    /** Провайдер: `type = "example"` в конфиге провайдеров. */
    private fun registerProvider(api: CapeApi) {
        api.sourceTypes.register(CapeSourceType("example") { values ->
            CapeSource { data ->
                val color = values.entries["color"] as? String ?: "ff0000"
                val width = (values.entries["width"] as? Number)?.toInt()?.coerceAtMost(64) ?: 16
                byteArrayOf(
                    'E'.code.toByte(), 'X'.code.toByte(),
                    color.take(6).padEnd(6, '0').toInt(16).toByte(),
                    width.toByte(),
                )
            }
        })
    }

    /** Декодер: распознаёт наш формат по сигнатуре "EX". */
    private fun registerDecoder(api: CapeApi) {
        api.decoders.register(CapeDecoderSpec(
            id = "example",
            detect = { data -> data.size >= 2 && data[0] == 'E'.code.toByte() && data[1] == 'X'.code.toByte() },
            decoder = CapeDecoder { data, source ->
                if (data.size < 3) throw ImageDecodeException("example: байтов мало", source)
                val color = data[2].toInt() and 0xFF
                val argb = 0xFF000000.toInt() or (color shl 16) or (color shl 8) or color
                val width = (data.getOrNull(3)?.toInt() ?: 16).coerceAtMost(64)
                val px = IntArray(width * width) { argb }
                AnimatedImage(width, width, listOf(Frame(px, 1_000)))
            },
        ))
    }

    /** Плейсхолдер: `{player-color}` подставляется в шаблоны URL/путей. */
    private fun registerPlaceholder(api: CapeApi) {
        api.placeholders.register(CapePlaceholderSpec(
            name = "player-color",
            resolver = CapePlaceholder { ctx ->
                // Хэш игрока (детерминированно) → цвет в "rrggbb".
                val hash = ctx.uuid.hashCode() and 0xFFFFFF
                "%06x".format(hash)
            },
        ))
    }

    /** Конфиг: `capeCraft.addons.example-addon { ... }`. */
    private fun registerConfig(api: CapeApi) {
        api.config.register(CapeAddonConfig(
            addonId = "example-addon",
            defaults = mapOf(
                "background" to Value.VStr("#000000"),
                "brightness" to Value.VInt(100),
            ),
        ))
    }

    /** Модификатор рендера: в дождь подменяет текстуру на «мокрую». */
    private fun registerRenderModifier(api: CapeApi) {
        api.renderModifiers.register(object : CapeRenderModifier {
            override val condition = CapeCondition(
                root = WorldRoot.WEATHER,
                field = "condition",
                expected = "rain",
            )
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                return Identifier.of("example", "cape_wet")
            }
        })
    }

    /** События: логируем загрузку плаща. */
    private fun subscribeToEvents(api: CapeApi) {
        api.events.on(CapeEvent.Type.CAPE_LOADED) { event ->
            if (event is CapeEvent.CapeLoaded) {
                println("example-addon: плащ загружен для ${event.uuid}")
            }
        }
    }
}