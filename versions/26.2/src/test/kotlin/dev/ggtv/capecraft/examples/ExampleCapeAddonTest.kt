package dev.ggtv.capecraft.examples

import dev.ggtv.capecraft.api.CapeApi
import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import dev.ggtv.capecraft.api.placeholder.PlaceholderContext
import dev.ggtv.capecraft.image.Frame
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.KorenConfig
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.AfterEach
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
            textureId = Identifier.fromNamespaceAndPath("test", "cape"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 255, 255, 255),
        )

        val inRain = api.renderModifiers.applyBefore(ctx, ctx.textureId, rainWorld)
        assertEquals(Identifier.fromNamespaceAndPath("example", "cape_wet"), inRain)

        val inClear = api.renderModifiers.applyBefore(ctx, ctx.textureId, clearWorld)
        assertEquals(ctx.textureId, inClear)
    }

    @Test
    fun `корень when аддона объявлен и читается`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)

        val root = api.whenConditions["example"]!!
        assertEquals("counter", root.defaultField)
        assertEquals(listOf("counter", "tag"), root.fields.map { it.name })
        assertTrue(root.fields.first { it.name == "counter" }.numeric)

        val world = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                throw CrenError.NotFound(path)

            override fun subjectEntity(): Any? = ExampleCapeAddon.ExampleState(42L, "живой")

            override fun addonField(root: String, field: String, path: String): Value =
                CapeWhenRoots.read(this, root, field, path)
        }
        val condition = Condition.parse(
            Value.VDict(
                listOf(
                    "example.counter" to Value.VStr(">40"),
                    "example.tag" to Value.VStr("живой"),
                ),
            ),
        )
        assertTrue(condition.matches(world))
        assertTrue(condition.hasSelfOnly, "корень аддона self-only по определению")
    }

    @AfterEach
    fun forgetWhenRoots() = CapeWhenRoots.clear()

    @Test
    fun `корень when аддона не читается у чужого контекста`() {
        val api = CapeApi()
        ExampleCapeAddon().register(api)
        val world = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                throw CrenError.NotFound(path)

            override fun subjectEntity(): Any? = ExampleCapeAddon.ExampleState(42L, "чужой")
        }
        val condition = Condition.parse(
            Value.VDict(listOf("example.counter" to Value.VStr(">40"))),
        )
        assertFalse(condition.matches(world))
    }
}
