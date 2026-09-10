package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.condition.ProviderSelector
import dev.ggtv.capecraft.schema.Json
import dev.ggtv.capecraft.schema.JsonPath
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.WorldContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path as JPath

/**
 * Источник байтов капки. Отделяем «как взять байты» от «какой провайдер»:
 * в тестах подставляется фейк без сети, в проде — [HttpFetcher].
 */
fun interface CapeFetcher {
    /** Вернуть байты капки для конкретного [Resolved] или бросить [FetchError]. */
    fun fetch(r: Resolved): ByteArray
}

/** Ошибка получения капки с человеческим контекстом (какой провайдер где упал). */
class FetchError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Локальный источник: читает файл, для JSON-схемы тоже поддерживает извлечение
 * из файла. Используется для `type = file`.
 */
class FileFetcher : CapeFetcher {
    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.File -> readFile(r.path)
        is Resolved.Url -> throw FetchError("file-источник не умеет URL: ${r.url}")
        is Resolved.Json -> {
            // JSON из файла: читаем файл по шаблону, вытаскиваем URL, читаем его локально.
            val j = Json.parse(String(readFile(r.url)))
            val url = JsonPath.extractString(j, r.extract)
            readFile(url)
        }
        is Resolved.Addon -> r.source.fetch(r.values)
    }

    private fun readFile(path: String): ByteArray {
        val p = JPath.of(path)
        if (!Files.exists(p)) throw FetchError("файл не найден: «$path»")
        if (Files.isDirectory(p)) throw FetchError("«$path» — директория, ожидался файл")
        return try {
            Files.readAllBytes(p)
        } catch (e: IOException) {
            throw FetchError("не могу прочитать файл «$path»: ${e.message.orEmpty()}", e)
        }
    }
}

/**
 * Пройти провайдеров по порядку выбора — берём первого, кто отдал байты
 * (fallback: при ошибке пробуем следующего).
 *
 * Порядок выбора определяет [ProviderSelector.select]: сначала совпавшие
 * условия по убыванию приоритета, затем default-провайдеры. См. также роадмап п. 2.
 *
 * @param providers все провайдеры из конфига (без учёта мира)
 * @param world состояние мира для условий `when` (по умолчанию мир недоступен —
 *          тогда провайдеры с условием не выбираются, остаются только default)
 * @param fetcher источник байтов (прод — HTTP, тесты — фейк)
 * @return байты первой успешной капки; если все упали — [FetchError]
 *          со сводкой по каждому провайдеру.
 */
fun resolveCape(
    providers: List<Provider>,
    ctx: dev.ggtv.capecraft.schema.Placeholders.Context,
    root: String,
    fetcher: CapeFetcher,
    world: WorldContext = EmptyWorldContext,
): ByteArray {
    val ordered = ProviderSelector.select(providers, world)
    val errors = mutableListOf<String>()
    for (p in ordered) {
        val resolved = try {
            p.resolve(ctx, root)
        } catch (e: Exception) {
            errors += "провайдер «${p.name}»: сборка шаблона: ${e.message.orEmpty()}"
            continue
        }
        try {
            return fetcher.fetch(resolved)
        } catch (e: Exception) {
            errors += "провайдер «${p.name}»: ${e.message.orEmpty()}"
        }
    }
    throw FetchError(
        "ни один провайдер не отдал капку. Причины:\n  " + errors.joinToString("\n  "),
    )
}
