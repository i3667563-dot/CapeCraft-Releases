package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.World

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
 * MC 1.21.x: имена yarn.
 */
class ServerWorldContext(private val player: ServerPlayerEntity) : WorldContext {

    /**
     * Мир игрока. С 1.21.10 поле [net.minecraft.entity.Entity.world] стало
     * приватным, и доступ к нему даёт только `getEntityWorld()`.
     */
    private val world: World get() = player.entityWorld

    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = this.world
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

    private fun biomeField(world: World, field: String): Value {
        val biome = world.getBiome(player.blockPos).value()
        return when (field) {
            "temperature" -> Value.VFloat(biome.temperature.toDouble())
            else -> Value.VStr("unknown")
        }
    }

    private fun weatherField(world: World, field: String): Value = when (field) {
        "condition" -> Value.VStr(when {
            world.isThundering -> "thunder"
            world.isRaining -> "rain"
            else -> "clear"
        })

        else -> Value.VStr("unknown")
    }

    private fun timeField(world: World, field: String): Value {
        val time = world.timeOfDay
        return when (field) {
            "tick" -> Value.VInt(time)
            "period" -> Value.VStr(periodOf(time))
            else -> Value.VStr("unknown")
        }
    }

    private fun dimensionField(world: World, field: String): Value = when (field) {
        "id" -> Value.VStr(world.getRegistryKey().value.toString())
        "type" -> Value.VStr(when (world.getRegistryKey()) {
            World.NETHER -> "nether"
            World.END -> "end"
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
