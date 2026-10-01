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
 * Условия `when`, объявленные аддоном: что видит человек в редакторе, когда
 * рядом стоит jar аддона с дескриптором.
 *
 * Здесь важны обе стороны. Подсветка на правильном `fire.burning` ломает
 * конфиг, который в игре работает, — и человек начинает верить редактору меньше,
 * чем самому себе. Но и молчание на файле без аддона тоже неверно: `fire` там
 * действительно неизвестен, и молчание выглядит как «условие просто не сработало».
 */
class CrenAnalyzerWhenAddonTest {

    private val descriptor = """
        addon {
            id = "capecraft-fire"
            version = "1.0.0"
            apiVersion = 2

            conditions = [
                {
                    id = "fire",
                    doc = "Горение игрока.",
                    defaultField = "burning",
                    fields = [
                        { name = "burning", doc = "Горит ли игрок.", values = ["true", "false"] },
                        { name = "ticks", doc = "Сколько тиков горит.", numeric = true }
                    ]
                },
                {
                    id = "depth",
                    doc = "Глубина.",
                    fields = [
                        { name = "below", doc = "Под водой.", values = ["true", "false"] }
                    ]
                }
            ]
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

    private fun config(vararg whens: String) = whens.joinToString(
        prefix = "capeCraft {\n    providers [\n",
        postfix = "    ]\n}\n",
    ) { """        { name = "мой", url = "https://e/c.png", when = { $it } }""" }

    private fun labels(text: String) =
        CrenAnalyzer.complete(CrenDocument(text), text.indexOf('|')).map { it.label }

    // ------------------------------------------------------------ разбор дескриптора

    @Test
    fun `корни when читаются из дескриптора`() {
        val schema = AddonSchemaParser.parse(descriptor, "test.jar")!!
        assertEquals(listOf("fire", "depth"), schema.conditions.map { it.id })
        assertEquals("burning", schema.conditions[0].defaultField)
        assertEquals(listOf("true", "false"), schema.conditions[0].fields[0].values)
        assertTrue(schema.conditions[0].fields[1].numeric)
        assertEquals("Горит ли игрок.", schema.conditions[0].fields[0].doc)
        assertEquals(null, schema.conditions[1].defaultField)
    }

    @Test
    fun `точка в имени корня отбрасывается`() {
        val bad = descriptor.replace("""id = "fire",""", """id = "fire.temp",""")
        val schema = AddonSchemaParser.parse(bad, "test.jar")!!
        assertEquals(listOf("depth"), schema.conditions.map { it.id })
    }

    @Test
    fun `корень без полей отбрасывается`() {
        val bad = """
            addon {
                id = "x"
                conditions = [ { id = "fire", doc = "Пусто." } ]
            }
        """.trimIndent()
        val schema = AddonSchemaParser.parse(bad, "test.jar")!!
        assertTrue(schema.conditions.isEmpty())
    }

    @Test
    fun `поле по умолчанию не из объявленных отбрасывает корень`() {
        val bad = descriptor.replace("""defaultField = "burning",""", """defaultField = "wet",""")
        val schema = AddonSchemaParser.parse(bad, "test.jar")!!
        assertEquals(listOf("depth"), schema.conditions.map { it.id })
    }

    // -------------------------------------------------------------- диагностика

    @Test
    fun `правильное условие аддона не ругается`() {
        val text = config("""fire.burning: "true"""")
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
    }

    @Test
    fun `короткая запись работает`() {
        val text = config("""fire: "true"""")
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
    }

    @Test
    fun `без дескриптора корень аддона неизвестен`() {
        ConfigSchema.clearAddonSchemas()
        val text = config("""fire.burning: "true"""")
        val errors = errors(text)
        assertTrue(errors.any { it.code == CrenAnalyzer.CODE_WHEN }, "получили: ${errors.map { it.message }}")
        // В списке корней `fire` быть не должно: без дескриптора такого корня
        // не существует, и называть его опечаткой в списке — враньё.
        val available = errors.first { it.code == CrenAnalyzer.CODE_WHEN }
            .message.substringAfter("Доступны: ")
        assertFalse(
            available.split(", ").contains("fire"),
            "без дескриптора `fire` в списке корней быть не должно: $available",
        )
    }

    @Test
    fun `неизвестное поле перечисляет поля аддона`() {
        val errors = errors(config("""fire.wet: "true""""))
        assertTrue(
            errors.any { it.message.contains("burning") && it.message.contains("ticks") },
            "получили: ${errors.map { it.message }}",
        )
    }

    @Test
    fun `значение не из списка ругается с перечислением`() {
        val errors = errors(config("""fire.burning: "да""""))
        assertTrue(
            errors.any { it.message.contains("true") && it.message.contains("false") },
            "получили: ${errors.map { it.message }}",
        )
    }

    @Test
    fun `числовое поле аддона предлагает сравнение`() {
        assertTrue(errors(config("""fire.ticks: ">5"""")).isEmpty())
        assertTrue(errors(config("""fire.ticks: "много"""")).isNotEmpty())
    }

    @Test
    fun `корень без поля по умолчанию требует точку`() {
        val errors = errors(config("""depth: "true""""))
        assertTrue(
            errors.any { it.message.contains("below") },
            "получили: ${errors.map { it.message }}",
        )
    }

    @Test
    fun `встроенные корни продолжают работать вместе с аддонными`() {
        val text = config("""biome: "plains", fire.burning: "true"""")
        assertTrue(errors(text).isEmpty(), "ошибки: ${errors(text).map { it.message }}")
    }

    @Test
    fun `корень мира в if не считается переменной и для аддона`() {
        // Тот же запрет, что и для встроенных: `fire` в `if` — это попытка
        // перенести условие, а не имя переменной.
        val text = """
            capeCraft {
                providers [
                    { name = "мой", url = "https://e/c.png", if = { fire: "true" } }
                ]
            }
        """.trimIndent()
        assertTrue(errors(text).any { it.code == CrenAnalyzer.CODE_IF })
    }

    // ------------------------------------------------------------------ подсказки

    @Test
    fun `корень аддона предлагается в списке корней`() {
        val text = "capeCraft {\n  providers [\n    { name = \"м\", url = \"https://e/c.png\", when = { | } }\n  ]\n}"
        assertTrue(labels(text).contains("fire"), "нет корня `fire` среди подсказок")
    }

    @Test
    fun `поля аддона предлагаются после точки`() {
        val text = "capeCraft {\n  providers [\n    { name = \"м\", url = \"https://e/c.png\", when = { fire.| } }\n  ]\n}"
        val labels = labels(text)
        assertTrue(labels.contains("fire.burning"), "нет `fire.burning`: $labels")
        assertTrue(labels.contains("fire.ticks"), "нет `fire.ticks`: $labels")
    }

    @Test
    fun `значения аддона предлагаются`() {
        val text = "capeCraft {\n  providers [\n    { name = \"м\", url = \"https://e/c.png\", when = { fire.burning:| } }\n  ]\n}"
        val labels = labels(text)
        assertTrue(labels.contains("true"), "нет `true`: $labels")
        assertTrue(labels.contains("false"), "нет `false`: $labels")
    }

    @Test
    fun `операторы предлагаются только числовым полям аддона`() {
        val bool = "capeCraft {\n  providers [\n    { name = \"м\", url = \"https://e/c.png\", when = { fire.burning:| } }\n  ]\n}"
        val num = "capeCraft {\n  providers [\n    { name = \"м\", url = \"https://e/c.png\", when = { fire.ticks:| } }\n  ]\n}"
        assertFalse(labels(bool).any { it.startsWith(">") }, "у булева поля не должно быть `>`: ${labels(bool)}")
        assertTrue(labels(num).any { it.startsWith(">") }, "у числового поля должно быть `>`: ${labels(num)}")
    }

    @Test
    fun `подсказка поля аддона несёт его описание`() {
        val text = """
            capeCraft {
                providers [
                    { name = "м", url = "https://e/c.png", when = { fire.burning: "true" } }
                ]
            }
        """.trimIndent()
        val hover = CrenAnalyzer.hover(CrenDocument(text), text.indexOf("fire.burning"))
        assertTrue(hover != null && hover.text.contains("Горит ли игрок."), "hover: ${hover?.text}")
    }

    @Test
    fun `подсказка корня аддона называет аддон и self-only`() {
        val text = """
            capeCraft {
                providers [
                    { name = "м", url = "https://e/c.png", when = { fire: "true" } }
                ]
            }
        """.trimIndent()
        val hover = CrenAnalyzer.hover(CrenDocument(text), text.indexOf("fire"))
        assertTrue(hover != null && hover.text.contains("capecraft-fire"), "hover: ${hover?.text}")
        assertTrue(hover != null && hover.text.contains("не уезжает по сети"), "hover: ${hover?.text}")
    }

    // --------------------------------------------------------------- конфликты

    @Test
    fun `встроенное имя не перекрывается аддонным`() {
        val evil = """
            addon {
                id = "evil"
                version = "1.0"
                conditions = [
                    { id = "biome", defaultField = "id", fields = [ { name = "id", values = ["x"] } ] }
                ]
            }
        """.trimIndent()
        ConfigSchema.setAddonSchemas(listOf(AddonSchemaParser.parse(evil, "evil.jar")!!))
        assertTrue(ConfigSchema.addonWhenRoots().isEmpty())
        // Встроенный `biome` продолжает работать: подмена не молча ломает
        // рабочее условие, а просто не показывается.
        assertTrue(errors(config("""biome: "plains"""")).isEmpty())
    }

    @Test
    fun `два аддона с одним корнем — показывается первый`() {
        val other = descriptor.replace("capecraft-fire", "capecraft-ember")
        ConfigSchema.setAddonSchemas(
            listOf(
                AddonSchemaParser.parse(descriptor, "fire.jar")!!,
                AddonSchemaParser.parse(other, "ember.jar")!!,
            ),
        )
        val roots = ConfigSchema.addonWhenRoots()
        assertEquals(1, roots.count { it.segment == "fire" })
        assertEquals("capecraft-fire", roots.first { it.segment == "fire" }.addon)
    }
}