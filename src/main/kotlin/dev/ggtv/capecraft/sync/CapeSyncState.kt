package dev.ggtv.capecraft.sync

import kotlin.random.Random

/**
 * Состояние клиентской синхронизации: когда отправлять запрос, когда
 * считать его проваленным, применять ли ответ сервера.
 *
 * Чистая машина состояний без Minecraft API — вся логика опроса и
 * fallback'а тестируется обычными юнит-тестами. Фактическую отправку
 * делает версионно-зависимый `CapeSyncClient`, реагируя на возвращённый
 * [requestId] (null = отправлять нечего).
 *
 * ## Правила
 * - Один запрос в полёте: пока не пришёл ответ (или не вышел таймаут) —
 *   новый запрос не шлём, чтобы не завалить сервер пачками.
 * - Таймаут = [timeoutTicks] тиков без ответа; после него запрос считается
 *   проваленным, состояние переходит в «ждём следующего интервала».
 * - Ответ с чужим [SyncResponse.requestId] игнорируется: это либо опоздавший
 *   ответ на предыдущий запрос, либо мусор от другого клиента.
 * - Валидный ПУСТОЙ ответ (0 провайдеров) — это «плащей нет», а не «сервер
 *   не ответил»: клиент обязан перестать использовать локальный набор.
 * - Ответ без изменений ничего не перезагружает (см. fingerprint), чтобы не
 *   дёргать кэш плащей каждые пару секунд.
 *
 * ## Адаптивный интервал (backoff)
 *
 * Фиксированный опрос раз в [intervalTicks] — это ~0.5 запроса в секунду на
 * КАЖДОГО игрока. На 1000 игроках это 500 запросов/с на сервер, при том что
 * набор плащей меняет сам владелец, то есть несколько раз в день. Почти все
 * эти запросы возвращают неизменившийся ответ — чистая нагрузка.
 *
 * Поэтому интервал растёт при стабильном наборе (×2 за шаг, до
 * [maxIntervalTicks]) и падает обратно при любом изменении. Итог: в простое
 * клиент шлёт ~1 запрос в минуту вместо 30 — в 30 раз меньше работы на
 * сервере, — а смена плащей по-прежнему ловится за пару секунд, потому что
 * после самого изменения клиент снова уходит на базовый интервал.
 *
 * Дополнительно к интервалу добавляется детерминированный разброс
 * ±[SyncProtocol.BACKOFF_JITTER_PERCENT]%: после рестарта сервера у всех
 * игроков таймеры стартуют в один тик, и без разброса запросы приходят
 * синхронными «пачками» (thundering herd) вместо равномерной нагрузки.
 *
 * @param enabled включена ли синхронизация (из конфига).
 * @param intervalTicks базовый интервал между запросами (и интервал «горячего»
 *        режима — сразу после смены набора).
 * @param timeoutTicks сколько ждать ответа.
 * @param requireServer true = не использовать локальный набор, пока сервер
 *        ни разу не ответил (для серверов, где плащи решает только админ).
 * @param maxIntervalTicks потолок адаптивного интервала.
 * @param backoff false = всегда [intervalTicks], без замедления (для отладки).
 * @param jitterSeed сид генератора разброса. По умолчанию СЛУЧАЙНЫЙ и это
 *        обязательно: с общим фиксированным сидом все клиенты получали бы
 *        одну и ту же последовательность сдвигов, то есть herd не расходился
 *        бы никогда (просто сдвинулся бы на одну и ту же константу). Тесты
 *        передают сид явно, чтобы тайминги были воспроизводимы.
 */
class CapeSyncState(
    val enabled: Boolean = true,
    val intervalTicks: Int = SyncProtocol.DEFAULT_INTERVAL_TICKS,
    val timeoutTicks: Int = SyncProtocol.DEFAULT_TIMEOUT_TICKS,
    val requireServer: Boolean = false,
    val maxIntervalTicks: Int = SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS,
    val backoff: Boolean = true,
    val jitterSeed: Long = Random.nextLong(),
) {
    /** Идентификатор последнего отправленного запроса (0 = ничего не слали). */
    var lastRequestId: Int = 0
        private set

    /** Ответили ли хоть раз с начала сессии. */
    var hasServerAnswer: Boolean = false
        private set

    /** Был ли применён хотя бы один ответ (false = работает локальный fallback). */
    var usingServerProviders: Boolean = false
        private set

    /**
     * Активный отпечаток применённого набора провайдеров.
     *
     * Начинается с [FINGERPRINT_UNSET], а не с пустой строки: отпечаток
     * ПУСТОГО набора — это тоже `""`, и с таким стартовым значением первый
     * же ответ «плащей нет» выглядел бы как «ничего не изменилось» и молча
     * оставил бы локальный набор включённым.
     */
    var fingerprint: String = FINGERPRINT_UNSET
        private set

    /** Последняя ошибка протокола/таймаут (для `/cp status`). */
    var lastError: String? = null
        private set

    /** Счётчик успешных ответов (диагностика). */
    var responsesAccepted: Int = 0
        private set

    /**
     * Счётчик запросов, истёкших по таймауту. Клиент читает его ДО и ПОСЛЕ
     * [onTick]: рост означает «сервер только что перестал отвечать» — пора
     * применить fallback. Счётчик, а не колбэк, чтобы машина оставалась чистой.
     */
    var timedOut: Int = 0
        private set

    /**
     * Текущий базовый интервал между запросами (без джиттера).
     *
     * Равен [intervalTicks] в «горячем» режиме и растёт при стабильном наборе
     * до [maxIntervalTicks]. Именно его показывает `/cp status`, чтобы было
     * видно, во сколько раз клиент сэкономил нагрузку на сервер.
     */
    var currentIntervalTicks: Int = intervalTicks
        private set

    /** Ответов подряд без изменений набора (управляет замедлением). */
    var stableResponses: Int = 0
        private set

    /** Всего отправлено запросов за сессию (диагностика). */
    var requestsSent: Int = 0
        private set

    private var nextId: Int = 1
    private var pendingId: Int = 0
    private var lastAnsweredId: Int = 0
    private var pendingSinceTick: Int = 0
    private var lastRequestTick: Int = 0
    private var ticks: Int = 0

    /**
     * Тик, на который наступает следующий запрос.
     *
     * Считается ОДИН раз в [send] (а не пересчитывается каждый тик), иначе
     * джиттер «прыгал» бы под проверкой и интервал стал бы размытым.
     * Из-за [ticks] этот тик недостижим при переполнении Int — смена мира
     * делает [reset], так что на многолетней сессии точность не важна.
     */
    private var nextDueTick: Int = 0

    /** Генератор джиттера: LCG, детерминированный ради тестируемости. */
    private var rng: Long = jitterSeed

    /** Только что подключились к серверу: сброс и немедленный запрос. */
    fun onJoin(): Int? {
        reset()
        return send()
    }

    /** Выход из мира/сервера: всё забываем, возвращаемся к локальному набору. */
    fun onDisconnect() {
        reset()
    }

    /**
     * Тик клиента. Вернёт [requestId], если пора отправить запрос.
     *
     * @param inWorld есть ли игрок/мир (в меню запросы не шлём).
     * @param channelReady принимает ли клиент канал от сервера (мод есть и там).
     */
    fun onTick(inWorld: Boolean, channelReady: Boolean): Int? {
        ticks++
        if (!enabled || !inWorld || !channelReady) {
            // Вне игры/на сервере без мода: сбрасываем «ожидание», но НЕ
            // забываем hasServerAnswer — тот же сервер может вернуть мод
            // при следующем входе раньше первого ответа.
            pendingId = 0
            return null
        }
        if (pendingId != 0) {
            if (ticks - pendingSinceTick < timeoutTicks) return null
            lastError = "сервер не ответил за $timeoutTicks тиков"
            pendingId = 0
            timedOut++
            // Замедляемся и после таймаута: если сервер не отвечает (перегрузка,
            // рестарт, лаг), учащённые запросы только ухудшают ситуацию — их
            // тем больше, чем хуже серверу и так.
            growBackoff()
            reschedule()
        }
        if (ticks < nextDueTick) return null
        return send()
    }

    /**
     * Ручной запрос (`/cp sync`): уходит НЕМЕДЛЕННО, минуя интервал.
     *
     * Неизвестный/выключенный sync (null) отсекается, а вот висящий запрос
     * просто заменяется новым — старый всё равно отбросится по requestId.
     */
    fun requestNow(): Int? {
        if (!enabled) return null
        return send()
    }

    /**
     * Пришёл ответ. true — ответ принят и набор применился (или подтвердил
     * текущий, тогда false по «изменилось» — см. [changed]).
     */
    fun onResponse(response: SyncResponse): Boolean {
        if (!enabled) return false
        if (lastRequestId == 0 || response.requestId != lastRequestId) {
            lastError = "проигнорирован ответ с requestId=${response.requestId} (последний наш — $lastRequestId)"
            return false
        }
        // Переигровка/дубликат уже съеденного ответа: иначе повторный payload
        // удвоил бы счётчик стабильности и резко ускорил backoff.
        if (response.requestId == lastAnsweredId) {
            lastError = "повторный ответ с requestId=${response.requestId} проигнорирован"
            return false
        }
        lastAnsweredId = response.requestId
        pendingId = 0
        responsesAccepted++
        hasServerAnswer = true
        lastError = null
        val fp = fingerprintOf(response.providers)
        if (fp == fingerprint) {
            usingServerProviders = true
            stableResponses++
            // Набор не меняется — замедляемся. Именно этот путь и снимает
            // основную нагрузку: в простое запросы идут раз в минуту.
            if (stableResponses >= SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF) growBackoff()
            return false
        }
        fingerprint = fp
        usingServerProviders = true
        stableResponses = 0
        // Смена набора = «горячий» режим: возвращаем базовый интервал и
        // подтягиваем следующий запрос вперёд, чтобы быстро поймать
        // последующие изменения (игрок переодевает кап комплектом).
        if (backoff) {
            currentIntervalTicks = intervalTicks
            reschedule()
        }
        return true
    }

    /**
     * Мод сервера не найден (или он не ответил вовсе).
     *
     * - `requireServer = false` → возвращаемся к локальному набору: сбрасываем
     *   и флаг, и отпечаток, иначе следующий ответ сервера с тем же набором
     *   сочтётся «без изменений» и мы так и не вернёмся на серверный.
     * - `requireServer = true` → «плащей нет» (пустой набор), а не fallback.
     */
    fun onServerUnsupported() {
        pendingId = 0
        if (requireServer) {
            usingServerProviders = true
            fingerprint = FINGERPRINT_EMPTY_SERVER
        } else {
            usingServerProviders = false
            fingerprint = FINGERPRINT_UNSET
        }
    }

    /** Сброс всего состояния (вход/выход из мира). */
    fun reset() {
        lastRequestId = 0
        pendingId = 0
        lastAnsweredId = 0
        pendingSinceTick = 0
        lastRequestTick = ticks
        hasServerAnswer = false
        usingServerProviders = false
        fingerprint = FINGERPRINT_UNSET
        lastError = null
        responsesAccepted = 0
        timedOut = 0
        stableResponses = 0
        requestsSent = 0
        currentIntervalTicks = intervalTicks
        // «Пора прямо сейчас»: первый запрос после входа должен уйти сразу,
        // а не через интервал. onJoin() отправляет его сразу же.
        nextDueTick = ticks
        rng = jitterSeed
    }

    private fun send(): Int? {
        if (!enabled) return null
        val id = nextId++
        pendingId = id
        lastRequestId = id
        pendingSinceTick = ticks
        lastRequestTick = ticks
        requestsSent++
        reschedule()
        return id
    }

    /**
     * Пересчитать момент следующего запроса (базовый интервал + джиттер).
     *
     * Отсчёт от [lastRequestTick], а НЕ от текущего тика: интервал — это
     * ограничение частоты, то есть «не чаще, чем раз в N тиков ПОСЛЕ
     * ОТПРАВКИ». Если считать от момента обнаружения таймаута, то ретрай
     * сдвигался бы на величину задержки обнаружения (а она тем больше, чем
     * хуже сервер) — и мы бы тихо разъехались со старой каденцией.
     */
    private fun reschedule() {
        nextDueTick = lastRequestTick + currentIntervalTicks + jitter()
    }

    /**
     * Замедлить опрос вдвое, но не выше [maxIntervalTicks].
     *
     * Без [backoff] (или если уже у потолка) интервал не трогаем — тогда
     * поведение ровно как было до адаптивного режима.
     */
    private fun growBackoff() {
        if (!backoff) return
        if (currentIntervalTicks >= maxIntervalTicks) return
        currentIntervalTicks = (currentIntervalTicks * 2).coerceAtMost(maxIntervalTicks)
    }

    /**
     * Детерминированный разброс интервала: от `0` до `+2 ×
     * [SyncProtocol.BACKOFF_JITTER_PERCENT]`% от текущего интервала.
     *
     * Смещение только вверх, и это осознанно: базовый интервал — ограничение
     * СКОРОСТИ, и случайный «ранний» запрос превращал бы его в rate-limit,
     * который срабатывает по таймеру, а не по воле клиента. Разброс вверх
     * раскладывает клиентов по тикам ровно так же, как двусторонний.
     *
     * LCG, а не [kotlin.random.Random], чтобы тесты были воспроизводимы:
     * при одном и том же сценарии вызовов получаем одни и те же тайминги.
     *
     * Применяется ТОЛЬКО когда интервал уже вырос ([growBackoff]). В горячем
     * режиме (смена набора, первые секунды после входа) опрос остаётся ровно
     * [intervalTicks]: там нужна предсказуемая скорость реакции, а herd всё
     * равно рассасывается за несколько секунд, как только интервал вырастет.
     */
    private fun jitter(): Int {
        if (!backoff) return 0
        if (currentIntervalTicks <= intervalTicks) return 0
        val span = currentIntervalTicks * SyncProtocol.BACKOFF_JITTER_PERCENT / 100
        if (span <= 0) return 0
        rng = rng * LCG_MULTIPLIER + LCG_INCREMENT
        return ((rng ushr 1) % (2L * span + 1L)).toInt()
    }

    companion object {
        /** «Набор ещё ни разу не применялся» — не пустой набор, а его отсутствие. */
        const val FINGERPRINT_UNSET: String = "\u0000unset"

        /** Отпечаток пустого набора: сервер ответил «плащей нет». */
        const val FINGERPRINT_EMPTY_SERVER: String = ""

        /** Константы LCG (Numerical Recipes): 64-битный, свой стартовый сид. */
        private const val LCG_MULTIPLIER: Long = 6364136223846793005L
        private const val LCG_INCREMENT: Long = 1442695040888963407L

        /** Отпечаток набора: сортировка не нужна — порядок важен (это цепочка). */
        fun fingerprintOf(providers: List<ActiveCape>): String =
            providers.joinToString("\u0001") { "${it.kind.tag}\u0002${it.name}\u0002${it.primary}\u0002${it.extract}\u0002${it.priority}" }
    }
}
