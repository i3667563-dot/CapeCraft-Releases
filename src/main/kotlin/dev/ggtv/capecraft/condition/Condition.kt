package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.capecraft.api.condition.UNKNOWN as CAPE_WHEN_UNKNOWN
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot

/**
 * Условие выбора провайдера: все [WhenCheck] должны выполниться (AND).
 * Разбирается из блока `when { ... }` в `.kn`.
 *
 * Предикат = «поле мира <оператор> значение». Поле — живой корень мира
 * ([WorldRoot]) с полем через точку (`biome.temperature`, `weather.condition`,
 * `time.period`, `dimension.type`, `location.y`) или без точки — тогда
 * берётся дефолтное поле корня (biome→id, weather→condition, time→period,
 * dimension→type). Корни, объявленные аддоном (`fire.burning`), разбираются
 * так же, но приходят из [CapeWhenRoots], а не из koren: свой корень в
 * [WorldRoot] добавить нельзя, это закрытый перечень вендоренной библиотеки.
 *
 * Значение — строка или число. Операторы в строке:
 * - `">63"`, `">=63"`, `"<100"`, `"<=100"` — числовое сравнение;
 * - `"63..80"` — диапазон включительно;
 * - `"!rain"` — «не равно»;
 * - без оператора — равенство (строка = строка, число = число),
 *   числовая строка и число одинаковы.
 */
data class Condition(val predicates: List<WhenCheck>) {

    /** Выполняется ли условие на живом мире (AND по всем предикатам). */
    fun matches(world: WorldContext): Boolean = predicates.all { p ->
        val actual = try {
            p.read(world)
        } catch (e: CrenError.NotFound) {
            return@all false // поле мира недоступно — условие не выполнилось
        }
        p.op.apply(actual, p.expected)
    }

    /**
     * Есть ли в условии хотя бы один self-only предикат.
     *
     * Провайдер с таким условием целиком остаётся локальным: остальные
     * клиенты не знают ни про этот набор, ни про то, какое именно условие
     * сработало. Смешивать в одном провайдере публичные и self-only условия
     * бессмысленно — половина всё равно не уедет, поэтому такое срабатывает
     * как пометка «локальный».
     *
     * Аддонные корни self-only по определению: их читает только владелец
     * (см. [AddonPredicate.isSelfOnly]).
     */
    val hasSelfOnly: Boolean get() = predicates.any { it.isSelfOnly }

    companion object {
        /** Поле по умолчанию для корня без точки (`when { weather: "rain" }`). */
        val DEFAULT_FIELDS = mapOf(
            WorldRoot.BIOME to "id",
            WorldRoot.WEATHER to "condition",
            WorldRoot.TIME to "period",
            WorldRoot.DIMENSION to "type",
            // У брони есть осмысленное поле по умолчанию: нагрудник — тот слот,
            // про который чаще всего и спрашивают («а в чём я?»).
            WorldRoot.ARMOR to "chest",
            // У `health` поля по умолчанию нет намеренно: оба поля числовые,
            // а короткая запись разворачивается в сравнение со строкой и
            // никогда бы не совпала. Пишут `health.current` и `health.max`.
        )

        /**
         * Поля, которые живой мир действительно отдаёт.
         *
         * Держится в одном месте, потому что расходится с
         * `DEFAULT_FIELDS`: у `biome` поле по умолчанию — `id`, у `location`
         * поля по умолчанию нет вовсе, и без этого списка ошибка советовала
         * несуществующее `location.id`.
         */
        val LIVE_FIELDS = mapOf(
            WorldRoot.BIOME to listOf("id", "temperature", "precipitation"),
            WorldRoot.WEATHER to listOf("condition"),
            WorldRoot.TIME to listOf("period", "tick"),
            WorldRoot.DIMENSION to listOf("type", "id"),
            WorldRoot.LOCATION to listOf("x", "y", "z"),
            WorldRoot.ARMOR to listOf("head", "chest", "legs", "feet"),
            WorldRoot.HEALTH to listOf("current", "max"),
            WorldRoot.STATE to listOf("inWater", "sneaking", "sprinting", "onGround", "pose"),
        )

        /**
         * Тиры брони — обещание, а не описание Minecraft.
         *
         * Имя достаётся из id предмета (`diamond_chestplate` → `diamond`),
         * а не из `ArmorMaterial`: в 1.21 это `RegistryEntry` по id, в 26.2
         * материал вообще стал записью без имени, и общего у них ничего нет.
         * Список нужен ещё и для того, чтобы `unknown` отличался от настоящего
         * тира: без него подошло бы что угодно с подстрокой до `_`.
         */
        val ARMOR_TIERS = listOf(
            "none", "leather", "chainmail", "iron", "gold", "diamond", "netherite", "turtle",
        )

        /**
         * Значения булевых полей: мир отдаёт их строкой, а не `Value.VBool`.
         *
         * Вынесено в константу, потому что [FIELD_VALUES] и подсказки редактора
         * обязаны предлагать один и тот же список — иначе подсказка предложит
         * значение, на котором мод потом упадёт.
         */
        val BOOLEANS = listOf("true", "false")

        /**
         * Значения, которые поле отдаёт **ровно** в таком виде.
         *
         * Ключ — корень, значение — поле → множество. Пустое множество означает
         * «значение не ограничено» (`biome.id` — реестр модов, `location.y` —
         * число), и проверять там нечего.
         *
         * Список лежит здесь, а не в редакторе, потому что проверка и подсказка —
         * два следствия одного факта: мир отдаёт именно эти строки. Список в
         * `WhenSchema` был бы второй копией того же самого, и опечатка в любой
         * из них превращалась в тихо неработающий провайдер: `armor.chest:
         * "plate"` принимался, подсказка его не предлагала, и совпадение не
         * наступало никогда.
         *
         * Чего здесь нет: `unknown`. Мир отдаёт его вместо любого значения,
         * которое прочитать не удалось (в том числе для брони нестандартного
         * мода), и `armor.chest: "unknown"` — законное условие, поэтому
         * [UNKNOWN] принимается везде, где принимается значение.
         */
        val FIELD_VALUES: Map<WorldRoot, Map<String, List<String>>> = mapOf(
            WorldRoot.BIOME to mapOf("precipitation" to listOf("none", "rain", "snow")),
            WorldRoot.WEATHER to mapOf("condition" to listOf("clear", "rain", "thunder")),
            // Порядок — как в `timeField`: сначала то, что человек ищет чаще.
            WorldRoot.TIME to mapOf("period" to listOf("day", "sunrise", "sunset", "night")),
            WorldRoot.DIMENSION to mapOf("type" to listOf("overworld", "nether", "end")),
            // Тир брони одинаков для всех слотов — список один, а не на слот.
            WorldRoot.ARMOR to mapOf(
                "head" to ARMOR_TIERS,
                "chest" to ARMOR_TIERS,
                "legs" to ARMOR_TIERS,
                "feet" to ARMOR_TIERS,
            ),
            // Булевы поля отдаются строками "true"/"false", а не `Value.VBool`:
            // тогда `state.sneaking: true` разбирается в равенство строке и
            // работает тем же кодом сравнения, что остальные поля.
            WorldRoot.STATE to mapOf(
                "inWater" to BOOLEANS,
                "sneaking" to BOOLEANS,
                "sprinting" to BOOLEANS,
                "onGround" to BOOLEANS,
                "pose" to listOf(
                    "standing", "crouching", "swimming", "fall_flying",
                    "sleeping", "spin_attack", "long_jumping", "dying",
                ),
            ),
        )

        /**
         * Поля, где значение приходит числом.
         *
         * Только у них осмысленны `>`, `<`, `..`: [Op] для строк просто
         * возвращает `false`, и подсказка «`>`» у `weather.condition` была бы
         * враньём. В списке [FIELD_VALUES] их нет, и это не пропуск: поле
         * числовое и строковое одновременно не бывает, а расхождение этих двух
         * списков проверяет тест.
         */
        val NUMERIC_FIELDS: Map<WorldRoot, List<String>> = mapOf(
            WorldRoot.BIOME to listOf("temperature"),
            WorldRoot.TIME to listOf("tick"),
            WorldRoot.LOCATION to listOf("x", "y", "z"),
            // И текущее, и максимальное здоровье — числа, поэтому `health.max`
            // пишется как `">=20"`, а `health.current: "<10"`.
            WorldRoot.HEALTH to listOf("current", "max"),
        )

        /** Принимает ли поле числа, а значит ли операторы сравнения. */
        fun isNumeric(root: WorldRoot, field: String): Boolean =
            NUMERIC_FIELDS[root]?.contains(field) == true

        /**
         * Значение, которым мир отвечает «не смог прочитать».
         *
         * Принимается в любом поле со строковыми значениями: для брони это
         * способ поймать нестандартный слот, для остальных полей — просто
         * «мир не отвечает», и условие честно не срабатывает, а не падает.
         *
         * Объявлено в API-пакете ([CAPE_WHEN_UNKNOWN]): читатель аддонного
         * корня обязан отвечать тем же словом, и тянуть сюда `Condition`
         * нельзя — публичный API от него не зависит.
         */
        const val UNKNOWN = CAPE_WHEN_UNKNOWN

        /** Значения поля; пустой список — поле не ограничено. */
        fun valuesOf(root: WorldRoot, field: String): List<String> =
            FIELD_VALUES[root]?.get(field).orEmpty()

        /** Принимает ли поле строковое значение из закрытого списка. */
        fun isClosedField(root: WorldRoot, field: String): Boolean =
            valuesOf(root, field).isNotEmpty()

        /**
         * Встроенные корни, которые не уезжают по сети.
         *
         * `state` описывает то, чего про другого игрока не узнать: в воде ли
         * он, крадётся ли, на земле ли. Владелец считает это про себя, а
         * чужой клиент про такого игрока не знает ничего — поэтому условие с
         * таким корнем делает провайдера локальным целиком.
         *
         * Аддонных корней здесь нет и быть не может: они приходят из
         * [CapeWhenRoots] и self-only по определению (см.
         * [dev.ggtv.capecraft.api.condition.CapeWhenRoot.selfOnly]).
         *
         * Само свойство живёт здесь, а не в koren: [WorldRoot] — вендоренный
         * формат, и продуктовое решение о синхронизации в него тащить нельзя.
         */
        val SELF_ONLY_ROOTS = setOf(WorldRoot.STATE)

        /** Что отдаёт только владелец, а что видят все. */
        fun isSelfOnlyRoot(root: WorldRoot): Boolean = root in SELF_ONLY_ROOTS

        /** Сегменты всех корней, известных разбору: встроенные и аддонные. */
        fun knownSegments(): List<String> =
            WorldRoot.entries.map { it.segment } + CapeWhenRoots.registry.names()

        /** Разобрать блок `when { ... }` из словаря. Пустой словарь — пустое условие. */
        fun parse(dict: Value.VDict): Condition {
            val predicates = dict.pairs.map { (key, value) -> parsePredicate(key, value) }
            return Condition(predicates)
        }

        /**
         * Разобрать один предикат.
         *
         * Сначала ищется корень среди встроенных и только потом — среди
         * аддонных: имя `biome` не должно перестать работать оттого, что
         * какой-то аддон захотел назваться так же (такая регистрация
         * отвергается, но полагаться на это в разборе не надо).
         */
        private fun parsePredicate(key: String, value: Value): WhenCheck {
            val parts = key.split('.').filter { it.isNotBlank() }
            val segment = parts.firstOrNull().orEmpty()
            val root = WorldRoot.bySegment(segment)
                ?: return parseAddonPredicate(key, parts, segment, value)
            val field = parts.getOrNull(1) ?: DEFAULT_FIELDS[root]
                ?: throw IllegalArgumentException(
                    "условие when: для «${root.segment}» нужно указать поле — " +
                        "доступны: ${LIVE_FIELDS.getValue(root).joinToString()}",
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
            val (op, expected) = parseValueFor("when", value)
            checkValue(root.segment, field, isNumeric(root, field), valuesOf(root, field), op, expected)
            return Predicate(root, field, op, expected)
        }

        /**
         * Разобрать предикат аддонного корня: `fire.burning: "true"`.
         *
         * Проверки те же, что у встроенного корня, но по данным аддона
         * ([dev.ggtv.capecraft.api.condition.CapeWhenRoot]): поле должно быть
         * объявлено, а значение — из его списка. Иначе `fire.burnning: "true"`
         * разобрался бы и никогда не совпал бы — то самое молчание, из-за
         * которого [checkValue] и существует.
         *
         * Синонимов у аддонного корня нет: короткая запись без поля читается
         * через `defaultField`, а придумывать за корнем словарь алиасов ради
         * одного значения незачем — аддон объявит это полем.
         */
        private fun parseAddonPredicate(
            key: String,
            parts: List<String>,
            segment: String,
            value: Value,
        ): AddonPredicate {
            val spec = CapeWhenRoots.registry[segment]
                ?: throw IllegalArgumentException(
                    "условие when: неизвестный корень мира «$segment» " +
                        "(доступны: ${knownSegments().joinToString()})",
                )
            if (parts.size > 2) {
                throw IllegalArgumentException(
                    "условие when: слишком глубокий путь «$key» (максимум «$segment.поле»)",
                )
            }
            val field = parts.getOrNull(1) ?: spec.defaultField
                ?: throw IllegalArgumentException(
                    "условие when: для «$segment» нужно указать поле — " +
                        "доступны: ${spec.fields.joinToString { it.name }}",
                )
            val specField = spec.fields.firstOrNull { it.name == field }
                ?: throw IllegalArgumentException(
                    "условие when: у корня «$segment» нет поля «$field». " +
                        "Доступны: ${spec.fields.joinToString { it.name }}",
                )
            val (op, expected) = parseValueFor("when", value)
            checkValue(segment, field, specField.numeric, specField.values, op, expected)
            return AddonPredicate(segment, field, op, expected)
        }

        /**
         * Проверить, что значение вообще может совпасть.
         *
         * Без этой проверки `armor.chest: "plate"` молча превращался в условие,
         * которое не срабатывает никогда: разбор проходил, провайдер грузился,
         * ошибки не было нигде, а плащ просто не появлялся — и винить не на
         * что. Теперь это ошибка загрузки с перечнем допустимых значений.
         *
         * Проверяются только те значения, где список закрыт. `biome.id` и
         * `dimension.id` открыты (реестр модов), числа открыты по построению, а
         * [UNKNOWN] принимается везде: это ответ мира «не прочитано», и
         * `armor.chest: "unknown"` — законный способ поймать нестандартный слот.
         *
         * Параметры строкой, а не [WorldRoot]: правила должны быть одни и те же
         * для встроенного и аддонного корня, а не «как для корня, так и
         * отдельно почти то же» для аддонного.
         */
        private fun checkValue(
            segment: String,
            field: String,
            numeric: Boolean,
            allowed: List<String>,
            op: Op,
            expected: Expected,
        ) {
            // Числовое поле со строкой: `health.current: "низко"`. Строка не
            // число, и `parseValueFor` оставил её строкой — сравнивать её
            // тут не с чем, условие не сработает никогда.
            if (numeric && expected is Expected.Str) {
                throw IllegalArgumentException(
                    "условие when: «$segment.$field» — числовое поле, " +
                        "а значение «${expected.s}» не число. Пишите " +
                        "\">20\", \"<=20\" или \"10..20\"",
                )
            }
            if (allowed.isEmpty()) return
            val text = (expected as? Expected.Str)?.s ?: throw IllegalArgumentException(
                "условие when: «$segment.$field» — поле со списком значений, " +
                    "а здесь число или диапазон. Допустимо: ${allowed.joinToString(", ")}",
            )
            // `!` сравнивает строки, а `>`/`<`/`..` — числа. Оператор над
            // закрытым списком строк не может сработать, поэтому это ошибка
            // формата, а не значения.
            if (op != Op.Eq && op != Op.NotEq) {
                throw IllegalArgumentException(
                    "условие when: «$segment.$field» — поле со списком значений, " +
                        "оператор «${op.symbol()}» к нему неприменим. " +
                        "Допустимо: ${allowed.joinToString(", ")}",
                )
            }
            if (text != UNKNOWN && text !in allowed) {
                throw IllegalArgumentException(
                    "условие when: «$text» не подходит для «$segment.$field». " +
                        "Допустимо: ${allowed.joinToString(", ")}" +
                        " (или $UNKNOWN, если поле не удалось прочитать)",
                )
            }
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
                    // Реальные периоды: day, sunset, night, sunrise. «dawn» и «dusk» —
                    // человеческие синонимы, а не значения, которые отдаёт мир.
                    "day" -> Triple("period", Op.Eq, Expected.Str("day"))
                    "dawn", "sunrise" -> Triple("period", Op.Eq, Expected.Str("sunrise"))
                    "dusk", "sunset" -> Triple("period", Op.Eq, Expected.Str("sunset"))
                    "night", "midnight" -> Triple("period", Op.Eq, Expected.Str("night"))
                    else -> null
                }
                WorldRoot.DIMENSION -> when (v) {
                    "overworld" -> Triple("type", Op.Eq, Expected.Str("overworld"))
                    "nether", "the_nether" -> Triple("type", Op.Eq, Expected.Str("nether"))
                    "end", "the_end" -> Triple("type", Op.Eq, Expected.Str("end"))
                    else -> null
                }
                // `armor: "netherite"` — нагрудник, поле по умолчанию.
                WorldRoot.ARMOR -> ARMOR_TIERS
                    .takeIf { v in it }
                    ?.let { Triple("chest", Op.Eq, Expected.Str(v)) }
                // У `health` и `state` поля по умолчанию нет, а значит нет и
                // смысла в короткой записи: сравнивать не с чем.
                WorldRoot.HEALTH, WorldRoot.STATE -> null
                else -> null
            }
        }

        /**
         * Строка → операция + ожидаемое значение.
         *
         * Общий разбор для `when` и `if`: операторный набор у них один, и
         * разбирать его в двух местах означало бы, что со временем один блок
         * получит правило, которого нет у второго. [block] подставляется в
         * текст ошибки, чтобы человек знал, в каком блоке опечатка.
         */
        internal fun parseValueFor(block: String, value: Value): Pair<Op, Expected> {
            val text = when (value) {
                is Value.VStr -> value.s
                is Value.VInt -> value.i.toString()
                is Value.VFloat -> value.f.toString()
                is Value.VBool -> value.b.toString()
                else -> throw IllegalArgumentException(
                    "условие $block: значение должно быть строкой или числом, найдено «${value.kind}»",
                )
            }
            val s = text.trim()

            // Диапазон «a..b» — приоритетнее операторов сравнения.
            val range = "^(.+?)\\.\\.(.+)$".toRegex().matchEntire(s)
            if (range != null) {
                val from = range.groupValues[1].trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("условие $block: неверный диапазон «$s»")
                val to = range.groupValues[2].trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("условие $block: неверный диапазон «$s»")
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

/**
 * Разбор одного предиката: что в мире на самом деле против чего ждали.
 *
 * Отдельный тип, а не строка, потому что `/cp list` должен показать не
 * «условие не выполнилось», а пару значений: иначе провайдер, который
 * справедливо отсеялся, выглядит так же, как тот, который не проверили.
 */
data class PredicateReport(
    /** `armor.chest` — корни и поле, как их пишут в конфиге. */
    val path: String,
    /** Значение из мира, как его вернул контекст. */
    val actual: String,
    /** Ожидание с оператором: `= netherite`, `<= -20`, `= rain`. */
    val expected: String,
    val ok: Boolean,
)

/**
 * Разбор [Condition] по предикатам — для диагностики выбора провайдера.
 *
 * Значения берутся тем же [WorldContext] и тем же правилом «поле недоступно —
 * не подошло», что и в `matches`. Отдельный обход нужен именно ради
 * отчёта: сам `matches` отдаёт один `Boolean` на всё условие, а разбирать
 * надо, **какая** из четырёх проверок не сошлась.
 */
fun Condition.explain(world: WorldContext): List<PredicateReport> = predicates.map { p ->
    val path = "${p.segment}.${p.field}"
    val actual = try {
        p.read(world)
    } catch (_: CrenError.NotFound) {
        // Ровно как в `matches`: неизвестное поле = «не подошло».
        // Только здесь это ещё и видно в отчёте, а не теряется внутри AND.
        Value.VStr(Condition.UNKNOWN)
    }
    PredicateReport(
        path = path,
        actual = renderValue(actual),
        expected = "${p.op.symbol()} ${renderExpected(p.expected)}",
        ok = p.op.apply(actual, p.expected),
    )
}

/** Значение поля мира строкой, как его показывает отчёт. */
internal fun renderValue(v: Value): String = when (v) {
    is Value.VStr -> v.s
    is Value.VInt -> v.i.toString()
    is Value.VFloat -> v.f.toString()
    is Value.VBool -> v.b.toString()
    else -> v.toString()
}

/** Ожидание предиката строкой: `netherite`, `-20`, `63..80`. */
internal fun renderExpected(e: Expected): String = when (e) {
    is Expected.Str -> e.s
    is Expected.Num -> e.d.toString()
    is Expected.Range -> "${e.from}..${e.to}"
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

    /** Символ оператора — для текста ошибки. */
    fun symbol(): String = when (this) {
        Eq -> "="
        NotEq -> "!"
        Gt -> ">"
        Ge -> ">="
        Lt -> "<"
        Le -> "<="
        Range -> ".."
    }

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
    override val field: String,
    override val op: Op,
    override val expected: Expected,
) : WhenCheck {

    override val segment: String get() = root.segment

    override val isSelfOnly: Boolean get() = Condition.isSelfOnlyRoot(root)

    override fun read(world: WorldContext): Value = world.field(root, field, "$segment.$field")
}

/**
 * Предикат аддонного корня: `fire.burning`, `myaddon.level`.
 *
 * Отдельный тип, а не [Predicate] с корнем-строкой: [Predicate.root] — это
 * [WorldRoot], закрытый перечень koren, и подставить туда «fire» нельзя. Всё
 * остальное — оператор, ожидаемое значение, чтение из мира — у них общее,
 * поэтому оно вынесено в [WhenCheck].
 *
 * ## Почему self-only всегда
 *
 * Наблюдатель не знает состояния чужого игрока: он видит плащ и клетки
 * инвентаря, а не «горит ли он». Если такое условие поехало бы по сети,
 * наблюдатель посчитал бы его против СВОЕГО игрока и показал плащ не тому:
 * я горю, а он увидит мой «горящий» плащ, стоя в пустыне. Наблюдателю это
 * ничего не говорит, а игроку — врёт.
 *
 * Отсюда и второе следствие: [dev.ggtv.capecraft.sync.WireCondition.from]
 * возвращает на таком условии `null`, поэтому провайдер целиком выпадает из
 * объявления и остаётся локальным — так же, как с [WorldRoot.STATE].
 */
data class AddonPredicate(
    val root: String,
    override val field: String,
    override val op: Op,
    override val expected: Expected,
) : WhenCheck {

    override val segment: String get() = root

    override val isSelfOnly: Boolean get() = true

    /**
     * Значение поля — через сам контекст, а не напрямую в реестр.
     *
     * Именно контекст решает, наблюдаем ли корень: мой мир читает, чужой
     * игрок и сервер отдают `NotFound`, и условие молча не совпадает. Спросить
     * реестр напрямую — значит убрать этот запрет и начать показывать мой
     * «горящий» плащ каждому, кто стоит рядом со мной.
     */
    override fun read(world: WorldContext): Value =
        world.addonField(root, field, "$segment.$field")
}

/**
 * Проверка одного предиката.
 *
 * Общая часть [Predicate] и [AddonPredicate]: отличаются они только тем, откуда
 * берётся значение поля, а не тем, как оно сравнивается с ожиданием.
 */
sealed interface WhenCheck {
    /** Поле корня: `temperature`, `burning`. */
    val field: String

    /** Операция сравнения, разобранная из строки значения. */
    val op: Op

    /** Ожидаемое значение. */
    val expected: Expected

    /** Корневой сегмент, как его пишут в конфиге: `biome`, `fire`. */
    val segment: String

    /** Читает ли условие только владелец плаща. */
    val isSelfOnly: Boolean

    /**
     * Значение поля на живом мире.
     *
     * @throws dev.ggtv.kjen.CrenError.NotFound прочитать нечем: корня нет,
     * поле не поддерживается или контекст не тот (чужой игрок, сервер).
     */
    fun read(world: WorldContext): Value
}

/** Ожидаемое значение предиката после разбора строки. */
sealed interface Expected {
    data class Str(val s: String) : Expected
    data class Num(val d: Double) : Expected
    data class Range(val from: Double, val to: Double) : Expected
}