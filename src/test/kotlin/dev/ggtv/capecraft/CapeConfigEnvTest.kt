package dev.ggtv.capecraft

import dev.ggtv.capecraft.sync.ServerSyncSettings
import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Конфигурация из окружения: переменные `CAPECRAFT_*` и `-Dcapecraft.*`.
 *
 * `System.getenv` в тестах не подставляется, поэтому [CapeConfigEnv.overrideSourcesForTest]
 * подменяет источники. Проверяем ровно то, на что натыкается пользователь
 * лаунчера: значение из окружения побеждает файл, битое значение не ломает
 * мод, а отсутствие переменных ничего не меняет.
 */
class CapeConfigEnvTest {

    @AfterEach
    fun tearDown() = CapeConfigEnv.resetSources()

    private fun env(vararg pairs: Pair<String, String>) = CapeConfigEnv.overrideSourcesForTest(
        env = pairs.toMap(),
        properties = emptyMap(),
    )

    private fun props(vararg pairs: Pair<String, String>) = CapeConfigEnv.overrideSourcesForTest(
        env = emptyMap(),
        properties = pairs.toMap(),
    )

    // ── имя переменной выводится механически ─────────────────────────────────

    @Test
    fun `имя переменной строится из ключа конфига`() {
        assertEquals("CAPECRAFT_LIMITS_MAXFRAMES", CapeConfigEnv.variableFor("capeCraft.limits.maxFrames"))
        assertEquals("CAPECRAFT_SERVERSYNC_ALLOWFILEPROVIDERS", CapeConfigEnv.variableFor("capeCraft.serverSync.allowFileProviders"))
        assertEquals("CAPECRAFT_CONFIG", CapeConfigEnv.variableFor("config"))
    }

    @Test
    fun `без переменных значение не появляется`() {
        env()
        assertNull(CapeConfigEnv.lookup("capeCraft.limits.maxFrames"))
        assertEquals(42L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 42))
        assertTrue(CapeConfigEnv.booleanOr("capeCraft.serverSync.enabled", true))
    }

    // ── приоритеты ───────────────────────────────────────────────────────────

    @Test
    fun `переменная окружения читается`() {
        env("CAPECRAFT_LIMITS_MAXFRAMES" to "7")
        assertEquals(7L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))
    }

    @Test
    fun `свойство JVM читается по имени переменной и по точному ключу`() {
        props("CAPECRAFT_LIMITS_MAXFRAMES" to "5")
        assertEquals(5L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))

        props("capecraft_limits_maxframes" to "6")
        assertEquals(6L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))

        props("capeCraft.limits.maxFrames" to "8")
        assertEquals(8L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))
    }

    @Test
    fun `переменная окружения важнее свойства JVM`() {
        CapeConfigEnv.overrideSourcesForTest(
            env = mapOf("CAPECRAFT_LIMITS_MAXFRAMES" to "7"),
            properties = mapOf("capecraft.limits.maxframes" to "5"),
        )
        assertEquals(7L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))
    }

    // ── битые значения не ломают мод ─────────────────────────────────────────

    @Test
    fun `нечисловое значение отбрасывается с предупреждением, а не падает`() {
        env("CAPECRAFT_LIMITS_MAXFRAMES" to "много")
        assertEquals(99L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))
    }

    @Test
    fun `мусорное значение флага отбрасывается`() {
        env("CAPECRAFT_SERVERSYNC_ENABLED" to "да")
        assertTrue(CapeConfigEnv.booleanOr("capeCraft.serverSync.enabled", true))
    }

    @Test
    fun `булевы значения понимаются в обычных для лаунчера форматах`() {
        for (truthy in listOf("true", "TRUE", "yes", "on", "1")) {
            env("CAPECRAFT_SERVERSYNC_ENABLED" to truthy)
            assertTrue(CapeConfigEnv.booleanOr("capeCraft.serverSync.enabled", false), truthy)
        }
        for (falsy in listOf("false", "FALSE", "no", "off", "0")) {
            env("CAPECRAFT_SERVERSYNC_ENABLED" to falsy)
            assertEquals(false, CapeConfigEnv.booleanOr("capeCraft.serverSync.enabled", true), falsy)
        }
    }

    @Test
    fun `пробелы вокруг значения не мешают`() {
        env("CAPECRAFT_LIMITS_MAXFRAMES" to "  12  ")
        assertEquals(12L, CapeConfigEnv.longOr("capeCraft.limits.maxFrames", 99))
    }

    // ── переопределение бьёт значение из файла ────────────────────────────────

    @Test
    fun `переменная важнее значения из файла конфига`() {
        val cfg = serverSync("intervalTicks" to "40", "backoff" to "true")
        env("CAPECRAFT_SERVERSYNC_INTERVALTICKS" to "80")
        val s = ServerSyncSettings.parse(cfg)
        assertEquals(80, s.intervalTicks)
        assertTrue(s.backoff, "опция без переменной должна остаться из файла")
    }

    @Test
    fun `без переменных настройки остаются файловыми`() {
        val cfg = serverSync("intervalTicks" to "40", "requireServer" to "true")
        env()
        val s = ServerSyncSettings.parse(cfg)
        assertEquals(40, s.intervalTicks)
        assertTrue(s.requireServer)
    }

    @Test
    fun `флаг синхронизации выключается переменной`() {
        val cfg = serverSync("enabled" to "true")
        env("CAPECRAFT_SERVERSYNC_ENABLED" to "false")
        assertEquals(false, ServerSyncSettings.parse(cfg).enabled)
    }

    // ── CAPECRAFT_CONFIG: альтернативный файл ────────────────────────────────

    @Test
    fun `без переменной файла конфига нет`(@TempDir dir: Path) {
        env()
        assertNull(CapeConfigEnv.configFile())
    }

    @Test
    fun `CAPECRAFT_CONFIG указывает на существующий файл`(@TempDir dir: Path) {
        val cfgFile = write(dir, "capeCraft { }")
        env("CAPECRAFT_CONFIG" to cfgFile.toString())
        val resolved = CapeConfigEnv.configFile()
        assertNull(resolved?.error)
        assertEquals(cfgFile.toString(), resolved?.path)
    }

    @Test
    fun `несуществующий файл из переменной даёт ошибку, а не тишину`(@TempDir dir: Path) {
        env("CAPECRAFT_CONFIG" to dir.resolve("нет-такого.kn").toString())
        val resolved = CapeConfigEnv.configFile()
        assertTrue(resolved != null, "переменная задана — путь должен возвращаться")
        assertTrue(resolved.error!!.contains("не найден"), "ожидалась ошибка о файле, а не успех")
    }

    @Test
    fun `пустое значение означает использовать обычный файл`() {
        env("CAPECRAFT_CONFIG" to "   ")
        assertNull(CapeConfigEnv.configFile())
    }

    @Test
    fun `путь файла настраивается свойством JVM`(@TempDir dir: Path) {
        val cfgFile = write(dir, "capeCraft {\n}\n")
        for (property in listOf("capecraft.config", "capeCraft.config", "config")) {
            props(property to cfgFile.toString())
            assertEquals(cfgFile.toString(), CapeConfigEnv.configFile()?.path, property)
        }
    }

    @Test
    fun `путь файла настраивается именем переменной`(@TempDir dir: Path) {
        val cfgFile = write(dir, "capeCraft {\n}\n")
        props("CAPECRAFT_CONFIG" to cfgFile.toString())
        assertEquals(cfgFile.toString(), CapeConfigEnv.configFile()?.path)
    }

    /** Блок `capeCraft.serverSync` с одним значением на строку — формат .kn строгий. */
    @Test
    fun `активные переопределения видны в статусе`() {
        env(
            "CAPECRAFT_LIMITS_MAXFRAMES" to "20",
            "CAPECRAFT_SERVERSYNC_ENABLED" to "false",
        )
        val lines = CapeConfigEnv.activeOverrides()
        assertEquals(
            listOf(
                "CAPECRAFT_LIMITS_MAXFRAMES=20",
                "CAPECRAFT_SERVERSYNC_ENABLED=false",
            ),
            lines,
        )
    }

    @Test
    fun `без переопредений статус молчит`() {
        env()
        assertTrue(CapeConfigEnv.activeOverrides().isEmpty())
    }

    @Test
    fun `в статусе путь файла приведён к нормальному виду`(@TempDir dir: Path) {
        val cfgFile = write(dir, "capeCraft {\n}\n")
        env("CAPECRAFT_CONFIG" to "  ${cfgFile}  ")
        assertEquals(listOf("CAPECRAFT_CONFIG=$cfgFile"), CapeConfigEnv.activeOverrides())
    }


    private fun serverSync(vararg pairs: Pair<String, String>): KorenConfig {
        val body = pairs.joinToString("\n") { "    ${it.first} = ${it.second}" }
        return KorenConfig.fromString("capeCraft {\n    serverSync {\n$body\n    }\n}")
    }

    private fun write(dir: Path, text: String): Path =
        Files.writeString(dir.resolve("capecraft.kn"), text)
}
