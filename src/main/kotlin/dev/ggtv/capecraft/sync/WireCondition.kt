package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.condition.Expected
import dev.ggtv.capecraft.condition.Op
import dev.ggtv.capecraft.condition.Predicate
import dev.ggtv.koren.WorldRoot

/**
 * Проводное представление условия `when` в Sync v2.
 *
 * Условие едет по сети, потому что вычислять его должен **каждый клиент сам**,
 * против контекста наблюдаемого игрока. Иначе все увидели бы чужой плащ,
 * посчитанный в биоме того, кто его объявил: игрок стоит в джунглях, а его
 * сосед видит его в пустыне — и cape остался бы джунглевым.
 *
 * ## Почему не отправлять `Condition` напрямую
 *
 * Формально [Condition] уже сериализуем, но класс лежит в вендоренном koren
 * (`dev.ggtv.koren.WorldRoot` — enum из внешней библиотеки). Кодировать его
 * `ordinal` нельзя: добавление или перестановка корня в koren молча сдвинет
 * значения и старые клиенты начнут читать `biome` как `weather`. Поэтому в
 * проводе живёт собственный [WireRoot] с явными тегами, а мост к koren идёт
 * через **имя сегмента** ([WorldRoot.segment]) — перестановка enum koren
 * формат не сломает, а настоящее изменение состава корней требует бампа
 * [SyncProtocol.VERSION], как и любое другое изменение формата.
 *
 * AST плоский: предикаты не вкладываются друг в друга, поэтому глубина
 * фиксирована и рекурсии при разборе нет.
 */
data class WireCondition(val predicates: List<WirePredicate>) {

    /** Проверка формы и лимитов. Пустой список = всё в порядке. */
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (predicates.isEmpty()) {
            out += "пустое условие"
        }
        if (predicates.size > SyncProtocol.MAX_PREDICATES) {
            out += "${predicates.size} предикатов, максимум ${SyncProtocol.MAX_PREDICATES}"
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
         * Возвращает `null`, если предикат не переводится — это лучше, чем
         * молча выкинуть условие: провайдер без `when` в сети смотрится совсем
         * не так, как этот же провайдер локально, и расхождение не видно.
         */
        fun from(condition: Condition?): WireCondition? {
            if (condition == null) return null
            val out = ArrayList<WirePredicate>(condition.predicates.size)
            for (p in condition.predicates) {
                val root = WireRoot.of(p.root) ?: return null
                val op = WireOp.of(p.op) ?: return null
                val expected = when (val e = p.expected) {
                    is Expected.Str -> WireExpected.Str(e.s)
                    is Expected.Num -> WireExpected.Num(e.d)
                    is Expected.Range -> WireExpected.Range(e.from, e.to)
                }
                out += WirePredicate(root, p.field, op, expected)
            }
            return WireCondition(out)
        }
    }
}

/** Предикат в проводном виде. */
data class WirePredicate(
    val root: WireRoot,
    val field: String,
    val op: WireOp,
    val expected: WireExpected,
) {
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (field.isEmpty()) {
            out += "пустое поле в условии"
        }
        if (ActiveCape.utf8Len(field) > SyncProtocol.MAX_FIELD_BYTES) {
            out += "поле «${field}» длиннее ${SyncProtocol.MAX_FIELD_BYTES} байт"
        }
        if (op.requiresNumeric && expected !is WireExpected.Num && expected !is WireExpected.Range) {
            out += "оператор ${op.name} требует числа, а задана строка"
        }
        if (expected is WireExpected.Str &&
            ActiveCape.utf8Len(expected.s) > SyncProtocol.MAX_EXPECTED_STR_BYTES
        ) {
            out += "значение условия длиннее ${SyncProtocol.MAX_EXPECTED_STR_BYTES} байт"
        }
        return out
    }

    /** Обратно в локальный предикат — ради вычисления `matches`. */
    fun toLocal(): Predicate? {
        val korenRoot = root.toKoren() ?: return null
        val localOp = op.toLocal() ?: return null
        val localExpected = when (val e = expected) {
            is WireExpected.Str -> Expected.Str(e.s)
            is WireExpected.Num -> Expected.Num(e.d)
            is WireExpected.Range -> Expected.Range(e.from, e.to)
        }
        return Predicate(korenRoot, field, localOp, localExpected)
    }
}

/**
 * Корень условия на проводе.
 *
 * Порядок и набор — часть формата, поэтому добавление корня = бамп
 * [SyncProtocol.VERSION]. Связь с koren — только по [segment].
 *
 * `state` на проводе нет намеренно: это self-only корень, про который
 * наблюдатель ничего не знает, и провайдер с таким условием не доходит до
 * сети вовсе (`Provider.isSelfOnly`). Пустой набор тегов для него означал бы
 * «это состояние одинаково у всех», а это ровно то поведение, от которого
 * отказывались: условие должно быть видно только мне, а не всем.
 */
enum class WireRoot(val tag: Int, val segment: String) {
    BIOME(0, "biome"),
    WEATHER(1, "weather"),
    TIME(2, "time"),
    DIMENSION(3, "dimension"),
    LOCATION(4, "location"),
    ARMOR(5, "armor"),
    HEALTH(6, "health"),
    ;

    companion object {
        fun byTag(tag: Int): WireRoot? = entries.firstOrNull { it.tag == tag }

        /**
         * `null`, если корень self-only: он не переводится в провод, и
         * [WireCondition.from] на таком условии вернёт `null`, то есть
         * провайдер целиком выпадет из объявления.
         */
        fun of(root: WorldRoot): WireRoot? {
            if (Condition.isSelfOnlyRoot(root)) return null
            return entries.firstOrNull { it.segment == root.segment }
        }
    }

    fun toKoren(): WorldRoot? = WorldRoot.bySegment(segment)
}

/**
 * Операция сравнения на проводе.
 *
 * [Op] в koren — sealed interface с `data object`, а не enum, поэтому
 * сопоставление явное в обе стороны: рефлексия по имени тут не годится, а
 * `.name`-матчинг всё равно связывает провод с чужим исходником.
 *
 * Порядок и набор — часть формата: новая операция = бамп [SyncProtocol.VERSION].
 */
enum class WireOp(val tag: Int, val requiresNumeric: Boolean) {
    EQ(0, false),
    NOT_EQ(1, false),
    GT(2, true),
    GE(3, true),
    LT(4, true),
    LE(5, true),
    RANGE(6, true),
    ;

    companion object {
        fun byTag(tag: Int): WireOp? = entries.firstOrNull { it.tag == tag }

        fun of(op: Op): WireOp? = when (op) {
            Op.Eq -> EQ
            Op.NotEq -> NOT_EQ
            Op.Gt -> GT
            Op.Ge -> GE
            Op.Lt -> LT
            Op.Le -> LE
            Op.Range -> RANGE
        }
    }

    fun toLocal(): Op? = when (this) {
        EQ -> Op.Eq
        NOT_EQ -> Op.NotEq
        GT -> Op.Gt
        GE -> Op.Ge
        LT -> Op.Lt
        LE -> Op.Le
        RANGE -> Op.Range
    }
}

/** Ожидаемое значение на проводе. */
sealed interface WireExpected {
    data class Str(val s: String) : WireExpected
    data class Num(val d: Double) : WireExpected
    data class Range(val from: Double, val to: Double) : WireExpected
}

/** Собрать локальное [Condition] из проводного — или `null`, если что-то не перевелось. */
fun WireCondition.toLocal(): Condition? {
    val out = ArrayList<Predicate>(predicates.size)
    for (p in predicates) {
        out += p.toLocal() ?: return null
    }
    return Condition(out)
}
