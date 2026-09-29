package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.schema.Json
import dev.ggtv.capecraft.schema.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP-источник на встроенном `java.net.http` (без внешних зависимостей).
 *
 * - таймаут соединения и чтения задаётся отдельно;
 * - для `Json` делает два запроса: тянет JSON, вытаскивает URL по инструкции,
 *   затем качает сам файл капки;
 * - ошибки оборачиваются в [FetchError] с контекстом (какой URL, какой статус).
 *
 * **Ответ читается с потолком, а не в память целиком.** `ofByteArray()` на
 * сервере, который отдаёт 4 ГиБ (сломанный CDN, HTML-заглушка на 200,
 * недоверенный хост), сводит мод к OOM — ровно та же дыра, что `u16 65535` в
 * [dev.ggtv.capecraft.sync.SyncProtocol]. Поэтому потолок [MAX_CAPE_BYTES]
 * повторяет протокольный `SyncProtocol.MAX_IMAGE_BYTES`; согласованность
 * проверяется тестом, а не импортом, потому что `sync` уже зависит от
 * `provider` и обратный импорт закрыл бы цикл пакетов.
 *
 * **`Content-Length` не доверяем.** Заголовок может соврать в обе стороны, так
 * что единственная защита — фактически прочитанное число байт.
 * `readNBytes(limit + 1)` аллоцирует не больше `limit + 1` байт даже для
 * бесконечного потока: память ограничена до чтения, а не после.
 *
 * **Таймаут покрывает только получение заголовков.** `HttpRequest.timeout` при
 * `send` не сторожит тело, поэтому сервер, отдающий байты по одному в минуту,
 * удержит фоновый поток. Лечится общим watchdog-ом на стороне загрузки, а не
 * здесь.
 */
class HttpFetcher(
    client: HttpClient? = null,
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    private val maxCapeBytes: Int = MAX_CAPE_BYTES,
    private val maxJsonBytes: Int = MAX_JSON_BYTES,
) : CapeFetcher {

    private val client: HttpClient = client ?: defaultClient(connectTimeout)

    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.File -> throw FetchError("http-источник не умеет локальные файлы: ${r.path}")
        is Resolved.Url -> getBytes(r.url, maxCapeBytes)
        is Resolved.Json -> {
            val body = String(getBytes(r.url, maxJsonBytes), Charsets.UTF_8)
            val j = try {
                Json.parse(body)
            } catch (e: Exception) {
                throw FetchError("не удалось разобрать JSON с ${r.url}: ${e.message.orEmpty()}", e)
            }
            val capeUrl = try {
                JsonPath.extractString(j, r.extract)
            } catch (e: Exception) {
                throw FetchError("не удалось извлечь URL из JSON (${r.url}, инструкция «${r.extract}»): ${e.message.orEmpty()}", e)
            }
            getBytes(capeUrl, maxCapeBytes)
        }
        is Resolved.Addon -> r.source.fetch(r.values)
        // Сетевой картинке HTTP не нужен: байты уже пришли чанками по Sync.
        is Resolved.NetImage -> throw FetchError("http-источник не умеет сетевую картинку: ${r.hash.take(12)}")
    }

    private fun getBytes(url: String, limit: Int): ByteArray {
        val uri = try {
            URI.create(url)
        } catch (e: Exception) {
            throw FetchError("неверный URL «$url»: ${e.message.orEmpty()}", e)
        }
        // Схему проверяем сами: `URI.create` спокойно глотает `file:`, `jar:` и
        // `data:`, а `newBuilder` на них падает с сырым IllegalArgumentException
        // мимо политики «все ошибки — типизированные». Локальный файл по URL
        // читать нечего: это не сеть, этим занимается FileFetcher.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw FetchError("схема «${uri.scheme ?: "нет"}» не поддерживается, нужен http или https: «$url»")
        }
        val req = try {
            HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "CapeCraft/1.0.0")
                .GET()
                .build()
        } catch (e: Exception) {
            throw FetchError("не удалось собрать запрос к «$url»: ${e.message.orEmpty()}", e)
        }
        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw FetchError("сетевая ошибка при запросе «$url»: ${e.message.orEmpty()}", e)
        }
        // Тело закрываем всегда: на не-2xx, на превышении лимита и на успехе.
        // Иначе соединение осталось бы занятым до истечения таймаута.
        resp.body().use { body ->
            if (resp.statusCode() !in 200..299) {
                throw FetchError("HTTP ${resp.statusCode()} при запросе «$url»")
            }
            val bytes = body.readNBytes(limit + 1)
            if (bytes.size > limit) {
                throw FetchError(
                    "ответ «$url» больше лимита в $limit байт (сервер отдал не меньше ${bytes.size})",
                )
            }
            return bytes
        }
    }

    companion object {
        /**
         * Потолок одной капки, равен `SyncProtocol.MAX_IMAGE_BYTES`: то, что не
         * влезло бы в Sync, не должно занимать память и при загрузке.
         */
        const val MAX_CAPE_BYTES: Int = 8 * 1024 * 1024

        /**
         * Потолок JSON-документа, из которого вытаскивается URL. Синхронный
         * протокол кладёт всю полезную нагрузку в 128 КиБ, поэтому 1 МиБ — с
         * большим запасом, но всё равно не «сколько отдадут».
         */
        const val MAX_JSON_BYTES: Int = 1024 * 1024

        fun defaultClient(connectTimeout: Duration = Duration.ofSeconds(5)): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}
