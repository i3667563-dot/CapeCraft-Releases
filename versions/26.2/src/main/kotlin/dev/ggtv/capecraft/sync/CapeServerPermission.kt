package dev.ggtv.capecraft.sync

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
import java.util.function.Predicate

/**
 * Право на серверные команды CapeCraft (`/capecraft`).
 *
 * Отдельный файл — единственное место, где серверная синхронизация упирается
 * в разницу API между версиями:
 *  - 1.21.1–1.21.10 — `ServerCommandSource.hasPermissionLevel(2)`;
 *  - 1.21.11 — `PermissionPredicate.hasPermission(Permission.Level(...))`;
 *  - 26.2 (Mojang) — система прав целиком переехала в
 *    `net.minecraft.server.permissions`: у источника есть `PermissionSet`,
 *    а «нужен уровень оператора N» выражается как
 *    `hasPermission(Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))`.
 *
 * Последняя форма работает и для обычного `LevelBasedPermissionSet`, и для
 * объединения наборов (`PermissionSetUnion`), в отличие от сравнения уровней
 * через `as?`-cast.
 */
object CapeServerPermission {

    /** Gamemaster — тот же уровень, что у ванильных команд перезагрузки. */
    val GAMEMASTER: Predicate<CommandSourceStack> = Predicate { source ->
        source.permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))
    }
}
