package dev.ggtv.capecraft.schema

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор `capecraft-addon.kn`.
 *
 * Проверяется не «файл прочитался», а два свойства, на которых держится вся
 * подсказка аддонов:
 *
 * - одна плохая запись не выключает остальные. Аддон пишет дескриптор руками,
 *   опечатка в одном ключе не должна оставлять человека вообще без подсказок.
 * - неизвестное имя типа или ключа не проходит молча. Иначе дескриптор выглядит
 *   как будто ключ объявлен, а подсказки его не показывают.
 */
class AddonSchemaParserTest {

    @AfterTest
    fun forgetAddons() = ConfigSchema.clearAddonSchemas()

    private fun parse(text: String) = AddonSchemaParser.parse(text, "test.jar")

    private val good = """
        addon {
            id = "capecraft-seed"
            version = "1.0.0"
            apiVersion = 1
            types [
                {
                    id = "seed",
                    doc = "Плащ из UUID.",
                    keys = [
                        { name = "gray", type = "bool", def = "false", doc = "В оттенках серого." },
                        { name = "elytra", type = "bool" }
                    ]
                }
            ]
        }
    """.trimIndent()

    @Test
    fun `читает тип и его ключи`() {
        val s = assertNotNull(parse(good), "корректный дескриптор должен разбираться")
        assertEquals("capecraft-seed", s.id)
        assertEquals("1.0.0", s.version)
        assertEquals(1, s.apiVersion)
        val type = assertNotNull(s.type("seed"), "тип seed должен найтись")
        assertEquals(listOf("gray", "elytra"), type.keys.map { it.name })
        assertEquals(SchemaType.BOOL, type.keys[0].type)
        assertEquals("false", type.keys[0].def)
        assertEquals("В оттенках серого.", type.keys[0].doc)
    }

    @Test
    fun `имя типа ключа регистронезависимо`() {
        // `bool` — строчными, как в конфиге; `SchemaType` объявлен прописными.
        // Строгое сравнение отбросило бы ключ, и подсказка молча пропала бы.
        val s = assertNotNull(parse(good), "разбирается")
        assertEquals(SchemaType.BOOL, s.type("seed")!!.keys[0].type)
    }

    @Test
    fun `неизвестный тип ключа отбрасывает ключ, а не весь дескриптор`() {
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        types = [
{
                            id = "seed",
                            keys = [
                                { name = "gray", type = "bool" },
                                { name = "olor", type = "цвет" }
                            ]
                        }
                        ]
                    }
                """.trimIndent(),
            ),
            "один негодный ключ не должен ронять дескриптор",
        )
        assertEquals(listOf("gray"), s.type("seed")!!.keys.map { it.name })
    }

    @Test
    fun `запись без id отбрасывается, остальные типы остаются`() {
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        types = [
                            { doc = "без имени", keys = [] },
                            { id = "seed", keys = [ { name = "gray", type = "bool" } ] }
                        ]
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("seed"), s.types.map { it.id })
    }

    @Test
    fun `тип без id не попадает в список`() {
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        types = [
                            { doc = "без имени" },
                            { id = "seed" }
                        ]
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("seed"), s.types.map { it.id })
    }

    @Test
    fun `дескриптор без id не принимается`() {
        assertNull(parse("addon { types = [] }"), "без id не сказать, чей это тип")
    }

    @Test
    fun `незакрытая скобка даёт null, а не частичный разбор`() {
        // Частичный разбор хуже отказа: подсказки по половине дескриптора
        // выглядели бы как «аддон объявил не всё», а на деле файл сломан.
        assertNull(parse("addon { id = \"x\" types = ["))
    }

    @Test
    fun `apiVersion без значения считается равным 1`() {
        // Дефолт, а не ошибка: старый аддон без этой строки — обычное дело.
        val s = assertNotNull(parse("addon { id = \"x\" }"))
        assertEquals(1, s.apiVersion)
    }

    @Test
    fun `отсутствующий types даёт пустой список, а не null`() {
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        version = "1"
                    }
                """.trimIndent(),
            ),
        )
        assertTrue(s.types.isEmpty(), "пустой список схеме удобнее, чем разбираться с null")
        assertTrue(s.placeholders.isEmpty())
    }

    @Test
    fun `плейсхолдер без типа считается строкой`() {
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        placeholders = [
                            { name = "seedHash", doc = "Число, из которого вырос плащ." },
                            { name = "dark", type = "int" },
                            { doc = "без имени" }
                        ]
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("seedHash", "dark"), s.placeholders.map { it.name })
        assertEquals(SchemaType.STR, s.placeholders[0].type)
        assertEquals(SchemaType.INT, s.placeholders[1].type)
    }

    @Test
    fun `не-строка в ключе отбрасывается вместе с записью`() {
        // Например `keys = [ "gray" ]` — кто-то забыл фигурные скобки.
        // Молча принять это нельзя: ключ был бы один, а подсказка думает, что
        // их нет, и предлагает пустоту вместо `gray`.
        val s = assertNotNull(
            parse(
                """
                    addon {
                        id = "x"
                        types = [ { id = "seed", keys = [ "gray" ] } ]
                    }
                """.trimIndent(),
            ),
        )
        assertTrue(s.type("seed")!!.keys.isEmpty())
    }

    @Test
    fun `прочитанный дескриптор попадает в схему через setAddonSchemas`() {
        // Связка «дескриптор -> подсказки» целиком: без неё файл читается в
        // пустоту, и подсказки остаются встроенными.
        val s = assertNotNull(parse(good))
        ConfigSchema.setAddonSchemas(listOf(s))
        assertEquals(listOf("seed"), ConfigSchema.addons().flatMap { it.types.map { t -> t.id } })
        assertNotNull(ConfigSchema.addonForType("seed"))
        assertNull(ConfigSchema.addonForType("url"), "встроенный тип аддоном не объявлен")
        assertNotNull(ConfigSchema.addonField("seed", "gray"))
        assertNull(ConfigSchema.addonField("seed", "url"))
    }
}