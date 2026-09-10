package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.api.CapeApiHolder

/**
 * Составной источник: выбирает фетчер по типу [Resolved].
 *
 * Нужен, потому что один [CapeFetcher] не умеет всё сразу:
 * [HttpFetcher] ходит по URL/JSON (и бросает ошибку на локальный файл),
 * [FileFetcher] — читает локальные файлы (и не умеет URL).
 * Аддон-провайдеры (Resolved.Addon) сразу делегируют фетчеру [CapeSource] из
 * расширения.
 */
class CompositeFetcher(
    private val http: CapeFetcher = HttpFetcher(),
    private val file: CapeFetcher = FileFetcher(),
) : CapeFetcher {
    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.File -> file.fetch(r)
        is Resolved.Url, is Resolved.Json -> http.fetch(r)
        is Resolved.Addon -> r.source.fetch(r.values)
    }
}
