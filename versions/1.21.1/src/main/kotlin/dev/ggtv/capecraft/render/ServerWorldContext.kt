package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.World

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

    override fun field(root: WorldRoot, field: String, path: String): Value {
        val world = player.world
        return try {
            when (root) {
                WorldRoot.BIOME -> biomeField(world, field)
                WorldRoot.WEATHER -> weatherField(world, field)
                WorldRoot.TIME -> timeField(world, field)
                WorldRoot.DIMENSION -> dimensionField(world, field)
                WorldRoot.LOCATION -> locationField(field)
                WorldRoot.ARMOR -> armorField(field)
                WorldRoot.HEALTH -> healthField(field)
                // Self-only: сервер это не решает. Условие считает только
                // владелец на своей машине — и то, что сервер знает про игрока,
                // к его собственному состоянию отношения не имеет: серверный
                // `food.level` не отвечает на вопрос «а что видно на МОЕМ
                // плащу?», а подмена одного другим сделала бы выбор провайдера
                // у клиента и на сервере разным без единой ошибки.
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

    private fun biomeField(world: World, field: String): Value {
        val pos = player.blockPos
        val holder = world.getBiome(pos)
        val biome = holder.value()
        return when (field) {
            "temperature" -> Value.VFloat(biome.temperature.toDouble())
            "id" -> holder.getKey()
                .map { Value.VStr(it.value.toString()) }
                .orElse(Value.VStr("unknown"))
            "precipitation" -> Value.VStr(biome.getPrecipitation(pos).name.lowercase())
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

    /**
     * Броня в слоте: `head`, `chest`, `legs`, `feet`.
     *
     * Публичное условие: броня видна и другим, поэтому сервер вправе считать
     * её так же, как клиент. Тир берётся из id предмета
     * (`diamond_chestplate` → `diamond`) — это данные, а не API, и в 26.2
     * `ArmorMaterial` перестал быть именованным, так что через него не вышло бы.
     */
    private fun armorField(field: String): Value {
        val slot = when (field) {
            "head" -> EquipmentSlot.HEAD
            "chest" -> EquipmentSlot.CHEST
            "legs" -> EquipmentSlot.LEGS
            "feet" -> EquipmentSlot.FEET
            else -> return Value.VStr("unknown")
        }
        return Value.VStr(armorTier(player.getEquippedStack(slot)))
    }

    /** Тир брони в стопке. `unknown` — не броня, `none` — пустой слот. */
    private fun armorTier(stack: ItemStack): String {
        if (stack.isEmpty) return "none"
        val id = Registries.ITEM.getId(stack.item).path
        val tier = id.substringBefore('_')
        val piece = id.substringAfter('_', "")
        if (piece !in ARMOR_PIECES) return "unknown"
        return if (tier in ARMOR_TIERS) tier else "unknown"
    }

    /** Здоровье: `current` падает от урона, `max` — нет. Оба публичные. */
    private fun healthField(field: String): Value = when (field) {
        "current" -> Value.VFloat(player.health.toDouble())
        "max" -> Value.VFloat(player.maxHealth.toDouble())
        else -> return Value.VStr("unknown")
    }
}
