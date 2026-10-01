package dev.ggtv.capecraft.schema

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поиск дескрипторов в папке `mods` рядом с конфигом.
 *
 * Проверяется то, на чём держится вся идея: путь к папке выводится из uri
 * документа, без единой настройки у пользователя. Поэтому отдельно проверяется
 * подъём вверх по дереву, отказ от чужих папок и кэш — иначе либо подсказки
 * молча не появятся, либо появятся от чужого набора модов.
 */
class AddonSchemaFinderTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        AddonSchemaFinder.clearCache()
        ConfigSchema.clearAddonSchemas()
        root = Files.createTempDirectory("capecraft-lsp")
    }

    @AfterTest
    fun tearDown() {
        AddonSchemaFinder.clearCache()
        ConfigSchema.clearAddonSchemas()
        root.toFile().deleteRecursively()
    }

    /** Настоящий jar: zip обязателен, `ZipFile` не берёт мусор. */
    private fun jar(path: Path, entries: Map<String, String>): Path {
        path.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }

    private fun descriptor(id: String, types: String = "seed") = """
        addon {
            id = "$id"
            version = "1.0.0"
            apiVersion = 1
            types = [ { id = "$types", keys = [ { name = "gray", type = "bool" } ] } ]
        }
    """.trimIndent()

    private fun config() = root.resolve("config/capecraft.kn").also {
        it.parent.createDirectories()
        it.writeText("capeCraft { }\n")
    }

    @Test
    fun `находит дескриптор в jar рядом с конфигом`() {
        jar(root.resolve("mods/capecraft-seed.jar"), mapOf("capecraft-addon.kn" to descriptor("capecraft-seed")))
        val found = AddonSchemaFinder.forConfig(config())
        assertEquals(listOf("capecraft-seed"), found.map { it.id })
        assertEquals(listOf("gray"), found[0].type("seed")!!.keys.map { it.name })
    }

    @Test
    fun `папка mods ищется вверх от конфига`() {
        // Типичный расклад: конфиг в config/, моды уровнем выше. Без подъёма
        // подсказки не появлялись бы ни у кого.
        jar(root.resolve("mods/a.jar"), mapOf("capecraft-addon.kn" to descriptor("a")))
        val nested = root.resolve("config/sub/dir/capecraft.kn")
        nested.parent.createDirectories()
        nested.writeText("capeCraft { }\n")
        assertNotNull(AddonSchemaFinder.findModsDir(nested))
        assertEquals(listOf("a"), AddonSchemaFinder.forConfig(nested).map { it.id })
    }

    @Test
    fun `мод без дескриптора молча игнорируется`() {
        jar(root.resolve("mods/capecraft.jar"), mapOf("fabric.mod.json" to "{}"))
        assertTrue(
            AddonSchemaFinder.forConfig(config()).isEmpty(),
            "мод без дескриптора не аддон для подсказок",
        )
    }

    @Test
    fun `сломанный дескриптор одного аддона не отменяет остальные`() {
        // Аддон у разработчика может быть битый. Подсказки второго аддона от
        // этого пропасть не должны.
        jar(root.resolve("mods/broken.jar"), mapOf("capecraft-addon.kn" to "addon { id = \"x\""))
        jar(root.resolve("mods/good.jar"), mapOf("capecraft-addon.kn" to descriptor("good")))
        assertEquals(listOf("good"), AddonSchemaFinder.forConfig(config()).map { it.id })
    }

    @Test
    fun `sources-jar не считается аддоном`() {
        // У установленного мода часто лежит sources-jar с тем же дескриптором.
        // Без фильтра подсказки получили бы аддон дважды.
        jar(
            root.resolve("mods/capecraft-seed-sources.jar"),
            mapOf("capecraft-addon.kn" to descriptor("capecraft-seed")),
        )
        assertTrue(AddonSchemaFinder.forConfig(config()).isEmpty())
    }

    @Test
    fun `папки без mods даёт пустой список, а не ошибку`() {
        // Конфиг в папке без установленных модов — обычное дело, а не поломка.
        assertTrue(AddonSchemaFinder.forConfig(config()).isEmpty())
        assertNull(AddonSchemaFinder.findModsDir(config()))
    }

    @Test
    fun `не-jar в mods не считается аддоном`() {
        jar(root.resolve("mods/capecraft-seed.jar"), mapOf("capecraft-addon.kn" to descriptor("a")))
        root.resolve("mods/какая-то папка").createDirectories()
        root.resolve("mods/readme.txt").writeText("не jar\n")
        assertEquals(listOf("a"), AddonSchemaFinder.forConfig(config()).map { it.id })
    }

    @Test
    fun `подъём по дереву ограничен`() {
        // `mods` соседнего проекта не должен подхватываться: подсказки от чужого
        // набора аддонов путают больше, чем их отсутствие.
        jar(root.resolve("mods/сосед.jar"), mapOf("capecraft-addon.kn" to descriptor("сосед")))
        val deep = root.resolve("a/b/c/d/e/f/config/capecraft.kn")
        deep.parent.createDirectories()
        deep.writeText("capeCraft { }\n")
        assertNull(AddonSchemaFinder.findModsDir(deep), "слишком глубоко подниматься нельзя")
        assertTrue(AddonSchemaFinder.forConfig(deep).isEmpty())
    }

    @Test
    fun `после установки аддона подсказки появляются без перезапуска редактора`() {
        // Кэш живёт в процессе LSP. Установка аддона меняет содержимое mods, и
        // подсказки обязаны появиться при следующем открытии файла — иначе
        // пришлось бы перезапускать редактор после установки каждого аддона.
        assertTrue(AddonSchemaFinder.forConfig(config()).isEmpty())
        jar(root.resolve("mods/late.jar"), mapOf("capecraft-addon.kn" to descriptor("late")))
        assertEquals(listOf("late"), AddonSchemaFinder.forConfig(config()).map { it.id })
    }

    @Test
    fun `пересборка аддона обновляет кэш`() {
        val jarPath = jar(
            root.resolve("mods/x.jar"),
            mapOf("capecraft-addon.kn" to descriptor("x", types = "seed")),
        )
        assertEquals(listOf("seed"), AddonSchemaFinder.forConfig(config())[0].types.map { it.id })

        jarPath.toFile().delete()
        jar(jarPath, mapOf("capecraft-addon.kn" to descriptor("x", types = "image")))
        assertEquals(listOf("image"), AddonSchemaFinder.forConfig(config())[0].types.map { it.id })
    }

    @Test
    fun `путь к источнику попадает в дескриптор`() {
        // По нему в сообщении об ошибке видно, чей jar сломан.
        jar(root.resolve("mods/capecraft-seed.jar"), mapOf("capecraft-addon.kn" to descriptor("a")))
        assertEquals("capecraft-seed.jar", AddonSchemaFinder.forConfig(config())[0].origin)
    }
}