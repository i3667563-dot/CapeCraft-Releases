package dev.ggtv.capecraft.sync

import java.util.function.Predicate
import net.minecraft.server.command.ServerCommandSource

/**
 * Право на серверные команды CapeCraft (`/capecraft`).
 *
 * Отдельный файл — единственное место, где синхронизация упирается в разницу
 * API между версиями: в 1.21.11 Minecraft переписал систему прав на
 * `PermissionPredicate`, и `hasPermissionLevel(2)` там больше не существует
 * (см. вариант в `versions/26.2` и в `1.21.11`).
 *
 * MC 1.21.x (yarn, 1.21.1–1.21.10): обычные числовые уровни прав.
 */
object CapeServerPermission {

    /** Уровень 2 — gamemaster: тот же, что у ванильных команд перезагрузки. */
    val GAMEMASTER: Predicate<ServerCommandSource> = Predicate { it.hasPermissionLevel(2) }
}
