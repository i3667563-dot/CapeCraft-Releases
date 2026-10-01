package dev.ggtv.capecraft.fire

import dev.ggtv.capecraft.api.CapeAddon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * То, что видно только в собранном jar'е.
 *
 * Ошибки этого класса не ловит ни один другой тест: класс на месте, но точка
 * входа в `fabric.mod.json` написана с опечаткой — и аддон молча не грузится в
 * игре, где мод работает, а в редакторе подсказки есть. Проверяется ровно тот
 * jar, который уедет игроку.
 */
class PackagedJarTest {

    private fun jar(): ZipFile {
        val file = checkNotNull(
            File("build/libs")
                .listFiles()
                ?.firstOrNull { it.name.endsWith(".jar") && !it.name.contains("sources") },
        ) { "в build/libs нет собранного jar'а аддона" }
        return ZipFile(file)
    }

    @Test
    fun `в jar есть класс из точки входа и дескриптор`() {
        jar().use { zip ->
            val mod = zip.getInputStream(zip.getEntry("fabric.mod.json")).readBytes().decodeToString()
            val entrypoint = Regex("\"value\"\\s*:\\s*\"([^\"]+)\"")
                .find(mod)!!.groupValues[1]
            assertEquals("dev.ggtv.capecraft.fire.FireCapeAddon", entrypoint)
            assertNotNull(
                zip.getEntry(entrypoint.replace('.', '/') + ".class"),
                "в jar нет класса, указанного точкой входа: $entrypoint",
            )
            assertNotNull(zip.getEntry("capecraft-addon.kn"), "в jar нет дескриптора")
            assertTrue(
                mod.contains("\"capecraft:addons\""),
                "точка входа аддона не в том разделе — мод её не найдёт",
            )
        }
    }

    @Test
    fun `версия в jar совпадает с версией сборки`() {
        jar().use { zip ->
            val mod = zip.getInputStream(zip.getEntry("fabric.mod.json")).readBytes().decodeToString()
            assertTrue(
                mod.contains("\"version\": \"1.0.0\""),
                "версия не подставилась в fabric.mod.json: $mod",
            )
        }
    }

    @Test
    fun `байт-код не новее Java 21`() {
        // Ровно на этом аддон уже падал в игре: class file 70 (Java 26) не
        // читается JVM 25, и падение выглядит как «мод сломался», хотя сломался
        // аддон, который даже не успел зарегистрироваться. Формат класса
        // первые восемь байт: magic, minor, major — major и есть версия.
        val maxMajor = 65 // Java 21
        jar().use { zip ->
            for (name in listOf(
                "dev/ggtv/capecraft/fire/FireCapeAddon.class",
                "dev/ggtv/capecraft/fire/FireCapeAddon\$register\$1.class",
            )) {
                val head = zip.getInputStream(zip.getEntry(name)).readNBytes(8)
                val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                assertTrue(
                    major <= maxMajor,
                    "$name собран под class file $major, а игра читает максимум $maxMajor",
                )
            }
        }
    }

    @Test
    fun `аддон реализует CapeAddon`() {
        assertTrue(CapeAddon::class.java.isAssignableFrom(FireCapeAddon::class.java))
    }
}