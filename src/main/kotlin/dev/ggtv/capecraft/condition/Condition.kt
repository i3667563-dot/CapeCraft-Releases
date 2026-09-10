package dev.ggtv.capecraft.condition

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot

/**
 * Условие выбора провайдера: все [Predicate] должны выполниться (AND).
 * Разбирается из блока `when { ... }` в `.kn`.
 *
 * Предикат = «поле мира <оператор> значение». Поле — живой корень мира
 * ([WorldRoot]) с полем через точку (`biome.temperature`, `weather.condition`,
 * `time.period`, `dimension.type`, `location.y`) или без точки — тогда
 * берётся дефолтное поле корня (biome→id, weather→condition, time→period,
 * dimension→type).
 *
 * Значение — строка или число. Операторы в строке:
 * - `">63"`, `">=63"`, `"<100"`, `"<=100"` — числовое сравнение;
 * - `"63..80"` — диапазон включительно;
 * - `"!rain"` — «не равно»;
 * - без оператора — равенство (строка = строка, число = число),
 *   числовая строка и число одинаковы.
 */
data class Condition(val predicates: List<Predicate>) {

    /** Выполняется ли условие на живом мире (AND по всем предикатам). */
    fun matches(world: WorldContext): Boolean = predicates.all { it.matches(world) }

    companion object {
        /** Поле по умолчанию для корня без точки (`when { biome: "snowy" }`). */
        private val DEFAULT_FIELD = mapOf(
            WorldRoot.BIOME to "id",
            WorldRoot.WEATHER to "condition",
            WorldRoot.TIME to "period",
            WorldRoot.DIMENSION to "type",
        )

        /** Разобрать блок `when { ... }` из словаря. Пустой словарь — пустое условие. */
        fun parse(dict: Value.VDict): Condition {
            val predicates = dict.pairs.map { (key, value) -> parsePredicate(key, value) }
            return Condition(predicates)
        }

        private fun parsePredicate(key: String, value: Value): Predicate {
            val parts = key.split('.').filter { it.isNotBlank() }
            val root = WorldRoot.bySegment(parts.firstOrNull().orEmpty())
                ?: throw IllegalArgumentException(
                    "условие when: неизвестный корень мира «${parts.firstOrNull()}» " +
                        "(доступны: ${WorldRoot.entries.joinToString { it.segment }})",
                )
            val field = parts.getOrNull(1) ?: DEFAULT_FIELD[root]
                ?: throw IllegalArgumentException(
                    "условие when: для «${root.segment}» укажите поле, например «${root.segment}.id»",
                )
            if (parts.size > 2) {
                throw IllegalArgumentException(
                    "условие when: слишком глубокий путь «$key» (максимум «${root.segment}.поле»)",
                )
            }
            // Семантические алиасы для корня без поля (роадмап: `when biome: "snowy"`).
            if (parts.size == 1) {
                val alias = alias(root, value)
                if (alias != null) {
                    val (newField, op, expected) = alias
                    return Predicate(root, newField, op, expected)
                }
            }
            val (op, expected) = parseValue(value)
            return Predicate(root, field, op, expected)
        }

        /** Переписать значение для корня без поля в нормализованное поле. */
        private fun alias(root: WorldRoot, value: Value): Triple<String, Op, Expected>? {
            val v = (value as? Value.VStr)?.s?.lowercase() ?: return null
            return when (root) {
                WorldRoot.BIOME -> when (v) {
                    "snowy", "snow", "frozen" -> Triple("precipitation", Op.Eq, Expected.Str("snow"))
                    "rain", "rainy" -> Triple("precipitation", Op.Eq, Expected.Str("rain"))
                    else -> null
                }
                WorldRoot.WEATHER -> when (v) {
                    "clear", "fair" -> Triple("condition", Op.Eq, Expected.Str("clear"))
                    "rain", "rainy" -> Triple("condition", Op.Eq, Expected.Str("rain"))
                    "thunder", "storm" -> Triple("condition", Op.Eq, Expected.Str("thunder"))
                    else -> null
                }
                WorldRoot.TIME -> when (v) {
                    "day", "dawn", "dusk" -> Triple("period", Op.Eq, Expected.Str(v))
                    "night", "midnight" -> Triple("period", Op.Eq, Expected.Str("night"))
                    else -> null
                }
                WorldRoot.DIMENSION -> when (v) {
                    "overworld" -> Triple("type", Op.Eq, Expected.Str("overworld"))
                    "nether", "the_nether" -> Triple("type", Op.Eq, Expected.Str("nether"))
                    "end", "the_end" -> Triple("type", Op.Eq, Expected.Str("end"))
                    else -> null
                }
                else -> null
            }
        }

        /** Строка → операция + ожидаемое значение. */
        private fun parseValue(value: Value): Pair<Op, Expected> {
            val text = when (value) {
                is Value.VStr -> value.s
                is Value.VInt -> value.i.toString()
                is Value.VFloat -> value.f.toString()
                is Value.VBool -> value.b.toString()
                else -> throw IllegalArgumentException(
                    "условие when: значение должно быть строкой или числом, найдено «${value.kind}»",
                )
            }
            val s = text.trim()

            // Диапазон «a..b» — приоритетнее операторов сравнения.
            val range = "^(.+?)\\.\\.(.+)$".toRegex().matchEntire(s)
            if (range != null) {
                val from = range.groupValues[1].trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("условие when: неверный диапазон «$s»")
                val to = range.groupValues[2].trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("условие when: неверный диапазон «$s»")
                return Op.Range to Expected.Range(from, to)
            }

            val (op, num) = when {
                s.startsWith(">=") -> Op.Ge to s.removePrefix(">=")
                s.startsWith("<=") -> Op.Le to s.removePrefix("<=")
                s.startsWith(">") -> Op.Gt to s.removePrefix(">")
                s.startsWith("<") -> Op.Lt to s.removePrefix("<")
                s.startsWith("!") -> Op.NotEq to s.removePrefix("!")
                else -> Op.Eq to s
            }.let { (op, raw) -> op to raw.trim() }

            // «123», «>63», «!3.5» — числа; «snowy», «!rain» — строки.
            return op to if (num.toDoubleOrNull() != null) {
                Expected.Num(num.toDouble())
            } else {
                Expected.Str(num)
            }
        }
    }
}

/** Проверка выполнения одного предиката. */
private fun Predicate.matches(world: WorldContext): Boolean {
    val actual = try {
        world.field(root, field, "$root.$field")
    } catch (e: CrenError.NotFound) {
        return false // поле мира недоступно — условие не выполнилось
    }
    return op.apply(actual, expected)
}

/** Операция сравнения для предиката. */
sealed interface Op {
    data object Eq : Op
    data object NotEq : Op
    data object Gt : Op
    data object Ge : Op
    data object Lt : Op
    data object Le : Op
    data object Range : Op

    /** Применить к фактическому значению поля мира. */
    fun apply(actual: Value, expected: Expected): Boolean = when (this) {
        Eq -> equal(actual, expected)
        NotEq -> !equal(actual, expected)
        Gt -> cmp(actual, expected) { a, b -> a > b }
        Ge -> cmp(actual, expected) { a, b -> a >= b }
        Lt -> cmp(actual, expected) { a, b -> a < b }
        Le -> cmp(actual, expected) { a, b -> a <= b }
        Range -> {
            val r = expected as? Expected.Range ?: return false
            val v = toNumber(actual) ?: return false
            v >= r.from && v <= r.to
        }
    }

    private fun equal(actual: Value, expected: Expected): Boolean = when (expected) {
        is Expected.Str -> actual is Value.VStr && actual.s == expected.s
        is Expected.Num -> toNumber(actual) == expected.d
        is Expected.Range -> false
    }

    private fun cmp(actual: Value, expected: Expected, test: (Double, Double) -> Boolean): Boolean {
        val num = expected as? Expected.Num ?: return false
        val a = toNumber(actual) ?: return false
        return test(a, num.d)
    }

    private fun toNumber(v: Value): Double? = when (v) {
        is Value.VInt -> v.i.toDouble()
        is Value.VFloat -> v.f
        else -> null
    }
}

/** Предикат: поле мира + операция + ожидаемое значение. */
data class Predicate(
    val root: WorldRoot,
    val field: String,
    val op: Op,
    val expected: Expected,
)

/** Ожидаемое значение предиката после разбора строки. */
sealed interface Expected {
    data class Str(val s: String) : Expected
    data class Num(val d: Double) : Expected
    data class Range(val from: Double, val to: Double) : Expected
}