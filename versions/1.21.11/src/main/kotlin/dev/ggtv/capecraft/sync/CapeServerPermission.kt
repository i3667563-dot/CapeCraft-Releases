package dev.ggtv.capecraft.sync

import java.util.function.Predicate
import net.minecraft.command.permission.Permission
import net.minecraft.command.permission.PermissionLevel
import net.minecraft.server.command.ServerCommandSource

/**
 * Право на серверные команды CapeCraft (`/capecraft`).
 *
 * Отдельный файл — единственное место, где синхронизация упирается в разницу
 * API между версиями. В 1.21.11 Minecraft переписал систему прав:
 * `ServerCommandSource.hasPermissionLevel(int)` удалён, а право проверяется
 * как `permissions.hasPermission(Permission.Level(...))`.
 *
 * MC 1.21.11 (yarn): система `PermissionPredicate`.
 */
object CapeServerPermission {

    /** Gamemaster — тот же уровень, что у ванильных команд перезагрузки. */
    val GAMEMASTER: Predicate<ServerCommandSource> = Predicate { src ->
        src.permissions.hasPermission(Permission.Level(PermissionLevel.GAMEMASTERS))
    }
}
