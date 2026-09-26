package dev.ggtv.capecraft.sync

import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Настройки `capeCraft.serverSync`: значения из `.kn` зажимаются в разумные
 * пределы, чтобы опечатка в конфиге не превратилась в DDoS сервера.
 */
class ServerSyncSettingsTest {

    private fun config(vararg pairs: Pair<String, String>): KorenConfig {
        val body = pairs.joinToString("\n") { "    ${it.first} = ${it.second}" }
        return KorenConfig.fromString("capeCraft {\n    serverSync {\n$body\n    }\n}")
    }

    @Test
    fun `defaults are usable as-is`() {
        val s = ServerSyncSettings()
        assertEquals(SyncProtocol.DEFAULT_INTERVAL_TICKS, s.intervalTicks)
        assertEquals(SyncProtocol.DEFAULT_TIMEOUT_TICKS, s.timeoutTicks)
        assertTrue(s.enabled)
        assertFalse(s.requireServer)
        assertFalse(s.allowFileProviders)
    }

    @Test
    fun `the default interval survives clamping`() {
        assertEquals(
            SyncProtocol.DEFAULT_INTERVAL_TICKS,
            ServerSyncSettings().toState().intervalTicks,
            "дефолт не должен превращаться в другое число clamping'ом",
        )
        assertTrue(
            SyncProtocol.DEFAULT_INTERVAL_TICKS >= SyncProtocol.MIN_INTERVAL_TICKS,
            "дефолт меньше минимума",
        )
    }

    @Test
    fun `the default timeout survives clamping`() {
        assertEquals(SyncProtocol.DEFAULT_TIMEOUT_TICKS, ServerSyncSettings().toState().timeoutTicks)
        assertTrue(SyncProtocol.DEFAULT_TIMEOUT_TICKS >= SyncProtocol.MIN_TIMEOUT_TICKS)
    }

    @Test
    fun `values are read from the kn block`() {
        val s = ServerSyncSettings.parse(
            config(
                "enabled" to "false",
                "intervalTicks" to "80",
                "timeoutTicks" to "200",
                "requireServer" to "true",
                "allowFileProviders" to "true",
            ),
        )
        assertFalse(s.enabled)
        assertEquals(80, s.intervalTicks)
        assertEquals(200, s.timeoutTicks)
        assertTrue(s.requireServer)
        assertTrue(s.allowFileProviders)
    }

    @Test
    fun `a missing block means defaults`() {
        val empty = KorenConfig.fromString("capeCraft {\n    providers = []\n}")
        assertEquals(ServerSyncSettings(), ServerSyncSettings.parse(empty))
    }

    @Test
    fun `a broken value falls back to the default instead of failing`() {
        val s = ServerSyncSettings.parse(config("intervalTicks" to "не-число"))
        assertEquals(SyncProtocol.DEFAULT_INTERVAL_TICKS, s.intervalTicks)
    }

    @Test
    fun `an absurdly small interval is clamped`() {
        assertEquals(
            SyncProtocol.MIN_INTERVAL_TICKS,
            ServerSyncSettings.parse(config("intervalTicks" to "1")).toState().intervalTicks,
        )
    }

    @Test
    fun `an absurdly long interval is clamped`() {
        assertEquals(
            SyncProtocol.MAX_INTERVAL_TICKS,
            ServerSyncSettings.parse(config("intervalTicks" to "99999999")).toState().intervalTicks,
        )
    }

    @Test
    fun `a zero timeout is clamped so the reply can still arrive`() {
        assertEquals(
            SyncProtocol.MIN_TIMEOUT_TICKS,
            ServerSyncSettings.parse(config("timeoutTicks" to "0")).toState().timeoutTicks,
        )
    }

    @Test
    fun `negative numbers are clamped, not rejected`() {
        val state = ServerSyncSettings.parse(config("intervalTicks" to "-5", "timeoutTicks" to "-5")).toState()
        assertEquals(SyncProtocol.MIN_INTERVAL_TICKS, state.intervalTicks)
        assertEquals(SyncProtocol.MIN_TIMEOUT_TICKS, state.timeoutTicks)
    }

    @Test
    fun `requireServer reaches the state machine`() {
        assertTrue(ServerSyncSettings.parse(config("requireServer" to "true")).toState().requireServer)
        assertFalse(ServerSyncSettings.DISABLED.toState().enabled)
    }

    @Test
    fun `describe mentions every knob`() {
        val text = ServerSyncSettings.describe(ServerSyncSettings())
        for (key in listOf("enabled", "requireServer", "allowFileProviders")) {
            assertTrue(text.contains(key), "нет $key в «$text»")
        }
    }
}
