package dev.ggtv.capecraft.schema

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.provider.ProviderNames
import dev.ggtv.capecraft.sync.ServerSyncSettings
import dev.ggtv.koren.WorldRoot

/**
 * Декларативное описание конфига CapeCraft: какие ключи бывают, какого они типа,
 * что означают и какие значения допустимы.
 *
 * ## Зачем это отдельный файл
 *
 * Раньше схемы не существовало: мод читала ключи по месту использования
 * (`cfg.getIntOr("capeCraft.limits.$key", ...)`), а неизвестные ключи молча
 * игнорировались. Из-за этого «где ты накосячил» было негде спросить — правила
 * были размазаны по вызовам, и редактор не мог их показать.
 *
 * Теперь правила собраны здесь, и из **одного** описания питаются:
 *
 * - подсказки в редакторе (какие ключи вообще бывают на этом уровне);
 * - всплывающая документация по ключу;
 * - диагностика «неизвестный ключ», «неверный тип», «значение не из списка»;
 * - `when`-подсказки: корни, поля, операторы, короткие записи.
 *
 * ## Почему строки берутся из кода, а не из констант здесь
 *
 * Имена ключей и корни `when` не продублированы строкой: [ProviderNames.Keys],
 * [ProviderNames.Types], [WorldRoot] и [Condition.LIVE_FIELDS] — источник
 * истины. Разъехаться с модом схема не может: переименовали константу в
 * `ProviderLoader` — переименуется и здесь, а не развалится молча.
 *
 * ## Про открытые множества
 *
 * [Field.allowed] пуст — значит значений любое количество. [Field.open] означает,
 * что множество заранее не задано и расширяется извне: `type` у провайдера может
 * прийти из аддона через `CapeApiHolder.api.sourceTypes`, поэтому чужой `type` —
 * это не ошибка, а предупреждение (см. [Field.open] на `providers.type`).
 */
enum class SchemaType {
    /** Любое значение. */
    ANY,

    /** Строка в кавычках. */
    STR,

    /** Целое число. */
    INT,

    /** Число с дробной частью. */
    FLOAT,

    /** `true` / `false`. */
    BOOL,

    /** Словарь в фигурных скобках: `{ name = "x" }`. */
    DICT,

    /** Массив в квадратных скобках: `[ 1, 2 ]`. */
    ARRAY,

    /** Именованный блок: `server { ... }`. */
    BLOCK,

    /** Ссылка на другой ключ: `server.token[1]`. */
    REF,
}

/**
 * Один ключ схемы.
 *
 * @property name имя ключа, как пишется в `.kn`
 * @property type какой тип значения ожидается
 * @property doc текст для всплывающей подсказки; пишется от третьего лица, коротко
 * @property required обязателен ли: отсутствие — ошибка, а не молчаливый дефолт
 * @property def значение по умолчанию строкой, если оно есть
 * @property allowed закрытое множество допустимых значений; пусто — любое
 * @property children вложенные ключи для [SchemaType.DICT] и [SchemaType.BLOCK]
 * @property appliesTo ключ имеет смысл только при таких значениях соседнего `type`
 * @property open множество значений расширяется извне, чужое значение — не ошибка
 * @property deprecated ключ больше не читается; здесь — чем заменён
 */
data class Field(
    val name: String,
    val type: SchemaType,
    val doc: String,
    val required: Boolean = false,
    val def: String? = null,
    val allowed: List<String> = emptyList(),
    val children: List<Field> = emptyList(),
    val appliesTo: Set<String> = emptySet(),
    /**
     * Типы, для которых ключ обязателен, хотя [required] снят.
     *
     * Обязательность по существу привязана к типу, а не к имени: `path`
     * обязателен у встроенного `file` и не обязателен у аддонного `image`,
     * где достаточно `url`. Пока [appliesTo] был один, это разойтись не могло;
     * с аддонами — может, и [required] + [appliesTo] перестали хватать.
     *
     * Пусто вместе с [required] = false — «никогда не обязателен».
     */
    val requiredFor: Set<String> = emptySet(),
    val open: Boolean = false,
    val deprecated: String? = null,
) {
    /**
     * Ключ показывается на этом уровне для провайдера типа [type].
     *
     * Ключи без [appliesTo] общие и показываются всегда; остальные — только когда
     * соседний `type` уже известен. Для аддон-типа показываем всё: чужой аддон
     * может знать и свои ключи, а скрывать их — хуже, чем показать лишнее.
     */
    fun offeredFor(type: String?): Boolean = when {
        appliesTo.isEmpty() -> true
        type == null -> true
        type in appliesTo -> true
        // Аддон-типа в списке нет, значит это не url/file/json — свой набор.
        appliesTo.containsAll(BUILTIN_TYPES) -> true
        else -> false
    }

    /**
     * Ключ обязателен именно для провайдера типа [type].
     *
     * [required] без условия — обязан быть всегда. [requiredFor] — обязан для
     * перечисленных типов и только для них: `path` у аддонного `image`
     * обязателен не больше, чем `extract` у `url`.
     */
    fun requiredForType(type: String?): Boolean =
        required || (type != null && type in requiredFor)

    companion object {
        private val BUILTIN_TYPES = setOf(
            ProviderNames.Types.URL,
            ProviderNames.Types.FILE,
            ProviderNames.Types.JSON,
        )

        /**
         * Тип, о котором схема знает целиком: у него понятный набор ключей.
         *
         * Всё, что не [BUILTIN_TYPES], — тип от аддона. Аддон приходит своим
         * jar'ом и никакого реестра не заполняет, поэтому узнать его можно
         * только таким способом.
         *
         * Правило одно на [offeredFor] и на проверку `appliesTo`, иначе они
         * противоречат друг другу: ключ показывается как годный, а через
         * строку подсвечивается предупреждением.
         */
        fun isBuiltinProviderType(type: String): Boolean = type in BUILTIN_TYPES
    }
}

/**
 * Схема условий `when`.
 *
 * Отдельный объект, а не вложенные [Field], потому что форма у `when`
 * не как у обычного словаря: один корень живёт в двух видах — короткой
 * записью (`biome = "snowy"`, только если у корня есть поле по умолчанию)
 * и плоским ключом с точкой (`biome.precipitation = "snow"`, всегда).
 * Внутри одного уровня это два разных смысла, и [Field] их не выражает.
 *
 * Именно плоский ключ с точкой, а не вложенный словарь: значение условия —
 * строка, число или диапазон ([dev.ggtv.capecraft.condition.Condition.parseValueFor]),
 * и `biome { id = "..." }` игра отвергает при загрузке.
 */
object WhenSchema {
    /**
     * Операторы сравнения в порядке от короткого к длинному.
     *
     * Диапазон `a..b` сюда не входит: он не оператор, а форма значения, и
     * разбирается раньше, потому что `10..20` начинается с числа.
     */
    val OPERATORS = listOf("=", "!", ">=", "<=", ">", "<")

    /** Разделитель диапазона; в тексте — `от..до`. */
    const val RANGE_SEPARATOR = ".."

    /**
     * Поля, где значение приходит числом.
     *
     * Только у них осмысленны `>`, `<`, `..` — [dev.ggtv.capecraft.condition.Op]
     * для строк просто возвращает `false`, и подсказка «`>`» у
     * `weather.condition` была бы враньём.
     */
    val NUMERIC_FIELDS: Map<WorldRoot, List<String>> = mapOf(
        WorldRoot.BIOME to listOf("temperature"),
        WorldRoot.TIME to listOf("tick"),
        WorldRoot.LOCATION to listOf("x", "y", "z"),
        // И текущее, и максимальное здоровье — числа, поэтому `health.max`
        // пишется как `">=20"`, а `health.current: "<10"`.
        WorldRoot.HEALTH to listOf("current", "max"),
    )


    /**
     * Поля со списком значений: всё, что мир отдаёт, — и больше ничего.
     *
     * Список — **не** второй экземпляр правил, а ссылка на
     * [Condition.FIELD_VALUES]: мод проверяет значение по этому же списку и
     * падает на опечатке, поэтому копия здесь разошлась бы с ним при первом же
     * изменении. Раньше так и вышло: подсказка предлагала тиры брони, а мод
     * принимал любую строку — и провайдер с `armor.chest: "plate"` не работал
     * молча.
     *
     * Сверяется с исходниками всех версий тестом `WorldContextAgreementTest`:
     * `MinecraftWorldContext` отдаёт эти строки литералами, и разойтись с ним
     * можно только молча.
     *
     * Поля, которых тут нет, — свободные (`biome.id`, `dimension.id`) или
     * числа ([NUMERIC_FIELDS]); перечислять их нельзя.
     */
    val VALUES: Map<WorldRoot, Map<String, List<String>>> get() = Condition.FIELD_VALUES

    /**
     * Self-only корни — те, что не уезжают по сети.
     *
     * Дублирует [Condition.SELF_ONLY_ROOTS] как источник правды для редактора:
     * подсказка не должна предлагать синхронизируемое поле, если провайдер с
     * ним всё равно не объявляется.
     */
    fun isSelfOnlyRoot(root: WorldRoot): Boolean = Condition.isSelfOnlyRoot(root)

    /** Поле по умолчанию для корня без точки — из рантайма, не второй раз. */
    fun defaultFieldOf(root: WorldRoot): String? = Condition.DEFAULT_FIELDS[root]

    /** Значения поля: пустой список, если поле свободное или числовое. */
    fun valuesOf(root: WorldRoot, field: String): List<String> =
        VALUES[root]?.get(field).orEmpty()

    /** Принимает ли поле числа, а значит ли [OPERATORS]. */
    fun isNumeric(root: WorldRoot, field: String): Boolean =
        NUMERIC_FIELDS[root]?.contains(field) == true

    /**
     * Короткие записи корня без поля, разобранные в нормализованный вид.
     *
     * Ключ — синоним, как его пишет человек; значение — во что он превращается.
     * Порядок внутри [Field.allowed] совпадает с порядком здесь, чтобы
     * подсказки шли в том же порядке, что и парсер.
     */
    val ALIASES: Map<WorldRoot, Map<String, String>> = mapOf(
        WorldRoot.BIOME to linkedMapOf(
            "snowy" to "precipitation = snow",
            "snow" to "precipitation = snow",
            "frozen" to "precipitation = snow",
            "rain" to "precipitation = rain",
            "rainy" to "precipitation = rain",
        ),
        WorldRoot.WEATHER to linkedMapOf(
            "clear" to "condition = clear",
            "fair" to "condition = clear",
            "rain" to "condition = rain",
            "rainy" to "condition = rain",
            "thunder" to "condition = thunder",
            "storm" to "condition = thunder",
        ),
        WorldRoot.TIME to linkedMapOf(
            "day" to "period = day",
            "dawn" to "period = sunrise",
            "sunrise" to "period = sunrise",
            "dusk" to "period = sunset",
            "sunset" to "period = sunset",
            "night" to "period = night",
            "midnight" to "period = night",
        ),
        WorldRoot.DIMENSION to linkedMapOf(
            "overworld" to "type = overworld",
            "nether" to "type = nether",
            "the_nether" to "type = nether",
            "end" to "type = end",
            "the_end" to "type = end",
        ),
        // У координат коротких записей нет: сравнивать не с чем.
        WorldRoot.LOCATION to linkedMapOf(),
        // Короткая запись брони — всегда нагрудник, поле по умолчанию.
        WorldRoot.ARMOR to Condition.ARMOR_TIERS.associateWith { "chest = $it" },
        WorldRoot.HEALTH to linkedMapOf(),
        WorldRoot.STATE to linkedMapOf(),
    )

    /** Поля корня — из [Condition.LIVE_FIELDS], чтобы совпадало с рантаймом. */
    fun fieldsOf(root: WorldRoot): List<String> =
        Condition.LIVE_FIELDS[root].orEmpty()

    /** Корни, у которых вообще есть поля. */
    fun roots(): List<WorldRoot> = WorldRoot.entries.filter { fieldsOf(it).isNotEmpty() }

    /** Короткие записи корня; у [WorldRoot.LOCATION] их нет. */
    fun aliasesOf(root: WorldRoot): List<String> = ALIASES[root]?.keys?.toList().orEmpty()

    /**
     * Человеческое описание поля — для подсказки.
     *
     * Перечисление значений не пишется руками: оно собирается из [VALUES], иначе
     * подсказка «`snowy_plains`» и подсказка со списком значений разъедутся
     * при первом же изменении списка — и разъедутся молча.
     */
    fun docFor(root: WorldRoot, field: String): String? {
        val described = when (root) {
            WorldRoot.BIOME -> when (field) {
                "id" -> "Идентификатор биома, как в реестре: `plains`, `snowy_plains`."
                "temperature" -> "Температура биома, как отдаёт мир: ниже 0 — холодно."
                "precipitation" -> withValues(root, field, "Осадки биома")
                else -> return null
            }

            WorldRoot.WEATHER -> when (field) {
                "condition" -> withValues(root, field, "Погода")
                else -> return null
            }

            WorldRoot.TIME -> when (field) {
                "period" -> withValues(root, field, "Период суток")
                "tick" -> "Тик времени суток как число: 0 — полдень, `0..24000` — цикл."
                else -> return null
            }

            WorldRoot.DIMENSION -> when (field) {
                "type" -> withValues(root, field, "Тип измерения")
                "id" -> "Идентификатор измерения, как в реестре."
                else -> return null
            }

            WorldRoot.LOCATION -> when (field) {
                "x" -> "Координата X плаща."
                "y" -> "Координата Y плаща."
                "z" -> "Координата Z плаща."
                else -> return null
            }

            WorldRoot.ARMOR -> when (field) {
                "head" -> armorDoc("Шлем")
                "chest" -> armorDoc("Нагрудник")
                "legs" -> armorDoc("Поножи")
                "feet" -> armorDoc("Ботинки")
                else -> return null
            }

            WorldRoot.HEALTH -> when (field) {
                "current" -> "Текущее здоровье: упало после урона, " +
                    "поэтому `health.current: \">10\"` — «более-менее жив»."
                "max" -> "Максимальное здоровье. Не меняется от урона, " +
                    "поэтому подходит для проверки «а сколько у меня вообще»."
                else -> return null
            }

            WorldRoot.STATE -> when (field) {
                "inWater" -> withValues(root, field, "Касается ли воды")
                "sneaking" -> withValues(root, field, "Крадётся ли игрок")
                "sprinting" -> withValues(root, field, "Бежит ли игрок")
                "onGround" -> withValues(root, field, "Стоит ли игрок на земле")
                "pose" -> withValues(root, field, "Поза игрока")
                else -> return null
            }
        }
        return described
    }

    /**
     * Описание тира брони.
     *
     * `armor` — публичный корень: надетую броню видно и другим, поэтому
     * условие по нему считает каждый клиент сам. Слот назван словом, а не
     * полем `head/chest/...`, потому что в подсказке «нагрудник» понятнее.
     */
    private fun armorDoc(caption: String): String = withValues(
        WorldRoot.ARMOR, "chest", "Тир $caption",
    )

    /**
     * Описание с перечислением из [VALUES].
     *
     * Значения не выдумываются: если поля в [VALUES] нет, подпись остаётся
     * голой. Дописывать «например» своими словами нельзя — человек вставит
     * их в конфиг и будет ждать, что условие сработает.
     */
    private fun withValues(root: WorldRoot, field: String, caption: String): String {
        val values = valuesOf(root, field)
        if (values.isEmpty()) return caption
        return values.joinToString(", ", "$caption: ", ".")
    }
}

/**
 * Схема `config/capecraft.kn` целиком.
 *
 * Дерево от корня файла. Верхний уровень — блок `capeCraft`; внутри него
 * `providers`, `limits` и `serverSync`. Ключи вне `capeCraft` в этом файле
 * не имеют смысла и подсвечиваются как неизвестные.
 */
object ConfigSchema {
    /** Имя верхнего блока. */
    const val ROOT = "capeCraft"

    /** Ключ массива провайдеров. */
    const val PROVIDERS = "providers"

    /**
     * Дескрипторы аддонов, о которых знает текущий процесс.
     *
     * В моде сюда попадает то, что зарегистрировали аддоны при загрузке, в
     * редакторе — то, что прочитано из `mods` рядом с конфигом (см.
     * [AddonSchemaFinder]). Один список на оба случая, чтобы подсказки в
     * редакторе и подсказки в `/cp status` не могли разойтись по составу.
     *
     * `@Volatile`, потому что пишет LSP-поток, а читают все: без volatile
     * анализатор мог бы увидеть наполовину обновлённый список ключей.
     */
    @Volatile
    private var addonSchemas: List<AddonSchema> = emptyList()

    /**
     * Словарь типов провайдеров известен полностью?
     *
     * Отличать «папка `mods` нашлась и в ней ни одного аддона» от «папки `mods`
     * нет, сервер ничего не видел» приходится по-разному. В первом случае
     * перечень типов исчерпывающий: `url`, `json`, `file` плюс всё, что
     * принесли аддоны, — и значение не из этого списка конфиг сломает. Во
     * втором случае тот же `seed` может прийти из аддона, чьего дескриптора
     * сервер не видел, и ругать человека нечем.
     *
     * Без этой разницы аддон, который человек вынул из папки, давал одну
     * бледную подсказку вместо ошибки: значок в статусной строке считал
     * проблему, а на самом файле было видно ничего.
     */
    @Volatile
    private var addonVocabularyKnown = false

    /**
     * Заменить список аддонов целиком.
     *
     * [known] — найдена ли папка `mods`. Список и флаг кладутся вместе: по
     * одному флагу проверять нечего, а список без флага не отвечает на
     * вопрос «мы всё видели?».
     */
    fun setAddonSchemas(schemas: List<AddonSchema>, known: Boolean = addonVocabularyKnown) {
        addonSchemas = schemas.toList()
        addonVocabularyKnown = known
    }

    /** Что сейчас известно про аддонов. */
    fun addons(): List<AddonSchema> = addonSchemas

    /** Исчерпывающий ли сейчас список типов провайдеров. */
    fun addonVocabularyKnown(): Boolean = addonVocabularyKnown

    /** Забыть всех аддонов — для тестов и для смены папки `mods`. */
    fun clearAddonSchemas() {
        addonSchemas = emptyList()
        addonVocabularyKnown = false
    }

    /** Аддон по `type` провайдера, или `null`, если тип не аддонный. */
    fun addonForType(type: String?): AddonSchema? {
        if (type == null || Field.isBuiltinProviderType(type)) return null
        return addonSchemas.firstOrNull { it.type(type) != null }
    }

    /** Поле ключа аддон-типа: `gray` у `type = "seed"`, иначе `null`. */
    fun addonField(type: String?, key: String): Field? =
        addonForType(type)?.type(type!!)?.keys?.firstOrNull { it.name == key }

    /** Имена аддонных плейсхолдеров — для подсказок в `{...}` и `if`. */
    fun addonPlaceholders(): List<AddonPlaceholder> =
        addonSchemas.flatMap { it.placeholders }

    /**
     * Ключи провайдера, общие для всех типов, плюс специфичные для `type`.
     *
     * `url` нужен типам `url` и `json` (оба ходят по сети), `path` — только
     * `file`, `extract` — только `json`.
     *
     * Ключи аддона сюда **не** входят намеренно: их набор зависит от
     * значения соседнего `type`, а статический список знает только про
     * встроенные. Аддонные ключи добавляются в [providerItem] через
     * [addonProviderFields], где тип уже известен.
     */
    fun providerFields(): List<Field> = listOf(
        Field(
            name = ProviderNames.Keys.NAME,
            type = SchemaType.STR,
            doc = "Имя провайдера. Идёт только в сообщения и `/cp status`, " +
                "на выбор плаща не влияет.",
            def = "provider-<type>",
        ),
        Field(
            name = ProviderNames.Keys.TYPE,
            type = SchemaType.STR,
            doc = "Как получать плащ: `url` — прямая ссылка, `json` — достать " +
                "ссылку из JSON, `file` — файл в папке игры. Тип от аддона " +
                "тоже подходит.",
            required = true,
            allowed = listOf(ProviderNames.Types.URL, ProviderNames.Types.JSON, ProviderNames.Types.FILE),
            open = true,
        ),
        Field(
            name = ProviderNames.Keys.PRIORITY,
            type = SchemaType.INT,
            doc = "Чем больше, тем раньше проверяется провайдер. Работает и на " +
                "тех, у кого есть условие, и на тех, у кого его нет. При равном " +
                "приоритете — порядок в списке, а провайдеры с условием идут " +
                "перед провайдерами без условия.",
            def = "0",
        ),
        Field(
            name = ProviderNames.Keys.WHEN,
            type = SchemaType.DICT,
            doc = "Условия проверки по миру того, кто смотрит. Пустое условие " +
                "совпадает всегда. Условие с полем `state` делает провайдер " +
                "self-only: такой плащ виден только вам.",
        ),
        Field(
            name = ProviderNames.Keys.SELF,
            type = SchemaType.BOOL,
            doc = "Не отдавать этот провайдер другим игрокам. Плащ остаётся " +
                "вашим, но в сетевом объявлении его не будет. Ставится " +
                "автоматически, если в `when` есть self-only поле: `state`, " +
                "`fire`, `hand`, `food`, `xp`, `effect`, `effect_amplifier` " +
                "или `effect_duration`. Смешивать в одном провайдере публичные " +
                "и self-only условия бессмысленно: половина всё равно не уедет.",
            def = "false",
        ),
        Field(
            name = ProviderNames.Keys.IF,
            type = SchemaType.DICT,
            doc = "Условия по переменным того, кто смотрит: `username`, " +
                "`uuid`, `root`, `\$ИМЯ` и аддонные плейсхолдеры. Операторы " +
                "те же, что в `when`. Считается вместе с `when`: нужны оба.",
        ),
        Field(
            name = ProviderNames.Keys.URL,
            type = SchemaType.STR,
            doc = "Адрес плаща. Внутри понимает `{username}`, `{uuid}` и `{root}`.",
            required = true,
            appliesTo = setOf(ProviderNames.Types.URL, ProviderNames.Types.JSON),
        ),
        Field(
            name = ProviderNames.Keys.PATH,
            type = SchemaType.STR,
            doc = "Файл плаща в папке игры. Внутри понимает `{username}`, " +
                "`{uuid}` и `{root}`.",
            required = true,
            appliesTo = setOf(ProviderNames.Types.FILE),
        ),
        Field(
            name = ProviderNames.Keys.EXTRACT,
            type = SchemaType.STR,
            doc = "Путь к ссылке внутри JSON-ответа, например `$.data.cape_url`.",
            required = true,
            appliesTo = setOf(ProviderNames.Types.JSON),
        ),
    )

    /**
     * Блок `capeCraft.limits` — потолки, выше которых мод начинает ужиматься.
     *
     * Дефолты берутся из [Limits], а не пишутся строкой: если в моде поменяется
     * значение, подсказка в редакторе разойдётся с модом без всякой ошибки
     * компиляции — ровно тот случай, ради которого схема и вынесена отдельно.
     */
    private fun limits(): List<Field> {
        val d = Limits()
        return listOf(
            Field(
                name = "maxPixelsPerFrame",
                type = SchemaType.INT,
                doc = "Пикселей в одном кадре (ширина * высота). Дальше кадр " +
                    "сжимается, а не отбрасывается.",
                def = d.maxPixelsPerFrame.toString(),
            ),
            Field(
                name = "maxFrames",
                type = SchemaType.INT,
                doc = "Максимум кадров в анимации. Дальше — пропуск кадров.",
                def = d.maxFrames.toString(),
            ),
            Field(
                name = "maxBytesPerCape",
                type = SchemaType.INT,
                doc = "Байт под пиксели одного плаща, все кадры вместе.",
                def = d.maxBytesPerCape.toString(),
            ),
            Field(
                name = "maxBytesTotal",
                type = SchemaType.INT,
                doc = "Суммарно байт под все плащи в кэше.",
                def = d.maxBytesTotal.toString(),
            ),
        )
    }

    /**
     * Блок `capeCraft.serverSync` — синхронизация набора с сервером.
     *
     * Снизу лежат ключи из v1, которые больше не читаются: в v2 опроса нет,
     * клиент объявляет набор один раз. Раньше они молча игнорировались,
     * поэтому человек мог полгода носить настройку, которая ничего не делает.
     * Теперь у них есть [Field.deprecated], и редактор скажет об этом прямо.
     */
    private fun serverSync(): List<Field> {
        val d = ServerSyncSettings()
        return listOf(
            Field(
                name = "enabled",
                type = SchemaType.BOOL,
                doc = "Объявлять свой набор и принимать чужой.",
                def = d.enabled.toString(),
            ),
            Field(
                name = "shareLocalProviders",
                type = SchemaType.BOOL,
                doc = "Отдавать другим свои локальные `file`-плащи. Выключено " +
                    "намеренно: байты уезжают на сервер и оттуда ко всем.",
                def = d.shareLocalProviders.toString(),
            ),
            Field(
                name = "allowForeignUrls",
                type = SchemaType.BOOL,
                doc = "Принимать плащи по чужим `http`-адресам.",
                def = d.allowForeignUrls.toString(),
            ),
            Field(
                name = "backoff",
                type = SchemaType.BOOL,
                doc = "Замедлять запросы картинок, пока нет ответа. К докачке " +
                    "картинок, а не к опросу сервера.",
                def = d.backoff.toString(),
            ),
            Field(
                name = "intervalTicks",
                type = SchemaType.INT,
                doc = "Ключ из v1.",
                deprecated = "В v2 опроса нет: клиент объявляет набор один раз, " +
                    "обновления приходят рассылкой. Ключ не читается.",
            ),
            Field(
                name = "timeoutTicks",
                type = SchemaType.INT,
                doc = "Ключ из v1.",
                deprecated = "В v2 опроса нет. Ключ не читается.",
            ),
            Field(
                name = "requireServer",
                type = SchemaType.BOOL,
                doc = "Ключ из v1.",
                deprecated = "Сервер больше не диктует, что носить, а только " +
                    "объясняет, у какого объекта какой набор. Ключ не читается.",
            ),
            Field(
                name = "allowFileProviders",
                type = SchemaType.BOOL,
                doc = "Ключ из v1.",
                deprecated = "Замёнён на `shareLocalProviders`. Ключ не читается.",
            ),
        )
    }

    /** Дерево от корня файла. */
    val root: Field = Field(
        name = ROOT,
        type = SchemaType.BLOCK,
        doc = "Весь конфиг CapeCraft.",
        children = listOf(
            Field(
                name = PROVIDERS,
                type = SchemaType.ARRAY,
                doc = "Провайдеры плащей. Проверяются по порядку, первый " +
                    "успешный отдаёт плащ; остальные — резерв.",
            ),
            Field(
                name = "limits",
                type = SchemaType.BLOCK,
                doc = "Потолки нагрузки.",
                children = limits(),
            ),
            Field(
                name = "serverSync",
                type = SchemaType.BLOCK,
                doc = "Синхронизация набора с сервером.",
                children = serverSync(),
            ),
        ),
    )

    /**
     * Найти поле по пути сегментов, начиная с корня схемы.
     *
     * Путь может начинаться с `capeCraft` или не начинаться — оба варианта
     * означают одно и то же, потому что корень в схеме ровно один. Без этой
     * поправки путь `capeCraft.limits` искал бы `capeCraft` как **ребёнка**
     * `capeCraft` и всегда возвращал `null`.
     *
     * Возвращает `null`, если на каком-то шаге ключа нет. Для подсказок этого
     * достаточно: неизвестный путь — значит на этом уровне ключей нет вовсе.
     */
    fun resolve(path: List<String>): Field? {
        val rest = if (path.firstOrNull() == root.name) path.drop(1) else path
        var cur: Field = root
        for (seg in rest) {
            cur = cur.children.firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    /**
     * Найти подходящего родителя для ключа, который пользователь уже напечатал.
     *
     * Нужен для диагностики «вы похожи на это»: путь может быть неверным
     * только в последнем сегменте (`serverSync.enabeled` — опечатка), а без
     * этого родительский путь найти нечем.
     */
    fun resolveParent(path: List<String>): Pair<Field, Field>? {
        val parent = resolve(path.dropLast(1)) ?: return null
        val key = path.lastOrNull() ?: return null
        val child = parent.children.firstOrNull { it.name == key }
        return if (child != null) parent to child else null
    }

    /**
     * Поле одного элемента массива `providers`.
     *
     * Отдельное поле нужно, потому что `providers` — массив, а ключи лежат
     * внутри каждого его элемента. Без этого шага спуск по пути
     * `providers[0]` упирался бы в `providers` и не нашёл бы `url`.
     */
    /**
     * Ключи аддон-типа [type], объявленные дескриптором.
     *
     * Ключ помечен [Field.appliesTo] ровно одним своим типом, поэтому
     * [Field.offeredFor] отдаёт его только когда `type` совпал, и не выдаёт
     * чужим типам. Дефолт [Field.appliesTo] — пустой, то есть «показывай
     * всегда»: без него подсказка появлялась бы у всех провайдеров подряд.
     */
    fun addonProviderFields(type: String): List<Field> =
        addonForType(type)?.type(type)?.keys.orEmpty()

    /**
     * Ключи провайдера: встроенные плюс аддонные для всех известных типов.
     *
     * Ключи с одинаковым именем **сливаются**, а не дописываются вторым
     * элементом. `url` и `path` есть и во встроенных типах, и в аддонном
     * `image`; если бы они остались двумя записями, [childrenOf] вернул бы
     * первую, и проверка `appliesTo` ругалась бы на совершенно правильный
     * `url` внутри `type = "image"`. Слиянием один `url` получает и
     * `url`,`json`, и `image` в своём [Field.appliesTo] — то есть
     * показывается у всех трёх и не показывается у остальных.
     */
    fun allProviderFields(): List<Field> {
        val merged = LinkedHashMap<String, Field>()
        for (f in providerFields()) merged[f.name] = f
        for (schema in addons()) {
            for (type in schema.types) {
                for (key in type.keys) {
                    val existing = merged[key.name]
                    merged[key.name] = if (existing == null) {
                        key.copy(appliesTo = setOf(type.id))
                    } else if (existing.appliesTo.isEmpty()) {
                        // Встроенный ключ без appliesTo общий для всех типов;
                        // ограничивать его аддонным типом нельзя — у встроенного
                        // типа он нужен в любом случае, а вот показать его
                        // аддонному типу, который его не читает, нельзя.
                        existing
                    } else {
                        // Обязательность при добавлении типа переносится в
                        // requiredFor, иначе `path`, обязательный у `file`,
                        // стал бы обязательным и у `image`, где хватает `url`.
                        val moved = if (existing.required && type.id !in existing.appliesTo) {
                            existing.requiredFor + existing.appliesTo
                        } else {
                            existing.requiredFor
                        }
                        existing.copy(
                            appliesTo = existing.appliesTo + type.id,
                            required = false,
                            requiredFor = moved,
                        )
                    }
                }
            }
        }
        return merged.values.toList()
    }

    /**
 * Поле одного элемента `providers` со всеми ключами, какие сейчас известны.
 *
 * Именно вычисляемое свойство, а не `val`: ключи аддонов приезжают позже, чем
 * класс уже загрузился (дескрипторы читаются при открытии файла), и
 * `val`-поле навсегда осталось бы со встроенными ключами. Тогда `gray` был бы
 * «неизвестным ключом», а `url` внутри `type = "image"` — «лишним».
 *
 * Пересчёт на каждый вызов дешёвый: список из десятка полей, а [arrayItem]
 * зовётся на каждый узел дерева при разборе файла.
 */
val providerItem: Field
    get() = Field(
        name = "$PROVIDERS[]",
        type = SchemaType.DICT,
        doc = "Один провайдер из списка.",
        children = allProviderFields(),
    )

    /**
     * Тип одного элемента массива, если его содержимое описано.
     *
     * Сравнивается имя **массива** с [PROVIDERS], а не с именем
     * [providerItem]: элемент называется `providers[]`, и сравнение с ним
     * не совпало бы никогда — спуск по массиву молча переставал бы работать,
     * и все ключи провайдера выглядели бы как «уровень не описан».
     */
    fun arrayItem(field: Field): Field? =
        if (field.type == SchemaType.ARRAY && field.name == PROVIDERS) providerItem else null

    /**
     * Поле по пути, спускаясь сквозь массивы.
     *
     * Отличие от [resolve] — в том, что индекс массива не мешает: сегмент
     * `providers[0]` проходит как `providers`, `0`. [resolve] на этом
     * останавливается, потому что он не знает про [providerItem], а здесь
     * знает, и им пользуется анализатор редактора.
     */
    fun fieldAt(path: List<String>): Field? {
        val rest = if (path.firstOrNull() == root.name) path.drop(1) else path
        var cur: Field = root
        for (seg in rest) {
            cur = if (cur.type == SchemaType.ARRAY) {
                arrayItem(cur) ?: return null
            } else {
                cur.children.firstOrNull { it.name == seg } ?: return null
            }
        }
        return cur
    }

    /**
     * Это поле — один провайдер из `providers`?
     *
     * Отдельная проверка вместо сравнения с [providerItem]: поле собирается
     * заново на каждый вызов, а анализатор спрашивает про каждый ключ каждого
     * провайдера.
     */
    fun isProviderItem(field: Field?): Boolean = field?.name == "$PROVIDERS[]"

    /**
     * Ключи, которые можно написать по этому пути.
     *
     * Путь — это **контейнер**, а не ключ: чтобы узнать, что писать внутри
     * `limits`, просят `childrenOf(listOf("limits"))`. Для элемента массива
     * индекс не важен: `childrenOf(listOf("providers", "0"))` вернёт ключи
     * провайдера, как и `childrenOf(listOf("providers", "7"))`.
     *
     * Пустой список — это «на этом уровне ключей нет», а не «ошибка»:
     * именно так выглядит неизвестный раздел, и подсказки там должны быть
     * пустыми, а не выдуманными.
     */
    fun childrenOf(path: List<String>): List<Field> = fieldAt(path)?.children.orEmpty()

    /** Ключи, похожие на [name] по префиксу и по расстоянию редактирования. */
    fun similarTo(name: String, candidates: List<String>): List<String> {
        val exact = candidates.filter { it.equals(name, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return candidates
            .map { it to editDistance(name.lowercase(), it.lowercase()) }
            .filter { it.second <= maxOf(1, name.length / 2) }
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Расстояние Левенштейна, без аллокаций промежуточных строк. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }
}
