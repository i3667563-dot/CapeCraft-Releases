package dev.ggtv.capecraft.ide

import dev.ggtv.capecraft.schema.AddonSchemaParser
import dev.ggtv.capecraft.schema.ConfigSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Подсказки аддон-типов: что видит человек в редакторе, когда рядом стоит jar
 * аддона с дескриптором.
 *
 * Главное здесь — молчание на правильном файле. Ложная подсветка `url` внутри
 * `type = "seed"` или HINT на `type = "seed"` хуже отсутствия подсказок:
 * человек закрывает её и перестаёт смотреть, и аддон снова становится невидимым.
 * Поэтому проверяется и наличие подсказки, и отсутствие ругани.
 *
 * Курсор помечен `|` прямо в тексте и остаётся в нём — так же, как в
 * `CrenAnalyzerTest`: разбиратель относится к `|` как к обычному символу, и
 * подсказки считаются для позиции до него.
 */
class CrenAnalyzerAddonTest {

    private val descriptor = """
        addon {
            id = "capecraft-seed"
            version = "1.0.0"
            apiVersion = 1
            types = [
                {
                    id = "seed",
                    doc = "Плащ из UUID игрока.",
                    keys = [
                        { name = "elytra", type = "bool", def = "true", doc = "Крылья." },
                        { name = "gray", type = "bool", def = "false", doc = "В оттенках серого." }
                    ]
                },
                {
                    id = "image",
                    doc = "Картинка по ссылке или с диска.",
                    keys = [
                        { name = "url", type = "str", doc = "Ссылка." },
                        { name = "path", type = "str", doc = "Файл." },
                        { name = "gray", type = "bool", doc = "В оттенках серого." }
                    ]
                }
            ]
            placeholders = [ { name = "seedHash", type = "int", doc = "Число, из которого вырос плащ." } ]
        }
    """.trimIndent()

    @BeforeEach
    fun loadAddon() {
        val schema = AddonSchemaParser.parse(descriptor, "test.jar")
        requireNotNull(schema) { "тестовый дескриптор должен разбираться:\n$descriptor" }
        ConfigSchema.setAddonSchemas(listOf(schema))
    }

    @AfterEach
    fun forget() = ConfigSchema.clearAddonSchemas()

    private fun diags(text: String) = CrenAnalyzer.diagnostics(CrenDocument(text))

    private fun errors(text: String) = diags(text).filter { it.severity == CrenSeverity.ERROR }

    private fun warnings(text: String) = diags(text).filter { it.severity == CrenSeverity.WARNING }

    private fun config(vararg providers: String) = providers.joinToString(
        prefix = "capeCraft {\n    providers [\n",
        postfix = "    ]\n}\n",
    ) { "        $it\n" }

    private fun labels(text: String) =
        CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }

    // ------------------------------------------------------- конфиг аддона

    @Test
    fun `gray у seed не ругается`() {
        val text = config("""{ name = "мой", type = "seed", elytra = true, gray = true }""")
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
        assertTrue(warnings(text).isEmpty(), "предупреждения: ${warnings(text).map { it.message }}")
    }

    @Test
    fun `gray у image не ругается`() {
        val text = config("""{ name = "из-интернета", type = "image", url = "https://e/c.png", gray = true }""")
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
        assertTrue(warnings(text).isEmpty(), "предупреждения: ${warnings(text).map { it.message }}")
    }

    @Test
    fun `gray у чужого аддонного типа ругается`() {
        // Набор ключей известен, и `gray` у `image` объявлен, а у `json` — нет.
        // Значит, молчать было бы невежеством.
        val text = config("""{ name = "j", type = "json", url = "https://e/c.json", gray = true }""")
        assertTrue(
            warnings(text).any { it.code == CrenAnalyzer.CODE_APPLIES },
            "ожидалось предупреждение о лишнем ключе, а получили: ${diags(text).map { it.message }}",
        )
    }

    @Test
    fun `url у seed считается лишним`() {
        // Обратная сторона того же знания: раз набор ключей у `seed` известен,
        // `url` в нём лишний, и это надо сказать. Молчать здесь — значило бы
        // врать, что аддон когда-нибудь этот ключ прочитает.
        val text = config("""{ name = "s", type = "seed", url = "https://e/c.png" }""")
        assertTrue(
            warnings(text).any { it.code == CrenAnalyzer.CODE_APPLIES },
            "ожидалось предупреждение, а получили: ${warnings(text).map { it.message }}",
        )
    }

    @Test
    fun `url у image не считается лишним`() {
        // `url` есть и во встроенных типах, и в аддонном: слить надо так, чтобы
        // ограничение не срабатывало ни там, ни там.
        val text = config("""{ name = "i", type = "image", url = "https://e/c.png" }""")
        assertTrue(warnings(text).isEmpty(), "предупреждения: ${warnings(text).map { it.message }}")
    }

    @Test
    fun `встроенные типы продолжают работать вместе с аддонными`() {
        val text = config(
            """{ name = "u", type = "url", url = "https://e/c.png" }""",
            """{ name = "s", type = "seed", gray = true }""",
        )
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
        assertTrue(warnings(text).isEmpty(), "предупреждения: ${warnings(text).map { it.message }}")
    }

    @Test
    fun `без дескриптора ключи аддона остаются неизвестными`() {
        // Jar не найден — значит про `gray` сказать нечего, и «неизвестный ключ»
        // здесь честно. Важно, что молчание появилось только вместе с дескриптором
        // и не маскируется подсказками от соседнего набора аддонов.
        ConfigSchema.clearAddonSchemas()
        val text = config("""{ name = "чужой", type = "seed", gray = true }""")
        assertTrue(
            errors(text).any { it.code == CrenAnalyzer.CODE_UNKNOWN_KEY },
            "без дескриптора ожидалась ошибка, а получили: ${diags(text).map { it.message }}",
        )
        assertFalse(
            warnings(text).any { it.code == CrenAnalyzer.CODE_APPLIES },
            "набор ключей чужого типа неизвестен, ругаться не на что",
        )
    }

    // ------------------------------------------------------------ подсказки

    @Test
    fun `gray без строкового значения подсказывает true и false`() {
        val values = labels(config("""{ name = "s", type = "seed", gray = | }"""))
        assertTrue(
            "true" in values && "false" in values,
            "у bool-ключа должны предлагаться true/false, а предложили: $values",
        )
    }

    @Test
    fun `у type предлагаются аддонные типы`() {
        val values = labels(config("""{ name = "s", type = | }"""))
        assertTrue("seed" in values, "тип seed не предложен: $values")
        assertTrue("image" in values, "тип image не предложен: $values")
        assertTrue("url" in values, "встроенный url должен остаться: $values")
    }

    @Test
    fun `у аддонного типа предлагаются его ключи`() {
        val keys = labels(config("""{ name = "s", type = "seed", | }"""))
        assertTrue("gray" in keys, "ключ gray не предложен: $keys")
        assertTrue("elytra" in keys, "ключ elytra не предложен: $keys")
    }

    @Test
    fun `gray не предлагается у встроенного типа`() {
        // Ограничение appliesTo — ровно то, что мешает подсказке врать.
        val keys = labels(config("""{ name = "u", type = "url", | }"""))
        assertFalse("gray" in keys, "gray у url не имеет смысла, а предложен: $keys")
        assertFalse("elytra" in keys, "elytra у url не имеет смысла, а предложен: $keys")
        assertTrue("url" in keys, "собственный ключ url должен предлагаться: $keys")
    }

    @Test
    fun `path не обязателен у аддонного image`() {
        // `path` обязателен у встроенного `file`, и после появления аддона легко
        // начать требовать его у всех подряд. У `image` хватает `url`.
        val text = config("""{ name = "i", type = "image", url = "https://e/c.png" }""")
        assertTrue(
            warnings(text).none { it.message.contains("path") },
            "path у image не обязателен: ${warnings(text).map { it.message }}",
        )
        assertTrue(
            config("""{ name = "f", type = "file" }""").let { warnings(it) }
                .any { it.message.contains("path") },
            "у file path обязан остаться обязательным",
        )
    }

    @Test
    fun `описание аддонного типа попадает в подсказку`() {
        val text = config("""{ name = "s", type = | }""")
        val seed = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|'))
            .firstOrNull { it.label == "seed" }
        requireNotNull(seed) { "тип seed должен предлагаться" }
        val detail = seed.detail ?: ""
        assertTrue(detail.contains("Плащ из UUID игрока"), "в подсказке нет смысла типа: $detail")
        assertTrue(detail.contains("capecraft-seed"), "в подсказке нет имени аддона: $detail")
    }

    @Test
    fun `подсказка gray у seed объясняет ключ`() {
        val text = config("""{ name = "s", type = "seed", | }""")
        val gray = CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|'))
            .firstOrNull { it.label == "gray" }
        requireNotNull(gray) { "gray должен предлагаться у seed" }
        assertTrue(
            (gray.detail ?: "").contains("оттенках серого"),
            "нет объяснения: ${gray.detail}",
        )
    }

    // ----------------------------------------------------------- наведение

    @Test
    fun `наведение на gray показывает описание`() {
        val raw = config("""{ name = "s", type = "seed", gray = true }""")
        val hover = CrenAnalyzer.hover(CrenDocument(raw), raw.indexOf("gray") + 2)
        requireNotNull(hover) { "наведение на gray должно что-то говорить" }
        assertTrue(hover.text.contains("оттенках серого"), "нет описания: ${hover.text}")
    }

    @Test
    fun `наведение на аддонный type не пустое`() {
        val raw = config("""{ name = "s", type = "seed" }""")
        val hover = CrenAnalyzer.hover(CrenDocument(raw), raw.indexOf("seed") + 2)
        assertTrue(hover != null, "у типа от аддона должно быть наведение")
    }

    // -------------------------------------------------------- плейсхолдеры

    @Test
    fun `аддонный плейсхолдер предлагается в if`() {
        val text = config("""{ name = "s", type = "seed", if = { | } }""")
        val names = labels(text)
        assertTrue("seedHash" in names, "аддонный плейсхолдер не предложен: $names")
    }

    @Test
    fun `встроенные переменные if остались`() {
        val text = config("""{ name = "s", type = "url", url = "https://e/c.png", if = { | } }""")
        val names = labels(text)
        assertTrue("username" in names && "uuid" in names, "встроенные переменные пропали: $names")
        assertTrue(
            "seedHash" in names,
            "плейсхолдер аддона не зависит от типа провайдера: он резолвится в `if` " +
                "у любого, поэтому должен предлагаться и тут",
        )
    }

    @Test
    fun `одинаковое имя аддонного плейсхолдера не дублируется`() {
        // `username` есть и во встроенном списке, и может быть объявлен аддоном.
        // Дубли в подсказке выглядят как ошибка, и человек перестаёт ей верить.
        ConfigSchema.setAddonSchemas(
            listOf(
                requireNotNull(
                    AddonSchemaParser.parse(
                        """
                            addon {
                                id = "x"
                                placeholders = [ { name = "username", type = "str" } ]
                            }
                        """.trimIndent(),
                        "test.jar",
                    ),
                ),
            ),
        )
        val names = labels(config("""{ name = "s", type = "url", url = "https://e/c.png", if = { | } }"""))
        assertEquals(
            1,
            names.count { it == "username" },
            "имя предложено дважды: $names",
        )
    }

    /**
     * Без папки `mods` сервер ничего не видел: `seed` может прийти из аддона,
     * чей дескриптор не нашёлся, и ругать человека нечем — только подсказка.
     */
    @Test
    fun `без папки mods неизвестный тип остаётся подсказкой`() {
        // Папки `mods` не видно — сервер не знает ни одного аддона, и `seed`
        // вполне может прийти из аддона без дескриптора.
        ConfigSchema.setAddonSchemas(emptyList(), known = false)
        val seedType = diags(CONFIG_WITH_SEED)
            .single { it.message.contains("«seed» не подходит") }
        assertEquals(CrenSeverity.HINT, seedType.severity)
        assertTrue(seedType.message.contains("если это не тип от аддона"), seedType.message)
    }

    /**
     * Папка `mods` найдена — перечень типов исчерпывающий.
     *
     * Найдено на живых прогонах: аддон вынесли из папки, сервер отдал новую
     * диагностику мгновенно, значок в статусной строке показал «2 бага», а на
     * файле не было ни одной подсветки. Причина — `HINT`: редактор рисует его
     * бледно и не считает ошибкой. Здесь это обязано быть ошибкой, потому что
     * мод не соберёт провайдера с таким типом.
     */
    @Test
    fun `найденная папка mods превращает неизвестный тип в ошибку`() {
        val withoutAddon = AddonSchemaParser.parse(descriptor, "t.jar")!!
        ConfigSchema.setAddonSchemas(listOf(withoutAddon), known = true)
        assertEquals(emptyList(), errors(CONFIG_WITH_SEED), "пока аддон на месте, тип законен")

        ConfigSchema.setAddonSchemas(emptyList(), known = true)
        val seedType = diags(CONFIG_WITH_SEED).single { it.message.contains("«seed» не подходит") }
        assertEquals(
            CrenSeverity.ERROR,
            seedType.severity,
            "папка mods найдена и пуста — «seed» не от кого взять, это ошибка",
        )
        // Две ошибки, и обе настоящие: тип никто не даёт и ключ `gray` не
        // принадлежит ни одному известному типу. Обе были и до правки — плохо
        // было то, что про сам тип сообщали подсказкой.
        assertEquals(
            setOf(CrenAnalyzer.CODE_VALUE, CrenAnalyzer.CODE_UNKNOWN_KEY),
            errors(CONFIG_WITH_SEED).map { it.code }.toSet(),
        )
        assertTrue(!seedType.message.contains("если это не тип от аддона"), seedType.message)
    }

    private val CONFIG_WITH_SEED = """
        capeCraft {
            providers = [
                { type = "seed"
                  self = true
                  gray = true }
            ]
        }
    """.trimIndent()

}