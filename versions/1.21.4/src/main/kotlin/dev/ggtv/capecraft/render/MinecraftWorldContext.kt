package dev.ggtv.capecraft.render

import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
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

    /** Аддонные корни self-only: их читает только владелец, и никто другой. */
    override fun subjectEntity(): Any? = MinecraftClient.getInstance().player

    override fun addonField(root: String, field: String, path: String): Value =
        CapeWhenRoots.read(this, root, field, path)
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
                WorldRoot.ARMOR -> armorField(player, field)
                WorldRoot.HEALTH -> healthField(player, field)
                WorldRoot.STATE -> stateField(player, field)
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

    override fun subjectEntity(): Any? = entity

    /**
     * Аддонные корни self-only, и [entity] здесь — в том числе мой собственный
     * игрок: рендер-миксин отдаёт [EntityWorldContext] каждому игроку в кадре,
     * включая меня. Поэтому «свой» определяется тождеством с локальным игроком,
     * а не тем, каким контекстом пришли.
     *
     * Чужому игроку — [CrenError.NotFound]: наблюдатель не знает, горит ли
     * сосед, и условие обязано молча не совпасть, а не показать ему мой плащ.
     */
    override fun addonField(root: String, field: String, path: String): Value {
        if (entity === MinecraftClient.getInstance().player) return CapeWhenRoots.read(this, root, field, path)
        throw CrenError.NotFound(path)
    }
    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = MinecraftClient.getInstance().world ?: return Value.VStr("unknown")
        return try {
            when (root) {
                WorldRoot.BIOME -> biomeField(world, entity, field)
                WorldRoot.WEATHER -> weatherField(world, field)
                WorldRoot.TIME -> timeField(world, field)
                WorldRoot.DIMENSION -> dimensionField(world, field)
                WorldRoot.LOCATION -> locationField(entity, field)
                WorldRoot.ARMOR -> armorField(entity, field)
                WorldRoot.HEALTH -> healthField(entity, field)
                // Self-only: про чужого игрока это неизвестно, поэтому условие
                // не совпадает. Провайдер с таким условием и не объявляется —
                // см. `Provider.isSelfOnly`.
                WorldRoot.STATE -> Value.VStr("unknown")
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

// ──────────────────── броня, здоровье, состояние ────────────────────

/**
 * Тиры брони, которые обещает подсказка редактора.
 *
 * Дублируется в [dev.ggtv.capecraft.condition.Condition.ARMOR_TIERS] намеренно:
 * именно литералами в этом файле сверяет его `WorldContextAgreementTest`, и
 * ссылка на общий список сделала бы проверку совпадением с самим собой.
 */
private val ARMOR_TIERS = setOf(
    "none", "leather", "chainmail", "iron", "gold", "diamond", "netherite", "turtle",
)

/** Части тела, для которых id предмета разбирается на тир и слот. */
private val ARMOR_PIECES = setOf("helmet", "chestplate", "leggings", "boots")

/**
 * Тир брони в слоте.
 *
 * Тир берётся из id предмета: `diamond_chestplate` → `diamond`. Через
 * `ArmorMaterial` не вышло бы: в 1.21 это `RegistryEntry<ArmorMaterial>`, а
 * в 26.2 материал стал записью без имени вовсе, и общего у них нет ничего.
 * Зато id предмета — данные, а не API, и одинаковы во всех версиях.
 *
 * `unknown`, а не `none`, для не-брони и для брони с тиром, которого нет в
 * [ARMOR_TIERS]: различать «пусто» и «не то» нужно, иначе аддон с
 * нестандартной бронёй тихо выглядел бы как `none`.
 */
private fun armorTier(stack: ItemStack): String {
    if (stack.isEmpty) return "none"
    val id = Registries.ITEM.getId(stack.item).path
    val tier = id.substringBefore('_')
    val piece = id.substringAfter('_', "")
    if (piece !in ARMOR_PIECES) return "unknown"
    return if (tier in ARMOR_TIERS) tier else "unknown"
}

/**
 * Броня в слоте: `head`, `chest`, `legs`, `feet`.
 *
 * Публичное условие: надетую броню видно и со стороны, поэтому его считает
 * каждый клиент сам, против своего наблюдаемого игрока.
 */
private fun armorField(entity: Entity?, field: String): Value {
    val living = entity as? LivingEntity ?: return Value.VStr("unknown")
    val slot = when (field) {
        "head" -> EquipmentSlot.HEAD
        "chest" -> EquipmentSlot.CHEST
        "legs" -> EquipmentSlot.LEGS
        "feet" -> EquipmentSlot.FEET
        else -> return Value.VStr("unknown")
    }
    return Value.VStr(armorTier(living.getEquippedStack(slot)))
}

/**
 * Здоровье: `current` и `max`.
 *
 * Тоже публичное: здоровье чужого игрока видно над его головой. `current`
 * падает от урона, `max` — нет, поэтому для «а сколько у меня вообще
 * здоровья» нужен `max`, а для «сколько осталось» — `current`.
 */
private fun healthField(entity: Entity?, field: String): Value {
    val living = entity as? LivingEntity ?: return Value.VStr("unknown")
    return when (field) {
        "current" -> Value.VFloat(living.health.toDouble())
        "max" -> Value.VFloat(living.maxHealth.toDouble())
        else -> return Value.VStr("unknown")
    }
}

/**
 * Состояние: `inWater`, `sneaking`, `sprinting`, `onGround`, `pose`.
 *
 * Self-only. Про чужого игрока всё это неизвестно — в воде ли он, на земле ли,
 * — поэтому у наблюдаемого [EntityWorldContext] эти поля отдают `unknown`, и
 * условие не совпадает. Владелец же считает их про себя и видит свой плащ.
 *
 * Булевы поля отдаются строками, а не `Value.VBool`, чтобы работал тот же код
 * сравнения, что у строк: `state.sneaking: true` разбирается в равенство строке
 * `"true"`, а `!true` — в «не равно», без отдельной ветки в `Op`.
 */
private fun stateField(entity: Entity?, field: String): Value {
    if (entity == null) return Value.VStr("unknown")
    return when (field) {
        "inWater" -> Value.VStr(if (entity.isTouchingWater) "true" else "false")
        "sneaking" -> Value.VStr(if (entity.isSneaking) "true" else "false")
        "sprinting" -> Value.VStr(if (entity.isSprinting) "true" else "false")
        "onGround" -> Value.VStr(if (entity.isOnGround) "true" else "false")
        "pose" -> Value.VStr(poseName(entity.pose))
        else -> Value.VStr("unknown")
    }
}

/**
 * Поза как стабильная строка.
 *
 * [dev.ggtv.capecraft.schema.WhenSchema] обещает `fall_flying`, а Minecraft
 * называет эту позу по-разному: в 1.21.1 это `FALL_FLYING`, дальше — `GLIDING`.
 * Поэтому перечисление здесь явное, а не `name.lowercase()`: так строка в
 * конфиге одинакова во всех версиях, а переименование позы в игре не выдаст
 * себя молчаливым обещанием нового значения, которого в подсказке нет.
 */
private fun poseName(pose: EntityPose): String = when (pose) {
    EntityPose.STANDING -> "standing"
    EntityPose.CROUCHING -> "crouching"
    EntityPose.SWIMMING -> "swimming"
    EntityPose.GLIDING -> "fall_flying"
    EntityPose.SLEEPING -> "sleeping"
    EntityPose.SPIN_ATTACK -> "spin_attack"
    EntityPose.LONG_JUMPING -> "long_jumping"
    EntityPose.DYING -> "dying"
    else -> "unknown"
}
