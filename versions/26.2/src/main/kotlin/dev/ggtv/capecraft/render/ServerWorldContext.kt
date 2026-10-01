package dev.ggtv.capecraft.render

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

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
                WorldRoot.ARMOR -> armorField(field)
                WorldRoot.HEALTH -> healthField(field)
                // Self-only: сервер это не решает. Условие по `state` считает
                // только владелец на своей машине — и то, что сервер знает про
                // игрока, к его собственному состоянию отношения не имеет.
                WorldRoot.STATE -> Value.VStr("unknown")
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
        return Value.VStr(armorTier(player.getItemBySlot(slot)))
    }

    /** Тир брони в стопке. `unknown` — не броня, `none` — пустой слот. */
    private fun armorTier(stack: ItemStack): String {
        if (stack.isEmpty) return "none"
        val id = BuiltInRegistries.ITEM.getKey(stack.item).path
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
