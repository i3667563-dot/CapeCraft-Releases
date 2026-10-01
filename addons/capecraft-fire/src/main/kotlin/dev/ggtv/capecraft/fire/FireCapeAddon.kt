package dev.ggtv.capecraft.fire

import dev.ggtv.capecraft.api.CapeAddon
import dev.ggtv.capecraft.api.CapeApi
import dev.ggtv.capecraft.api.condition.CapeWhenField
import dev.ggtv.capecraft.api.condition.CapeWhenRoot
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import net.minecraft.world.entity.Entity

/**
 * Аддон `capecraft-fire`: условие `when` по горению.
 *
 * ## Что он объявляет
 *
 * Один корень `fire` с полями:
 *
 * - `burning` — горит ли игрок (`"true"`/`"false"`), поле по умолчанию;
 * - `ticks` — сколько тиков осталось гореть, число.
 *
 * Значит в конфиге работает и короткая запись:
 *
 * ```
 * when = { fire: "true" }
 * when = { fire.ticks: ">100" }
 * ```
 *
 * ## Почему это аддон, а не часть мода
 *
 * Горение — это сущность игрока, а не состояние мира. Мод не знает, что
 * считать «горящим» в чужом плаще, и не должен: ему бы пришлось держать
 * список таких состояний и разбираться, чем «покрыт снегом» отличается от
 * «под водой». Состояний столько же, сколько у сущности, а перечисление
 * успевает устареть раньше, чем им начнут пользоваться.
 *
 * ## Почему условие не уезжает по сети
 *
 * Наблюдатель не знает, горит ли сосед. Он видит плащ и клетки инвентаря, но
 * не состояние сущности. Поэтому условие с корнем `fire` помечает провайдер
 * как локальный, и тот не объявляется: иначе я поджёг бы свой плащ, а
 * сосед увидел бы его у себя — и не понял бы, почему.
 */
class FireCapeAddon : CapeAddon {

    override fun register(api: CapeApi) {
        api.whenConditions.register(
            CapeWhenRoot(
                root = "fire",
                doc = "Горение игрока. Читает только владелец плаща.",
                defaultField = "burning",
                fields = listOf(
                    CapeWhenField(
                        name = "burning",
                        doc = "Горит ли игрок.",
                        values = listOf("true", "false"),
                    ),
                    CapeWhenField(
                        name = "ticks",
                        doc = "Сколько тиков осталось гореть, число. 0 — не горит.",
                        numeric = true,
                    ),
                ),
                reader = ::read,
            ),
        )
    }

    /**
     * Прочитать поле корня у сущности.
     *
     * `subject` — сущность, отданная контекстом: у чужого игрока и на сервере
     * контекст отдаёт `null` и до сюда не доходит. Но проверка всё равно
     * нужна: контекст — чужой код, а приведение `null` к типу здесь упало бы
     * на каждом кадре.
     */
    private fun read(subject: Any?, field: String): Value {
        val entity = subject as? Entity ?: throw CrenError.NotFound("fire.$field")
        return when (field) {
            "burning" -> Value.VStr(if (entity.isOnFire) "true" else "false")
            "ticks" -> Value.VInt(entity.remainingFireTicks.toLong())
            else -> throw CrenError.NotFound("fire.$field")
        }
    }
}