package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.world.World

/**
 * [WorldContext], читающий из живого мира Minecraft.
 *
 * Вызывается из рендер-потока каждый кадр — поэтому только лёгкие чтения
 * из уже загруженных чанков (никакого I/O). Все обращения к MC API обёрнуты
 * в try-catch: если API сменился между версиями — вернёт "unknown" вместо краша.
 *
 * MC 1.21.10: имена yarn.
 */
object MinecraftWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = MinecraftClient.getInstance().world
            ?: return Value.VStr("unknown")
        val player = MinecraftClient.getInstance().player

        return try {
            when (root) {
                WorldRoot.BIOME -> biomeField(world, player, field)
                WorldRoot.WEATHER -> weatherField(world, field)
                WorldRoot.TIME -> timeField(world, field)
                WorldRoot.DIMENSION -> dimensionField(world, field)
                WorldRoot.LOCATION -> locationField(player, field)
            }
        } catch (_: Exception) {
            Value.VStr("unknown")
        }
    }

    private fun biomeField(
        world: World,
        player: ClientPlayerEntity?,
        field: String,
    ): Value {
        val pos = player?.blockPos ?: return Value.VStr("unknown")
        val biome = world.getBiome(pos).value()
        return when (field) {
            "temperature" -> Value.VFloat(biome.temperature.toDouble())
            else -> Value.VStr("unknown")
        }
    }

    private fun weatherField(world: World, field: String): Value {
        return when (field) {
            "condition" -> Value.VStr(when {
                world.isThundering -> "thunder"
                world.isRaining -> "rain"
                else -> "clear"
            })
            else -> Value.VStr("unknown")
        }
    }

    private fun timeField(world: World, field: String): Value {
        val time = world.timeOfDay
        return when (field) {
            "tick" -> Value.VInt(time)
            "period" -> Value.VStr(when (time % 24000L) {
                in 0L..12000L -> "day"
                in 12001L..13000L -> "sunset"
                in 13001L..23000L -> "night"
                else -> "sunrise"
            })
            else -> Value.VStr("unknown")
        }
    }

    private fun dimensionField(world: World, field: String): Value {
        return when (field) {
            "id" -> Value.VStr(world.getRegistryKey().value.toString())
            "type" -> Value.VStr(when (world.getRegistryKey()) {
                World.NETHER -> "nether"
                World.END -> "end"
                else -> "overworld"
            })
            else -> Value.VStr("unknown")
        }
    }

    private fun locationField(player: ClientPlayerEntity?, field: String): Value {
        if (player == null) return Value.VStr("unknown")
        return when (field) {
            "x" -> Value.VFloat(player.x)
            "y" -> Value.VFloat(player.y)
            "z" -> Value.VFloat(player.z)
            else -> Value.VStr("unknown")
        }
    }
}