package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.condition.VarCondition
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues

/**
 * Виды результата, который умеет отдавать провайдер.
 *
 * (пар. Этапа 4 — «виды результата: прямая ссылка, локальный файл/директория,
 * JSON-схема»). Шаблоны с плейсхолдерами (`{username}`, ...) подставляются
 * в [Provider.resolve], давая конкретный [Resolved].
 */
sealed interface Source {
    /** Прямая ссылка на картинку: `type = url`. */
    data class Url(val template: String) : Source

    /** Локальный файл/диретория: `type = file`. */
    data class File(val template: String) : Source

    /** Извлечь URL из вложенного JSON по path-инструкции: `type = json`. */
    data class Json(val template: String, val extract: String) : Source

    /**
     * Картинка по хэшу содержимого, пришедшая из сети Sync v2.
     *
     * Отдельный вид от [File] принципиально: у [File] есть путь на **моём**
     * диске, а у чужого `file`-провайдера пути нет вообще — на провод он не
     * уезжает. Есть только хэш, по которому байты лежат в кэше картинок,
     * накопленном из кусков, присланных владельцем. Смешивать их в один вид
     * значило бы либо выдумать фиктивный путь, либо таскать [ImageHash] через
     * шаблоны плейсхолдеров — и то и другое враньё.
     *
     * В конфиге `.kn` **невозможен**: парсер такого вида не производит, он
     * появляется только при разборе чужого объявления из роустера.
     */
    data class NetImage(val hash: String) : Source
}

/**
 * Провайдер плаща — один элемент списка `capeCraft.providers[]`.
 *
 * Встроенные провайдеры (url/file/json) хранят шаблон в [source].
 * Аддон-провайдеры (зарегистрированные через addon-API) хранят [addonSource]:
 * резолвер и фетчер определены аддоном, а конкретные параметры — в [values].
 *
 * [condition] — необязательное условие выбора (`when { ... }` в `.kn`):
 * провайдер становится активным только когда условие выполняется на живом
 * мире. Без условия провайдер активен всегда (default). [priority] задаёт
 * приоритет среди совпадших провайдеров: выше — ближе к началу fallback-цепи.
 *
 * [ifCondition] — необязательное условие `if { ... }`: те же операторы, но
 * сравниваются переменные (плейсхолдеры и `$ИМЯ`), а не поля мира. Оба
 * условия независимы и соединяются по И: `when` решает «где он стоит»,
 * `if` — «про что этот игрок».
 *
 * @param condition условие, при котором провайдер активен (null = всегда).
 * @param ifCondition условие по переменным (null = не проверять).
 * @param priority приоритет выбора — по умолчанию 0 (порядок в списке).
 * @param selfOnly `self = true`: не объявлять провайдер по сети.
 * @param addonSource non-null для аддон-провайдера (fetch определён аддоном).
 * @param values параметры из словаря .kn для аддон-провайдера (null для встроенного).
 */
class Provider(
    val name: String,
    val source: Source,
    val condition: Condition? = null,
    val ifCondition: VarCondition? = null,
    val priority: Int = 0,
    val selfOnly: Boolean = false,
    val addonSource: CapeSource? = null,
    val values: CapeValues? = null,
) {

    /** Есть ли хоть одно условие: без них провайдер работает всегда. */
    val hasConditions: Boolean
        get() = condition != null || ifCondition != null || selfOnly

    /**
     * Провайдер не объявляется по сети: его плащ видно только себе.
     *
     * Ставится автоматически, если в `when` есть self-only условие
     * ([Condition.hasSelfOnly]) — такие поля про другого игрока неизвестны,
     * отправлять их нечего. Плюс ключ `self = true` в конфиге, которым
     * помечается провайдер с публичными условиями: он остаётся в списке
     * владельца, но никому больше не объявляется.
     *
     * [hasConditions] учитывает и его: провайдер с `self = true` без
     * единого условия иначе попал бы в группу «без условий» наравне с
     * обычными и выигрывал бы только за счёт приоритета.
     */
    val isSelfOnly: Boolean get() = selfOnly || condition?.hasSelfOnly == true


    /**
     * Подставить плейсхолдеры и получить конкретный источник.
     * Чистая функция — без I/O, тестируется без сети.
     */
    fun resolve(ctx: Placeholders.Context, root: String): Resolved {
        val c = ctx.copy(root = root)
        if (addonSource != null) {
            val rendered = Placeholders.render("addon://${name}", c)
            return Resolved.Addon(addonSource, values ?: CapeValues(name, "addon", emptyMap()), rendered)
        }
        return when (val s = source) {
            is Source.Url -> Resolved.Url(Placeholders.render(s.template, c))
            is Source.File -> Resolved.File(Placeholders.render(s.template, c))
            is Source.Json -> Resolved.Json(
                Placeholders.render(s.template, c),
                s.extract,
            )
            // Хэш не шаблонизируется: он уже конкретный идентификатор,
            // подставлять в него нечего.
            is Source.NetImage -> Resolved.NetImage(s.hash)
        }
    }
}

/** Конкретный источник после подстановки плейсхолдеров. */
sealed interface Resolved {
    data class Url(val url: String) : Resolved
    data class File(val path: String) : Resolved
    data class Json(val url: String, val extract: String) : Resolved

    /**
     * Картинка из кэша Sync v2 по хэшу.
     *
     * Отдельный вид от [File] по той же причине, что и [Source.NetImage]:
     * пути нет, есть хэш, а байты лежат в накопленном кэше.
     */
    data class NetImage(val hash: String) : Resolved
    /** Аддон-провайдер: fetch определён аддоном через [source]. */
    data class Addon(val source: CapeSource, val values: CapeValues, val debugUrl: String) : Resolved
}
