package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.condition.Expected
import dev.ggtv.capecraft.condition.Op
import dev.ggtv.capecraft.condition.Predicate
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.koren.KorenConfig
import dev.ggtv.koren.WorldRoot
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

    @Test
    fun `resolveCapeOrdered follows externally-provided order`() {
        // Реалтайм-пересчёт: порядок выбирается на рендер-потоке (по миру),
        // а resolveCapeOrdered только исполняет его — без повторного мира.
        val ordered = listOf(
            Provider("night", Source.Url("https://x/night")),
            Provider("default", Source.Url("https://x/default")),
        )
        val fetch = CapeFetcher { r ->
            when ((r as Resolved.Url).url) {
                "https://x/night" -> byteArrayOf(1)
                "https://x/default" -> byteArrayOf(2)
                else -> throw FetchError("unexpected")
            }
        }
        val result = resolveCapeOrdered(ordered, ctx, "/root", fetch)
        assertEquals(1, result.bytes[0])
        assertEquals("night", result.providerName)
    }

    @Test
    fun `resolveCapeOrdered respects fallback within given order`() {
        val ordered = listOf(
            Provider("night", Source.Url("https://x/night")),
            Provider("default", Source.Url("https://x/default")),
        )
        val fetch = CapeFetcher { r ->
            if ((r as Resolved.Url).url == "https://x/night") throw FetchError("missing")
            byteArrayOf(2)
        }
        val result = resolveCapeOrdered(ordered, ctx, "/root", fetch)
        assertEquals(2, result.bytes[0])
        assertEquals("default", result.providerName)
    }
}

class ProviderLoaderTest {
    private fun crn(body: String): KorenConfig = KorenConfig.fromString(
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

    @Test
    fun `parses when and priority`() {
        val cfg = crn(
            """[
            |    {
            |        name = "snow",
            |        type = "url",
            |        url = "https://a/snow.png",
            |        when: { biome.precipitation: "snow", time.period: "night" },
            |        priority = 20
            |    },
            |    { name = "def", type = "url", url = "https://a/def.png" }
            |]""".trimMargin(),
        )
        val providers = ProviderLoader.load(cfg)
        assertEquals(2, providers.size)

        val snow = providers[0]
        assertEquals("snow", snow.name)
        assertEquals(20, snow.priority)
        val cond = snow.condition!!
        assertEquals(2, cond.predicates.size)
        assertEquals("precipitation", cond.predicates[0].field)

        val def = providers[1]
        assertEquals(null, def.condition)
        assertEquals(0, def.priority)
    }

    @Test
    fun `provider without when stays default`() {
        val cfg = crn("""[{ name = "d", type = "url", url = "https://a" }]""")
        val p = ProviderLoader.load(cfg).single()
        assertEquals(null, p.condition)
        assertEquals(0, p.priority)
    }

    /**
     * Реальный конфиг BedWarsV11 должен переживать проверку значений.
     *
     * Написано копией файла, а не ссылкой на него: тест обязан падать в репозитории
     * и на чужой машине, где инстанса нет. `$SECRET` заменён на обычный URL —
     * unset-переменная отвергается токенизатором ещё до разбора условий, и
     * проверяла бы не то. Смысл — в том, что условия здесь
     * настоящие, а не выдуманные под проверку: `dimension.type` берёт значение из
     * закрытого списка, а `location.y = "<= -20"` — числовое поле с оператором.
     * Обе формы проходят через разные ветки проверки, и потеря любой из них
     * выглядела бы как «мод сломался у пользователя на живом конфиге».
     */
    @Test
    fun `боевой конфиг BedWarsV11 грузится без ошибок`() {
        val kn = """
            |capeCraft {
            |    providers [
            |        { name = "secret", type = "url", url = "https://secret.invalid/cape.png", priority = 9 },
            |        { name = "animated", type = "json", url = "https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.json", extract = "${'$'}.cape" },
            |        { name = "hero", type = "url", url = "https://skins.ggshnikk.online/api/v3/skin-file/72.png",
            |          when = { dimension.type = "nether" }, priority = 11 },
            |        { name = "warden", type = "url", url = "https://skins.ggshnikk.online/api/v3/skin-file/73.png",
            |          when = { location.y = "<= -20" }, priority = 10 },
            |    ]
            |}
        """.trimMargin()
        val providers = ProviderLoader.load(KorenConfig.fromString(kn))
        assertEquals(4, providers.size)

        val hero = providers.first { it.name == "hero" }
        val heroCond = hero.condition!!.predicates.single()
        assertEquals(WorldRoot.DIMENSION, (heroCond as Predicate).root)
        assertEquals("type", heroCond.field)
        assertEquals(Expected.Str("nether"), heroCond.expected)

        val warden = providers.first { it.name == "warden" }
        val wardenCond = warden.condition!!.predicates.single()
        assertEquals(WorldRoot.LOCATION, (wardenCond as Predicate).root)
        assertEquals("y", wardenCond.field)
        // Отрицательный порог в глубине: «<= -20» — это Le(-20.0), а не строка.
        assertEquals(Op.Le, wardenCond.op)
        assertEquals(Expected.Num(-20.0), wardenCond.expected)
    }

    @Test
    fun `незаданная переменная не роняет конфиг целиком`() {
        // Требование: `$HOST` без переменной окружения — это пустой адрес, а не
        // падение. Раньше один такой провайдер ронял весь файл, и игрок оставался
        // без плащей вообще, хотя три других адреса были в порядке.
        val unset = mutableListOf<String>()
        val cfg = KorenConfig.fromString(
            """
            capeCraft {
                providers [
                    { name = "ok", type = "url", url = "https://a.example/c.png" },
                    { name = "broken", type = "url", url = "${'$'}NO_SUCH_HOST/c.png" },
                    { name = "ok2", type = "url", url = "https://b.example/c.png" },
                ]
            }
            """.trimIndent(),
            onUnset = { unset += it },
        )

        val providers = ProviderLoader.load(cfg)
        assertEquals(listOf("ok", "broken", "ok2"), providers.map { it.name })
        assertEquals(listOf("NO_SUCH_HOST"), unset)
    }

    @Test
    fun `пустой дефолт не считается незаданной переменной`() {
        // `${NAME:-}` — это «адреса нет», и в onUnset имя попасть не должно:
        // иначе в лог сыпалось бы предупреждение о том, чего человек не
        // забывал, а сам так и просил.
        val unset = mutableListOf<String>()
        val cfg = KorenConfig.fromString(
            """
            capeCraft {
                providers [
                    { name = "empty", type = "url", url = "${'$'}{NO_SUCH_HOST:-}/c.png" },
                ]
            }
            """.trimIndent(),
            onUnset = { unset += it },
        )

        val providers = ProviderLoader.load(cfg)
        assertEquals(listOf("empty"), providers.map { it.name })
        assertEquals(Source.Url("/c.png"), providers.single().source)
        assertEquals(emptyList<String>(), unset)
    }

    @Test
    fun `незаданная переменная без дефолта даёт пустой адрес и попадает в onUnset`() {
        val unset = mutableListOf<String>()
        val cfg = KorenConfig.fromString(
            """
            capeCraft {
                providers [
                    { name = "broken", type = "url", url = "${'$'}NO_SUCH_HOST/c.png" },
                ]
            }
            """.trimIndent(),
            onUnset = { unset += it },
        )

        val providers = ProviderLoader.load(cfg)
        assertEquals(listOf("broken"), providers.map { it.name })
        assertEquals(listOf("NO_SUCH_HOST"), unset)
    }

    @Test
    fun `имя с дефисом и подчёркиванием доезжает до адреса`() {
        val cfg = KorenConfig.fromStringWithEnv(
            """
            capeCraft {
                providers [
                    { name = "d", type = "url", url = "https://${'$'}{BASE-URL}/c.png" },
                    { name = "u", type = "url", url = "https://${'$'}BASE_URL/c.png" },
                    { name = "t", type = "url", url = "${'$'}BASE/api/cape.png" },
                ]
            }
            """.trimIndent(),
            mapOf("BASE-URL" to "d.example", "BASE_URL" to "u.example", "BASE" to "localhost:8080"),
        )

        val providers = ProviderLoader.load(cfg)
        assertEquals(Source.Url("https://d.example/c.png"), providers[0].source)
        assertEquals(Source.Url("https://u.example/c.png"), providers[1].source)
        assertEquals(Source.Url("localhost:8080/api/cape.png"), providers[2].source)
    }

    @Test
    fun `опечатка в значении роняет загрузку конфига`() {
        // Раньше такой провайдер грузился и просто не срабатывал: ошибки не
        // было нигде, плащ не появлялся, и причину нельзя было назвать.
        val cfg = crn("""[{ name = "x", type = "url", url = "https://a", when: { armor.chest: "plate" } }]""")
        val e = assertThrows(IllegalArgumentException::class.java) { ProviderLoader.load(cfg) }
        assertTrue(
            e.message!!.contains("plate"),
            "сообщение должно называть ошибочное значение: ${e.message}",
        )
    }
}
