package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import java.net.URI

/**
 * Один Cape-провайдер в том виде, в каком он едет по сети в Sync v2.
 *
 * Это НЕ [Provider] из конфига: здесь нет addon-объекта
 * [dev.ggtv.capecraft.api.provider.CapeSource] (у клиента он свой, см.
 * [toProvider]) и нет локального пути для [Kind.FILE] — вместо пути едет
 * [imageHash].
 *
 * ## Направление
 *
 * В v1 этот класс описывал то, что **сервер** прислал клиенту. В v2 он
 * описывает то, что **клиент** объявил серверу, и то, что сервер разослал
 * остальным. Поля те же, но смысл `primary` для [Kind.FILE] изменился с
 * «путь на диске» на «пусто» — см. [validate].
 */
data class ActiveCape(
    val name: String,
    val kind: Kind,
    /** url-шаблон / url для json / имя аддон-типа. Для [Kind.FILE] — пусто. */
    val primary: String,
    /** JSONPath-инструкция для [Kind.JSON], иначе пусто. */
    val extract: String = "",
    val priority: Int = 0,
    /** Условие `when`, вычисляет его получатель против своего контекста. */
    val condition: WireCondition? = null,
    /** Хэш картинки — только для [Kind.FILE]. */
    val imageHash: ImageHash? = null,
) {
    /** Вид провайдера. Значения совпадают с тегами в [SyncCodec]. */
    enum class Kind(val tag: Int) {
        URL(0),
        FILE(1),
        JSON(2),
        ADDON(3),
        ;

        companion object {
            fun byTag(tag: Int): Kind? = entries.firstOrNull { it.tag == tag }
        }
    }

    /**
     * Проверить поле до отправки/после приёма: длины, обязательность полей,
     * безопасную схему URL.
     *
     * Возвращает список проблем (пустой = всё в порядке) — вызывающий
     * решает, логировать это или отбросить пакет целиком.
     *
     * ## Правила [Kind.FILE] в v2
     *
     * Здесь `primary` обязан быть **пустым**, а [imageHash] — присутствовать.
     * Это не формальность, а граница приватности: локальный путь владельца
     * не уезжает никогда (кому он на другом диске?), а получатель узнаёт
     * картинку только по хэшу и скачивает её байты у того, кто её залил.
     * Провайдер `file` без хэша отбрасывается: показать его нечем, и молча
     * пропустить его — значит оставить игрока без плаща без объяснения.
     */
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (name.isEmpty()) out += "пустое имя провайдера"
        if (utf8Len(name) > SyncProtocol.MAX_NAME_BYTES) {
            out += "имя «${truncate(name)}» длиннее ${SyncProtocol.MAX_NAME_BYTES} байт"
        }
        if (utf8Len(primary) > SyncProtocol.MAX_STRING_BYTES) {
            out += "параметр провайдера «$name» длиннее ${SyncProtocol.MAX_STRING_BYTES} байт"
        }
        if (utf8Len(extract) > SyncProtocol.MAX_STRING_BYTES) {
            out += "extract провайдера «$name» длиннее ${SyncProtocol.MAX_STRING_BYTES} байт"
        }
        when (kind) {
            Kind.URL -> {
                if (primary.isEmpty()) out += "у url-провайдера «$name» пустой параметр"
                else if (!isHttpUrl(primary)) {
                    out += "у url-провайдера «$name» недопустимая ссылка «${truncate(primary)}»"
                }
                if (imageHash != null) out += "у url-провайдера «$name» не должно быть хэша картинки"
            }

            Kind.JSON -> {
                if (primary.isEmpty()) out += "у json-провайдера «$name» пустой параметр"
                else if (!isHttpUrl(primary)) {
                    out += "у json-провайдера «$name» недопустимая ссылка «${truncate(primary)}»"
                }
                if (extract.isEmpty()) {
                    out += "у json-провайдера «$name» пустой extract"
                }
                if (imageHash != null) out += "у json-провайдера «$name» не должно быть хэша картинки"
            }

            Kind.FILE -> {
                if (primary.isNotEmpty()) {
                    out += "у file-провайдера «$name» не должно быть пути на проводе"
                }
                if (imageHash == null) {
                    out += "у file-провайдера «$name» нет хэша картинки"
                }
            }

            Kind.ADDON -> {
                if (primary.isEmpty()) out += "у аддон-провайдера «$name» пустой тип"
                if (imageHash != null) out += "у аддон-провайдера «$name» не должно быть хэша картинки"
            }
        }
        condition?.let { out += it.validate().map { p -> "у провайдера «$name»: $p" } }
        return out
    }

    /** Восстановить провайдера НЕЛЬЗЯ без локального конфига — см. [SyncPolicy]. */
    companion object {
        /**
         * Плейсхолдер в шаблоне: `{username}`, `{uuid}`, `{name}`, `{root}` и любые
         * аддон-плейсхолдеры. Ровно та же форма, которую понимает
         * [dev.ggtv.capecraft.schema.Placeholders.render].
         */
        private val PLACEHOLDER = Regex("\\{[^{}]*\\}")

        /**
         * Только http/https — иначе клиент по ссылке сервера прочитает что угодно.
         *
         * Проверяется именно ШАБЛОН, а не готовая ссылка: плейсхолдеры
         * подставляются только при загрузке ([dev.ggtv.capecraft.schema.Placeholders]),
         * а `URI.create` фигурные скобки не принимает («Illegal character in path»).
         * Поэтому перед разбором каждый `{...}` заменяется безопасным токеном —
         * для схемы и хоста это ничего не меняет, а шаблоны перестают отбрасываться.
         *
         * Реальная ссылка проверяется ещё раз при запросе:
         * `HttpFetcher.getBytes` зовёт `URI.create` уже на отрендеренном URL.
         */
        fun isHttpUrl(value: String): Boolean = try {
            val uri = URI.create(PLACEHOLDER.replace(value, "x"))
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }

        fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

        private fun truncate(s: String, limit: Int = 48): String =
            if (s.length <= limit) s else s.take(limit) + "…"
    }
}

/**
 * Собрать проводное описание провайдера из [Provider].
 *
 * Звонит **клиент**: в v2 он объявляет свой набор, а не получает чужой.
 *
 * Условие `when` переносится ([WireCondition.from]) — получатель вычислит его
 * против своего контекста наблюдаемого игрока. Локальный путь [Source.File] на
 * провод **не уходит**: вместо него [fileHash], который клиент посчитал,
 * прочитав файл. Если хэша нет (файл не нашёлся/не прочитался), возвращается
 * `null` — объявлять `file`-провайдер без хэша бессмысленно, его бы отбросили
 * при первом же разборе.
 *
 * Аддон-тип переносится именем типа: параметры аддона у получателя свои.
 */
fun Provider.toActiveCape(fileHash: ImageHash? = null): ActiveCape? = when {
    addonSource != null -> values?.let {
        ActiveCape(
            name = name,
            kind = ActiveCape.Kind.ADDON,
            primary = it.type,
            priority = priority,
            condition = WireCondition.from(condition),
        )
    }

    else -> when (val s = source) {
        is Source.Url -> ActiveCape(
            name = name,
            kind = ActiveCape.Kind.URL,
            primary = s.template,
            priority = priority,
            condition = WireCondition.from(condition),
        )

        is Source.Json -> ActiveCape(
            name = name,
            kind = ActiveCape.Kind.JSON,
            primary = s.template,
            extract = s.extract,
            priority = priority,
            condition = WireCondition.from(condition),
        )

        is Source.File -> if (fileHash == null) {
            null
        } else {
            ActiveCape(
                name = name,
                kind = ActiveCape.Kind.FILE,
                primary = "",
                priority = priority,
                condition = WireCondition.from(condition),
                imageHash = fileHash,
            )
        }

        // Сетевая картинка уже привязана к хэшу — заново объявлять её как
        // «свою функцию» незачем: наружу уйдёт тот же хэш, что уже в кэше.
        is Source.NetImage -> null
    }
}
