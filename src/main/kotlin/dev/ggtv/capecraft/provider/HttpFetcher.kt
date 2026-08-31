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
 */
class HttpFetcher(
    private val client: HttpClient = defaultClient(),
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
) : CapeFetcher {

    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.File -> throw FetchError("http-источник не умеет локальные файлы: ${r.path}")
        is Resolved.Url -> getBytes(r.url)
        is Resolved.Json -> {
            val body = String(getBytes(r.url))
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
            getBytes(capeUrl)
        }
    }

    private fun getBytes(url: String): ByteArray {
        val uri = try {
            URI.create(url)
        } catch (e: Exception) {
            throw FetchError("неверный URL «$url»: ${e.message.orEmpty()}", e)
        }
        val req = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("User-Agent", "CapeCraft/0.1.0")
            .GET()
            .build()
        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw FetchError("сетевая ошибка при запросе «$url»: ${e.message.orEmpty()}", e)
        }
        if (resp.statusCode() !in 200..299) {
            throw FetchError("HTTP ${resp.statusCode()} при запросе «$url»")
        }
        return resp.body()
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
