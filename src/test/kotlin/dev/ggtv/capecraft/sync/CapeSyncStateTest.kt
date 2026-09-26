package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Машина состояний опроса: интервал, «один запрос в полёте», таймаут и
 * ключевое различие «сервер ответил „плащей нет“» vs «сервер молчит».
 */
class CapeSyncStateTest {

    private fun cape(name: String, priority: Int = 0) =
        ActiveCape(name, ActiveCape.Kind.URL, "https://e.com/$name.png", priority = priority)

    private fun tick(state: CapeSyncState, n: Int, inWorld: Boolean = true, ready: Boolean = true): List<Int> =
        (1..n).mapNotNull { state.onTick(inWorld, ready) }

    @Test
    fun `disabled state never asks`() {
        val state = CapeSyncState(enabled = false)
        assertNull(state.onTick(true, true))
        assertNull(state.requestNow())
        assertEquals(0, state.lastRequestId)
    }

    @Test
    fun `join asks immediately`() {
        val state = CapeSyncState(intervalTicks = 100)
        assertEquals(1, state.onJoin())
        assertEquals(1, state.lastRequestId)
    }

    @Test
    fun `no second request while one is in flight`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        assertEquals(1, state.onJoin())
        // Даже спустя 3 интервала тикать нельзя: первый запрос ещё не отвечен.
        assertEquals(emptyList(), tick(state, 60))
    }

    @Test
    fun `reply clears the pending request and re-arms the interval`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        val id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, emptyList())))
        // Сразу после ответа интервал ещё не выдержан.
        assertNull(state.onTick(true, true))
        assertEquals(2, tick(state, 20).single())
    }

    @Test
    fun `timeout releases the pending request and counts itself`() {
        // backoff = false: тест фиксирует базовую каденцию, а не замедление
        // после таймаута (см. CapeSyncStateBackoffTest).
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 30, backoff = false)
        state.onJoin()
        val ids = tick(state, 40)
        assertEquals(2, ids.single())
        assertEquals(1, state.timedOut)
        assertNotNull(state.lastError)
    }

    @Test
    fun `unchanged provider set does not ask for a reload`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        val id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, listOf(cape("a"), cape("b")))))
        assertTrue(state.usingServerProviders)

        val id2 = tick(state, 20).single()
        assertFalse(state.onResponse(SyncResponse(id2, listOf(cape("a"), cape("b")))))
        assertEquals(2, state.responsesAccepted)
    }

    @Test
    fun `changed provider set asks for a reload`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        val id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, listOf(cape("a")))))
        val id2 = tick(state, 20).single()
        assertTrue(state.onResponse(SyncResponse(id2, listOf(cape("a"), cape("b")))))
    }

    @Test
    fun `provider order is part of the fingerprint`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        var id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, listOf(cape("a"), cape("b")))))

        id = tick(state, 20).single()
        assertFalse(state.onResponse(SyncResponse(id, listOf(cape("a"), cape("b")))), "тот же набор")

        id = tick(state, 20).single()
        assertTrue(state.onResponse(SyncResponse(id, listOf(cape("b"), cape("a")))), "порядок важен")
    }

    @Test
    fun `priority is part of the fingerprint`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        val id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, listOf(cape("a", priority = 0)))))
        val id2 = tick(state, 20).single()
        assertTrue(state.onResponse(SyncResponse(id2, listOf(cape("a", priority = 5)))))
    }

    @Test
    fun `an empty set is not the same as an unset one`() {
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 100)
        assertEquals(CapeSyncState.FINGERPRINT_UNSET, state.fingerprint)
        val id = state.onJoin()!!
        assertTrue(
            state.onResponse(SyncResponse(id, emptyList())),
            "первый пустой ответ обязан примениться, иначе локальный набор останется",
        )
        assertEquals(CapeSyncState.FINGERPRINT_EMPTY_SERVER, state.fingerprint)
    }

    @Test
    fun `stale reply with a foreign requestId is ignored`() {
        val state = CapeSyncState(intervalTicks = 20)
        val id = state.onJoin()!!
        assertFalse(state.onResponse(SyncResponse(id + 100, listOf(cape("evil")))))
        assertFalse(state.hasServerAnswer)
        assertFalse(state.usingServerProviders)
        assertEquals(0, state.responsesAccepted)
    }

    @Test
    fun `empty reply is a no-capes answer and marks the set authoritative`() {
        val state = CapeSyncState(intervalTicks = 20)
        val id = state.onJoin()!!
        assertTrue(state.onResponse(SyncResponse(id, emptyList())))
        assertTrue(state.usingServerProviders)
        assertTrue(state.hasServerAnswer)
    }

    @Test
    fun `missing server without requireServer keeps the local set`() {
        val state = CapeSyncState(intervalTicks = 20, requireServer = false)
        val id = state.onJoin()!!
        state.onResponse(SyncResponse(id, listOf(cape("a"))))
        assertTrue(state.usingServerProviders)
        state.onServerUnsupported()
        assertFalse(state.usingServerProviders, "сервер исчез — возвращаемся к локальному набору")
    }

    @Test
    fun `a server reply after a fallback is applied again`() {
        val state = CapeSyncState(intervalTicks = 20, requireServer = false)
        val id = state.onJoin()!!
        state.onResponse(SyncResponse(id, listOf(cape("a"))))
        state.onServerUnsupported()
        // Тот же набор, что и раньше: после отката к локальному он обязан
        // снова переключить реестр на серверный.
        val id2 = tick(state, 20).single()
        assertTrue(state.onResponse(SyncResponse(id2, listOf(cape("a")))))
        assertTrue(state.usingServerProviders)
    }

    @Test
    fun `missing server with requireServer means no capes`() {
        val state = CapeSyncState(intervalTicks = 20, requireServer = true)
        state.onServerUnsupported()
        assertTrue(state.usingServerProviders)
        assertEquals(CapeSyncState.FINGERPRINT_EMPTY_SERVER, state.fingerprint)
    }

    @Test
    fun `unsupported server does not spam the fingerprint`() {
        val state = CapeSyncState(intervalTicks = 20, requireServer = true)
        repeat(100) { state.onServerUnsupported() }
        assertEquals(CapeSyncState.FINGERPRINT_EMPTY_SERVER, state.fingerprint)
    }

    @Test
    fun `out of world and unsupported server ask nothing`() {
        val state = CapeSyncState(intervalTicks = 20)
        assertEquals(emptyList(), tick(state, 50, inWorld = false))
        assertEquals(emptyList(), tick(state, 50, ready = false))
        assertEquals(0, state.lastRequestId)
    }

    @Test
    fun `leaving a world without a server answers nothing`() {
        val state = CapeSyncState(intervalTicks = 20)
        state.onJoin()
        tick(state, 100, ready = false)
        assertEquals(0, state.timedOut, "обрыв канала — не таймаут")
    }

    @Test
    fun `disconnect wipes the session but keeps the tick clock`() {
        val state = CapeSyncState(intervalTicks = 20)
        state.onJoin()
        state.onResponse(SyncResponse(1, listOf(cape("a"))))
        state.onDisconnect()
        assertFalse(state.hasServerAnswer)
        assertFalse(state.usingServerProviders)
        assertEquals(CapeSyncState.FINGERPRINT_UNSET, state.fingerprint)
        assertEquals(0, state.responsesAccepted)
    }

    @Test
    fun `requestNow bypasses the interval`() {
        val state = CapeSyncState(intervalTicks = 1000)
        val id = state.onJoin()!!
        val forced = state.requestNow()!!
        assertTrue(forced > id, "ручной запрос должен получить свежий id")
    }

    @Test
    fun `requestNow replaces a pending request instead of blocking on it`() {
        val state = CapeSyncState(intervalTicks = 1000)
        state.onJoin()
        val forced = state.requestNow()!!
        // Старый ответ больше не подходит, новый — подходит.
        assertFalse(state.onResponse(SyncResponse(forced - 1, listOf(cape("a")))))
        assertTrue(state.onResponse(SyncResponse(forced, listOf(cape("a")))))
    }

    @Test
    fun `request ids are unique and never reused after a reset`() {
        val state = CapeSyncState(intervalTicks = 20)
        val ids = generateSequence { state.onJoin() }.take(50).toList()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `after a timeout the retry still waits for the interval`() {
        // backoff = false: здесь важна базовая каденция повтора.
        // С backoff=true повтор после таймаута ждал бы удвоенный интервал.
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 5, backoff = false)
        state.onJoin()
        // Таймаут (5 тиков) истёк раньше интервала (20): запрос рассыпался,
        // но следующий не отправляется мгновенно — иначе это цикл запросов
        // к серверу, который и не отвечает.
        assertEquals(emptyList(), tick(state, 5))
        assertEquals(1, state.timedOut)
        assertEquals(2, tick(state, 15).single())
    }

    @Test
    fun `a timeout is counted once per request, not once per tick`() {
        // backoff = false: считаем таймауты на базовой каденции, иначе
        // окно в 100 тиков перестаёт накрывать целое число попыток.
        val state = CapeSyncState(intervalTicks = 20, timeoutTicks = 5, backoff = false)
        val sent = mutableListOf(state.onJoin()!!)
        val timeouts = mutableListOf<Int>()
        for (t in 1..100) {
            val before = state.timedOut
            val id = state.onTick(true, true)
            if (id != null) sent += id
            if (state.timedOut > before) timeouts += t
        }
        assertEquals(
            sent.size - 1,
            timeouts.size,
            "каждый завершившийся без ответа запрос даёт ровно один таймаут, последний ещё в полёте",
        )
        assertTrue(
            timeouts.zipWithNext().all { (a, b) -> b - a >= state.timeoutTicks },
            "таймауты не сливаются: $timeouts",
        )
        assertTrue(
            sent.zipWithNext().all { (a, b) -> b > a },
            "идентификаторы запросов строго растут",
        )
    }
}
