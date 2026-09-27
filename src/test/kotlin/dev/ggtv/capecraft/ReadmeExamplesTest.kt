package dev.ggtv.capecraft

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Примеры из README должны работать. Документация, которую нельзя
 * проверить, разъезжается с кодом на первом же релизе, а читателю остаётся
 * только верить, что текст верный.
 */
class ReadmeExamplesTest {

    /** Блок `when` из раздела «Условия `when`». */
    private val whenExample = """
        capeCraft {
            providers [
                { name = "rain",  type = "url", url = "https://example.com/rain.png",
                  when = { weather: "rain" } },

                { name = "night", type = "url", url = "https://example.com/night.png",
                  when = { time.period: "night", dimension: "overworld" } },

                { name = "deep",  type = "url", url = "https://example.com/deep.png",
                  when = { location.y: ">-20" }, priority = 10 },

                { name = "default", type = "url", url = "https://example.com/{username}.png" }
            ]
        }
    """.trimIndent()

    /** Таблица короткой записи из README целиком. */
    private val shortForms = listOf(
        "biome: \"snowy\"" to "biome.precipitation: \"snow\"",
        "biome: \"snow\"" to "biome.precipitation: \"snow\"",
        "biome: \"frozen\"" to "biome.precipitation: \"snow\"",
        "biome: \"rain\"" to "biome.precipitation: \"rain\"",
        "biome: \"rainy\"" to "biome.precipitation: \"rain\"",
        "weather: \"clear\"" to "weather.condition: \"clear\"",
        "weather: \"fair\"" to "weather.condition: \"clear\"",
        "weather: \"rain\"" to "weather.condition: \"rain\"",
        "weather: \"rainy\"" to "weather.condition: \"rain\"",
        "weather: \"thunder\"" to "weather.condition: \"thunder\"",
        "weather: \"storm\"" to "weather.condition: \"thunder\"",
        "time: \"day\"" to "time.period: \"day\"",
        "time: \"dawn\"" to "time.period: \"sunrise\"",
        "time: \"sunrise\"" to "time.period: \"sunrise\"",
        "time: \"dusk\"" to "time.period: \"sunset\"",
        "time: \"sunset\"" to "time.period: \"sunset\"",
        "time: \"night\"" to "time.period: \"night\"",
        "time: \"midnight\"" to "time.period: \"night\"",
        "dimension: \"overworld\"" to "dimension.type: \"overworld\"",
        "dimension: \"nether\"" to "dimension.type: \"nether\"",
        "dimension: \"the_nether\"" to "dimension.type: \"nether\"",
        "dimension: \"end\"" to "dimension.type: \"end\"",
        "dimension: \"the_end\"" to "dimension.type: \"end\"",
    )

    private fun kn(text: String) = KorenConfig.fromString(text, EmptyWorldContext)

    @Test
    fun `when example from README loads with all four providers`() {
        val providers = ProviderLoader.load(kn(whenExample))
        assertEquals(4, providers.size)

        assertNotNull(providers[0].condition)
        assertEquals("rain", providers[0].condition!!.predicates.single().expected.let {
            (it as dev.ggtv.capecraft.condition.Expected.Str).s
        })

        // Два предиката через И: ночь И обычный мир.
        assertEquals(2, providers[1].condition!!.predicates.size)

        // `location.y: ">-20"` — числовой оператор, priority отдельно.
        assertEquals(10, providers[2].priority)

        // У дефолтного провайдера условий нет вовсе.
        assertEquals(null, providers[3].condition)
    }

    @Test
    fun `every short form from the README table expands as documented`() {
        for ((short, expanded) in shortForms) {
            val predicate = Condition.parse(
                dev.ggtv.kjen.Value.VDict(
                    listOf(
                        kotlin.Pair(
                            short.substringBefore(':'),
                            dev.ggtv.kjen.Value.VStr(
                                short.substringAfter(": ").removeSurrounding("\""),
                            ),
                        ),
                    ),
                ),
            ).predicates.single()

            val want = Condition.parse(
                dev.ggtv.kjen.Value.VDict(
                    listOf(
                        kotlin.Pair(
                            expanded.substringBefore(':'),
                            dev.ggtv.kjen.Value.VStr(
                                expanded.substringAfter(": ").removeSurrounding("\""),
                            ),
                        ),
                    ),
                ),
            ).predicates.single()

            assertEquals(want, predicate, "короткая запись «$short» должна разворачиваться в «$expanded»")
        }
    }

    @Test
    fun `dollar example from README resolves with defaults`() {
        val env = mapOf(
            "CAPE_HOST" to "capes.example.com",
        )
        val cfg = KorenConfig.fromStringWithEnv(
            """
            capeCraft {
                providers [
                    { name = "cdn", type = "url",
                      url = "https://${'$'}{CAPE_HOST:-cdn.example.com}/capes/{username}.png" },

                    { name = "api", type = "json",
                      url = "https://${'$'}{CAPE_HOST}/cape?u={username}", extract = "${'$'}.data.url" }
                ]
            }
            """.trimIndent(),
            env,
        )
        val providers = ProviderLoader.load(cfg)
        assertEquals(2, providers.size)
        assertTrue(providers[0].source.toString().contains("capes.example.com/capes/"))
        assertTrue(providers[1].source.toString().contains("capes.example.com/cape?u="))
    }

    @Test
    fun `compound bare substitution from README parses`() {
        val cfg = KorenConfig.fromStringWithEnv(
            "url = ${'$'}{HOST}/capes/${'$'}{PORT}.png",
            mapOf("HOST" to "cdn.example.com", "PORT" to "8443"),
        )
        assertEquals("cdn.example.com/capes/8443.png", cfg.getStr("url"))
    }

    @Test
    fun `README claims bare substitution before text is not a value`() {
        // README говорит: без кавычек составное значение, только если начинается
        // с `$`. Такое утверждение проверяем — иначе в документе врём.
        assertThrowsParse {
            KorenConfig.fromStringWithEnv("v = a${'$'}{HOST}b", mapOf("HOST" to "x"))
        }
        // С кавычками — работает, и именно так это и написано в README.
        val ok = KorenConfig.fromStringWithEnv("v = \"a${'$'}{HOST}b\"", mapOf("HOST" to "x"))
        assertEquals("axb", ok.getStr("v"))
    }

    @Test
    fun `limits and serverSync blocks from README parse`() {
        val limits = kn(
            """
            limits {
                maxPixelsPerFrame = 4000000
                maxFrames         = 100
                maxBytesPerCape   = 67108864
                maxBytesTotal     = 134217728
            }
            """.trimIndent(),
        )
        assertEquals(100, limits.getInt("limits.maxFrames"))
        assertEquals(134217728, limits.getInt("limits.maxBytesTotal"))

        val sync = kn(
            """
            serverSync {
                enabled              = true
                shareLocalProviders  = true
                allowForeignUrls     = true
            }
            """.trimIndent(),
        )
        assertTrue(sync.getBool("serverSync.enabled"))
    }

    private fun assertThrowsParse(block: () -> Unit) {
        try {
            block()
            throw AssertionError("ожидалась ошибка разбора конфига")
        } catch (e: dev.ggtv.kjen.CrenError) {
            // именно ошибка разбора, а не что-то другое
            assertTrue(e is dev.ggtv.kjen.CrenError.Parse, "ожидался CrenError.Parse, а пришло: $e")
        }
    }
}
