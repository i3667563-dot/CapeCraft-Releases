package dev.ggtv.capecraft

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.ImageDecoder
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.memory.MemoryManager
import dev.ggtv.capecraft.provider.CapeFetcher
import dev.ggtv.capecraft.provider.FetchError
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.HttpFetcher
import dev.ggtv.capecraft.provider.resolveCape
import dev.ggtv.capecraft.schema.Placeholders
import net.minecraft.util.Identifier

/**
 * Реестр плащей: UUID игрока → [AnimatedImage].
 *
 * Связывает все части мода:
 *  - провайдеры (этап 4) — откуда взять байты капки;
 *  - декодер (этап 3) — распаковка PNG/APNG/GIF/WebP в кадры;
 *  - память (этап 5) — лимиты, LRU, сжатие/скип кадров.
 *
 * [get] не только отдаёт закэшированный плащ, но и подгружает недостающий
 * (сетевой/локальный fetch + декод + деградация памяти). При ошибке загрузки
 * возвращает null и запоминает причину в [lastError] — для `/cp status`.
 */
class CapeRegistry(
    var providers: List<Provider> = emptyList(),
    private val fetcher: CapeFetcher = HttpFetcher(),
    private val memory: MemoryManager = MemoryManager(Limits()),
    private val root: String = System.getProperty("user.dir", "."),
) {
    /** UUID → ошибка последней попытки (для диагностики). */
    private val errors = HashMap<String, String>()

    /** Последний кадр по игровому времени для анимации. */
    fun get(uuid: String, username: String): AnimatedImage? {
        memory.get(uuid)?.let { return it }

        try {
            val ctx = Placeholders.Context(username = username, uuid = stripDashes(uuid), name = "")
            val bytes = resolveCape(providers, ctx, root, fetcher)
            val decoded = ImageDecoder.decode(bytes, source = username)
            val stored = memory.store(uuid, decoded)
            errors.remove(uuid)
            return stored
        } catch (e: FetchError) {
            errors[uuid] = "загрузка: ${e.message}"
        } catch (e: ImageDecodeException) {
            errors[uuid] = "декод: ${e.message}"
        } catch (e: Exception) {
            errors[uuid] = "ошибка: ${e.message.orEmpty()}"
        }
        return null
    }

    /** Удалить плащ игрока из памяти. */
    fun forget(uuid: String) {
        memory.remove(uuid)
        CapeTexture.release(uuid)
        errors.remove(uuid)
    }

    /** Очистить весь кэш плащей. */
    fun clear() {
        for (k in memory.keys) CapeTexture.release(k)
        memory.clear()
        errors.clear()
    }

    /** Число закэшированных плащей. */
    val size: Int get() = memory.capesCount

    /** Суммарная память под плащи, байт. */
    val totalBytes: Long get() = memory.totalBytes

    /** Ошибка последней попытки для заданного UUID (или null). */
    fun error(uuid: String): String? = errors[uuid]

    /** Все закэшированные ключи плащей. */
    val cachedKeys: Set<String> get() = memory.keys

    /**
     * Зарегистрировать текущий кадр плаща [uuid] как динамическую текстуру
     * и вернуть её [net.minecraft.util.Identifier] для RenderLayer.
     * Возвращает null, если плаща нет.
     */
    fun dynamicTexture(uuid: String, anim: AnimatedImage? = null): Identifier? {
        val image = anim ?: memory.get(uuid) ?: return null
        val frame = image.singlePixels ?: image.frames.firstOrNull()?.pixels ?: return null
        return CapeTexture.register(uuid, image.width, image.height, frame)
    }

    private fun stripDashes(uuid: String) = uuid.replace("-", "")
}
