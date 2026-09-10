package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.level.Level

/**
 * [WorldContext], читающий из живого мира Minecraft.
 *
 * Вызывается из рендер-потока каждый кадр — поэтому только лёгкие чтения
 * из уже загруженных чанков (никакого I/O). Все обращения к MC API обёрнуты
 * в try-catch: если API сменился между версиями — вернёт "unknown" вместо краша.
 *
 * MC 26.2: имена классов необфусцированы (Mojang), поэтому используются
 * реальные имена Mojang.
 */
object MinecraftWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = Minecraft.getInstance().level
            ?: return Value.VStr("unknown")
        val player = Minecraft.getInstance().player

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
        world: Level,
        player: LocalPlayer?,
        field: String,
    ): Value {
        val pos = player?.blockPosition() ?: return Value.VStr("unknown")
        val biome = world.getBiomeManager().getBiome(pos).value()
        return when (field) {
            "temperature" -> Value.VFloat(biome.getBaseTemperature().toDouble())
            else -> Value.VStr("unknown")
        }
    }

    private fun weatherField(world: Level, field: String): Value {
        return when (field) {
            "condition" -> Value.VStr(when {
                world.isThundering() -> "thunder"
                world.isRaining() -> "rain"
                else -> "clear"
            })
            else -> Value.VStr("unknown")
        }
    }

    private fun timeField(world: Level, field: String): Value {
        val time = world.getDefaultClockTime()
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

    private fun dimensionField(world: Level, field: String): Value {
        return when (field) {
            "id" -> Value.VStr(world.dimension().identifier().toString())
            "type" -> Value.VStr(when (world.dimension()) {
                Level.NETHER -> "nether"
                Level.END -> "end"
                else -> "overworld"
            })
            else -> Value.VStr("unknown")
        }
    }

    private fun locationField(player: LocalPlayer?, field: String): Value {
        if (player == null) return Value.VStr("unknown")
        return when (field) {
            "x" -> Value.VFloat(player.x)
            "y" -> Value.VFloat(player.y)
            "z" -> Value.VFloat(player.z)
            else -> Value.VStr("unknown")
        }
    }
}