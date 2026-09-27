package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * [WorldContext] сервера: читает состояние мира конкретного игрока.
 *
 * Нужен, чтобы условия провайдеров (`when { time.period: "night" }`)
 * решались на СЕРВЕРЕ — там, где живёт настоящий мир игрока, а не
 * догадка клиента. Значения совпадают с клиентским
 * [MinecraftWorldContext], чтобы сервер и клиент приходили к одному
 * выбору провайдера.
 *
 * Все обращения к MC API обёрнуты в try-catch: смена API между версиями
 * даёт "unknown" вместо краша.
 *
 * MC 26.2: необфусцированные имена Mojang. Отличия от 1.21.x:
 *  - `ServerPlayerEntity` → [ServerPlayer], мир — `player.level()`;
 *  - `world.getBiome(pos)` → `world.biomeManager.getBiome(pos)`;
 *  - `Biome.temperature` → [net.minecraft.world.level.biome.Biome.getBaseTemperature];
 *  - `world.timeOfDay` → [Level.getOverworldClockTime] (в 26.2 отдельного
 *    `getDayTime()` у уровня больше нет);
 *  - `world.getRegistryKey()` → [Level.dimension], `World.NETHER` → [Level.NETHER].
 */
class ServerWorldContext(private val player: ServerPlayer) : WorldContext {

    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = player.level()
        return try {
            when (root) {
                WorldRoot.BIOME -> biomeField(world, field)
                WorldRoot.WEATHER -> weatherField(world, field)
                WorldRoot.TIME -> timeField(world, field)
                WorldRoot.DIMENSION -> dimensionField(world, field)
                WorldRoot.LOCATION -> locationField(field)
            }
        } catch (_: Exception) {
            Value.VStr("unknown")
        }
    }

    private fun biomeField(world: Level, field: String): Value {
        val pos = player.blockPosition()
        val holder = world.biomeManager.getBiome(pos)
        val biome = holder.value()
        return when (field) {
            "temperature" -> Value.VFloat(biome.getBaseTemperature().toDouble())
            "id" -> holder.unwrapKey()
                .map { Value.VStr(it.identifier().toString()) }
                .orElse(Value.VStr("unknown"))
            "precipitation" ->
                Value.VStr(biome.getPrecipitationAt(pos, world.getSeaLevel()).name.lowercase())
            else -> Value.VStr("unknown")
        }
    }

    private fun weatherField(world: Level, field: String): Value = when (field) {
        "condition" -> Value.VStr(when {
            world.isThundering -> "thunder"
            world.isRaining -> "rain"
            else -> "clear"
        })

        else -> Value.VStr("unknown")
    }

    private fun timeField(world: Level, field: String): Value {
        val time = world.getOverworldClockTime()
        return when (field) {
            "tick" -> Value.VInt(time)
            "period" -> Value.VStr(periodOf(time))
            else -> Value.VStr("unknown")
        }
    }

    private fun dimensionField(world: Level, field: String): Value = when (field) {
        "id" -> Value.VStr(world.dimension().identifier().toString())
        "type" -> Value.VStr(when (world.dimension()) {
            Level.NETHER -> "nether"
            Level.END -> "end"
            else -> "overworld"
        })

        else -> Value.VStr("unknown")
    }

    private fun locationField(field: String): Value = when (field) {
        "x" -> Value.VFloat(player.x)
        "y" -> Value.VFloat(player.y)
        "z" -> Value.VFloat(player.z)
        else -> Value.VStr("unknown")
    }

    companion object {
        /** Смена суток — общая для клиента и сервера, чтобы выбор совпадал. */
        fun periodOf(time: Long): String = when (time % 24000L) {
            in 0L..12000L -> "day"
            in 12001L..13000L -> "sunset"
            in 13001L..23000L -> "night"
            else -> "sunrise"
        }
    }
}
