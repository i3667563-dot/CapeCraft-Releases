package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.FakeWorld
import dev.ggtv.capecraft.condition.dictOf
import dev.ggtv.capecraft.condition.str
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.schema.Placeholders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals as assertEqualsKt

/**
 * Регресс: URL-шаблоны с плейсхолдерами отбрасывались сервером как битые.
 *
 * `ActiveCape.isHttpUrl` проверял ШАБЛОН через `URI.create`, а фигурные
 * скобки в URI недопустимы:
 *
 * ```
 * Illegal character in path at index 52:
 *   https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png
 * ```
 *
 * Из-за этого `ServerCapeCatalog.responseFor` выбрасывал провайдер, и в
 * интегрированном сервере лог показывал
 * `отдано 0 провайдеров (обрезано 0, отброшено битых 1)`, а в игре —
 * `Провайдеров: 0 (набор с сервера)`. Ломались ВСЕ url/json-провайдеры
 * с `{username}`/`{uuid}`, включая дефолтный конфиг мода.
 *
 * Плейсхолдеры подставляются только при загрузке (Placeholders.render),
 * поэтому валидировать надо шаблон.
 */
class ActiveCapeUrlTemplateTest {

    private val day = FakeWorld(mapOf("time.period" to str("day")))

    @Test
    @DisplayName("URL с {username} проходит валидацию — это и был баг")
    fun placeholderUrlIsAccepted() {
        assertTrue(
            ActiveCape.isHttpUrl("https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png"),
        )
        assertTrue(ActiveCape.isHttpUrl("https://example.com/capes/{username}.png"))
        assertTrue(ActiveCape.isHttpUrl("https://example.com/capes/{uuid}.png"))
        assertTrue(ActiveCape.isHttpUrl("https://example.com/{addonParam}/cape.png"))
        assertTrue(ActiveCape.isHttpUrl("http://example.com/capes/{username}.png?x={uuid}"))
    }

    @Test
    @DisplayName("плейсхолдерный провайдер доходит до ответа сервера")
    fun placeholderProviderSurvivesToResponse() {
        val providers = listOf(
            Provider(
                "ggshnikk",
                Source.Url("https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png"),
            ),
        )
        val body = ServerCapeCatalog(providers).responseFor(day)

        assertEquals(0, body.droppedInvalid, "шаблонный URL нельзя отбрасывать")
        assertEquals(1, body.providers.size, "провайдер должен уйти клиенту")
        assertEquals("ggshnikk", body.providers[0].name)
        assertEquals(
            "https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png",
            body.providers[0].primary,
        )
    }

    @Test
    @DisplayName("дефолтный конфиг мода тоже проходит (там тоже {username})")
    fun defaultConfigUrlPasses() {
        // Ровно то, что пишет CapeConfig.writeDefault().
        assertTrue(ActiveCape.isHttpUrl("https://example.com/capes/{username}.png"))
        val body = ServerCapeCatalog(
            listOf(Provider("example", Source.Url("https://example.com/capes/{username}.png"))),
        ).responseFor(day)
        assertEquals(0, body.droppedInvalid)
        assertEquals(1, body.providers.size)
    }

    @Test
    @DisplayName("валидация не ослабла: мусор и не-http по-прежнему отвергаются")
    fun validationStillRejectsGarbage() {
        // Безопасность важнее поддержки шаблонов: клиент по ссылке сервера
        // не должен уметь прочитать file:/ftp:/не-ссылку.
        assertFalse(ActiveCape.isHttpUrl("не-ссылка"))
        assertFalse(ActiveCape.isHttpUrl(""))
        assertFalse(ActiveCape.isHttpUrl("ftp://e.com/{username}.png"))
        assertFalse(ActiveCape.isHttpUrl("file:///etc/{username}.passwd"))
        assertFalse(ActiveCape.isHttpUrl("https:///no-host/{username}.png"))
        assertFalse(ActiveCape.isHttpUrl("javascript:alert('{username}')"))
        // Незакрытая скобка — тоже мусор.
        assertFalse(ActiveCape.isHttpUrl("https://e.com/{username.png"))
    }

    @Test
    @DisplayName("после подстановки плейсхолдеров схема не меняется")
    fun renderKeepsSchemeAndHost() {
        val template = "https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png"
        val rendered = Placeholders.render(
            template,
            Placeholders.Context(username = "GGSHNIKK", uuid = "abc"),
        )
        assertEquals(
            "https://skins.ggshnikk.online/api/animated/v1/skins/GGSHNIKK/cape.png",
            rendered,
        )
        // Реальная ссылка обязана быть валидной — её и проверяет HttpFetcher.
        assertTrue(ActiveCape.isHttpUrl(rendered))
        assertEqualsKt(true, java.net.URI.create(rendered).host == "skins.ggshnikk.online")
    }
}
