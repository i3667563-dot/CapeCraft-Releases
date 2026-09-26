package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.condition.FakeWorld
import dev.ggtv.capecraft.condition.dictOf
import dev.ggtv.capecraft.condition.int
import dev.ggtv.capecraft.condition.str
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Серверный каталог: в сеть уходит ТОЛЬКО то, что сервер уже отобрал по
 * миру игрока, в готовом порядке выбора.
 */
class ServerCapeCatalogTest {

    private fun url(name: String, priority: Int = 0, whenNight: Boolean = false) = Provider(
        name = name,
        source = Source.Url("https://e.com/$name.png"),
        condition = if (whenNight) {
            Condition.parse(dictOf("time.period" to str("night")))
        } else {
            null
        },
        priority = priority,
    )

    private val day = FakeWorld(mapOf("time.period" to str("day")))
    private val night = FakeWorld(mapOf("time.period" to str("night")))

    @Test
    fun `empty catalog answers no capes`() {
        val body = ServerCapeCatalog.EMPTY.responseFor(day)
        assertEquals(emptyList(), body.providers)
        assertEquals(0, body.truncated)
        assertEquals(0, body.droppedInvalid)
    }

    @Test
    fun `inactive provider is not described and not sent`() {
        val catalog = ServerCapeCatalog(listOf(url("night-only", whenNight = true)))
        assertEquals(emptyList(), catalog.responseFor(day).providers)
        assertEquals(1, catalog.responseFor(night).providers.size)
        assertEquals("night-only", catalog.responseFor(night).providers.single().name)
    }

    @Test
    fun `server order is the selector order`() {
        val catalog = ServerCapeCatalog(
            listOf(
                url("default-low", priority = 0),
                url("matched-high", priority = 9, whenNight = true),
                url("default-high", priority = 5),
                url("matched-low", priority = 1, whenNight = true),
            ),
        )
        // Совпавшие условием — по убыванию приоритета; дефолтные — в порядке
        // конфига (их приоритет в выбор не тасует, см. ProviderSelector).
        assertEquals(
            listOf("matched-high", "matched-low", "default-low", "default-high"),
            catalog.responseFor(night).providers.map { it.name },
        )
    }

    @Test
    fun `descriptor keeps priority and source kind but drops the condition`() {
        val body = ServerCapeCatalog(listOf(url("a", priority = 4, whenNight = true))).responseFor(night)
        val cape = body.providers.single()
        assertEquals(4, cape.priority)
        assertEquals(ActiveCape.Kind.URL, cape.kind)
        assertEquals("https://e.com/a.png", cape.primary)
    }

    @Test
    fun `descriptor covers every source kind`() {
        val providers = listOf(
            Provider("u", Source.Url("https://e.com/u.png")),
            Provider("f", Source.File("{root}/capes/{username}.png")),
            Provider("j", Source.Json("https://e.com/a.json", "data[0].url")),
        )
        val body = ServerCapeCatalog(providers).responseFor(day)
        assertEquals(
            listOf(ActiveCape.Kind.URL, ActiveCape.Kind.FILE, ActiveCape.Kind.JSON),
            body.providers.map { it.kind },
        )
    }

    @Test
    fun `a broken descriptor is dropped, the rest is served`() {
        val providers = listOf(
            Provider("good", Source.Url("https://e.com/a.png")),
            Provider("bad", Source.Url("не-ссылка")),
            Provider("also-good", Source.Url("https://e.com/b.png")),
        )
        val body = ServerCapeCatalog(providers).responseFor(day)
        assertEquals(listOf("good", "also-good"), body.providers.map { it.name })
        assertEquals(1, body.droppedInvalid)
    }

    @Test
    fun `catalog is truncated to the protocol limit`() {
        val providers = List(SyncProtocol.MAX_PROVIDERS + 20) { url("p$it") }
        val body = ServerCapeCatalog(providers).responseFor(day)
        assertEquals(SyncProtocol.MAX_PROVIDERS, body.providers.size)
        assertEquals(20, body.truncated)
    }

    @Test
    fun `truncation never breaks the protocol limit`() {
        val providers = List(SyncProtocol.MAX_PROVIDERS + 20) { url("p$it") }
        val body = ServerCapeCatalog(providers, maxProviders = 3).responseFor(day)
        assertEquals(3, body.providers.size)
        val res = SyncResponse(1, body.providers, body.truncated)
        assertEquals(3, SyncCodec.decodeResponse(res.encode()).providers.size)
    }

    @Test
    fun `answer always fits the wire format`() {
        val providers = List(SyncProtocol.MAX_PROVIDERS) {
            Provider("p$it", Source.Url("https://e.com/${"a".repeat(SyncProtocol.MAX_STRING_BYTES)}"))
        }
        val body = ServerCapeCatalog(providers).responseFor(day)
        val bytes = SyncResponse(1, body.providers, body.truncated).encode()
        assertTrue(bytes.size <= SyncProtocol.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `conditions are evaluated against the given world, not a global one`() {
        val catalog = ServerCapeCatalog(listOf(url("high", priority = 1, whenNight = true), url("low")))
        val rainy = FakeWorld(mapOf("time.period" to str("night")))
        assertEquals(listOf("high", "low"), catalog.responseFor(rainy).providers.map { it.name })
        assertEquals(listOf("low"), catalog.responseFor(day).providers.map { it.name })
    }

    @Test
    fun `numeric conditions work too`() {
        val high = Provider(
            "sky",
            Source.Url("https://e.com/s.png"),
            Condition.parse(dictOf("location.y" to str(">=300"))),
        )
        val catalog = ServerCapeCatalog(listOf(high))
        assertEquals(1, catalog.responseFor(FakeWorld(mapOf("location.y" to int(400)))).providers.size)
        assertEquals(0, catalog.responseFor(FakeWorld(mapOf("location.y" to int(64)))).providers.size)
    }

    @Test
    fun `size reports the configured provider count`() {
        assertEquals(3, ServerCapeCatalog(listOf(url("a"), url("b"), url("c"))).size)
    }
}
