package dev.ggtv.capecraft.sync

import dev.ggtv.koren.KorenConfig

/**
 * Настройки клиентской синхронизации с сервером — блок `capeCraft.serverSync`
 * в `config/capecraft.kn`.
 *
 * ```
 * capeCraft {
 *     serverSync {
 *         enabled          = true   # вообще опрашивать сервер
 *         intervalTicks    = 40     # как часто (40 тиков = 2 сек)
 *         timeoutTicks     = 100    # сколько ждать ответа (5 сек)
 *         requireServer    = false  # true: без ответа сервера локальный набор НЕ используется
 *         allowFileProviders = false # разрешить серверу присылать `type = file`
 *     }
 * }
 * ```
 *
 * Значения зажимаются в разумные пределы ([SyncProtocol]), чтобы опечатка в
 * конфиге (например `intervalTicks = 1`) не превратилась в DDoS сервера
 * или в вечное ожидание.
 */
data class ServerSyncSettings(
    val enabled: Boolean = true,
    val intervalTicks: Int = SyncProtocol.DEFAULT_INTERVAL_TICKS,
    val timeoutTicks: Int = SyncProtocol.DEFAULT_TIMEOUT_TICKS,
    val requireServer: Boolean = false,
    val allowFileProviders: Boolean = false,
) {
    /** Настройки для машины состояний [CapeSyncState] с уже зажатыми числами. */
    fun toState(): CapeSyncState = CapeSyncState(
        enabled = enabled,
        intervalTicks = clamp(intervalTicks, SyncProtocol.MIN_INTERVAL_TICKS, SyncProtocol.MAX_INTERVAL_TICKS),
        timeoutTicks = clamp(timeoutTicks, SyncProtocol.MIN_TIMEOUT_TICKS, SyncProtocol.MAX_INTERVAL_TICKS),
        requireServer = requireServer,
    )

    companion object {
        /** Блок в `.kn`, из которого читаются настройки. */
        const val ROOT: String = "capeCraft.serverSync"

        /** Выключенная синхронизация. */
        val DISABLED: ServerSyncSettings = ServerSyncSettings(enabled = false)

        /** Прочитать блок; любой промах (нет блока, битое значение) — дефолт. */
        fun parse(cfg: KorenConfig): ServerSyncSettings {
            val d = ServerSyncSettings()
            return ServerSyncSettings(
                enabled = bool(cfg, "enabled", d.enabled),
                intervalTicks = clamp(int(cfg, "intervalTicks", d.intervalTicks), SyncProtocol.MIN_INTERVAL_TICKS, SyncProtocol.MAX_INTERVAL_TICKS),
                timeoutTicks = clamp(int(cfg, "timeoutTicks", d.timeoutTicks), SyncProtocol.MIN_TIMEOUT_TICKS, SyncProtocol.MAX_INTERVAL_TICKS),
                requireServer = bool(cfg, "requireServer", d.requireServer),
                allowFileProviders = bool(cfg, "allowFileProviders", d.allowFileProviders),
            )
        }

        private fun int(cfg: KorenConfig, key: String, def: Int): Int = try {
            cfg.getInt("$ROOT.$key").toInt()
        } catch (_: Exception) {
            def
        }

        private fun bool(cfg: KorenConfig, key: String, def: Boolean): Boolean = try {
            cfg.getBool("$ROOT.$key")
        } catch (_: Exception) {
            def
        }

        private fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

        /** Читаемый текст для `/cp status`. */
        fun describe(s: ServerSyncSettings): String =
            "enabled=${s.enabled}, интервал=${s.intervalTicks} тиков, " +
                "таймаут=${s.timeoutTicks} тиков, requireServer=${s.requireServer}, " +
                "allowFileProviders=${s.allowFileProviders}"
    }
}
