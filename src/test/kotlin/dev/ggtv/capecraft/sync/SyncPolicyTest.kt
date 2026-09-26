package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.api.provider.CapeValues
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Политика доверия к серверу: сервер — чужая сторона, поэтому его ответ
 * проходит проверки перед тем, как что-либо скачивать с диска клиента.
 */
class SyncPolicyTest {

    private fun url(name: String, primary: String = "https://e.com/a.png", priority: Int = 0) =
        ActiveCape(name, ActiveCape.Kind.URL, primary, priority = priority)

    private fun file(name: String, primary: String) = ActiveCape(name, ActiveCape.Kind.FILE, primary)

    @Test
    fun `http and https url providers are accepted`() {
        val res = SyncPolicy.convert(
            listOf(url("a", "https://e.com/a.png"), url("b", "http://e.com/b.png")),
            emptyList(),
            allowFileProviders = false,
        )
        assertEquals(listOf("a", "b"), res.providers.map { it.name })
        assertEquals(emptyList(), res.rejected)
    }

    @Test
    fun `non-http url schemes are dropped`() {
        for (bad in listOf("file:///etc/passwd", "jar:file:///x.jar!/a", "ftp://e.com/a.png", "data:image/png;base64,AAA")) {
            val res = SyncPolicy.convert(listOf(url("x", bad)), emptyList(), allowFileProviders = false)
            assertTrue(res.providers.isEmpty(), "прошло: $bad")
            assertEquals(1, res.rejected.size, bad)
        }
    }

    @Test
    fun `url without a host is dropped`() {
        val res = SyncPolicy.convert(listOf(url("x", "https:///a.png")), emptyList(), allowFileProviders = false)
        assertTrue(res.providers.isEmpty())
    }

    @Test
    fun `file providers are dropped unless explicitly allowed`() {
        val res = SyncPolicy.convert(
            listOf(file("f", "{root}/capes/{username}.png")),
            emptyList(),
            allowFileProviders = false,
        )
        assertTrue(res.providers.isEmpty())
        assertTrue(res.rejected.single().contains("allowFileProviders"))
    }

    @Test
    fun `allowed file provider still needs the root placeholder`() {
        val res = SyncPolicy.convert(
            listOf(file("f", "/etc/passwd"), file("g", "../../secret.png")),
            emptyList(),
            allowFileProviders = true,
        )
        assertTrue(res.providers.isEmpty())
        assertEquals(2, res.rejected.size)
    }

    @Test
    fun `file template may only interpolate username and uuid`() {
        assertTrue(SyncPolicy.isSafeFileTemplate("{root}/capes/{username}.png"))
        assertTrue(SyncPolicy.isSafeFileTemplate("{root}/capes/{uuid}.png"))
        assertFalse(SyncPolicy.isSafeFileTemplate("{root}/capes/{whatever}.png"))
        assertFalse(SyncPolicy.isSafeFileTemplate("capes/{username}.png"))
        assertFalse(SyncPolicy.isSafeFileTemplate("{root}/../{username}.png"))
        assertFalse(SyncPolicy.isSafeFileTemplate("{root}/a/../../b.png"))
    }

    @Test
    fun `isInside rejects sibling directories with a shared prefix`() {
        val root = Files.createTempDirectory("capecraft-root")
        val inside = Files.createDirectories(root.resolve("capes/a.png"))
        val outside = Files.createTempDirectory("capecraft-out")
        try {
            assertTrue(SyncPolicy.isInside(root.toString(), inside.toString()))
            assertFalse(SyncPolicy.isInside(root.toString(), outside.resolve("b.png").toString()))
            assertFalse(SyncPolicy.isInside(root.toString(), root.toString()))
            assertFalse(SyncPolicy.isInside(root.toString(), root.resolve("..").resolve("x.png").toString()))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `json provider needs a non-empty extract`() {
        val bad = ActiveCape("j", ActiveCape.Kind.JSON, "https://e.com/a.json", extract = "")
        val res = SyncPolicy.convert(listOf(bad), emptyList(), allowFileProviders = false)
        assertTrue(res.providers.isEmpty())
        assertTrue(res.rejected.single().contains("extract"))
    }

    @Test
    fun `json provider is rebuilt with its extract`() {
        val good = ActiveCape("j", ActiveCape.Kind.JSON, "https://e.com/a.json", extract = "data[0].url")
        val res = SyncPolicy.convert(listOf(good), emptyList(), allowFileProviders = false)
        val source = res.providers.single().source as Source.Json
        assertEquals("https://e.com/a.json", source.template)
        assertEquals("data[0].url", source.extract)
    }

    @Test
    fun `addon provider is taken only from the local config`() {
        val local = Provider(
            name = "kjen",
            source = Source.Url("addon://kjen"),
            addonSource = { _ -> "bytes".toByteArray() },
            values = CapeValues("kjen", "kjen", mapOf("url" to "https://kjen.dev/{username}")),
        )
        val remote = ActiveCape("kjen", ActiveCape.Kind.ADDON, "kjen", priority = 3)
        val res = SyncPolicy.convert(listOf(remote), listOf(local), allowFileProviders = false)
        val p = res.providers.single()
        assertEquals(3, p.priority, "приоритет берётся с сервера")
        assertEquals(local.addonSource, p.addonSource)
        assertEquals(local.values, p.values)
    }

    @Test
    fun `addon type unknown to the client is dropped, not guessed`() {
        val remote = ActiveCape("koren", ActiveCape.Kind.ADDON, "koren")
        val res = SyncPolicy.convert(listOf(remote), emptyList(), allowFileProviders = false)
        assertTrue(res.providers.isEmpty(), "нельзя собирать аддон-источник из выдуманных значений")
        assertTrue(res.rejected.single().contains("не зарегистрирован"))
    }

    @Test
    fun `addon entry with the right type but a different name is dropped`() {
        val local = Provider(
            name = "other",
            source = Source.Url("addon://kjen"),
            addonSource = { _ -> ByteArray(0) },
            values = CapeValues("other", "kjen", emptyMap()),
        )
        val res = SyncPolicy.convert(
            listOf(ActiveCape("kjen", ActiveCape.Kind.ADDON, "kjen")),
            listOf(local),
            allowFileProviders = false,
        )
        assertTrue(res.providers.isEmpty())
    }

    @Test
    fun `server order is preserved exactly`() {
        val remote = listOf(url("c", priority = 5), url("a", priority = 1), url("b", priority = 9))
        val res = SyncPolicy.convert(remote, emptyList(), allowFileProviders = false)
        assertEquals(listOf("c", "a", "b"), res.providers.map { it.name })
    }

    @Test
    fun `a bad provider does not take the good ones down with it`() {
        val res = SyncPolicy.convert(
            listOf(url("good"), ActiveCape("bad", ActiveCape.Kind.URL, "nope"), url("also-good")),
            emptyList(),
            allowFileProviders = false,
        )
        assertEquals(listOf("good", "also-good"), res.providers.map { it.name })
        assertEquals(1, res.rejected.size)
    }
}
