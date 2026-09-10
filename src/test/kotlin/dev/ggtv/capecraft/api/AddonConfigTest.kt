package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.config.AddonConfig
import dev.ggtv.capecraft.api.config.CapeAddonConfig
import dev.ggtv.capecraft.api.config.CapeAddonConfigRegistry
import dev.ggtv.capecraft.api.render.CapeCondition
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.api.render.CapeRenderModifier
import dev.ggtv.capecraft.api.render.CapeRenderModifierRegistry
import dev.ggtv.kjen.Value
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.KorenConfig
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class AddonConfigTest {

    private lateinit var registry: CapeAddonConfigRegistry

    @BeforeEach
    fun setup() {
        registry = CapeAddonConfigRegistry()
    }

    @Test
    fun `register and load addon config`() {
        registry.register(CapeAddonConfig(
            addonId = "my-addon",
            defaults = mapOf(
                "apiKey" to Value.VStr("default-key"),
                "refreshMs" to Value.VInt(30000),
            ),
        ))

        val kn = """
            capeCraft {
                addons {
                    my-addon {
                        apiKey = "real-key"
                        refreshMs = 60000
                    }
                }
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        registry.loadFrom(cfg)

        val addonCfg = registry["my-addon"]!!
        assertEquals("real-key", addonCfg.getString("apiKey"))
        assertEquals(60000L, addonCfg.getLong("refreshMs"))
    }

    @Test
    fun `missing section uses defaults`() {
        registry.register(CapeAddonConfig(
            addonId = "my-addon",
            defaults = mapOf(
                "apiKey" to Value.VStr("default-key"),
                "enabled" to Value.VBool(true),
            ),
        ))

        val kn = """
            capeCraft {
                providers []
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        registry.loadFrom(cfg)

        val addonCfg = registry["my-addon"]!!
        assertEquals("default-key", addonCfg.getString("apiKey"))
        assertEquals(true, addonCfg.getBool("enabled"))
    }

    @Test
    fun `getDouble from int default`() {
        registry.register(CapeAddonConfig(
            addonId = "my-addon",
            defaults = mapOf("scale" to Value.VInt(2)),
        ))

        val kn = """
            capeCraft {
                providers []
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        registry.loadFrom(cfg)

        val addonCfg = registry["my-addon"]!!
        assertEquals(2.0, addonCfg.getDouble("scale"))
    }

    @Test
    fun `keys returns both defaults and config keys`() {
        registry.register(CapeAddonConfig(
            addonId = "my-addon",
            defaults = mapOf("a" to Value.VStr("1")),
        ))

        val kn = """
            capeCraft {
                addons {
                    my-addon {
                        b = "2"
                    }
                }
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        registry.loadFrom(cfg)

        val keys = registry["my-addon"]!!.keys()
        assertTrue(keys.contains("a"))
        assertTrue(keys.contains("b"))
    }

    @Test
    fun `unknown addon returns null`() {
        assertNull(registry["unknown"])
    }
}
