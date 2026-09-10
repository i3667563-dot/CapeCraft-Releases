package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.koren.KorenConfig
import dev.ggtv.koren.EmptyWorldContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderLoaderAddonTest {

    private val api = CapeApi()

    @BeforeEach
    fun setup() {
        CapeApiHolder.api = api
    }

    @Test
    fun `addon source type is recognized`() {
        api.sourceTypes.register(CapeSourceType("github") { values: CapeValues ->
            CapeSource { "github:${values.entries["repo"]}".toByteArray() }
        })

        val kn = """
            capeCraft {
                providers [
                    { name = "my-repo", type = "github", repo = "user/capes" }
                ]
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        val providers = ProviderLoader.load(cfg)
        assertEquals(1, providers.size)
        val p = providers[0]
        assertEquals("my-repo", p.name)
        assertNotNull(p.addonSource)
        assertEquals("github", p.values?.type)
    }

    @Test
    fun `addon source provider resolves to Addon`() {
        api.sourceTypes.register(CapeSourceType("local-mirror") { values: CapeValues ->
            CapeSource { "mirror:${values.name}".toByteArray() }
        })

        val kn = """
            capeCraft {
                providers [
                    { name = "mirror", type = "local-mirror" }
                ]
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        val providers = ProviderLoader.load(cfg)
        val p = providers[0]
        assertNotNull(p.addonSource)
        val bytes = p.addonSource!!.fetch(CapeValues("mirror", "local-mirror", emptyMap()))
        assertEquals("mirror:mirror", String(bytes))
    }

    @Test
    fun `built-in types still work`() {
        val kn = """
            capeCraft {
                providers [
                    { name = "web", type = "url", url = "http://example.com/{username}.png" }
                ]
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        val providers = ProviderLoader.load(cfg)
        val p = providers[0]
        assertNull(p.addonSource)
        assertTrue(p.source is dev.ggtv.capecraft.provider.Source.Url)
    }

    @Test
    fun `unknown type with no addon spec throws`() {
        val kn = """
            capeCraft {
                providers [
                    { name = "bad", type = "mystery" }
                ]
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLoader.load(cfg)
        }
    }

    @Test
    fun `addon entries are passed through CapeValues`() {
        api.sourceTypes.register(CapeSourceType("with-entries") { values: CapeValues ->
            CapeSource { "${values.entries["token"]}:${values.entries["path"]}".toByteArray() }
        })

        val kn = """
            capeCraft {
                providers [
                    { name = "tok", type = "with-entries", token = "abc", path = "/data" }
                ]
            }
        """.trimIndent()
        val cfg = KorenConfig.fromString(kn)
        val providers = ProviderLoader.load(cfg)
        val p = providers[0]
        assertNotNull(p.addonSource)
        val bytes = p.addonSource!!.fetch(CapeValues("tok", "with-entries", mapOf("token" to "abc", "path" to "/data")))
        assertEquals("abc:/data", String(bytes))
    }
}
