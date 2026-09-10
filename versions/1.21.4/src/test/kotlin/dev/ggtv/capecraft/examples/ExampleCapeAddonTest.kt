package dev.ggtv.capecraft.examples

import dev.ggtv.capecraft.api.CapeApi
import dev.ggtv.capecraft.api.placeholder.PlaceholderContext
import dev.ggtv.capecraft.image.Frame
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.KorenConfig
import net.minecraft.util.Identifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Интеграционный тест примера аддона: регистрирует [ExampleCapeAddon] на свежий
 * [CapeApi] и проверяет, что каждая регистрация реально работает end-to-end.
 */
class ExampleCapeAddonTest {

    @Test
    fun `example addon registers everything`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        assertNotNull(api.sourceTypes["example"])
        assertEquals(1, api.placeholders.names().size)
        assertEquals(listOf("example"), api.decoders.ids())
        assertEquals(listOf("example-addon"), api.config.ids())
        assertEquals(1, api.renderModifiers.size)
    }

    @Test
    fun `provider source returns example bytes`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val source = api.sourceTypes["example"]!!.source(
            dev.ggtv.capecraft.api.provider.CapeValues(
                name = "demo",
                type = "example",
                entries = mapOf("color" to "00ff00", "width" to 32),
            ),
        )
        val bytes = source.fetch(dev.ggtv.capecraft.api.provider.CapeValues("demo", "example", mapOf()))
        assertArrayEquals(
            byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 0x00, 32),
            bytes,
        )
    }

    @Test
    fun `decoder recognizes and decodes example format`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val spec = api.decoders.decoderFor(byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 0x10, 16))
        assertNotNull(spec)
        val image = spec!!.decoder.decode(
            byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 0x10, 16),
            "example:test",
        )
        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertEquals(1, image.frameCount)
        assertEquals(0xFF101010.toInt(), image.frames[0].pixels[0])

        assertNull(api.decoders.decoderFor("not example".toByteArray()))
    }

    @Test
    fun `placeholder resolves from uuid`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val spec = api.placeholders["player-color"]!!
        val color = spec.resolver.resolve(PlaceholderContext("Player", "deadbeef", "Player", "example"))
        assertEquals("%06x".format("deadbeef".hashCode() and 0xFFFFFF), color)
    }

    @Test
    fun `config schema loads from kn section`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val kn = """
            capeCraft {
                addons {
                    example-addon {
                        brightness = 200
                    }
                }
            }
        """.trimIndent()
        api.config.loadFrom(KorenConfig.fromString(kn))

        val cfg = api.config["example-addon"]!!
        assertEquals(200L, cfg.getLong("brightness"))
        assertEquals("#000000", cfg.getString("background"))
    }

    @Test
    fun `render modifier honors rain condition`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val rainWorld = object : dev.ggtv.koren.WorldContext {
            override fun field(root: dev.ggtv.koren.WorldRoot, field: String, path: String): dev.ggtv.kjen.Value =
                if (root == dev.ggtv.koren.WorldRoot.WEATHER && field == "condition") dev.ggtv.kjen.Value.VStr("rain")
                else dev.ggtv.kjen.Value.VStr("unknown")
        }
        val clearWorld = object : dev.ggtv.koren.WorldContext {
            override fun field(root: dev.ggtv.koren.WorldRoot, field: String, path: String): dev.ggtv.kjen.Value =
                if (root == dev.ggtv.koren.WorldRoot.WEATHER && field == "condition") dev.ggtv.kjen.Value.VStr("clear")
                else dev.ggtv.kjen.Value.VStr("unknown")
        }

        val ctx = dev.ggtv.capecraft.api.render.CapeRenderContext(
            uuid = "test",
            textureId = Identifier.of("test", "cape"),
            matrices = net.minecraft.client.util.math.MatrixStack(),
            light = 15,
            outlineColor = intArrayOf(255, 255, 255, 255),
        )

        val inRain = api.renderModifiers.applyBefore(ctx, ctx.textureId, rainWorld)
        assertEquals(Identifier.of("example", "cape_wet"), inRain)

        val inClear = api.renderModifiers.applyBefore(ctx, ctx.textureId, clearWorld)
        assertEquals(ctx.textureId, inClear)
    }
}