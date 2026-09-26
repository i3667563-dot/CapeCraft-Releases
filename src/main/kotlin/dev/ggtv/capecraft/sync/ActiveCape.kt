package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import java.net.URI

/**
 * Один активный Cape-провайдер в том виде, в каком он идёт по сети и
 * восстанавливается на клиенте в [Provider].
 *
 * Это НЕ [Provider] из конфига: здесь нет условия `when` (сервер уже
 * отобрал активные) и нет addon-объекта [dev.ggtv.capecraft.api.provider.CapeSource]
 * (у клиента он свой, см. [toProvider]).
 */
data class ActiveCape(
    val name: String,
    val kind: Kind,
    /** url-шаблон / путь-шаблон / url для json / имя аддон-типа. */
    val primary: String,
    /** JSONPath-инструкция для [Kind.JSON], иначе пусто. */
    val extract: String = "",
    val priority: Int = 0,
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
     * Проверить поле до отправки/после приёма: длины, обязательность
     * `extract` у json, безопасную схему URL.
     *
     * Возвращает список проблем (пустой = всё в порядке) — вызывающий
     * решает, логировать это или отбросить пакет целиком.
     */
    fun validate(): List<String> {
        val out = ArrayList<String>()
        if (name.isEmpty()) out += "пустое имя провайдера"
        if (utf8Len(name) > SyncProtocol.MAX_NAME_BYTES) {
            out += "имя «${truncate(name)}» длиннее ${SyncProtocol.MAX_NAME_BYTES} байт"
        }
        if (primary.isEmpty()) out += "у провайдера «$name» пустой параметр"
        if (utf8Len(primary) > SyncProtocol.MAX_STRING_BYTES) {
            out += "параметр провайдера «$name» длиннее ${SyncProtocol.MAX_STRING_BYTES} байт"
        }
        if (utf8Len(extract) > SyncProtocol.MAX_STRING_BYTES) {
            out += "extract провайдера «$name» длиннее ${SyncProtocol.MAX_STRING_BYTES} байт"
        }
        when (kind) {
            Kind.URL -> {
                if (primary.isNotEmpty() && !isHttpUrl(primary)) {
                    out += "у url-провайдера «$name» недопустимая ссылка «${truncate(primary)}»"
                }
            }

            Kind.JSON -> {
                if (primary.isNotEmpty() && !isHttpUrl(primary)) {
                    out += "у json-провайдера «$name» недопустимая ссылка «${truncate(primary)}»"
                }
                if (extract.isEmpty()) {
                    out += "у json-провайдера «$name» пустой extract"
                }
            }

            Kind.FILE -> Unit // путь проверяется политикой клиента (см. SyncPolicy)
            Kind.ADDON -> {
                if (primary.isEmpty()) out += "у аддон-провайдера «$name» пустой тип"
            }
        }
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
 * Собрать сетевое описание провайдера из [Provider].
 *
 * Условие `when` намеренно НЕ переносится: сервер уже применил
 * [dev.ggtv.capecraft.condition.ProviderSelector] и отправил только
 * активные. Аддон-тип переносится именем типа — параметры аддона клиент
 * восстановит из своего локального конфига (см. CapeSyncClient).
 */
fun Provider.toActiveCape(): ActiveCape? = when {
    addonSource != null -> values?.let { ActiveCape(name, ActiveCape.Kind.ADDON, it.type, priority = priority) }
    else -> when (val s = source) {
        is Source.Url -> ActiveCape(name, ActiveCape.Kind.URL, s.template, priority = priority)
        is Source.File -> ActiveCape(name, ActiveCape.Kind.FILE, s.template, priority = priority)
        is Source.Json -> ActiveCape(name, ActiveCape.Kind.JSON, s.template, s.extract, priority = priority)
    }
}
