package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.ProviderSelector
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.kjen.Value
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Главное требование Sync v2: у чужого объекта набор функций со своими
 * `when` и приоритетами, и клиент применяет его **против контекста этого
 * объекта**, а не своего.
 *
 * Здесь проверяется ровно та часть, которая ломается незаметнее всего: что
 * объявление, пережившее круг по сети, теряет ровно то, что не должно терять —
 * условие и приоритет.
 */
class SyncObjectFunctionsTest {

    /** Контекст переменных: в тестах проверяется `when`, а `if` пуст,
     *  но параметр обязателен — иначе тест врал бы про сигнатуру. */
    private val vars = dev.ggtv.capecraft.schema.Placeholders.Context(
        username = "Steve", uuid = "u", name = "", root = "/root",
    )


    /**
     * Мир с заданной температурой биома — подставляется вместо игрового.
     *
     * Нужны именно два разных значения: если бы клиент считал условие по своему
     * миру, тест на паре контекстов разошёлся бы, а на одном — прошёл бы.
     */
    private class FixedWorld(private val temperature: String) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value =
            when {
                root == WorldRoot.BIOME && field == "temperature" ->
                    Value.VFloat(temperature.toDouble())

                else -> Value.VStr("unknown")
            }
    }

    /** Порог `temperature > WARM`. */
    private val warmEnough = 0.5
    private val jungle = FixedWorld("0.95")

    private fun urlFunction(name: String, priority: Int = 0) = ActiveCape(
        name = name,
        kind = ActiveCape.Kind.URL,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        condition = null,
        imageHash = null,
    )

    private fun jsonFunction(name: String, priority: Int = 0) = ActiveCape(
        name = name,
        kind = ActiveCape.Kind.JSON,
        primary = "https://example.invalid/$name.json",
        extract = "$.cape",
        priority = priority,
        condition = null,
        imageHash = null,
    )
    private val tundra = FixedWorld("0.1")

    /**
     * Функция с числовым условием вида `when.biome.temperature > X`.
     *
     * Сравнение обязано быть числовым: `GT` с `WireExpected.Str` — это
     * невалидное объявление, которое `validate()` отвергнет, а условие без
     * числа молча не сойдётся никогда.
     */
    private fun function(
        name: String,
        priority: Int,
        warmerThan: Double? = null,
    ): ActiveCape = ActiveCape(
        kind = ActiveCape.Kind.URL,
        name = name,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        condition = warmerThan?.let {
            WireCondition(listOf(WirePredicate(WireRoot.BIOME, "temperature", WireOp.GT, WireExpected.Num(it))))
        },
    )

    // ── условие переживает сеть ─────────────────────────────────────────────

    @Test
    fun `условие объявления переживает кодирование и обратно`() {
        val original = function("jungle", priority = 5, warmerThan = warmEnough)
        val wire = SyncCodec.encodeAnnounce(Announce(listOf(original)))
        val decoded = SyncCodec.decodeAnnounce(wire).functions.single()

        assertNotNull(decoded.condition, "условие пропало при кодировании — набор станет безусловным")
        assertEquals(5, decoded.priority, "приоритет не пережил сеть")
        assertEquals("jungle", decoded.name)
    }

    @Test
    fun `условие чужого набора считается против его контекста, а не моего`() {
        val decoded = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(Announce(listOf(function("jungle", priority = 5, warmerThan = warmEnough)))),
        ).functions
        val providers = SyncRosterPolicy.toLocalProviders(decoded)

        // Смотрю на объект, стоящий в джунглях: его условие совпадает.
        val seenFromJungle = ProviderSelector.select(providers, jungle, vars).map { it.name }
        // Смотрю на того же объект из пустыни: тот же набор, но условие — нет.
        val seenFromTundra = ProviderSelector.select(providers, tundra, vars).map { it.name }

        assertEquals(listOf("jungle"), seenFromJungle, "в джунглях должен выиграть условный провайдер")
        assertTrue(
            seenFromTundra.isEmpty(),
            "в тундре условный провайдер выигрывать не должен, а выиграл: $seenFromTundra",
        )
    }

    // ── приоритет переживает сеть и реально сортирует ────────────────────────

    @Test
    fun `приоритет сортирует совпавшие условные провайдеры по убыванию`() {
        val functions = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(
                Announce(
                    listOf(
                        function("низкий", priority = 1, warmerThan = warmEnough),
                        function("высокий", priority = 99, warmerThan = warmEnough),
                        function("средний", priority = 50, warmerThan = warmEnough),
                    ),
                ),
            ),
        ).functions
        val providers = SyncRosterPolicy.toLocalProviders(functions)

        assertEquals(
            listOf("высокий", "средний", "низкий"),
            ProviderSelector.select(providers, jungle, vars).map { it.name },
            "совпавшие по условию должны идти по убыванию приоритета",
        )
    }

    @Test
    fun `приоритет с обратной стороны сети не переворачивается`() {
        // Порядок в объявлении другой, чем в выдаче: сортировка обязана
        // определяться приоритетом, а не тем, в каком порядке пришли байты.
        val shuffled = listOf(
            function("a", priority = 3, warmerThan = warmEnough),
            function("b", priority = 7, warmerThan = warmEnough),
        )
        val providers = SyncRosterPolicy.toLocalProviders(
            SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(shuffled))).functions,
        )
        assertEquals(listOf("b", "a"), ProviderSelector.select(providers, jungle, vars).map { it.name })
    }

    @Test
    fun `безусловный провайдер идёт после условных, даже с большим приоритетом`() {
        // Это не баг, а правило: безусловное — это fallback «на всякий случай»,
        // и условие обязано побеждать его всегда, иначе `when` не значил бы
        // ничего и любой default перебивал бы явное условие.
        val providers = SyncRosterPolicy.toLocalProviders(
            listOf(function("default", priority = 1000), function("jungle", priority = 1, warmerThan = warmEnough)),
        )
        assertEquals(listOf("jungle", "default"), ProviderSelector.select(providers, jungle, vars).map { it.name })
    }

    // ── приватность локального файла ─────────────────────────────────────────

    @Test
    fun `локальный файл не уезжает на провод, вместо него хэш`() {
        val declared = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(
                Announce(
                    listOf(
                        ActiveCape(
                            kind = ActiveCape.Kind.FILE,
                            name = "мой",
                            primary = "/home/gg_tv/плащ.png",
                            extract = "",
                            priority = 0,
                            condition = null,
                            imageHash = ImageHash.compute(byteArrayOf(1, 2, 3, 4)),
                        ),
                    ),
                ),
            ),
        ).functions.single()

        assertEquals("", declared.primary, "путь на проводе обязан быть пустым")
        assertNotNull(declared.imageHash, "вместо пути должен ехать хэш")
        val blob = String(SyncCodec.encodeAnnounce(Announce(listOf(declared))), Charsets.ISO_8859_1)
        assertTrue(
            !blob.contains("gg_tv") && !blob.contains("плащ"),
            "в байтах объявления не должно быть ни куска локального пути",
        )
    }

    @Test
    fun `объявление без хэша не превращается в сетевую картинку`() {
        // Пустой primary без хэша — мусор, а не ссылка: иначе клиент принял бы
        // файл-функцию за NetImage и ждал бы картинку, которой не существует.
        val declared = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(
                Announce(
                    listOf(
                        ActiveCape(
                            kind = ActiveCape.Kind.FILE,
                            name = "битый",
                            primary = "",
                            extract = "",
                            priority = 0,
                            condition = null,
                            imageHash = null,
                        ),
                    ),
                ),
            ),
        ).functions.single()

        assertNull(SyncRosterPolicy.toLocalProvider(declared), "битое объявление не должно давать провайдер")
    }

    // ── приоритет и условие не пересекаются с приоритетом сервера ───────────

    // ── allowForeignUrls: чужие ссылки не ходятся без разрешения ──────────────

    @Test
    fun `чужой url игнорируется когда allowForeignUrls выключен`() {
        val declared = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(
                Announce(listOf(urlFunction("чужая"), jsonFunction("чужая-карта"))),
            ),
        ).functions

        assertEquals(
            emptyList(),
            SyncRosterPolicy.toLocalProviders(declared, allowForeignUrls = false),
            "при запрете чужих ссылок набор должен остаться пустым, а не частично применяться",
        )
    }

    @Test
    fun `файл по хэшу разрешён даже при запрете чужих ссылок`() {
        val bytes = "плащ владельца".toByteArray()
        val hash = ImageHash.compute(bytes)
        val declared = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(
                Announce(
                    listOf(
                        ActiveCape(
                            name = "файл",
                            kind = ActiveCape.Kind.FILE,
                            primary = "",
                            extract = "",
                            priority = 0,
                            condition = null,
                            imageHash = hash,
                        ),
                    ),
                ),
            ),
        ).functions

        val providers = SyncRosterPolicy.toLocalProviders(declared, allowForeignUrls = false)
        assertEquals(1, providers.size, "чужой файл приходит по хэшу через сервер, а не по чужому адресу")
        assertTrue(
            providers.single().source is Source.NetImage,
            "ожидался NetImage, а не ${providers.single().source}",
        )
    }

    @Test
    fun `при allowForeignUrls по умолчанию чужие ссылки применяются`() {
        val declared = SyncCodec.decodeAnnounce(
            SyncCodec.encodeAnnounce(Announce(listOf(urlFunction("чужая")))),
        ).functions
        assertEquals(1, SyncRosterPolicy.toLocalProviders(declared).size)
    }

    @Test
    fun `список безусловных функций проходит без изменений`() {
        val functions = listOf(function("просто", priority = 0))
        val providers = SyncRosterPolicy.toLocalProviders(
            SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(functions))).functions,
        )
        assertEquals(listOf("просто"), ProviderSelector.select(providers, EmptyWorldContext, vars).map { it.name })
    }
}
