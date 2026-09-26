package dev.ggtv.capecraft.sync

import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Настройки Sync v2.
 *
 * Главное, что тут проверяется, — **значения по умолчанию**. В v2 появились
 * два флага, выключающих то, что раньше было безусловно: раздачу своего
 * локального файла и доверие чужим `http`-адресам. Оба по умолчанию `false`
 * не из осторожности, а потому что оба включают передачу личного: первый
 * увозит байты твоего плаща всем, второй даёт чужим клиентам возможность
 * втянуть тебя в запрос к их адресу.
 */
class ServerSyncSettingsTest {

    /** Конфиг собирается блоком: точечный `a.b.c = v` Cren не понимает. */
    private fun config(vararg pairs: Pair<String, String>): KorenConfig {
        val body = pairs.joinToString("\n") { (k, v) -> "        $k = $v" }
        return KorenConfig.fromString(
            """
            |capeCraft {
            |    serverSync {
            |$body
            |    }
            |}
            """.trimMargin(),
        )
    }

    @Test
    fun `локальный файл по умолчанию не раздаётся`() {
        assertFalse(ServerSyncSettings().shareLocalProviders, "личный файл не должен уезжать без явного согласия")
    }

    @Test
    fun `чужие http-адреса по умолчанию не принимаются`() {
        assertFalse(
            ServerSyncSettings().allowForeignUrls,
            "иначе любой клиент может заставить всех загрузить его адрес",
        )
    }

    @Test
    fun `синхронизация по умолчанию включена, backoff включён`() {
        val d = ServerSyncSettings()
        assertTrue(d.enabled)
        assertTrue(d.backoff)
    }

    @Test
    fun `флаги читаются из конфига`() {
        val s = ServerSyncSettings.parse(
            config("shareLocalProviders" to "true", "allowForeignUrls" to "true", "enabled" to "false"),
        )
        assertTrue(s.shareLocalProviders)
        assertTrue(s.allowForeignUrls)
        assertFalse(s.enabled)
    }

    @Test
    fun `пустой конфиг даёт умолчания`() {
        val s = ServerSyncSettings.parse(KorenConfig.fromString(""))
        assertTrue(s.enabled)
        assertFalse(s.shareLocalProviders)
        assertFalse(s.allowForeignUrls)
    }

    @Test
    fun `битое значение не ломает разбор, а берётся умолчание`() {
        val s = ServerSyncSettings.parse(
            config("shareLocalProviders" to "не_булево", "enabled" to "true"),
        )
        assertTrue(s.enabled, "живой ключ должен читаться")
        assertFalse(s.shareLocalProviders, "битое значение должно упасть в умолчание, а не в true")
    }

    @Test
    fun `удалённые ключи v1 игнорируются, а не превращаются в v2-флаги`() {
        // Самое опасное, что тут могло сломаться: allowFileProviders из v1
        // молча стал бы shareLocalProviders, и старый конфиг за молча начал бы
        // раздавать локальные файлы всем. Не должен.
        val s = ServerSyncSettings.parse(
            config(
                "requireServer" to "true",
                "allowFileProviders" to "true",
                "intervalTicks" to "1",
                "timeoutTicks" to "1",
            ),
        )
        assertFalse(s.shareLocalProviders, "allowFileProviders из v1 не должен включать раздачу файлов")
        assertFalse(s.allowForeignUrls, "requireServer из v1 не должен включать чужие адреса")
        assertTrue(s.enabled, "отсутствующий ключ enabled = умолчание true")
    }

    @Test
    fun `состояние собирается из настроек`() {
        val s = ServerSyncSettings(enabled = false, backoff = false).toState()
        assertFalse(s.enabled)
        assertFalse(s.backoff)
    }

    @Test
    fun `описание не упоминает удалённые ключи`() {
        val text = ServerSyncSettings.describe(ServerSyncSettings())
        assertFalse(text.contains("requireServer"), "в описании остался ключ из v1: $text")
        assertFalse(text.contains("intervalTicks"), "в описании остался ключ из v1: $text")
        assertTrue(text.contains("shareLocal"), "новый ключ должен быть виден в статусе")
    }
}
