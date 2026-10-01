package dev.ggtv.capecraft.render

import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
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
 * плащ обязан оцениваться по джунглям, а не по тому, где стою я.
 *
 * Общие для всех корней (погода, время, измерение) читаются из мира клиента:
 * объект, которого я вижу, по определению в моей же dimension, иначе его бы
 * не было на экране. А вот биом, координаты, броня и здоровье — его
 * собственные.
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
        if (entity === Minecraft.getInstance().player) return CapeWhenRoots.read(this, root, field, path)
        throw CrenError.NotFound(path)
    }

    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = Minecraft.getInstance().level ?: return Value.VStr("unknown")
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

/**
 * Мир моего собственного игрока.
 *
 * Отдельный синглтон, а не [EntityWorldContext] с локальным игроком, чтобы
 * его нельзя было перепутать: разница между «условия считаются для меня» и
 * «условия считаются для того, кого я вижу» — это ровно тот баг, который
 * здесь и чинится, и делать их одним объектом значит сделать баг снова.
 */
object MinecraftWorldContext : WorldContext {

    /** Аддонные корни self-only: их читает только владелец, и никто другой. */
    override fun subjectEntity(): Any? = Minecraft.getInstance().player

    override fun addonField(root: String, field: String, path: String): Value =
        CapeWhenRoots.read(this, root, field, path)
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
                WorldRoot.ARMOR -> armorField(player, field)
                WorldRoot.HEALTH -> healthField(player, field)
                WorldRoot.STATE -> stateField(player, field)
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
    val id = BuiltInRegistries.ITEM.getKey(stack.item).path
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
    return Value.VStr(armorTier(living.getItemBySlot(slot)))
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
        "inWater" -> Value.VStr(if (entity.isInWater) "true" else "false")
        "sneaking" -> Value.VStr(if (entity.isCrouching) "true" else "false")
        "sprinting" -> Value.VStr(if (entity.isSprinting) "true" else "false")
        "onGround" -> Value.VStr(if (entity.onGround()) "true" else "false")
        "pose" -> Value.VStr(poseName(entity.pose))
        else -> Value.VStr("unknown")
    }
}

/**
 * Поза как стабильная строка.
 *
 * [dev.ggtv.capecraft.schema.WhenSchema] обещает `fall_flying`, а в yarn
 * 1.21.4+ та же поза называется `GLIDING`. Поэтому перечисление явное, а не
 * `name.lowercase()`: так строка в конфиге одинакова во всех версиях, а
 * переименование позы в игре не выдаст себя молчаливым обещанием нового
 * значения, которого в подсказке нет.
 */
private fun poseName(pose: Pose): String = when (pose) {
    Pose.STANDING -> "standing"
    Pose.CROUCHING -> "crouching"
    Pose.SWIMMING -> "swimming"
    Pose.FALL_FLYING -> "fall_flying"
    Pose.SLEEPING -> "sleeping"
    Pose.SPIN_ATTACK -> "spin_attack"
    Pose.LONG_JUMPING -> "long_jumping"
    Pose.DYING -> "dying"
    else -> "unknown"
}
