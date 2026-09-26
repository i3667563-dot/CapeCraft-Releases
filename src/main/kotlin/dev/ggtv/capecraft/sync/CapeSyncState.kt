package dev.ggtv.capecraft.sync

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
 * @param enabled включена ли синхронизация (из конфига).
 * @param intervalTicks интервал между запросами.
 * @param timeoutTicks сколько ждать ответа.
 * @param requireServer true = не использовать локальный набор, пока сервер
 *        ни разу не ответил (для серверов, где плащи решает только админ).
 */
class CapeSyncState(
    val enabled: Boolean = true,
    val intervalTicks: Int = SyncProtocol.DEFAULT_INTERVAL_TICKS,
    val timeoutTicks: Int = SyncProtocol.DEFAULT_TIMEOUT_TICKS,
    val requireServer: Boolean = false,
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

    private var nextId: Int = 1
    private var pendingId: Int = 0
    private var pendingSinceTick: Int = 0
    private var lastRequestTick: Int = 0
    private var ticks: Int = 0

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
        }
        if (ticks - lastRequestTick < intervalTicks) return null
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
        pendingId = 0
        responsesAccepted++
        hasServerAnswer = true
        lastError = null
        val fp = fingerprintOf(response.providers)
        if (fp == fingerprint) {
            usingServerProviders = true
            return false
        }
        fingerprint = fp
        usingServerProviders = true
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
        pendingSinceTick = 0
        lastRequestTick = ticks
        hasServerAnswer = false
        usingServerProviders = false
        fingerprint = FINGERPRINT_UNSET
        lastError = null
        responsesAccepted = 0
        timedOut = 0
    }

    private fun send(): Int? {
        if (!enabled) return null
        val id = nextId++
        pendingId = id
        lastRequestId = id
        pendingSinceTick = ticks
        lastRequestTick = ticks
        return id
    }

    companion object {
        /** «Набор ещё ни разу не применялся» — не пустой набор, а его отсутствие. */
        const val FINGERPRINT_UNSET: String = "\u0000unset"

        /** Отпечаток пустого набора: сервер ответил «плащей нет». */
        const val FINGERPRINT_EMPTY_SERVER: String = ""

        /** Отпечаток набора: сортировка не нужна — порядок важен (это цепочка). */
        fun fingerprintOf(providers: List<ActiveCape>): String =
            providers.joinToString("\u0001") { "${it.kind.tag}\u0002${it.name}\u0002${it.primary}\u0002${it.extract}\u0002${it.priority}" }
    }
}
