package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.cren.CrenConfig
import dev.ggtv.capecraft.schema.Placeholders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProviderTest {
    private val ctx = Placeholders.Context(username = "Steve", uuid = "069a79f444e94726a5befca90e38aaf5", name = "x")
    private val root = "/tmp/capecraft"

    @Test
    fun `url provider resolves placeholders`() {
        val p = Provider("t", Source.Url("https://ex.com/{username}/{uuid}.png"))
        assertEquals(
            Resolved.Url("https://ex.com/Steve/069a79f444e94726a5befca90e38aaf5.png"),
            p.resolve(ctx, root),
        )
    }

    @Test
    fun `file provider resolves with root`() {
        val p = Provider("l", Source.File("{root}/capes/{uuid}.png"))
        assertEquals(
            Resolved.File("/tmp/capecraft/capes/069a79f444e94726a5befca90e38aaf5.png"),
            p.resolve(ctx, root),
        )
    }

    @Test
    fun `json provider keeps extract`() {
        val p = Provider("a", Source.Json("https://ex.com/api?user={username}", "$.data.cape_url"))
        assertEquals(
            Resolved.Json("https://ex.com/api?user=Steve", "$.data.cape_url"),
            p.resolve(ctx, root),
        )
    }
}

class FileFetcherTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads file bytes`() {
        val f = tmp.resolve("cape.png")
        Files.write(f, byteArrayOf(1, 2, 3, 4))
        val bytes = FileFetcher().fetch(Resolved.File(f.toString()))
        assertEquals(4, bytes.size)
        assertEquals(1, bytes[0])
    }

    @Test
    fun `missing file fails`() {
        assertThrows(FetchError::class.java) {
            FileFetcher().fetch(Resolved.File(tmp.resolve("nope.png").toString()))
        }
    }

    @Test
    fun `directory fails`() {
        assertThrows(FetchError::class.java) {
            FileFetcher().fetch(Resolved.File(tmp.toString()))
        }
    }
}

class ResolveCapeTest {
    private val ctx = Placeholders.Context(username = "Steve", uuid = "u", name = "x")

    @Test
    fun `returns bytes from first provider`() {
        val providers = listOf(
            Provider("a", Source.Url("https://a/{username}")),
            Provider("b", Source.Url("https://b/{username}")),
        )
        val fetch = CapeFetcher { r ->
            when ((r as Resolved.Url).url) {
                "https://a/Steve" -> byteArrayOf(1)
                "https://b/Steve" -> byteArrayOf(2)
                else -> throw FetchError("unexpected")
            }
        }
        assertEquals(1, resolveCape(providers, ctx, "/root", fetch)[0])
    }

    @Test
    fun `falls back to next on failure`() {
        val order = mutableListOf<String>()
        val providers = listOf(
            Provider("a", Source.Url("https://a")),
            Provider("b", Source.Url("https://b")),
        )
        val fetch = CapeFetcher { r ->
            order += (r as Resolved.Url).url
            if (r.url == "https://a") throw FetchError("down")
            byteArrayOf(9)
        }
        assertEquals(9, resolveCape(providers, ctx, "/root", fetch)[0])
        assertEquals(listOf("https://a", "https://b"), order)
    }

    @Test
    fun `all failing throws with summary`() {
        val providers = listOf(Provider("a", Source.Url("https://a")), Provider("b", Source.Url("https://b")))
        val fetch = CapeFetcher { throw FetchError("boom") }
        val e = assertThrows(FetchError::class.java) { resolveCape(providers, ctx, "/root", fetch) }
        assertTrue(e.message!!.contains("a") && e.message!!.contains("b"))
    }
}

class ProviderLoaderTest {
    private fun crn(body: String): CrenConfig = CrenConfig.fromString(
        """capeCraft {
            |    providers $body
            |}""".trimMargin(),
    )

    @Test
    fun `parses url file json providers preserving order`() {
        val cfg = crn(
            """[
            |    { name = "trusted", type = "url",  url = "https://a/{username}.png" },
            |    { name = "local",   type = "file", path = "{root}/capes/{uuid}.png" },
            |    { name = "api",     type = "json", url = "https://api/x", extract = "$.data.u" }
            |]""".trimMargin(),
        )
        val providers = ProviderLoader.load(cfg)
        assertEquals(3, providers.size)
        assertEquals("trusted", providers[0].name)
        assertEquals(Source.Url("https://a/{username}.png"), providers[0].source)
        assertEquals(Source.File("{root}/capes/{uuid}.png"), providers[1].source)
        assertEquals(Source.Json("https://api/x", "$.data.u"), providers[2].source)
    }

    @Test
    fun `empty providers yields empty list`() {
        val cfg = crn("[]")
        assertTrue(ProviderLoader.load(cfg).isEmpty())
    }

    @Test
    fun `unknown type fails`() {
        val cfg = crn("""[{ name = "x", type = "ftp", url = "..." }]""")
        assertThrows(IllegalArgumentException::class.java) { ProviderLoader.load(cfg) }
    }

    @Test
    fun `json provider without extract fails`() {
        val cfg = crn("""[{ name = "x", type = "json", url = "https://a" }]""")
        assertThrows(IllegalArgumentException::class.java) { ProviderLoader.load(cfg) }
    }
}
