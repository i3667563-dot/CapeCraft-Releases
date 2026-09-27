package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.world.World

/**
 * [WorldContext], читающий из живого мира Minecraft.
 *
 * Вызывается из рендер-потока каждый кадр — поэтому только лёгкие чтения
 * из уже загруженных чанков (никакого I/O). Все обращения к MC API обёрнуты
 * в try-catch: если API сменился между версиями — вернёт "unknown" вместо краша.
 *
 * MC 1.21.x: имена yarn.
 *
 * Два контекста, и путать их нельзя: [MinecraftWorldContext] — мой собственный
 * мир, [EntityWorldContext] — мир того, кого я вижу. Разница между «условия
 * считаются для меня» и «условия считаются для него» и есть весь смысл
 * объявленных наборов функций, поэтому это два разных объекта, а не один с
 * меняющимся параметром.
 */
object MinecraftWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = MinecraftClient.getInstance().world ?: return Value.VStr("unknown")
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
}

/**
 * [WorldContext] чужого объекта: биом и координаты берутся у того, кого видно.
 *
 * Общие для всех корней (погода, время, измерение) читаются из мира клиента:
 * объект, которого я вижу, по определению в моей же dimension, иначе его бы
 * не было на экране. А вот биом и координаты — его собственные: игрок может
 * стоять в джунглях, пока я в пустыне, и его «джунглевый» плащ обязан
 * оцениваться по джунглям, а не по тому, где стою я.
 */
class EntityWorldContext(private val entity: Entity?) : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = MinecraftClient.getInstance().world ?: return Value.VStr("unknown")
        return try {
            when (root) {
                WorldRoot.BIOME -> biomeField(world, entity, field)
                WorldRoot.WEATHER -> weatherField(world, field)
                WorldRoot.TIME -> timeField(world, field)
                WorldRoot.DIMENSION -> dimensionField(world, field)
                WorldRoot.LOCATION -> locationField(entity, field)
            }
        } catch (_: Exception) {
            Value.VStr("unknown")
        }
    }
}

// ──────────────────────── общие чтения из мира ────────────────────────

private fun biomeField(world: World, entity: Entity?, field: String): Value {
    val pos = entity?.blockPos ?: return Value.VStr("unknown")
    val holder = world.getBiome(pos)
    val biome = holder.value()
    return when (field) {
        "temperature" -> Value.VFloat(biome.temperature.toDouble())
        "id" -> holder.getKey()
            .map { Value.VStr(it.value.toString()) }
            .orElse(Value.VStr("unknown"))
        "precipitation" -> Value.VStr(biome.getPrecipitation(pos, world.getSeaLevel()).name.lowercase())
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

private fun locationField(entity: Entity?, field: String): Value {
    if (entity == null) return Value.VStr("unknown")
    return when (field) {
        "x" -> Value.VFloat(entity.x)
        "y" -> Value.VFloat(entity.y)
        "z" -> Value.VFloat(entity.z)
        else -> Value.VStr("unknown")
    }
}
