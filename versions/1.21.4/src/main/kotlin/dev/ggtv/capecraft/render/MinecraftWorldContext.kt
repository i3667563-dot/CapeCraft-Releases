package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.player.PlayerEntity
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
                WorldRoot.FIRE -> fireField(player, field)
                WorldRoot.HAND -> handField(player, field)
                WorldRoot.FOOD -> foodField(player, field)
                WorldRoot.XP -> xpField(player, field)
                WorldRoot.EFFECT -> effectActiveField(player, field)
                WorldRoot.EFFECT_AMPLIFIER -> effectAmplifierField(player, field)
                WorldRoot.EFFECT_DURATION -> effectDurationField(player, field)
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
                WorldRoot.ARMOR -> armorField(entity, field)
                WorldRoot.HEALTH -> healthField(entity, field)
                // Self-only: про чужого игрока это неизвестно, поэтому условие
                // не совпадает. Провайдер с таким условием и не объявляется —
                // см. `Provider.isSelfOnly`.
                //
                // Перечислены явно, а не через ветку `else`: пропущенный здесь
                // корень обязан падать компиляцией (`NO_ELSE_IN_WHEN` у when по
                // enum), иначе условие молча перестало бы совпадать у всех,
                // кроме владельца, и ошибки не дало бы нигде.
                WorldRoot.STATE,
                WorldRoot.FIRE,
                WorldRoot.HAND,
                WorldRoot.FOOD,
                WorldRoot.XP,
                WorldRoot.EFFECT,
                WorldRoot.EFFECT_AMPLIFIER,
                WorldRoot.EFFECT_DURATION,
                -> Value.VStr("unknown")
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

// ─────────────── горючее, руки, еда, опыт, эффекты ───────────────

/**
 * Горение: `fire.burning`.
 *
 * Self-only, как и остальные корни этого раздела. `isOnFire` спрашивает само
 * себя: лава, огонь от зажигалки и подожжённый creeper считаются одинаково, а
 * различать их без отдельного поля незачем — плащу достаточно «горит/не горит».
 */
private fun fireField(entity: Entity?, field: String): Value {
    if (entity == null) return Value.VStr("unknown")
    return when (field) {
        "burning" -> Value.VStr(if (entity.isOnFire()) "true" else "false")
        else -> Value.VStr("unknown")
    }
}

/**
 * Руки: `hand.main` и `hand.off`.
 *
 * Отдаётся **id предмета**, а не тир и не слот: `hand: "shield"` должен
 * отличать щит от меча, а `armor` для этого уже есть.
 *
 * `none`, а не `""`, у пустых рук — по примеру брони: `unknown` значит «не
 * прочитано», и смешивать его с «пусто» нельзя, иначе не-игрок выглядел бы
 * как игрок с пустыми руками.
 */
private fun handField(entity: Entity?, field: String): Value {
    val living = entity as? LivingEntity ?: return Value.VStr("unknown")
    val slot = when (field) {
        "main" -> EquipmentSlot.MAINHAND
        "off" -> EquipmentSlot.OFFHAND
        else -> return Value.VStr("unknown")
    }
    val stack = living.getEquippedStack(slot)
    if (stack.isEmpty) return Value.VStr("none")
    return Value.VStr(Registries.ITEM.getId(stack.item).path)
}

/** Голод и сытость: `food.level`, `food.saturation`. Оба числа 0..20. */
private fun foodField(entity: Entity?, field: String): Value {
    val player = entity as? PlayerEntity ?: return Value.VStr("unknown")
    val hunger = player.hungerManager
    return when (field) {
        "level" -> Value.VInt(hunger.foodLevel.toLong())
        "saturation" -> Value.VFloat(hunger.saturationLevel.toDouble())
        else -> Value.VStr("unknown")
    }
}

/**
 * Опыт: `xp.level` целым и `xp.progress` долей уровня.
 *
 * `progress` — это 0..1 **внутри** текущего уровня, а не доля от 30: иначе
 * `">0.9"` означало бы «почти тридцатый уровень» и работало бы только на
 * первых двух. Для «вот-вот уровень» пишут `xp.progress: ">0.9"`.
 */
private fun xpField(entity: Entity?, field: String): Value {
    val player = entity as? PlayerEntity ?: return Value.VStr("unknown")
    return when (field) {
        "level" -> Value.VInt(player.experienceLevel.toLong())
        "progress" -> Value.VFloat(player.experienceProgress.toDouble())
        else -> Value.VStr("unknown")
    }
}

/**
 * Активный эффект по id: `effect.speed`.
 *
 * Поле — сам эффект, поэтому искать приходится по нему, а не наоборот. Нет
 * эффекта — `inactive`, а не `unknown`: различать надо, иначе `!effect.poison`
 * совпало бы и с «не отравлен», и с «прочитать нечем».
 */
private fun effectActiveField(entity: Entity?, field: String): Value {
    val instance = activeEffect(entity, field)
    return when (instance) {
        null -> if (entity is LivingEntity) Value.VStr("inactive") else Value.VStr("unknown")
        else -> Value.VStr("active")
    }
}

/** Усиление активного эффекта: `effect_amplifier.poison`. 0 — первая ступень. */
private fun effectAmplifierField(entity: Entity?, field: String): Value {
    val instance = activeEffect(entity, field)
    return if (instance == null) {
        if (entity is LivingEntity) Value.VInt(0) else Value.VStr("unknown")
    } else {
        Value.VInt(instance.amplifier.toLong())
    }
}

/** Остаток эффекта в тиках: `effect_duration.speed: "<100"`. */
private fun effectDurationField(entity: Entity?, field: String): Value {
    val instance = activeEffect(entity, field)
    return if (instance == null) {
        if (entity is LivingEntity) Value.VInt(0) else Value.VStr("unknown")
    } else {
        Value.VInt(instance.duration.toLong())
    }
}

/**
 * Активный эффект по id или `null`.
 *
 * `null` означает «нет такого эффекта» и не различает «игрок не живой» с
 * «эффекта нет»: различает вызывающий, у которого под рукой есть
 * `entity is LivingEntity`.
 */
private fun activeEffect(entity: Entity?, id: String): StatusEffectInstance? {
    if (id.isBlank()) return null
    val living = entity as? LivingEntity ?: return null
    for (instance in living.getStatusEffects()) {
        // Yarn отдаёт эффект как RegistryEntry, поэтому id берётся из ключа
        // записи — тем же путём, что и id биома выше. `getId` здесь не годится:
        // он ждёт развёрнутый StatusEffect, а не запись.
        val key = instance.getEffectType().getKey()
        if (key.isPresent && key.get().value.path == id) return instance
    }
    return null
}
