package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Адаптивный интервал опроса (backoff) — то, что снимает нагрузку с сервера.
 *
 * Проверяем не «интервал растёт» (это тривиально), а свойства, из-за которых
 * backoff можно включать по умолчанию: смена плащей ловится так же быстро,
 * как раньше; базовый интервал НЕ нарушается (никакого случайного
 * rate-limit); горячий режим остаётся с точной периодичностью ради
 * совместимости со старым поведением; таймауты не превращаются в частые
 * запросы к умирающему серверу.
 */
class CapeSyncStateBackoffTest {

    private fun cape(name: String) =
        ActiveCape(name, ActiveCape.Kind.URL, "https://e.com/$name.png", priority = 0)

    /** Ответить на текущий запрос серверным набором. */
    private fun answer(state: CapeSyncState, providers: List<ActiveCape>): Boolean =
        state.onResponse(SyncResponse(state.lastRequestId, providers))

    /** Тикировать до следующего запроса; вернуть номер тика или -1. */
    private fun nextRequestAt(state: CapeSyncState, maxTicks: Int = 8000): Int {
        repeat(maxTicks) { i ->
            if (state.onTick(inWorld = true, channelReady = true) != null) return i + 1
        }
        return -1
    }

    /** Войти, ответить на первый запрос, дальше тикать с ответами. */
    private fun joined(
        intervalTicks: Int = 40,
        backoff: Boolean = true,
        jitterSeed: Long = 42L,
    ): CapeSyncState {
        val state = CapeSyncState(
            intervalTicks = intervalTicks,
            timeoutTicks = 100,
            backoff = backoff,
            jitterSeed = jitterSeed,
        )
        state.onJoin()
        answer(state, listOf(cape("a")))
        return state
    }

    /**
     * Тикнуть до следующего запроса и ответить на него [providers].
     * Возвращает (тик, был ли ответ принят как ИЗМЕНЕНИЕ набора).
     */
    private fun nextCycle(state: CapeSyncState, providers: List<ActiveCape>): Pair<Int, Boolean> {
        val at = nextRequestAt(state)
        check(at >= 0) { "за 8000 тиков не последовало ни одного запроса" }
        val changed = state.onResponse(SyncResponse(state.lastRequestId, providers))
        return at to changed
    }

    /** Прогнать [rounds] циклов «ответил → замер интервала». */
    private fun measureIntervals(state: CapeSyncState, rounds: Int): List<Int> {
        val out = mutableListOf<Int>()
        repeat(rounds) {
            val at = nextRequestAt(state)
            if (at < 0) error("за 8000 тиков не последовало ни одного запроса")
            out += at
            answer(state, listOf(cape("a")))
        }
        return out
    }

    // ────────────────────────────── рост интервала ──────────────────────────────

    @Test
    fun `backoff is enabled by default`() {
        assertTrue(CapeSyncState().backoff, "backoff обязан быть включён по умолчанию")
    }

    @Test
    fun `interval stays at base until responses stop changing`() {
        val state = joined()
        repeat(SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF - 1) { measureIntervals(state, 1) }
        assertEquals(SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF - 1, state.stableResponses)
        assertEquals(
            40, state.currentIntervalTicks,
            "раньше порога замедляться нельзя: смена экипировки в первые секунды должна ловиться быстро",
        )
    }

    @Test
    fun `interval doubles once responses stop changing`() {
        val state = joined()
        repeat(SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF) { measureIntervals(state, 1) }
        assertTrue(
            state.currentIntervalTicks > 40,
            "после ${SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF} стабильных ответов " +
                "интервал обязан вырасти, получено ${state.currentIntervalTicks}",
        )
    }

    @Test
    fun `interval reaches the cap and stays there`() {
        val state = joined(intervalTicks = 40)
        repeat(40) { measureIntervals(state, 1) }
        assertEquals(
            SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS, state.currentIntervalTicks,
            "интервал обязан упираться в потолок, а не расти бесконечно",
        )
    }

    @Test
    fun `interval above maximum is not silently reduced`() {
        // Интервал в конфиге больше потолка backoff: это НЕ должно тихо менять
        // заданное пользователем поведение.
        val state = CapeSyncState(intervalTicks = 5000, maxIntervalTicks = 100, backoff = false)
        state.onJoin()
        assertEquals(5000, state.currentIntervalTicks)
    }

    // ────────────────────────────── горячий режим без джиттера ──────────────────────────────

    @Test
    fun `hot mode keeps the exact old cadence`() {
        // Совместимость: пока набор меняется, интервал остаётся РОВНО
        // intervalTicks — без джиттера и без сдвигов. Старые тесты и старая
        // логика полагаются на эту периодичность.
        val state = joined(intervalTicks = 40)
        repeat(SyncProtocol.STABLE_RESPONSES_BEFORE_BACKOFF - 1) { measureIntervals(state, 1) }
        assertEquals(40, state.currentIntervalTicks, "до роста интервала джиттер применяться не должен")
        repeat(1) { measureIntervals(state, 1) }
        assertTrue(state.currentIntervalTicks > 40, "на пороге интервал обязан вырасти")
    }

    // ────────────────────────────── сброс при смене ──────────────────────────────

    @Test
    fun `change returns to the fast interval`() {
        val state = joined()
        repeat(20) { measureIntervals(state, 1) }
        assertTrue(state.currentIntervalTicks > 40, "интервал должен был замедлиться")

        // Сервер сменил набор между запросами: новое приходит ответом на СЛЕДУЮЩИЙ
        // запрос (повторный ответ на уже съеденный id отбрасывается — это другой тест).
        val (_, changed) = nextCycle(state, listOf(cape("a"), cape("b")))
        assertTrue(changed, "новый набор обязан считаться изменением")
        assertEquals(40, state.currentIntervalTicks, "после смены набора — снова горячий режим")
        assertEquals(0, state.stableResponses)
    }

    @Test
    fun `change pulls the next request forward`() {
        val state = joined()
        repeat(20) { measureIntervals(state, 1) }
        val slow = state.currentIntervalTicks
        assertTrue(slow > 40, "интервал должен был замедлиться")

        val (_, changed) = nextCycle(state, listOf(cape("a"), cape("b")))
        assertTrue(changed, "смена набора обязана считаться изменением")
        assertEquals(40, state.currentIntervalTicks, "смена обязана вернуть горячий режим")

        val at = nextRequestAt(state)
        assertTrue(
            at in 1..slow,
            "смена набора обязана подтянуть запрос вперёд: ждали $at тиков при интервале $slow",
        )
        assertTrue(
            at <= 40 + 8,
            "ожидали запрос почти сразу (горячий режим, джиттера нет), а ждали $at тиков",
        )
    }

    @Test
    fun `duplicate response does not advance the backoff`() {
        val state = joined()
        measureIntervals(state, 1)
        val before = state.stableResponses
        val first = state.lastRequestId
        state.onResponse(SyncResponse(first, listOf(cape("a"))))
        assertEquals(before, state.stableResponses, "повторный ответ не должен ускорять замедление")
    }

    // ────────────────────────────── таймаут ──────────────────────────────

    @Test
    fun `timeout slows the poll down`() {
        // Сервер не отвечает: дёргать его чаще — только хуже. Но и молчать
        // совсем нельзя, иначе клиент не заметит, когда сервер ожил.
        val state = CapeSyncState(intervalTicks = 40, timeoutTicks = 100)
        state.onJoin()
        repeat(100) { state.onTick(true, true) }
        assertEquals(1, state.timedOut, "ответ не пришёл — должен быть ровно один таймаут")
        assertTrue(
            state.currentIntervalTicks > 40,
            "после таймаута опрос обязан замедлиться, получено ${state.currentIntervalTicks}",
        )
        assertTrue(
            state.currentIntervalTicks <= SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS,
            "замедление после таймаута обязано быть ограничено потолком",
        )
    }

    // ────────────────────────────── джиттер ──────────────────────────────

    @Test
    fun `jitter never fires before the base interval`() {
        // Критично: джиттер не имеет права превращаться в случайный rate-limit.
        // nextRequestAt возвращает ожидание С МОМЕНТА отправки, то есть сам
        // интервал — его и проверяем (разность двух интервалов — это разница
        // джиттера, а не интервал).
        for (interval in intArrayOf(20, 40, 100)) {
            val state = joined(intervalTicks = interval)
            repeat(80) {
                val wait = nextRequestAt(state)
                assertTrue(
                    wait >= interval,
                    "интервал=$interval: запрос через $wait тиков — чаще базового нельзя",
                )
                answer(state, listOf(cape("a")))
            }
        }
    }

    @Test
    fun `jitter keeps the interval inside a bounded band`() {
        val state = joined(intervalTicks = 40)
        repeat(30) { measureIntervals(state, 1) } // дорасти до потолка
        val base = state.currentIntervalTicks
        assertEquals(SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS, base)
        val max = base + 2 * (base * SyncProtocol.BACKOFF_JITTER_PERCENT / 100)

        val waits = (1..40).map {
            val w = nextRequestAt(state)
            answer(state, listOf(cape("a")))
            w
        }
        assertTrue(
            waits.all { it in base..max },
            "джиттер обязан держать интервал в [$base, $max], получили $waits",
        )
        assertTrue(
            waits.distinct().size > 1,
            "джиттер обязан реально разносить клиентов по тикам, а не быть константой: $waits",
        )
    }

    @Test
    fun `jitter is deterministic for a given seed`() {
        // Один и тот же сид + один и тот же сценарий → одна и та же
        // последовательность (LCG, а не Random): иначе баги ловятся случайно.
        assertEquals(joined(jitterSeed = 7L).let { s -> measureIntervals(s, 30) },
                     joined(jitterSeed = 7L).let { s -> measureIntervals(s, 30) },
                     "при заданном сиде джиттер обязан быть воспроизводимым")
        assertTrue(
            joined(jitterSeed = 1L).let { s -> measureIntervals(s, 30) } !=
                joined(jitterSeed = 2L).let { s -> measureIntervals(s, 30) },
            "разные сиды обязаны давать разные тайминги",
        )
    }

    @Test
    fun `jitter spreads clients apart`() {
        // Два клиента с РАЗНЫМИ сидами (как в проде, где сид случайный):
        // их разрыв обязан со временем гулять. С ОДНИМ сидом разрыв остаётся
        // постоянным — это общий сдвиг, а не разброс (проверяет
        // `same seed keeps clients in lockstep`).
        fun run(seed: Long): List<Long> {
            val state = joined(jitterSeed = seed)
            var absolute = 0L
            val out = mutableListOf<Long>()
            repeat(25) {
                absolute += nextRequestAt(state)
                out += absolute
                answer(state, listOf(cape("a")))
            }
            return out
        }
        val gaps = run(1L).zip(run(2L)).map { (a, b) -> b - a }
        assertTrue(
            gaps.distinct().size > 1,
            "разрыв между клиентами обязан гулять, а он постоянен: $gaps",
        )
    }

    @Test
    fun `same seed keeps clients in lockstep`() {
        // Обратная сторона: именно поэтому сид по умолчанию случайный.
        // Одинаковый сид = одинаковая последовательность сдвигов = herd.
        fun run(): List<Long> {
            val state = joined(jitterSeed = 42L)
            var absolute = 0L
            return (1..25).map {
                absolute += nextRequestAt(state)
                answer(state, listOf(cape("a")))
                absolute
            }
        }
        val gaps = run().zip(run()).map { (a, b) -> b - a }
        assertTrue(
            gaps.all { it == 0L },
            "при одном сиде разрыв обязан оставаться постоянным: $gaps",
        )
    }

    @Test
    fun `default seed differs between clients`() {
        // ГЛАВНОЕ свойство против herd: без общего сида разброс разный.
        // С фиксированным сидом все клиенты сдвинулись бы одинаково.
        val a = CapeSyncState(intervalTicks = 40, timeoutTicks = 100)
        val b = CapeSyncState(intervalTicks = 40, timeoutTicks = 100)
        assertTrue(
            a.jitterSeed != b.jitterSeed,
            "сид по умолчанию обязан быть случайным на каждый экземпляр",
        )
    }

    // ────────────────────────────── выключение и сброс ──────────────────────────────

    @Test
    fun `backoff off keeps a fixed interval`() {
        val state = joined(backoff = false)
        repeat(30) { measureIntervals(state, 1) }
        assertEquals(40, state.currentIntervalTicks, "с backoff=false поведение должно быть как раньше")
    }

    @Test
    fun `reset restores the base interval`() {
        val state = joined()
        repeat(20) { measureIntervals(state, 1) }
        assertTrue(state.currentIntervalTicks > 40)
        state.onDisconnect()
        assertEquals(40, state.currentIntervalTicks, "вход/выход обязаны возвращать горячий режим")
        assertEquals(0, state.stableResponses)
        assertEquals(0, state.requestsSent)
    }

    @Test
    fun `first request after join is immediate`() {
        val state = CapeSyncState(intervalTicks = 40, timeoutTicks = 100)
        assertEquals(1, state.onJoin(), "после входа первый запрос уходит сразу, а не через интервал")
    }

    // ────────────────────────────── главная цель ──────────────────────────────

    @Test
    fun `backoff cuts steady-state request count by an order of magnitude`() {
        // Ради этого backoff и делается: 10 минут игры (12 000 тиков) при
        // неизменном наборе плащей.
        val ticks = 20 * 60 * 10
        val set = listOf(cape("a"))

        val plain = CapeSyncState(intervalTicks = 40, timeoutTicks = 100, backoff = false)
        plain.onJoin()
        plain.onResponse(SyncResponse(plain.lastRequestId, set))
        val plainCount = countRequests(plain, ticks, set)

        val smart = joined()
        val smartCount = countRequests(smart, ticks, set)

        assertTrue(
            plainCount in 299..300,
            "фиксированные 2 секунды = ~300 запросов за 10 минут, получили $plainCount",
        )
        assertTrue(
            smartCount < plainCount / 10,
            "backoff должен срезать число запросов минимум в 10 раз: было $plainCount, стало $smartCount",
        )
    }

    @Test
    fun `steady state keeps answering but rarely`() {
        val state = joined()
        // Греемся до потолка явным циклом, а не «отбросить N замеров»:
        // число раунгов разогрева — деталь реализации, а не контракт.
        while (state.currentIntervalTicks < SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS) {
            measureIntervals(state, 1)
        }
        // Рост интервала применяется к СЛЕДУЮЩЕЙ отправке, поэтому уже
        // запланированный запрос ещё отработает по старому (640). Один
        // лишний цикл это съедает — дальше меряем честный потолок.
        measureIntervals(state, 1)
        val waits = measureIntervals(state, 6)
        assertTrue(
            waits.all { it >= SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS },
            "в простое интервал не может стать меньше потолка: $waits",
        )
    }

    @Test
    fun `backoff still reacts to a change quickly`() {
        // Обратная сторона: экономия не должна ломать главную функцию —
        // смену плаща игрок должен увидеть быстро, а не через минуту.
        val state = joined()
        repeat(600) { state.onTick(true, true) }

        // Сервер сменил набор «между» запросов — как только дойдёт запрос,
        // клиент обязан получить новый набор.
        var seenNew = false
        repeat(1200) {
            val id = state.onTick(true, true) ?: return@repeat
            if (state.onResponse(SyncResponse(id, listOf(cape("a"), cape("new"))))) seenNew = true
        }
        assertTrue(seenNew, "смена набора обязана быть замечена")
    }

    private fun countRequests(state: CapeSyncState, ticks: Int, providers: List<ActiveCape>): Int {
        var sent = 0
        repeat(ticks) {
            val id = state.onTick(inWorld = true, channelReady = true)
            if (id != null) {
                sent++
                state.onResponse(SyncResponse(id, providers))
            }
        }
        return sent
    }
}
