package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

/**
 * [WorldContext], читающий из живого мира Minecraft **для указанной сущности**.
 *
 * ## Зачем параметр, если раньше был синглтон
 *
 * В v1 мир был один — мой. Условия `when` считались про меня, и плащ в кадре
 * выбирался по моему биому и моей погоде. В v2 у каждого объекта в кадре свой
 * набор функций со своими условиями, и считать их надо против **его**
 * положения: объект стоит в джунглях, я вижу его из пустыни — его «джунглевый»
 * плащ должен показаться только тем, кто смотрит действительно из джунглей.
 *
 * Иначе получилось бы ровно то, что и было: чужой набор функций едет по сети,
 * но применяется к моему миру, то есть вычисляется не по тем условиям, которые
 * в нём объявлены.
 *
 * [entity] — тот, чей плащ считаем. `null` означает «сущности нет»: все
 * position-зависимые поля отдают `"unknown"`, и условие по ним не совпадёт.
 * Это безопаснее, чем молча считать по миру зрителя.
 *
 * Вызывается из рендер-потока каждый кадр — поэтому только лёгкие чтения из
 * уже загруженных чанков (никакого I/O). Все обращения к MC API обёрнуты в
 * try-catch: если API сменился между версиями — вернём `"unknown"` вместо краша.
 *
 * MC 26.2: имена классов необфусцированы (Mojang), поэтому используются
 * реальные имена Mojang.
 */
class EntityWorldContext(private val entity: Entity?) : WorldContext {

    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = Minecraft.getInstance().level ?: return Value.VStr("unknown")
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

/**
 * Мир моего собственного игрока.
 *
 * Отдельный синглтон, а не [EntityWorldContext] с локальным игроком, чтобы
 * его нельзя было перепутать: разница между «условия считаются для меня» и
 * «условия считаются для того, кого я вижу» — это ровно тот баг, который
 * здесь и чинится, и делать их одним объектом значит сделать баг снова.
 */
object MinecraftWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = Minecraft.getInstance().level ?: return Value.VStr("unknown")
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
}

// ──────────────────────── общие чтения из мира ────────────────────────

private fun biomeField(world: Level, entity: Entity?, field: String): Value {
    val pos = entity?.blockPosition() ?: return Value.VStr("unknown")
    val holder = world.getBiomeManager().getBiome(pos)
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
    "condition" -> Value.VStr(
        when {
            world.isThundering() -> "thunder"
            world.isRaining() -> "rain"
            else -> "clear"
        },
    )

    else -> Value.VStr("unknown")
}

private fun timeField(world: Level, field: String): Value {
    val time = world.getDefaultClockTime()
    return when (field) {
        "tick" -> Value.VInt(time)
        "period" -> Value.VStr(
            when (time % 24000L) {
                in 0L..12000L -> "day"
                in 12001L..13000L -> "sunset"
                in 13001L..23000L -> "night"
                else -> "sunrise"
            },
        )

        else -> Value.VStr("unknown")
    }
}

private fun dimensionField(world: Level, field: String): Value = when (field) {
    "id" -> Value.VStr(world.dimension().identifier().toString())
    "type" -> Value.VStr(
        when (world.dimension()) {
            Level.NETHER -> "nether"
            Level.END -> "end"
            else -> "overworld"
        },
    )

    else -> Value.VStr("unknown")
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
