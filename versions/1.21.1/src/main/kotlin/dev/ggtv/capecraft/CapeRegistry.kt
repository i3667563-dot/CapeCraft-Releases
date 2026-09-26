package dev.ggtv.capecraft

import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.event.CapeEvent
import dev.ggtv.capecraft.condition.ProviderSelector
import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.ImageDecoder
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.memory.MemoryManager
import dev.ggtv.capecraft.provider.CapeFetcher
import dev.ggtv.capecraft.provider.CompositeFetcher
import dev.ggtv.capecraft.provider.FetchError
import dev.ggtv.capecraft.provider.Guard
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.resolveCapeOrdered
import dev.ggtv.capecraft.render.MinecraftWorldContext
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.koren.WorldContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import net.minecraft.util.Identifier

/**
 * Реестр плащей: UUID игрока → [AnimatedImage].
 *
 * Связывает все части мода:
 *  - провайдеры (этап 4) — откуда взять байты капки;
 *  - декодер (этап 3) — распаковка PNG/APNG/GIF/WebP в кадры;
 *  - память (этап 5) — лимиты, LRU, сжатие/скип кадров.
 *
 * ## Потоки и производительность (жёсткая оптимизация)
 *
 * Вся тяжёлая работа — сетевой/локальный fetch, декод кадров и area-average
 * деградация — выполняется НА ФОНОВОМ ПОТОКЕ ([executor]). Рендер-поток и
 * игровой тик никогда не блокируются на этой работе, поэтому плащ любого
 * размера (даже 30 кадров 512x256, сжимающихся до ~313p) больше НЕ роняет
 * FPS до нуля.
 *
 * - [get] / [ensureLoading] лишь планируют загрузку и возвращаются сразу;
 * - готовый [AnimatedImage] кладётся в кэш воркером ([loadInBackground]);
 * - [textureId] — лёгкое чтение готового id текстуры (никаких getTexture/decode
 *   в горячем пути рендера);
 * - создание/обновление GPU-текстуры делает [animate] на рендер-потоке из
 *   уже готовых кадров (дёшево: перезапись пикселей + upload раз в ~100 мс).
 *
 * Кэш памяти ([memory]) и ошибки защищены общим мьютексом [lock]; идемпотентная
 * планировка — [loading] (ConcurrentHashMap), чтобы не грузить плащ дважды.
 */
class CapeRegistry(
    @Volatile
    var providers: List<Provider> = emptyList(),
    private val fetcher: CapeFetcher = CompositeFetcher(),
    limits: Limits = Limits(),
    @Volatile
    private var root: String = System.getProperty("user.dir", "."),
    @Volatile
    private var world: WorldContext = MinecraftWorldContext,
) {
    // @Volatile: reload сбрасывает эти ссылки с рендер-потока, а воркер читает их
    // в фоне — сменившийся провайдер/лимиты/root/кэш должны быть сразу видимы.
    @Volatile
    private var memory: MemoryManager = MemoryManager(limits)
    private val errors = ConcurrentHashMap<String, String>()
    private val usernames = ConcurrentHashMap<String, String>()

    /**
     * true = [providers] пришли с СЕРВЕРА и уже отобраны по его миру.
     *
     * В этом режиме клиент не пересчитывает `when`-условия ([order] отдаёт
     * список «как есть»), а [refreshConditions] молчит: новый набор придёт
     * следующим ответом сервера, а не локальным пересчётом по миру клиента.
     */
    @Volatile
    var isServerAuthoritative: Boolean = false
        private set

    /**
     * Проверка источника перед скачиванием (см. [Guard]). Задаётся только
     * для набора с сервера: локальный конфиг пользователь писал сам.
     */
    @Volatile
    private var guard: Guard? = null

    /**
     * Переключить реестр на набор, присланный сервером.
     *
     * Провайдеры приходят уже упорядоченными и активными, поэтому
     * [ProviderSelector] не применяется, а [refreshConditions] отключается.
     * Текстуры не трогаются — плащи бесшовно доезжают в фоне.
     *
     * Если набор структурно тот же, что уже применён, ничего не делаем: иначе
     * каждый тик на сервере с `requireServer` (или после каждого ответа с тем
     * же fingerprint) пересоздавал бы кэш памяти и заново качал все плащи.
     */
    fun useServerProviders(newProviders: List<Provider>, newRoot: String, newGuard: Guard? = null) {
        if (sameAsCurrent(newProviders, newRoot) && isServerAuthoritative && guard != null) return
        guard = newGuard
        isServerAuthoritative = true
        reload(newProviders, memory.limits, newRoot)
    }

    /** Вернуться к локальному конфигу (сервер не ответил / рассинхрон). */
    fun useLocalProviders(newProviders: List<Provider>, newRoot: String) {
        if (sameAsCurrent(newProviders, newRoot) && !isServerAuthoritative && guard == null) return
        guard = null
        isServerAuthoritative = false
        reload(newProviders, memory.limits, newRoot)
    }

    /**
     * Структурное сравнение текущего набора с новым.
     *
     * [Provider] — обычный класс (не data), поэтому `==` на списках сравнивал
     * бы ссылки. Сравниваем всё, что реально влияет на загрузку: имя, вид
     * источника, приоритет и наличие аддон-источника.
     */
    private fun sameAsCurrent(newProviders: List<Provider>, newRoot: String): Boolean =
        root == newRoot && fingerprintOf(providers) == fingerprintOf(newProviders)

    private fun fingerprintOf(list: List<Provider>): String = list.joinToString("\u0000") {
        "${it.name}\u0001${it.priority}\u0001${it.addonSource != null}\u0001${it.source}"
    }

    /** UUID, чей плащ сейчас грузится в фоне (защита от дублей). */
    private val loading = ConcurrentHashMap.newKeySet<String>()
    /** UUID → текстура уже создана и готова к рендеру. */
    private val textureReady = ConcurrentHashMap.newKeySet<String>()
    /** UUID → стабильный id текстуры плаща. */
    private val textureIds = ConcurrentHashMap<String, Identifier>()
    /** UUID, чью текстуру надо принудительно перезаписать после [reload]. */
    private val pendingRefresh = HashSet<String>()

    /**
     * Поколение реестра: инкрементируется при [reload]. Фоновая задача
     * запоминает своё поколение при старте и записывает результат только если
     * оно всё ещё актуально — иначе это устаревшая загрузка со старыми
     * провайдерами/лимитами, и её результат отбрасывается (не перезапишет
     * свежий плащ).
     */
    private val generation = AtomicInteger(0)

    /** Общий мьютекс для [memory] и кадровых данных. */
    private val lock = Any()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CapeCraft-Loader").apply { isDaemon = true }
    }

    /** Остановить фоновый воркер (вызывается при полном пересоздании реестра). */
    fun shutdown() = executor.shutdown()

    /**
     * Перезагрузить реестр: новые провайдеры/лимиты + перезагрузка плащей.
     *
     * Вызывается из `/cp reload` (рендер-поток). Работает бесшовно и не
     * блокирует рендер:
     *  - провайдеры/лимиты/root обновляются сразу, поколение инкрементируется —
     *    незавершённые загрузки старого поколения при завершении отбрасываются;
     *  - CPU-кэш сбрасывается, а GPU-текстуры НЕ трогаются: старые плащи
     *    продолжают рендериться, пока новые не загрузятся (без мигания в ваниль);
     *  - все известные игроки перепланируются на фоновую загрузку;
     *  - когда свежий плащ готов, [animate] принудительно перезаписывает
     *    текстуру (идемпотентно, по флагу [pendingRefresh]).
     */
    fun reload(newProviders: List<Provider>, newLimits: Limits, newRoot: String) {
        synchronized(lock) {
            providers = newProviders
            root = newRoot
            memory = MemoryManager(newLimits)
            refreshLocked()
        }
    }

    /**
     * Полная перезагрузка по команде `/cp reload`: состояние мода с нуля.
     *
     * В отличие от [reload] сбрасывает ещё и GPU-часть — текстуры, их id и факт
     * загрузки, — поэтому плащ на время загрузки исчезает и рисуется ванильный.
     * Это осознанно: `/cp reload` означает «перезагрузить мод», а бесшовное
     * обновление (старое висит, пока не приедет новое) остаётся у [reload].
     *
     * Заодно снимается авторитетность сервера: команда — это явное «возьми мой
     * локальный конфиг», поэтому локальный набор применяется немедленно. Если
     * сервер пришлёт свой, он применится следующим ответом.
     */
    fun reloadAll(newProviders: List<Provider>, newLimits: Limits, newRoot: String) {
        synchronized(lock) {
            isServerAuthoritative = false
            guard = null
            providers = newProviders
            root = newRoot
            memory = MemoryManager(newLimits)
            textureReady.clear()
            textureIds.clear()
            uploadedFrame.clear()
            pendingRefresh.clear()
            refreshLocked()
        }
    }

    /** Общее тело: сбросить состояние и перепланировать загрузку всех игроков. */
    private fun refreshLocked() {
        errors.clear()
        loading.clear()          // старые задачи в очереди отбросятся по поколению
        generation.incrementAndGet()
        pendingRefresh.addAll(usernames.keys)
        lastConditionsFingerprint = ""
        // Перезагрузка всех известных игроков — напрямую в очередь воркера
        // (single-thread, FIFO): последняя задача с новым поколением победит.
        val ordered = order()
        for (uuid in usernames.keys) {
            loading.add(uuid)
            executor.execute { loadInBackground(uuid, ordered) }
        }
    }

    /**
     * Порядок провайдеров для загрузки.
     *
     * Обычный режим — [ProviderSelector] пересчитывает `when`-условия на
     * живом мире клиента. Режим сервера — список уже отобран и упорядочен
     * сервером, пересчитывать нечего (и опасно: мир клиента может отличаться).
     */
    private fun order(): List<Provider> =
        if (isServerAuthoritative) providers else ProviderSelector.select(providers, world)

    /**
     * Достать плащ по UUID, если он уже загружен. НЕ блокирует рендер-поток:
     * если плащ ещё грузится в фоне — вернёт null (загрузка уже идёт).
     */
    fun get(uuid: String): AnimatedImage? = synchronized(lock) { memory.get(uuid) }

    /**
     * Гарантировать, что плащ загружается (идемпотентно). Планирует фоновую
     * загрузку, если плаща ещё нет и он не грузится. Возвращается сразу,
     * без декода на вызывающем потоке.
     *
     * Горячий путь (вызывается из рендера каждый кадр): сначала дешёвые
     * ConcurrentHashMap-проверки без lock; [lock] берётся только один раз —
     * пока плащ реально не готов.
     */
    fun ensureLoading(uuid: String, username: String) {
        if (textureReady.contains(uuid)) return // текстура уже на экране
        if (loading.contains(uuid)) return      // фон уже грузит
        val ready = synchronized(lock) { memory.contains(uuid) }
        if (ready) return                       // в CPU-кэше — текстуру создаст animate
        if (loading.add(uuid)) {
            usernames[uuid] = username
            // Порядок провайдеров вычисляем ЗДЕСЬ (рендер-поток), где безопасно
            // читать живой мир; воркеру передаём уже готовый список.
            val ordered = order()
            synchronized(lock) { lastConditionsFingerprint = ordered.joinToString("\u0000") { it.name } }
            executor.execute { loadInBackground(uuid, ordered) }
        }
    }

    /** Фоновая загрузка+декод+деградация. Работает НЕ на рендер-потоке. */
    private fun loadInBackground(uuid: String, ordered: List<Provider>) {
        val gen = generation.get()
        val username = usernames[uuid] ?: ""
        try {
            val ctx = Placeholders.Context(username = username, uuid = stripDashes(uuid), name = "")
            val bytes = resolveCapeOrdered(ordered, ctx, root, fetcher, guard).bytes
            val decoded = ImageDecoder.decode(bytes, source = username)
            // Тяжёлая деградация (area-average по всем кадрам) — на воркере,
            // до захвата lock. Под lock только быстрая вставка в кэш.
            val fit = memory.degrade(decoded)
            synchronized(lock) {
                if (gen != generation.get()) return // устаревшая загрузка — результат скипаем
                memory.putCached(uuid, fit)
                errors.remove(uuid)
            }
            emit(CapeEvent.CapeLoaded(uuid, username, fit))
        } catch (e: FetchError) {
            synchronized(lock) {
                if (gen == generation.get()) errors[uuid] = "загрузка: ${e.message}"
            }
            emit(CapeEvent.ProviderNotFound(uuid, username, e.message.orEmpty()))
        } catch (e: ImageDecodeException) {
            synchronized(lock) {
                if (gen == generation.get()) errors[uuid] = "декод: ${e.message}"
            }
            emit(CapeEvent.CapeLoadFailed(uuid, username, "декод: ${e.message}"))
        } catch (e: Exception) {
            synchronized(lock) {
                if (gen == generation.get()) errors[uuid] = "ошибка: ${e.message.orEmpty()}"
            }
            emit(CapeEvent.CapeLoadFailed(uuid, username, e.message.orEmpty()))
        } finally {
            loading.remove(uuid)
        }
    }

    /** Удалить плащ игрока из памяти. */
    fun forget(uuid: String) {
        synchronized(lock) {
            memory.remove(uuid)
            CapeTexture.release(uuid)
            errors.remove(uuid)
            textureReady.remove(uuid)
            textureIds.remove(uuid)
            pendingRefresh.remove(uuid)
        }
    }

    /** Очистить весь кэш плащей. */
    fun clear() {
        synchronized(lock) {
            for (k in memory.keys) CapeTexture.release(k)
            memory.clear()
            loading.clear()
            generation.incrementAndGet() // отбросить незавершённые загрузки
            errors.clear()
            textureReady.clear()
            textureIds.clear()
            pendingRefresh.clear()
            uploadedFrame.clear()
        }
    }

    /** Число закэшированных плащей. */
    val size: Int get() = synchronized(lock) { memory.capesCount }

    /** Суммарная память под плащи, байт. */
    val totalBytes: Long get() = synchronized(lock) { memory.totalBytes }

    /** Ошибка последней попытки для заданного UUID (или null). */
    fun error(uuid: String): String? = errors[uuid]

    /** Копия ошибок загрузки: UUID → причина (для `/cp list`, даже если плащей нет). */
    val errorsSnapshot: Map<String, String> get() = synchronized(lock) { errors.toMap() }

    /** Все закэшированные ключи плащей. */
    val cachedKeys: Set<String> get() = synchronized(lock) { memory.keys.toSet() }

    /**
     * Id текстуры плаща для рендера. ЛЁГКИЙ путь: читает готовый id из кэша.
     * Никогда не декодирует, не создаёт texture-manager lookup и не аплоадит.
     *
     * Возвращает null, пока плащ не загружен и текстура не создана (тикер
     * [animate] создаёт её из готовых кадров). Рендер в этом случае НЕ отменяет
     * ванильный рендер, а просто рисует штатно — без белого плаща и без FPS-спайков.
     */
    fun textureId(uuid: String): Identifier? =
        if (textureReady.contains(uuid)) textureIds[uuid] else null

    /**
     * Обновить текстуры плащей до кадра на момент [timeMs]. Вызывается из
     * игрового тика (~10-20 раз/сек), а не из рендера, чтобы не просаживать
     * FPS перезаписью/upload на каждый рендер-кадр.
     *
     * Здесь на рендер-потоке выполняется ТОЛЬКО лёгкая подгрузка уже
     * декодированных кадров в GPU (перезапись пикселей + upload), не стоящая
     * за собой декода/деградации — те уже сделаны воркером.
     *
     * Оптимизация: под [lock] собираем только дешёвые метаданные (uuid/кадр
     * для каждого плаща, чей кадр сменился), а собственно `CapeTexture.register`
     * (texture-manager lookup + upload пикселей) выполняется ВНЕ lock — это
     * дорогая операция, которую не должны ждать `ensureLoading`/`get` с
     * рендер-потока в mixin.
     */
    fun animate(timeMs: Long) {
        // План работ: (uuid, кадр, index-для-uploadedFrame, нужно ли register).
        val jobs = ArrayList<AnimJob>()

        synchronized(lock) {
            for (key in memory.keys.toList()) {
                val img = memory.get(key) ?: continue
                // После перезагрузки — принудительно перезаписать текстуру новым
                // кадром (покрывает и смену анимация→статичный PNG).
                if (pendingRefresh.remove(key)) {
                    jobs += AnimJob(key, img, img.frameIndexAt(timeMs))
                    continue
                }
                if (!textureReady.contains(key)) {
                    // Первый раз — создаём текстуру из текущего кадра (или статичного).
                    jobs += AnimJob(key, img, img.frameIndexAt(timeMs))
                    continue
                }
                if (!img.isAnimated) continue
                val frameIndex = img.frameIndexAt(timeMs)
                if (uploadedFrame[key] != null && uploadedFrame[key] == frameIndex) continue
                uploadedFrame[key] = frameIndex
                jobs += AnimJob(key, img, frameIndex)
            }
        }

        // Вне lock: дорогая регистрация/перезапись текстур. Потом вернём
        // результат под lock. Одиночный проход без повторных блокировок.
        if (jobs.isEmpty()) return
        val finalized = ArrayList<Pair<String, Identifier>>(jobs.size)
        for (job in jobs) {
            val id = CapeTexture.register(job.key, job.img.width, job.img.height, job.img.frames[job.index].pixels)
            finalized += job.key to id
        }
        synchronized(lock) {
            for ((key, id) in finalized) {
                textureIds[key] = id
                textureReady.add(key)
            }
        }
        for (job in jobs) {
            emit(CapeEvent.FrameBuilt(job.key, job.index))
        }
    }

    /** Единица работы анимации: чей кадр и какой индекс заливать в текстуру. */
    private class AnimJob(
        val key: String,
        val img: AnimatedImage,
        val index: Int,
    )

    /** UUID → индекс кадра, который уже залит в текстуру. */
    private val uploadedFrame = HashMap<String, Int>()

    /** Реалтайм-пересчёт выбора провайдера по текущему состоянию мира
     * (смена плаща по времени суток/погоде/биому по событиям, без `/cp reload`).
     *
     * Вызывается из игрового тика (рендер-поток), где безопасно читать живой
     * мир. Если упорядоченный список провайдеров под текущий мир изменился —
     * перепланирует загрузку всех известных игроков с новым порядком.
     *
     * В режиме сервера не делает ничего: набор отобран сервером и приедет
     * следующим ответом, а пересчёт по миру клиента дал бы ложные смены.
     */
    fun refreshConditions(world: WorldContext) {
        if (isServerAuthoritative) return
        val ordered = order()
        val fp = ordered.joinToString("\u0000") { it.name }
        synchronized(lock) {
            if (fp == lastConditionsFingerprint) return
            lastConditionsFingerprint = fp
            loading.clear()
            generation.incrementAndGet() // старые загрузки со старым миром отбросятся
            pendingRefresh.addAll(usernames.keys)
            for (uuid in usernames.keys) {
                loading.add(uuid)
                executor.execute { loadInBackground(uuid, ordered) }
            }
        }
    }

    /** Отпечаток выбранного порядка провайдеров для последнего реалтайм-пересчёта. */
    private var lastConditionsFingerprint: String = ""

    /** Безопасная отправка события в шину (не падаем, если аддон упал). */
    private fun emit(event: CapeEvent) {
        try {
            CapeApiHolder.api.events.emit(event)
        } catch (_: Exception) {
        }
    }

    private fun stripDashes(uuid: String) = uuid.replace("-", "")
}