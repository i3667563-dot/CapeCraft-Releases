package dev.ggtv.capecraft.schema

import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig

/**
 * Описание, что аддон умеет: свои типы провайдеров, ключи каждого типа и
 * имена плейсхолдеров.
 *
 * ## Зачем это отдельный файл
 *
 * Редактор запускает LSP отдельным процессом (см. `lsp/Main.kt`), а мод — в
 * JVM игры. В момент разбора `.kn` в редакторе рядом не запущен ни один мод,
 * поэтому всё, что схема знает про аддоны, должно приехать из данных на
 * диске. Данные — этот дескриптор.
 *
 * ## Почему формат `.kn`, а не JSON
 *
 * У LSP-сервера ровно три зависимости: kotlin-stdlib и slf4j
 * (`build.gradle`, конфигурация `lspRuntime`). JSON-парсер туда не влезает без
 * новой зависимости, а `.kn` уже разбирается готовым [KorenConfig]. Один
 * формат на конфиг и на описание аддона — значит, его показывает и редактор,
 * и мод, и никакая третья сторона не должна знать, что для описания есть
 * отдельный синтаксис.
 *
 * ## Формат
 *
 * ```
 * addon {
 *     id = "capecraft-seed"
 *     version = "1.0"
 *     apiVersion = 1
 *
 *     types [
 *         { id = "seed", doc = "Плащ, который рисуется по UUID игрока.",
 *           keys [
 *               { name = "gray", type = "bool", doc = "Отдать в оттенках серого." },
 *               { name = "elytra", type = "bool", def = "true" },
 *           ] },
 *         { id = "image", doc = "Картинка из `url` или `path`.",
 *           keys [ { name = "gray", type = "bool" } ] },
 *     ]
 *
 *     placeholders [
 *         { name = "seedHash", type = "str", doc = "Число, из которого вырос плащ." },
 *     ]
 * }
 * ```
 *
 * Обязателен только [id] — без него не сказать, чей это тип. Остальные списки
 * можно не писать вовсе: аддон, который только добавляет плейсхолдеры, не
 * объявляет ни одного типа, и это нормально, а не ошибка.
 *
 * ## Правила записи
 *
 * Формат тот же, что у обычного конфига, и это не совпадение: тот же
 * [KorenConfig] и тот же разбор. Отсюда две особенности, о которые спотыкается
 * любой, кто пишет дескриптор первый раз:
 *
 * - пары внутри `{ }` разделяются запятой, а перевод строки разделителем не
 *   считается — в том числе когда следующая пара стоит на следующей строке;
 * - в блоке верхнего уровня (`addon { … }`) записи разделяются переводом
 *   строки, и запятая после последнего значения блока не нужна, но и лишняя
 *   запятая внутри блока не прощается.
 *
 * Имя типа ключа регистронезависимо: и `bool`, и `BOOL` означают одно.
 */
data class AddonSchema(
    /** Идентификатор аддона, как в `fabric.mod.json`. */
    val id: String,
    /** Версия аддона строкой; в подсказках показывается как есть. */
    val version: String,
    /** Версия рантайм-API, под которую написан дескриптор. */
    val apiVersion: Int,
    /** Типы провайдеров по имени типа. */
    val types: List<AddonProviderType>,
    /** Имена плейсхолдеров, которые аддон резолвит в `{...}` и `if`. */
    val placeholders: List<AddonPlaceholder>,
    /** Корни `when`, которые аддон регистрирует в рантайме. */
    val conditions: List<AddonWhenRoot> = emptyList(),
    /** Откуда прочитали: путь к jar'у — попадает в сообщение об ошибке. */
    val origin: String,
) {
    /** Тип провайдера по имени, или `null`, если аддон такого не объявил. */
    fun type(id: String): AddonProviderType? = types.firstOrNull { it.id == id }

    /** Корень `when` по имени сегмента, или `null`. */
    fun condition(id: String): AddonWhenRoot? = conditions.firstOrNull { it.id == id }
}

/**
 * Корень `when`, объявленный аддоном: `fire.burning`.
 *
 * Описание, а не значение: в моде здесь [dev.ggtv.capecraft.api.condition.CapeWhenRoot]
 * с живым читателем, а в редакторе — только эта карточка из дескриптора.
 */
data class AddonWhenRoot(
    val id: String,
    val doc: String,
    /** Поле, которое подставляется, если в конфиге написали корень без точки. */
    val defaultField: String?,
    val fields: List<AddonWhenField>,
)

/**
 * Поле аддонного корня `when`.
 *
 * [values] и [numeric] повторяют [dev.ggtv.capecraft.api.condition.CapeWhenField]
 * один в один, а не «тип» вида `bool`: список значений пишет сам аддон, и
 * придумывать его тут — значит выдумывать значения, которых рантайм не отдаст.
 */
data class AddonWhenField(
    val name: String,
    val doc: String,
    val values: List<String>,
    val numeric: Boolean,
)

/** Один тип провайдера от аддона: `type = "..."` в конфиге. */
data class AddonProviderType(
    val id: String,
    val doc: String,
    /** Ключи, которые этот тип понимает. */
    val keys: List<Field>,
)

/** Плейсхолдер аддона: `{имя}` в значениях и `имя` в блоке `if`. */
data class AddonPlaceholder(
    val name: String,
    val type: SchemaType,
    val doc: String,
)

/**
 * Разбор дескриптора аддона.
 *
 * Разбор строгий, но не валит файл целиком: одна негодная запись не должна
 * отключать подсказки для остальных ключей. Каждая негодная запись
 * попадает в [warnings], а сам дескриптор возвращается — что получилось.
 * Молча проглотить опечатку нельзя: в редакторе не будет ни ошибки, ни
 * подсказки, и выглядит это как «аддон ничего не объявил».
 */
object AddonSchemaParser {

    /** Имя блока верхнего уровня в дескрипторе. */
    const val ROOT = "addon"

    /** Максимум записок, о которых стоит сказать: список ради списка. */
    private const val MAX_WARNINGS = 5

    /**
     * Прочитать дескриптор из строки.
     *
     * @param origin откуда строка — для сообщений об ошибке
     */
    fun parse(text: String, origin: String): AddonSchema? {
        val cfg = try {
            KorenConfig.fromString(text)
        } catch (e: Exception) {
            // Причина обязана попасть в лог: иначе аддон, у которого опечатка в
            // дескрипторе, выглядит как аддон без подсказок, и непонятно куда
            // смотреть. Строка с местом ошибки — единственное, что тут есть.
            LspLog.warn("$origin: дескриптор не разобран: ${e.message}")
            return null
        }

        val warnings = mutableListOf<String>()
        val id = cfg.getStrOrNull("$ROOT.id")
        if (id.isNullOrBlank()) {
            warnings += "нет `id`"
            return null
        }
        val version = cfg.getStrOrNull("$ROOT.version").orEmpty()
        val apiVersion = cfg.getIntOrNull("$ROOT.apiVersion")?.toInt() ?: 1

        val types = cfg.getArrayOrNull("$ROOT.types").orEmpty().mapNotNull { v ->
            val dict = v as? Value.VDict
            if (dict == null) {
                warnings += "запись в `types` — не словарь `{}`"
                return@mapNotNull null
            }
            val typeId = dict.str("id")
            if (typeId.isNullOrBlank()) {
                warnings += "тип без `id`"
                return@mapNotNull null
            }
            val doc = dict.str("doc").orEmpty()
            val keys = (dict.array("keys")).mapNotNull { kv ->
                val kd = kv as? Value.VDict
                if (kd == null) {
                    warnings += "ключ типа `$typeId` — не словарь `{}`"
                    return@mapNotNull null
                }
                val name = kd.str("name")
                if (name.isNullOrBlank()) {
                    warnings += "ключ типа `$typeId` без `name`"
                    return@mapNotNull null
                }
                val word = kd.str("type").orEmpty()
                val keyType = parseType(word)
                if (keyType == null) {
                    warnings += "ключ `$name` типа `$typeId`: неизвестный тип `$word`"
                    return@mapNotNull null
                }
                Field(
                    name = name,
                    type = keyType,
                    doc = kd.str("doc").orEmpty(),
                    def = kd.str("def"),
                )
            }
            AddonProviderType(typeId, doc, keys)
        }

        val placeholders = cfg.getArrayOrNull("$ROOT.placeholders").orEmpty().mapNotNull { v ->
            val pd = v as? Value.VDict
            if (pd == null) {
                warnings += "запись в `placeholders` — не словарь `{}`"
                return@mapNotNull null
            }
            val name = pd.str("name")
            if (name.isNullOrBlank()) {
                warnings += "плейсхолдер без `name`"
                return@mapNotNull null
            }
            val word = pd.str("type").orEmpty()
            val phType = parseType(word) ?: SchemaType.STR
            AddonPlaceholder(name, phType, pd.str("doc").orEmpty())
        }

        val conditions = cfg.getArrayOrNull("$ROOT.conditions").orEmpty().mapNotNull { v ->
            val dict = v as? Value.VDict
            if (dict == null) {
                warnings += "запись в `conditions` — не словарь `{}`"
                return@mapNotNull null
            }
            val rootId = dict.str("id")
            if (rootId.isNullOrBlank()) {
                warnings += "корень `when` без `id`"
                return@mapNotNull null
            }
            // Точка в имени означала бы второй сегмент, а разбор ключа делит
            // строку по первой точке: корень `fire.temp` и поле `burning`
            // разъехались бы на `fire.temp` + пустое поле, и условие не
            // совпало бы никогда — молча и навсегда.
            if (rootId.contains('.')) {
                warnings += "корень `when` «$rootId»: в имени не может быть точки"
                return@mapNotNull null
            }
            val fields = dict.array("fields").mapNotNull { fv ->
                val fd = fv as? Value.VDict
                if (fd == null) {
                    warnings += "поле корня `when` «$rootId» — не словарь `{}`"
                    return@mapNotNull null
                }
                val fname = fd.str("name")
                if (fname.isNullOrBlank()) {
                    warnings += "поле корня `when` «$rootId» без `name`"
                    return@mapNotNull null
                }
                if (fname.contains('.')) {
                    warnings += "поле «$fname» корня `when` «$rootId»: в имени не может быть точки"
                    return@mapNotNull null
                }
                AddonWhenField(
                    name = fname,
                    doc = fd.str("doc").orEmpty(),
                    values = fd.array("values").mapNotNull { (it as? Value.VStr)?.s },
                    numeric = fd.bool("numeric"),
                )
            }
            if (fields.isEmpty()) {
                warnings += "у корня `when` «$rootId» нет ни одного поля"
                return@mapNotNull null
            }
            val def = dict.str("defaultField")
            if (def != null && fields.none { it.name == def }) {
                warnings += "у корня `when` «$rootId» поле по умолчанию «$def» не объявлено"
                return@mapNotNull null
            }
            AddonWhenRoot(rootId, dict.str("doc").orEmpty(), def, fields)
        }

        if (warnings.isNotEmpty()) {
            warn(origin, warnings.take(MAX_WARNINGS))
        }
        return AddonSchema(id, version, apiVersion, types, placeholders, conditions, origin)
    }

    /**
 * Имя типа в дескрипторе -> [SchemaType].
 *
 * Сравнение регистронезависимое, и это не прихоть: `SchemaType` объявлен
 * прописными (`BOOL`, `STR`), а в дескрипторе `bool`/`str` читается
 * естественнее, и такие же строчные имена уже используются в конфиге.
 * Строгое `==` отбросило бы ключи с типичной ошибкой в регистре, а дескриптор
 * аддона молча потерял бы половину подсказок.
 */
private fun parseType(word: String): SchemaType? {
    if (word.isBlank()) return null
    return SchemaType.entries.firstOrNull { it.name.equals(word.trim(), ignoreCase = true) }
}

/** Сообщение о непонятных записях дескриптора: тихо это не оставить. */
    private fun warn(origin: String, warnings: List<String>) {
        LspLog.warn("$origin: в дескрипторе аддона ${warnings.joinToString("; ")}")
    }

    // Мягкие чтения: отсутствующий ключ — это отсутствие, а не ошибка типа.
    // Строгие getStr бросают, и один негодный ключ уронил бы весь дескриптор.

    private fun KorenConfig.getStrOrNull(path: String): String? = try {
        getStr(path)
    } catch (e: Exception) {
        null
    }

    private fun KorenConfig.getIntOrNull(path: String): Long? = try {
        getInt(path)
    } catch (e: Exception) {
        null
    }

    private fun KorenConfig.getArrayOrNull(path: String): List<Value>? = try {
        getArray(path)
    } catch (e: Exception) {
        null
    }

    private fun Value.VDict.str(key: String): String? = pairs.firstOrNull { it.first == key }
        ?.let { (it.second as? Value.VStr)?.s }

    private fun Value.VDict.array(key: String): List<Value> =
        pairs.firstOrNull { it.first == key }?.let { (it.second as? Value.VArray)?.items }
            .orEmpty()

    private fun Value.VDict.bool(key: String): Boolean =
        pairs.firstOrNull { it.first == key }?.let { (it.second as? Value.VBool)?.b } ?: false
}