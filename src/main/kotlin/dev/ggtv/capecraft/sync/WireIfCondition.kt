package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.Expected
import dev.ggtv.capecraft.condition.Op
import dev.ggtv.capecraft.condition.VarCondition
import dev.ggtv.capecraft.condition.VarPredicate

/**
 * Проводное представление условия `if` в Sync v3.
 *
 * Едет по сети по той же причине, что и [WireCondition]: считать должен
 * **каждый клиент сам**, против контекста наблюдаемого игрока. `username` и
 * `uuid` у зрителя и у того, кого видно, совпадают, поэтому проверка честная.
 *
 * ## Почему отдельный класс, а не ещё один корень в [WireRoot]
 *
 * `when` сравнивает поля мира, у которых есть фиксированный закрытый набор
 * ([WireRoot] с тегами). У `if` набор **открытый**: плейсхолдеры аддонов
 * приезжают и уезжают вместе с их установкой, а `$ИМЯ` заводится любым
 * именем переменной окружения. Кодировать такое перечислением нельзя — на
 * другой машине просто не будет того аддона, и клиент не смог бы отличить
 * «неизвестная переменная» от «условие нарушено».
 *
 * Поэтому имя переменной едет **строкой**, как в `.kn`. Разбирать его обратно
 * должен получатель, у которого свой набор аддонов и своё окружение, — это и
 * есть смысл: отправлять значение, посчитанное на чужой машине, значило бы
 * показать всем плащ, выбранный под чужой `root` и чужие `$*`.
 *
 * Операторы и ожидаемые значения ([WireOp], [WireExpected]) — те же, что у
 * `when`, и переиспользуются как есть: дублировать семь операций ради
 * «параллельного» формата означало бы, что со временем правила разъедутся.
 *
 * AST плоский, как у [WireCondition]: предикаты не вкладываются.
 */
data class WireIfCondition(val predicates: List<WireVarPredicate>) {

    /** Проверка формы и лимитов. Пустой список = всё в порядке. */
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (predicates.isEmpty()) {
            out += "пустое условие if"
        }
        if (predicates.size > SyncProtocol.MAX_PREDICATES) {
            out += "${predicates.size} предикатов в if, максимум ${SyncProtocol.MAX_PREDICATES}"
        }
        for (p in predicates) {
            out += p.validate()
        }
        return out
    }

    companion object {
        /**
         * Из локального условия в провод.
         *
         * `null`, если предикат не переводится — по тем же основаниям, что и в
         * [WireCondition.from]: провайдер без `if` в сети выглядит не так, как
         * этот же провайдер локально, и расхождение не видно нигде.
         */
        fun from(condition: VarCondition?): WireIfCondition? {
            if (condition == null) return null
            val out = ArrayList<WireVarPredicate>(condition.predicates.size)
            for (p in condition.predicates) {
                val op = WireOp.of(p.op) ?: return null
                val expected = when (val e = p.expected) {
                    is Expected.Str -> WireExpected.Str(e.s)
                    is Expected.Num -> WireExpected.Num(e.d)
                    is Expected.Range -> WireExpected.Range(e.from, e.to)
                }
                out += WireVarPredicate(p.name, op, expected)
            }
            return WireIfCondition(out)
        }
    }
}

/**
 * Предикат по переменной в проводном виде.
 *
 * [name] — имя переменной целиком, точкой в том числе: `$FOO` и аддонное
 * `myAddon.level` едут как есть и разбираются получателем.
 */
data class WireVarPredicate(
    val name: String,
    val op: WireOp,
    val expected: WireExpected,
) {
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (name.isEmpty()) {
            out += "пустое имя переменной в условии if"
        }
        if (name != name.trim()) {
            out += "имя переменной «$name» имеет пробелы по краям"
        }
        if (ActiveCape.utf8Len(name) > SyncProtocol.MAX_FIELD_BYTES) {
            out += "имя переменной «$name» длиннее ${SyncProtocol.MAX_FIELD_BYTES} байт"
        }
        if (op.requiresNumeric && expected !is WireExpected.Num && expected !is WireExpected.Range) {
            out += "оператор ${op.name} для «$name» требует числа, а задана строка"
        }
        if (expected is WireExpected.Str &&
            ActiveCape.utf8Len(expected.s) > SyncProtocol.MAX_EXPECTED_STR_BYTES
        ) {
            out += "значение условия if длиннее ${SyncProtocol.MAX_EXPECTED_STR_BYTES} байт"
        }
        return out
    }

    /** Обратно в локальный предикат — ради вычисления `matches`. */
    fun toLocal(): VarPredicate? {
        val localOp = op.toLocal() ?: return null
        val localExpected = when (val e = expected) {
            is WireExpected.Str -> Expected.Str(e.s)
            is WireExpected.Num -> Expected.Num(e.d)
            is WireExpected.Range -> Expected.Range(e.from, e.to)
        }
        return VarPredicate(name, localOp, localExpected)
    }
}

/** Собрать локальный [VarCondition] из проводного — или `null`, если что-то не перевелось. */
fun WireIfCondition.toLocal(): VarCondition? {
    val out = ArrayList<VarPredicate>(predicates.size)
    for (p in predicates) {
        out += p.toLocal() ?: return null
    }
    return VarCondition(out)
}
