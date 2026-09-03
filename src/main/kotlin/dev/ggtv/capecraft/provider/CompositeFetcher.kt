package dev.ggtv.capecraft.provider

/**
 * Составной источник: выбирает фетчер по типу [Resolved].
 *
 * Нужен, потому что один [CapeFetcher] не умеет всё сразу:
 * [HttpFetcher] ходит по URL/JSON (и бросает ошибку на локальный файл),
 * [FileFetcher] — читает локальные файлы (и не умеет URL). Реестр использует
 * этот фетчер по умолчанию, чтобы `type = url|json` и `type = file` работали
 * в одном конфиге.
 */
class CompositeFetcher(
    private val http: CapeFetcher = HttpFetcher(),
    private val file: CapeFetcher = FileFetcher(),
) : CapeFetcher {
    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.File -> file.fetch(r)
        is Resolved.Url, is Resolved.Json -> http.fetch(r)
    }
}
