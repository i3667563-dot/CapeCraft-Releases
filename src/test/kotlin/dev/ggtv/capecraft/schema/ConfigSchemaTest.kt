package dev.ggtv.capecraft.schema

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.condition.Expected
import dev.ggtv.capecraft.condition.Predicate
import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.capecraft.provider.ProviderNames
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.sync.ServerSyncSettings
import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig
import dev.ggtv.koren.WorldRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Схема не должна разъезжаться с кодом.
 *
 * Каждый тест здесь — про конкретный способ разъехаться, который уже случался
 * или случится сам, если схему не сверять:
 *
 * - дефолт в схеме разошёлся с настоящим дефолтом мода;
 * - ключ в схеме переименовали, а в схеме забыли;
 * - у `when` появилось новое поле, а подсказка про него не знает;
 * - короткая запись разъехалась с тем, во что её правда превращается.
 *
 * Схема читает константы кода напрямую, поэтому первые три класса ошибок
 * ловятся компилятором, и это хорошо. Но алиасы `when` — это правила,
 * которые в коде живут в `when`-ветке на строковых литералах, и сверять их
 * можно только прогоном. Поэтому здесь они и проверяются по-настоящему.
 */
class ConfigSchemaTest {
    private fun field(path: String): Field =
        assertNotNull(
            ConfigSchema.resolve(path.split('.')),
            "в схеме нет пути «$path»",
        )

    @Test
    fun `дефолты limits совпадают с настоящими`() {
        val d = Limits()
        assertEquals(d.maxPixelsPerFrame.toString(), field("capeCraft.limits.maxPixelsPerFrame").def)
        assertEquals(d.maxFrames.toString(), field("capeCraft.limits.maxFrames").def)
        assertEquals(d.maxBytesPerCape.toString(), field("capeCraft.limits.maxBytesPerCape").def)
        assertEquals(d.maxBytesTotal.toString(), field("capeCraft.limits.maxBytesTotal").def)
    }

    @Test
    fun `дефолты serverSync совпадают с настоящими`() {
        val d = ServerSyncSettings()
        assertEquals(d.enabled.toString(), field("capeCraft.serverSync.enabled").def)
        assertEquals(d.shareLocalProviders.toString(), field("capeCraft.serverSync.shareLocalProviders").def)
        assertEquals(d.allowForeignUrls.toString(), field("capeCraft.serverSync.allowForeignUrls").def)
        assertEquals(d.backoff.toString(), field("capeCraft.serverSync.backoff").def)
    }

    @Test
    fun `каждый ключ схемы действительно читается загрузчиком`() {
        // Загрузчик лишние ключи молча игнорирует, а редактор по схеме их
        // подсвечивает. Расхождение невидимо: ключ выглядит
        // работающим, а значения никто не читает. Поэтому сверка идёт по
        // **поведению**: ключ из схемы обязан менять собранный провайдер.
        //
        // Все три типа в одном конфиге: `path` и `extract` читаются не всеми
        // сразу, и по одному провайдеру их не различить с «загрузчик их
        // игнорирует».
        val kn = """
            capeCraft {
                providers [
                    { name = "a", type = "url", url = "https://ex.invalid/a.png",
                      extract = "$.a", path = "/tmp/a.png", priority = 7,
                      when = { biome: "snowy" },
                      if = { username: "Steve" } },

                    { name = "b", type = "file", url = "https://ex.invalid/b.png",
                      extract = "$.b", path = "/tmp/b.png", priority = 7,
                      when = { biome: "snowy" },
                      if = { username: "Steve" },
                      self = true },

                    { name = "c", type = "json", url = "https://ex.invalid/c",
                      extract = "$.c", path = "/tmp/c.png", priority = 7,
                      when = { biome: "snowy" },
                      if = { username: "Steve" } },
                ]
            }
        """.trimIndent()
        val loaded = ProviderLoader.load(KorenConfig.fromString(kn))
        assertEquals(3, loaded.size)

        val url = loaded[0]
        assertEquals("a", url.name)
        assertEquals(Source.Url("https://ex.invalid/a.png"), url.source)
        assertEquals(7, url.priority)
        assertEquals(1, url.condition?.predicates?.size)
        assertEquals("username", url.ifCondition?.predicates?.single()?.name)

        assertEquals(Source.File("/tmp/b.png"), loaded[1].source)
        assertTrue(loaded[1].selfOnly, "self = true обязан читаться загрузчиком")
        assertFalse(loaded[0].selfOnly, "без self провайдер остаётся публичным")
        assertEquals(Source.Json("https://ex.invalid/c", "$.c"), loaded[2].source)
    }

    @Test
    fun `type провайдера помечен открытым — аддоны регистрируют свои`() {
        val type = ConfigSchema.providerFields().first { it.name == ProviderNames.Keys.TYPE }
        assertTrue(type.open, "type открыт для аддон-типов, иначе чужие типы будут ругаться")
        for (builtin in listOf(
            ProviderNames.Types.URL,
            ProviderNames.Types.FILE,
            ProviderNames.Types.JSON,
        )) {
            assertTrue(
                type.allowed.contains(builtin),
                "встроенный тип $builtin должен быть в списке подсказки",
            )
        }
    }

    @Test
    fun `специфичные ключи провайдера не показываются чужому типу`() {
        val extract = ConfigSchema.providerFields().first { it.name == ProviderNames.Keys.EXTRACT }
        val path = ConfigSchema.providerFields().first { it.name == ProviderNames.Keys.PATH }

        assertTrue(extract.offeredFor(ProviderNames.Types.JSON), "extract нужен json")
        assertTrue(!extract.offeredFor(ProviderNames.Types.URL), "extract у url лишний")
        assertTrue(path.offeredFor(ProviderNames.Types.FILE), "path нужен file")
        assertTrue(!path.offeredFor(ProviderNames.Types.JSON), "path у json лишний")
    }

    @Test
    fun `поля when совпадают с живыми полями`() {
        for (root in WhenSchema.roots()) {
            val live = Condition.LIVE_FIELDS[root].orEmpty()
            assertEquals(live, WhenSchema.fieldsOf(root), "поля корня ${root.segment} разошлись")
        }
    }

    @Test
    fun `у каждого поля when есть описание`() {
        for (root in WhenSchema.roots()) {
            for (field in WhenSchema.fieldsOf(root)) {
                assertNotNull(
                    WhenSchema.docFor(root, field),
                    "нет описания для ${root.segment}.$field",
                )
            }
        }
    }

    /**
     * Главный тест: каждая задокументированная короткая запись в схеме реально
     * разворачивается в то, что в ней написано.
     *
     * Схема обещает `dawn -> period = sunrise`. Если в коде алиас отвалится или
     * его переименуют, тест упадёт здесь, а не у человека в редакторе, который
     * час будет гадать, почему плащ не надевается в 6 утра.
     */
    @Test
    fun `задокументированные алиасы разворачиваются ровно как написано в схеме`() {
        for ((root, aliases) in WhenSchema.ALIASES) {
            for ((alias, described) in aliases) {
                val dict = Value.VDict(listOf(root.segment to Value.VStr(alias)))
                val predicate = Condition.parse(dict).predicates.single()

                assertEquals(root, predicate.root, "алиас $alias потерял корень")
                val rendered = render(predicate)
                assertEquals(
                    described,
                    rendered,
                    "алиас ${root.segment} = \"$alias\": схема обещает «$described», " +
                        "код даёт «$rendered»",
                )
            }
        }
    }

    @Test
    fun `алиасы координат не существуют`() {
        assertTrue(
            WhenSchema.aliasesOf(WorldRoot.LOCATION).isEmpty(),
            "у location нет полей по умолчанию, сравнивать не с чем",
        )
    }

    @Test
    fun `каждый корень when хотя бы раз описан или имеет поля`() {
        for (root in WorldRoot.entries) {
            val hasAliases = WhenSchema.aliasesOf(root).isNotEmpty()
            val hasFields = WhenSchema.fieldsOf(root).isNotEmpty()
            assertTrue(hasAliases || hasFields, "корень ${root.segment} пустой в схеме")
        }
    }

    @Test
    fun `устаревшие ключи serverSync помечены и не имеют дефолта`() {
        val deprecated = listOf(
            "intervalTicks",
            "timeoutTicks",
            "requireServer",
            "allowFileProviders",
        )
        for (key in deprecated) {
            val f = field("capeCraft.serverSync.$key")
            assertNotNull(f.deprecated, "$key должен быть помечен устаревшим")
            assertNull(f.def, "у устаревшего ключа не должно быть дефолта")
        }
    }

    @Test
    fun `живые ключи serverSync не помечены устаревшими и все на месте`() {
        val d = ServerSyncSettings()
        for (key in listOf("enabled", "shareLocalProviders", "allowForeignUrls", "backoff")) {
            val f = field("capeCraft.serverSync.$key")
            assertNull(f.deprecated, "$key жив, а не устаревший")
            assertTrue(f.required.not(), "$key не обязателен: у него есть дефолт")
        }
        assertEquals(d.enabled.toString(), field("capeCraft.serverSync.enabled").def)
    }

    @Test
    fun `схема находит опечатку и предлагает похожий ключ`() {
        assertEquals(
            listOf("enabled"),
            ConfigSchema.similarTo("enabeled", listOf("enabled", "backoff")),
            "опечатка в один символ должна находиться",
        )
        assertEquals(
            listOf("maxFrames"),
            ConfigSchema.similarTo("maxFrame", listOf("maxFrames", "maxBytesTotal")),
            "опечатка в названии лимита должна находиться",
        )
        assertTrue(
            ConfigSchema.similarTo("абракадабра", listOf("enabled", "backoff")).isEmpty(),
            "далёкое слово не должно предлагать похожие ключи",
        )
    }

    @Test
    fun `путь вне схемы не резолвится`() {
        assertNull(ConfigSchema.resolve(listOf("capeCraft", "providers", "nope")))
        assertNull(ConfigSchema.resolve(listOf("что-то", "else")))
    }

    @Test
    fun `корень схемы это capeCraft и он блок`() {
        assertEquals("capeCraft", ConfigSchema.root.name)
        assertEquals(SchemaType.BLOCK, ConfigSchema.root.type)
    }

    /**
     * Отрисовать предикат так же, как его пишут в README: `поле = значение`.
     *
     * Значения берутся локальными переменными, а не прямо в строковом шаблоне:
     * в шаблоне `${expected.to}` компилятор не сужает тип и находит
     * одноимённую `Iterable.to()` из stdlib вместо поля `Range.to`.
     */
    private fun render(predicate: Predicate): String {
        val field = predicate.field
        return when (val e = predicate.expected) {
            is Expected.Str -> "$field = ${e.s}"
            is Expected.Num -> "$field = ${e.d}"
            is Expected.Range -> "$field = ${e.from}..${e.to}"
        }
    }
}
