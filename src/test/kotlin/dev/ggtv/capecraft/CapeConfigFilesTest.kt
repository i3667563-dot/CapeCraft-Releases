package dev.ggtv.capecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.capecraft.sync.ServerSyncSettings
import dev.ggtv.koren.KorenConfig
import java.nio.file.Path

/**
 * Регресс: конфиг пересоздавался дефолтом на каждом запуске Minecraft.
 *
 * Пользовательский `config/capecraft.kn` затирался, и все правки провайдеров
 * терялись. Проверено на реальных логах инстанса 26.2: восемь запусков
 * подряд (2026-09-11 … 2026-09-13), файл с 4 своими провайдерами сохранил
 * mtime и содержимое — сейчас баг не воспроизводится, и его нельзя вернуть.
 *
 * Тест бьёт по единственной точке, где мод пишет на диск: паре
 * [CapeConfigFiles.active] + [CapeConfigFiles.mustCreateDefault].
 */
class CapeConfigFilesTest {

    private fun write(dir: Path, name: String, text: String): Path {
        val p = dir.resolve(name)
        Files.createDirectories(dir)
        Files.writeString(p, text)
        return p
    }

    @Test
    @DisplayName("нет ни одного файла — читаем .kn и создаём дефолт")
    fun emptyDirCreatesDefault(@TempDir dir: Path) {
        val active = CapeConfigFiles.active(dir)
        assertEquals(CapeConfigFiles.KN_NAME, active.fileName.toString())
        assertTrue(CapeConfigFiles.mustCreateDefault(active))
    }

    @Test
    @DisplayName("существующий .kn НЕ перезаписывается — это и был баг")
    fun existingKnIsNotRecreated(@TempDir dir: Path) {
        val mine = "capeCraft { providers [ { name = \"мой\", type = \"file\" } ] }"
        write(dir, CapeConfigFiles.KN_NAME, mine)

        val active = CapeConfigFiles.active(dir)
        assertFalse(
            CapeConfigFiles.mustCreateDefault(active),
            "существующий конфиг нельзя пересоздавать",
        )
        // Содержимое живо после всех проверок, что имитирует запуск игры.
        assertEquals(mine, Files.readString(active))
    }

    @Test
    @DisplayName("многократный запуск не меняет конфиг (тот же сценарий, 3 раза)")
    fun repeatedLoadsDoNotTouchConfig(@TempDir dir: Path) {
        val mine = "capeCraft { providers [ { name = \"мой\" } ] } # моя правка"
        write(dir, CapeConfigFiles.KN_NAME, mine)
        val before = Files.getLastModifiedTime(dir.resolve(CapeConfigFiles.KN_NAME))

        repeat(3) {
            val active = CapeConfigFiles.active(dir)
            if (CapeConfigFiles.mustCreateDefault(active)) {
                Files.writeString(active, "ДЕФОЛТ")
            }
        }

        assertEquals(mine, Files.readString(dir.resolve(CapeConfigFiles.KN_NAME)))
        assertEquals(before, Files.getLastModifiedTime(dir.resolve(CapeConfigFiles.KN_NAME)))
    }

    @Test
    @DisplayName("legacy .crn читается и тоже не перезаписывается")
    fun legacyCrnIsNotOverwritten(@TempDir dir: Path) {
        val legacy = "capeCraft { providers [ { name = \"старый\" } ] }"
        write(dir, CapeConfigFiles.CRN_NAME, legacy)

        val active = CapeConfigFiles.active(dir)
        assertEquals(CapeConfigFiles.CRN_NAME, active.fileName.toString())
        assertFalse(CapeConfigFiles.mustCreateDefault(active))
        assertEquals(legacy, Files.readString(active))
    }

    @Test
    @DisplayName("при наличии обоих файлов приоритет у .kn, .crn не трогаем")
    fun knWinsOverLegacy(@TempDir dir: Path) {
        write(dir, CapeConfigFiles.KN_NAME, "новый")
        write(dir, CapeConfigFiles.CRN_NAME, "старый")

        val active = CapeConfigFiles.active(dir)
        assertEquals(CapeConfigFiles.KN_NAME, active.fileName.toString())
        assertFalse(CapeConfigFiles.mustCreateDefault(active))
        assertEquals("старый", Files.readString(dir.resolve(CapeConfigFiles.CRN_NAME)))
    }

    @Test
    @DisplayName("мёртвый симлинк на .kn не считается существующим конфигом")
    fun brokenSymlinkFallsBackToCrn(@TempDir dir: Path) {
        write(dir, CapeConfigFiles.CRN_NAME, "legacy")
        Files.createSymbolicLink(dir.resolve(CapeConfigFiles.KN_NAME), dir.resolve("нет-такого.kn"))

        // exists() == false для битого линка, поэтому .kn не выбирается.
        val active = CapeConfigFiles.active(dir)
        assertEquals(CapeConfigFiles.CRN_NAME, active.fileName.toString())
    }

    @Test
    fun `дефолтный шаблон валиден и без мёртвых ключей v1`() {
        val text = DEFAULT_KN_TEXT
        // Ключи v1 в ServerSyncSettings больше не читаются. Пока они были в
        // шаблоне, новичок видел в своём конфиге опрос по таймеру и
        // `allowFileProviders`, которых в протоколе v2 нет.
        // Проверяем присваивания, а не любое упоминание: в комментарии про
        // миграцию ключи v1 назвать нужно, чтобы человек понял, куда делись
        // его настройки. Задавать их нельзя — их никто не читает.
        for (dead in listOf("intervalTicks", "timeoutTicks", "requireServer", "allowFileProviders")) {
            assertFalse(
                text.contains("$dead ="),
                "в дефолтном шаблоне осталось присваивание мёртвого ключа v1: $dead",
            )
        }
        for (live in listOf("shareLocalProviders", "allowForeignUrls", "backoff")) {
            assertTrue(text.contains(live), "в шаблоне нет ключа v2: $live")
        }
        // Заглушки закомментированы: example.com недостижим, и чистая
        // установка не должна никуда ходить сама.
        // Заглушка `example.com` недостижима: на чистой установке она должна
        // остаться закомментированной, иначе мод сам пойдёт по мёртвой ссылке.
        for (line in text.lines().filter { it.contains("name = \"example\"") }) {
            assertTrue(
                line.trimStart().startsWith("#"),
                "провайдер-заглушка снова активен — новая установка полезет в example.com: $line",
            )
        }
    }

    @Test
    fun `дефолтный шаблон парсится и даёт дефолтные настройки v2`(@TempDir dir: Path) {
        val path = dir.resolve(CapeConfigFiles.KN_NAME)
        Files.writeString(path, DEFAULT_KN_TEXT)

        val cfg = KorenConfig.load(path)
        assertEquals(
            0,
            ProviderLoader.load(cfg).size,
            "активных провайдеров в шаблоне быть не должно — иначе чистая установка снова получит два",
        )
        val sync = ServerSyncSettings.parse(cfg)
        assertTrue(sync.enabled)
        assertFalse(sync.shareLocalProviders, "раздача своих файлов по умолчанию выключена")
        assertFalse(sync.allowForeignUrls, "чужие адреса по умолчанию выключены")
        assertTrue(sync.backoff)
    }
}
