package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.CapeConfigEnv
import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.kjen.CrenError
import dev.ggtv.koren.KorenConfig

/**
 * Настройки клиентской синхронизации с сервером — блок `capeCraft.serverSync`
 * в `config/capecraft.kn`.
 *
 * ```
 * capeCraft {
 *     serverSync {
 *         enabled             = true   # вообще объявлять набор и принимать чужие
 *         shareLocalProviders = false  # true: отдавать свой локальный `file`-плащ всем
 *         allowForeignUrls    = false  # true: принимать чужие `http`-адреса
 *         backoff             = true   # замедлять запросы картинок, пока нет ответа
 *     }
 * }
 * ```
 *
 * ## Что изменилось в v2
 *
 * В v1 здесь был опрос: `intervalTicks`, `timeoutTicks`, `requireServer`.
 * В v2 опроса нет — клиент объявляет набор один раз при входе и при
 * `/cp reload`, а обновления приходят рассылкой. Поэтомуinterval-ов больше
 * нет, и `requireServer` тоже: сервер не диктует, что носить, он только
 * объясняет, у какого объекта какой набор. Без ростера у чужого объекта нет
 * функций, и это не поломка, а норма — надевать на него свой конфиг нельзя
 * (см. [dev.ggtv.capecraft.CapeRegistry.orderFor]).
 *
 * Ключи `intervalTicks`, `timeoutTicks`, `requireServer`, `allowFileProviders`
 * больше не читаются. Оставлены в конфиге — они просто игнорируются.
 *
 * `backoff` остался и теперь относится к докачке картинок, а не к опросу.
 *
 */
data class ServerSyncSettings(
    val enabled: Boolean = true,

    /**
     * Отдавать ли другим свои локальные `file`-плащи.
     *
     * По умолчанию **выключено**, и это осознанно: включение означает, что
     * байты твоего плаща уезжают на сервер и оттуда ко всем остальным. Локальный
     * файл — это личное, и по умолчанию оно не раздаётся. `http`/`json` и так
     * видны всем, кто откроет ссылку, — там отдельного согласия не нужно.
     */
    val shareLocalProviders: Boolean = false,

    /**
     * Разрешать ли чужие объявления с произвольным `http`-адресом.
     *
     * По умолчанию **выключено**: иначе любой клиент в чате может объявить
     * функцию, чей адрес указывает на его хост, и каждый, кто на меня
     * посмотрит, схватит этот адрес. Это превращает мой рендер в маячок.
     * Включать только если доверяешь всем в лобби.
     */
    val allowForeignUrls: Boolean = false,

    val backoff: Boolean = true,
) {
    /** Настройки для машины состояний [CapeSyncState] с уже зажатыми числами. */
    fun toState(): CapeSyncState = CapeSyncState(
        enabled = enabled,
        maxRetryTicks = SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS,
        backoff = backoff,
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
                shareLocalProviders = bool(cfg, "shareLocalProviders", d.shareLocalProviders),
                allowForeignUrls = bool(cfg, "allowForeignUrls", d.allowForeignUrls),
                backoff = bool(cfg, "backoff", d.backoff),
            )
        }

        /**
         * Флаг из `.kn`, иначе дефолт.
         *
         * Отсутствие ключа берём молча, несовпадение типа — нет: пользователь
         * написал значение, а мод затихо заменил его своим. Подстановка
         * окружения (`enabled = "${CAPE_SYNC}"`) всегда даёт строку, поэтому
         * для булевых нужен [CapeConfigEnv].
         */
        private fun bool(cfg: KorenConfig, key: String, def: Boolean): Boolean {
            val path = "$ROOT.$key"
            val fromFile = try {
                cfg.getBool(path)
            } catch (_: CrenError.NotFound) {
                def
            } catch (e: CrenError.TypeMismatch) {
                CapeCraftLog.LOGGER.warn(
                    "{}: в конфиге ожидалось {}, а там строка — беру дефолт {}. " +
                        "Подстановка окружения всегда даёт строку, для флага нужно {} " +
                        "или -D{}=...",
                    path,
                    e.expected,
                    def,
                    CapeConfigEnv.variableFor(path),
                    path,
                )
                def
            }
            return CapeConfigEnv.booleanOr(path, fromFile)
        }

        /** Читаемый текст для `/cp status`. */
        fun describe(s: ServerSyncSettings): String =
            "enabled=${s.enabled}, shareLocal=${s.shareLocalProviders}, " +
                "allowForeignUrls=${s.allowForeignUrls}, backoff=${s.backoff}"
    }
}
