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
import dev.ggtv.capecraft.provider.NetImageFetcher
import dev.ggtv.capecraft.sync.NetImageStore
import dev.ggtv.capecraft.sync.ObjectCapePolicy
import dev.ggtv.capecraft.provider.FetchError
import dev.ggtv.capecraft.provider.Guard
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.provider.resolveCapeOrdered
import dev.ggtv.capecraft.render.MinecraftWorldContext
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.koren.WorldContext
import net.minecraft.resources.Identifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    limits: Limits = Limits(),
    @Volatile
    private var root: String = System.getProperty("user.dir", "."),
    @Volatile
    private var world: WorldContext = MinecraftWorldContext,
) {
    /**
     * Кэш картинок, пришедших по Sync v2, и одновременно источник для
     * [NetImageFetcher].
     *
     * Одна и та же инстанция на оба: если бы кэш и фетчер были разными,
     * ассемблер клал бы байты в один, а резолвер искал в другом — и плащ
     * вечно считался бы «ещё не докачан».
     */
    val networkImages: NetImageStore = NetImageStore()

    private val fetcher: CapeFetcher = CompositeFetcher(net = NetImageFetcher(networkImages))

    // @Volatile: reload сбрасывает эти ссылки с рендер-потока, а воркер читает их
    // в фоне — сменившийся провайдер/лимиты/root/кэш должны быть сразу видимы.
    @Volatile
    private var memory: MemoryManager = MemoryManager(limits)
    private val errors = ConcurrentHashMap<String, String>()

    /**
     * Набор функций каждого объекта в кадре, объявленный через Sync v2.
     *
     * Ключ — непрозрачный id из роустера. Значение — локальные [Provider],
     * уже восстановленные с их `when` и приоритетами.
     *
     * Именно здесь «у этого объекта такой набор функций» превращается в то,
     * что клиент умеет считать.
     *
     * Объекта нет в карте — значит он ничего не объявил: у него нет мода,
     * синхронизация выключена или сервер не CapeCraft. Такому объекту мой
     * набор навязывается принудительно, но только спустя срок ожидания
     * (см. [ObjectCapePolicy] и [orderFor]).
     */
    private val objectFunctions = ConcurrentHashMap<String, List<Provider>>()

    /**
     * Когда объект появился в кадре впервые, мс — отсчёт срока ожидания.
     *
     * Ключ — тот же id, что и у [objectFunctions], то есть UUID с дефисами
     * (`AbstractClientPlayerEntity.uuidAsString` и профиль на сервере дают
     * одно и то же значение). Объекты без объявления тоже попадают сюда: иначе
     * срок не от чего было бы отсчитывать.
     *
     * Чистится в [forget], [forgetObject] и [forgetSession] — иначе карта росла
     * бы на каждого вошедшего и держала память до конца сессии.
     */
    private val firstSeenAt = ConcurrentHashMap<String, Long>()

    /**
     * У каких объектов вообще есть условия.
     *
     * Нужно, чтобы не пересчитывать условия у всех подряд: без условий порядок
     * не меняется никогда, а пересчёт стоит чтения биома. Объекты без
     * условий в этот цикл не попадают вообще.
     */
    private val objectHasConditions = ConcurrentHashMap<String, Boolean>()

    /**
     * Мой собственный id.
     *
     * Набор функций для себя берётся из локального конфига всегда, без
     * ожидания и независимо от того, что прислал сервер: свой `file`-плащ по
     * умолчанию наружу не уезжает, и в собственном снимке роустера его нет.
     *
     * Остальным объектам мой набор тоже достаётся — но по сроку, а не сразу:
     * не объявился за [ObjectCapePolicy.ANNOUNCE_GRACE_MS] — значит синхронизации
     * у него не будет, и тогда показывать ему надо мой конфиг, а не пустоту.
     * Решение целиком в [ObjectCapePolicy], здесь только адаптация.
     */
    @Volatile
    var localPlayerId: String? = null
        private set

    /**
     * Свой ник — для `if`, считаемого против себя.
     *
     * Отдельное поле, а не чтение [usernames]: `refreshLocked` зовёт [orderFor]
     * для себя раньше, чем миксин рендера успеет позвать [ensureLoading], и в
     * этот момент карты ещё пусты. Пустой ник ознал бы, что `if { username:
     * "..." }` молча не срабатывает для самого владельца конфига — ровно тот
     * случай, который проверяют руками и не замечают.
     */
    @Volatile
    var localUsername: String = ""
        private set

    /** Сообщить реестру свой id и ник при входе на сервер. */
    fun setLocalPlayer(id: String, username: String = localUsername) {
        localPlayerId = id
        localUsername = username
    }
    private val usernames = ConcurrentHashMap<String, String>()

    /**
     * Проверка источника перед скачиванием (см. [Guard]).
     *
     * Ставится только для локального конфига: чужой набор приходит по сети, но
     * там нечего проверять — путь на проводе не едет (см.
     * [dev.ggtv.capecraft.provider.Source.NetImage]), а `http`-ссылки и так
     * ограничены схемой. Поэтому объявления других объектов guard не проходят
     * и не должны.
     */
    @Volatile
    private var guard: Guard? = null

    /**
     * Переключить реестр на локальный конфиг.
     *
     * В v2 чужой набор функций **не заменяет** мой: он живёт отдельно, в
     * [objectFunctions], и применяется только к своему объекту. Метод
     * остался только для смены собственного набора, поэтому и настройки guard
     * тут локальные, как и раньше.
     */
    fun useLocalProviders(newProviders: List<Provider>, newRoot: String) {
        if (sameAsCurrent(newProviders, newRoot) && guard == null) return
        guard = null
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
        "${'$'}{it.name}\u0001${'$'}{it.priority}\u0001${'$'}{it.addonSource != null}\u0001${'$'}{it.source}"
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

    /** Общее тело: сбросить состояние и перепланировать загрузку. */
    private fun refreshLocked() {
        errors.clear()
        loading.clear()          // старые задачи в очереди отбросятся по поколению
        generation.incrementAndGet()
        lastConditionsFingerprint = ""
        val local = localPlayerId
        if (local == null) {
            pendingRefresh.clear()
        } else {
            pendingRefresh.add(local)
            lastObjectFingerprint.remove(local)
            val gen = (objectGeneration[local] ?: 0) + 1
            objectGeneration[local] = gen
            // Последняя задача с новым поколением победит.
            val ordered = orderFor(local, world, localUsername)
            lastObjectFingerprint[local] = fingerprint(ordered)
            if (ordered.isNotEmpty()) {
                loading.add(local)
                executor.execute { loadInBackground(local, ordered, gen) }
            }
        }
        // Мой конфиг изменился — пересчитать надо ещё и тех, кто носит его по
        // принудительному правилу, а не по объявлению.
        refreshLocalDependents(local)
    }

    /**
     * Сбросить объекты, плащи которых держатся на **моём** наборе.
     *
     * Объявленные наборы не трогаем: они принадлежат чужим игрокам и от моего
     * конфига не зависят (иначе `/cp reload` дёргал бы текстуры всего сервера).
     * А вот навязанные — зависят, и без этого сброса `/cp reload` поменял бы
     * плащ только мне, а на всех молчащих игроков в кадре молча оставил бы
     * старый.
     *
     * Само перевычисление — не здесь: контекст мира у каждого объекта свой
     * (см. [reevaluate]), а его знает только миксин. Поэтому тут только сброс,
     * а следующий кадр рендера пересчитает объект через [ensureLoading].
     */
    private fun refreshLocalDependents(local: String?) {
        for (id in firstSeenAt.keys) {
            if (id == local) continue
            if (objectFunctions.containsKey(id)) continue
            synchronized(lock) {
                memory.remove(id)
                CapeTexture.release(id)
            }
            textureReady.remove(id)
            loading.remove(id)
            lastObjectFingerprint.remove(id)
            lastReevaluatedAt.remove(id)
            // Растёт поколение, а не сбрасывается: задача, уже висящая в
            // воркере, должна отброситься, а не записать устаревший результат.
            objectGeneration[id] = (objectGeneration[id] ?: 0) + 1
        }
    }

    /**
     * Порядок функций для конкретного объекта в его собственном контексте.
     *
     * Вот ради чего объявленные наборы едут с условиями и приоритетами: сначала
     * те функции, чьё условие совпало **у этого объекта**, по убыванию
     * приоритета, потом функции без условия как fallback.
     *
     * Считаем против [context] того, кого видно, а не против своего мира —
     * иначе объявленные условия применялись бы к миру зрителя и были бы
     * не «его» условиями.
     *
     * @param username ник **этого** объекта: подставляется в `if` того, кого
     *   видно. Для `uuid` берётся сам [id], а `root` — свой на машине зрителя.
     *
     * Кто набор получает — целиком в [ObjectCapePolicy]; здесь только учёт
     * момента первого появления объекта. Правило простое: объявился — его
     * набор, не объявился за [ObjectCapePolicy.ANNOUNCE_GRACE_MS] — мой, а
     * между этим ждём.
     */
    private fun orderFor(id: String, context: WorldContext, username: String): List<Provider> {
        val now = nowMs()
        // putIfAbsent, а не присваивание: пересчёт `when` зовёт orderFor на
        // каждом кадре, и счётчик ожидания обязан считаться от первого
        // появления, а не обнуляться каждым вызовом.
        val firstSeen = firstSeenAt.putIfAbsent(id, now) ?: now
        return ObjectCapePolicy.resolve(
            isSelf = id == localPlayerId,
            declaredProviders = objectFunctions[id],
            localProviders = providers,
            firstSeenMs = firstSeen,
            nowMs = now,
            context = context,
            vars = Placeholders.Context(
                username = username,
                uuid = stripDashes(id),
                // `name` — про провайдер, а не про игрока, и в `loadInBackground`
                // он пустой; подставлять имя провайдера сюда значило бы выдумать
                // переменную, которой ни у кого нет.
                name = "",
                root = root,
            ),
        ).providers
    }

    /**
     * Принять объявленный набор функций объекта.
     *
     * Планирует перезагрузку плаща **только этого** объекта: чужие наборы
     * не должны вызывать перезагрузку всем подряд.
     *
     * @return `true`, если набор изменился и плащ был перепланирован.
     */
    fun useObjectFunctions(id: String, functions: List<Provider>): Boolean {
        // `if` — тоже условие: объект, у которого только `if`, обязан
        // перечитываться, иначе появившееся позже окружение/-D его бы не включило.
        val hasCond = functions.any { it.condition != null || it.ifCondition != null }
        // Отпечаток именно ОБЪЯВЛЕННОГО набора: он отсекает повторы, когда
        // сервер шлёт тот же ростер целиком (ревизия изменилась, а набор нет).
        val declaredFp = functions.joinToString("\u0000") { describe(it) }
        val changed = lastDeclaredFingerprint.put(id, declaredFp) != declaredFp
        objectFunctions[id] = functions
        objectHasConditions[id] = hasCond
        if (!changed) return false

        // Применённый отпечаток сбрасываем: следующий ensureLoading обязан
        // пересчитать порядок под новым набором. Сверять новый набор с
        // последним ПРИМЕНЁННЫМ нельзя — это разные списки, один до
        // отбора по условиям, другой после, и сверка всегда расходилась бы.
        lastObjectFingerprint.remove(id)

        val gen = (objectGeneration[id] ?: 0) + 1
        objectGeneration[id] = gen
        synchronized(lock) {
            // Кэш объекта выкидываем: набор функций другой, картинка может
            // быть другой, и показывать старую нельзя.
            memory.remove(id)
            CapeTexture.release(id)
        }
        // Без снятия отсюда `ensureLoading` выйдет по `textureReady` и
        // перезагрузка не случится — плащ навсегда остался бы от старого набора.
        textureReady.remove(id)
        loading.remove(id)
        return true
    }

    /** Отпечаток последнего объявленного набора — только для отсечки повторов. */
    private val lastDeclaredFingerprint = ConcurrentHashMap<String, String>()

    /**
     * Забыть объявление объекта.
     *
     * Отличие от [forget]: снимает только набор функций, но не трогает уже
     * загруженную текстуру и учёт игроков. Вызывается, когда объекта в снимке
     * роустера нет — то есть он вышел или ещё не объявился, и его плащ надо
     * убрать с экрана, а сам игрок в реестре остаётся.
     *
     * [firstSeenAt] тоже сбрасывается: срок ожидания отсчитывается от первого
     * появления, и вернувшийся объект должен получить полный срок заново, а не
     * мгновенно принудительный набор.
     */
    fun forgetObject(id: String) {
        objectFunctions.remove(id)
        objectHasConditions.remove(id)
        lastObjectFingerprint.remove(id)
        lastDeclaredFingerprint.remove(id)
        firstSeenAt.remove(id)
    }

    /** id всех объектов с объявленным набором — чтобы убрать пропавшие из снимка. */
    fun knownObjectIds(): Set<String> = objectFunctions.keys.toSet()

    /**
     * Сколько объектов сейчас носят мой набор по принудительному правилу.
     *
     * Считается решением на текущий момент, а не «сколько не объявилось»:
     * объект в первые [ObjectCapePolicy.ANNOUNCE_GRACE_MS] после появления ещё
     * ждёт объявления, и звать его принудительным рано. Для `/cp status`, где
     * видно, отработало ли правило.
     */
    fun forcedLocalCount(): Int {
        val now = nowMs()
        val local = localPlayerId
        var forced = 0
        for ((id, seen) in firstSeenAt) {
            if (id == local) continue
            val decision = ObjectCapePolicy.decide(
                isSelf = false,
                declared = objectFunctions.containsKey(id),
                firstSeenMs = seen,
                nowMs = now,
            )
            if (decision == ObjectCapePolicy.Decision.FORCED_LOCAL) forced++
        }
        return forced
    }

    /**
     * id всех объектов, которых я видел: и объявившихся, и навязанных.
     *
     * Отличие от [knownObjectIds] принципиально: там только те, чей набор
     * пришёл по сети, а плащи навязанных объектов тоже занимают память и
     * GPU-текстуры, и на выходе с сервера их надо убрать.
     */
    fun seenObjectIds(): Set<String> =
        LinkedHashSet<String>(firstSeenAt.keys).apply { addAll(objectFunctions.keys) }

    /**
     * Забыть всё, накопленное за сессию.
     *
     * Вызывается на выходе с сервера. Раньше тут был цикл по
     * [knownObjectIds], а он покрывал только объявившихся: плащи игроков без
     * синхронизации, надетые по принудительному правилу, пережили бы сессию и
     * показались на следующем сервере — вместе с чужим набором провайдеров.
     * Заодно сбрасывается [localPlayerId]: при выключенной синхронизации он
     * в [knownObjectIds] не попадал вовсе и уезжал на следующий сервер.
     */
    fun forgetSession() {
        for (id in seenObjectIds()) {
            objectFunctions.remove(id)
            objectHasConditions.remove(id)
            lastObjectFingerprint.remove(id)
            lastDeclaredFingerprint.remove(id)
            lastReevaluatedAt.remove(id)
            objectGeneration.remove(id)
            firstSeenAt.remove(id)
        }
        synchronized(lock) {
            for (key in memory.keys) CapeTexture.release(key)
            memory.clear()
            textureReady.clear()
            textureIds.clear()
            uploadedFrame.clear()
            pendingRefresh.clear()
            errors.clear()
        }
        loading.clear()
        lastConditionsFingerprint = ""
        localPlayerId = null
        localUsername = ""
    }


    /**
     * Заставить пересчитать порядок у всех объектов с условиями.
     *
     * После докачки картинки набор не меняется, но доступных байтов становится
     * больше: раньше `file`-провайдер был «ещё не докачан» и уходил в fallback,
     * а теперь отдаст картинку. Отпечаток порядка при этом прежний, поэтому
     * без явного сброса пересчёт не запустился бы.
     */
    fun invalidateAppliedOrder() {
        for (id in objectHasConditions.keys) {
            lastObjectFingerprint.remove(id)
            lastReevaluatedAt[id] = 0L
        }
        localPlayerId?.let { lastObjectFingerprint.remove(it) }
        lastConditionsFingerprint = ""
    }

    /** Есть ли у объекта набор функций в кадре. */
    fun hasObjectFunctions(id: String): Boolean = objectFunctions.containsKey(id)

    /** Есть ли у объекта функции с условиями — их надо пересчитывать. */
    fun objectNeedsReevaluation(id: String): Boolean = objectHasConditions[id] == true

    /**
     * Достать плащ по UUID, если он уже загружен. НЕ блокирует рендер-поток:
     * если плащ ещё грузится в фоне — вернёт null (загрузка уже идёт).
     */
    fun get(uuid: String): AnimatedImage? = synchronized(lock) { memory.get(uuid) }

    /**
     * Совместимость с аддонами, собранными против CapeCraft, где у метода
     * не было параметра контекста.
     *
     * Тонкость, из-за которой это нельзя было сделать значением по
     * умолчанию: у `ensureLoading(uuid, username, context = world)` есть
     * только дескриптор `(String, String, WorldContext)` плюс синтетический
     * мост для Kotlin-вызовов. Настоящей `(String, String)V` в байткоде нет.
     * Kotlin-вызов этого не замечает, а вот аддон — замечает:
     * `capecraft-bedwars` звал ровно `ensureLoading(String, String)` и на
     * CapeCraft 1.1.1 падал с `NoSuchMethodError` в тике, то есть игра
     * рушилась в первом же матче. Поэтому перегрузка настоящая, а не
     * значение по умолчанию, и синтетический мост трёхаргументной версии
     * остаётся на месте — старые Kotlin-вызовы продолжают работать.
     */
    fun ensureLoading(uuid: String, username: String) = ensureLoading(uuid, username, world)

    /**
     * Гарантировать, что плащ загружается (идемпотентно). Планирует фоновую
     * загрузку, если плаща ещё нет и он не грузится. Возвращается сразу,
     * без декода на вызывающем потоке.
     *
     * Горячий путь (вызывается из рендера каждый кадр): сначала дешёвые
     * ConcurrentHashMap-проверки без lock; [lock] берётся только один раз —
     * пока плащ реально не готов.
     */
    fun ensureLoading(uuid: String, username: String, context: WorldContext = world) {
        usernames[uuid] = username
        // Свой ник известен и с рендера, и с входа; берём отсюда, чтобы
        // `localUsername` не зависел от порядка вызовов на клиенте.
        if (uuid == localPlayerId) localUsername = username

        // Проверка переоценки идёт ДО ранних выходов. Иначе объект, у которого
        // уже готова текстура, навсегда сохранил бы плащ, выбранный в момент
        // входа: зашёл в джунгли → вышел в пустыню → условие изменилось, а
        // `textureReady` возвращал нас наверх и мимо пересчёта.
        if (objectNeedsReevaluation(uuid)) reevaluateIfDue(uuid, context)

        if (textureReady.contains(uuid)) return // текстура уже на экране
        if (loading.contains(uuid)) return      // фон уже грузит
        val ready = synchronized(lock) { memory.contains(uuid) }
        if (ready) return                       // в CPU-кэше — текстуру создаст animate
        // Порядок провайдеров вычисляем ЗДЕСЬ (рендер-поток), где безопасно
        // читать живой мир; воркеру передаём уже готовый список.
        val ordered = orderFor(uuid, context, username)
        // Набора нет — грузить нечего: объект ещё ждёт объявления
        // (ObjectCapePolicy.WAIT) либо объявление пришло пустым. Раньше здесь
        // всё равно планировалась задача, и она падала в воркере с «нет
        // провайдеров» — по одной на каждый кадр рендера, все пять секунд
        // ожидания, плюс ошибка в `/cp list`.
        if (ordered.isEmpty()) return
        if (loading.add(uuid)) {
            val fp = fingerprint(ordered)
            lastObjectFingerprint[uuid] = fp
            lastReevaluatedAt[uuid] = nowMs()
            val gen = objectGeneration[uuid] ?: 0
            executor.execute { loadInBackground(uuid, ordered, gen) }
        }
    }

    /**
     * Пересчитать условия объекта, если подошло время.
     *
     * Троттлинг — [RECHECK_INTERVAL_MS], чтобы миксин, зовущий
     * [ensureLoading] каждый кадр, не читал биомы всех в кадре каждый кадр.
     */
    private fun reevaluateIfDue(uuid: String, context: WorldContext) {
        val now = nowMs()
        val last = lastReevaluatedAt[uuid] ?: 0L
        if (now - last < RECHECK_INTERVAL_MS) return
        // Пишем сразу: если пересчёт идёт тяжело, следующий кадр не влезет
        // в ту же секунду и не запустит второй.
        lastReevaluatedAt[uuid] = now
        reevaluate(uuid, context)
    }

    /**
     * Пересчитать набор функций объекта и, если он изменился, перезагрузить
     * его плащ.
     *
     * Условия считаются против [context] — мира **этого** объекта, а не
     * зрителя. Поэтому объект, дошедший от джунглей до пустыни, честно
     * переключает свой плащ, а зритель этого не инициирует: он просто видит
     * результат чужого пересчёта.
     */
    private fun reevaluate(uuid: String, context: WorldContext) {
        val ordered = orderFor(uuid, context, usernames[uuid].orEmpty())
        val fp = fingerprint(ordered)
        if (fp == lastObjectFingerprint[uuid]) return // порядок тот же — не трогаем

        lastObjectFingerprint[uuid] = fp
        val gen = (objectGeneration[uuid] ?: 0) + 1
        objectGeneration[uuid] = gen

        // Старая текстура больше не соответствует набору функций: снимаем её,
        // иначе показывалась бы картинка от удалённого провайдера.
        synchronized(lock) {
            memory.remove(uuid)
            CapeTexture.release(uuid)
        }
        textureReady.remove(uuid)
        loading.remove(uuid)

        if (ordered.isEmpty()) return // новый набор пуст — плаща не будет
        loading.add(uuid)
        executor.execute { loadInBackground(uuid, ordered, gen) }
    }

    private fun fingerprint(ordered: List<Provider>): String =
        ordered.joinToString("\u0000") { describe(it) }

    /**
     * Полное описание провайдера для отпечатка.
     *
     * Одного имени мало: игрок вправе поменять картинку или ссылку, оставив
     * подпись прежней, а по имени это неотличимо от «ничего не изменилось» —
     * и плащ остался бы со старой текстурой. Поэтому сравниваем всё, что
     * влияет на результат: источник, приоритет, условие и наличие аддона.
     */
    private fun describe(p: Provider): String = buildString {
        append(p.name).append('\u0001').append(p.priority).append('\u0001')
        when (val s = p.source) {
            is Source.Url -> append("url\u0001").append(s.template)
            is Source.File -> append("file\u0001").append(s.template)
            is Source.Json -> append("json\u0001").append(s.template).append('\u0001').append(s.extract)
            is Source.NetImage -> append("net\u0001").append(s.hash)
            else -> append(s::class.simpleName.orEmpty())
        }
        append('\u0001').append(p.condition?.toString() ?: "-")
        // `if` тоже в отпечаток: иначе смена `if` в конфиге была бы не видна
        // и плащ остался бы со старой текстурой — ровно тот баг, ради которого
        // сюда попал `when`.
        append('\u0001').append(p.ifCondition?.toString() ?: "-")
        append('\u0001').append(p.addonSource != null)
    }

    /** Фоновая загрузка+декод+деградация. Работает НЕ на рендер-потоке. */
    private fun loadInBackground(uuid: String, ordered: List<Provider>, objectGen: Int) {
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
                if (objectGen != (objectGeneration[uuid] ?: 0)) return // набор сменился, пока грузили
                memory.putCached(uuid, fit)
                errors.remove(uuid)
            }
            emit(CapeEvent.CapeLoaded(uuid, username, fit))
        } catch (e: FetchError) {
            synchronized(lock) {
                if (gen == generation.get() && objectGen == (objectGeneration[uuid] ?: 0)) {
                    errors[uuid] = "загрузка: ${e.message}"
                }
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
        if (localPlayerId == uuid) localPlayerId = null
        objectFunctions.remove(uuid)
        objectHasConditions.remove(uuid)
        lastObjectFingerprint.remove(uuid)
        lastDeclaredFingerprint.remove(uuid)
        lastReevaluatedAt.remove(uuid)
        objectGeneration.remove(uuid)
        firstSeenAt.remove(uuid)
        synchronized(lock) {
            memory.remove(uuid)
            CapeTexture.release(uuid)
            errors.remove(uuid)
            textureReady.remove(uuid)
            textureIds.remove(uuid)
            pendingRefresh.remove(uuid)
        }
    }

    /**
     * Очистить весь кэш плащей.
     *
     * Карта первых появлений тоже сбрасывается: кэш чистят при сбросе
     * (`/cp reload` с полным обнулением, смена мира), и оставшийся срок
     * ожидания означал бы, что вернувшийся объект получит принудительный набор
     * мгновенно, минуя ожидание.
     */
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
        firstSeenAt.clear()
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
     */
    fun refreshConditions(world: WorldContext) {
        // Троттлинг: раньше это пересчитывалось каждый тик. Порядок меняется
        // максимум раз в 20 тиков (смена времени суток), а [orderFor] дёргает
        // биом — 20 раз в секунду на пустом месте вместо 1.
        val now = nowMs()
        if (now - lastLocalRecheckedAt < LOCAL_RECHECK_INTERVAL_MS) return
        lastLocalRecheckedAt = now

        // Пересчитываем ТОЛЬКО себя. Остальные объекты живут по своим наборам
        // и своим условиям: их пересчитывает миксин через ensureLoading с
        // EntityWorldContext(игрок). Раньше здесь перезагружались все
        // известные игроки локальным порядком — то есть чужие плащи
        // выбирались по моему биому, а это ровно то, что чинится.
        val local = localPlayerId ?: return
        val ordered = orderFor(local, world, localUsername)
        val fp = fingerprint(ordered)
        if (fp == lastObjectFingerprint[local]) return
        lastObjectFingerprint[local] = fp

        val gen = (objectGeneration[local] ?: 0) + 1
        objectGeneration[local] = gen
        synchronized(lock) {
            memory.remove(local)
            CapeTexture.release(local)
        }
        textureReady.remove(local)
        loading.remove(local)
        if (ordered.isEmpty()) return
        loading.add(local)
        executor.execute { loadInBackground(local, ordered, gen) }
    }

    /** Отпечаток выбранного порядка провайдеров для последнего реалтайм-пересчёта. */
    private var lastConditionsFingerprint: String = ""

    /** Отпечаток применённого набора функций по каждому объекту. */
    private val lastObjectFingerprint = ConcurrentHashMap<String, String>()

    /**
     * Счётчик поколений на объект — свой, а не общий [generation].
     *
     * Общий счётчик пришлось бы поднимать при пересчёте одного объекта, и тогда
     * протухали бы загрузки всех остальных: игрок сменил биом → обслуживание
     * плащей всего сервера. Своё поколение на объект отбрасывает ровно одну
     * устаревшую загрузку.
     */
    private val objectGeneration = ConcurrentHashMap<String, Int>()

    /** Когда последний раз реально переоценивали условия объекта, мс. */
    private val lastReevaluatedAt = ConcurrentHashMap<String, Long>()

    /**
     * Как часто пересчитывать условия объекта с `when`, мс.
     *
     * Миксин зовёт [ensureLoading] каждый кадр. Проверять биом у всех в кадре
     * каждый кадр незачем: 20 игроков × 60 fps = 1200 чтений биома в секунду
     * ради данных, меняющихся раз в 20 тиков. 250 мс ≈ 5 тиков — глазом
     * незаметно, а работы в 12 раз меньше.
     *
     * Объекты без условий сюда не попадают вообще: их порядок измениться не
     * может, пересчитывать нечего.
     */
    private val RECHECK_INTERVAL_MS = 250L

    /** Частота пересчёта собственных условий локального игрока, мс. */
    private val LOCAL_RECHECK_INTERVAL_MS = 250L

    private var lastLocalRecheckedAt = 0L

    private fun nowMs() = System.nanoTime() / 1_000_000L

    /** Безопасная отправка события в шину (не падаем, если аддон упал). */
    private fun emit(event: CapeEvent) {
        try {
            CapeApiHolder.api.events.emit(event)
        } catch (_: Exception) {
        }
    }

    private fun stripDashes(uuid: String) = uuid.replace("-", "")
}
