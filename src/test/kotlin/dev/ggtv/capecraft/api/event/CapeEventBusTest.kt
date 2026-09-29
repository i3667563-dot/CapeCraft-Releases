package dev.ggtv.capecraft.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [CapeEventBus]: изоляция слушателей и отсутствие блокировки подписки.
 *
 * Слушатели — код аддонов, а аддон вправе делать в них что угодно, включая
 * долгий IO. Поэтому `emit` не должен держать монитор шины, пока зовёт их:
 * иначе один «спящий» слушатель заблокирует подписку с любого потока.
 */
class CapeEventBusTest {

    private val event = CapeEvent.CapeLoadFailed("uuid-1", "user", "причина")

    @Test
    fun `упавший слушатель не срывает остальных`() {
        val bus = CapeEventBus()
        val seen = AtomicInteger()
        bus.on(event.type) { error("аддон упал") }
        bus.on(event.type) { seen.incrementAndGet() }

        bus.emit(event)
        assertEquals(1, seen.get(), "второй слушатель не получил событие")
    }

    @Test
    fun `событие уходит только подписчикам своего типа`() {
        val bus = CapeEventBus()
        val loaded = AtomicInteger()
        val failed = AtomicInteger()
        bus.on(CapeEvent.Type.CAPE_LOADED) { loaded.incrementAndGet() }
        bus.on(CapeEvent.Type.CAPE_LOAD_FAILED) { failed.incrementAndGet() }

        bus.emit(event)
        assertEquals(0, loaded.get())
        assertEquals(1, failed.get())
    }

    @Test
    fun `подписка с другого потока не ждёт долгого слушателя`() {
        val bus = CapeEventBus()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        bus.on(event.type) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        val emitter = thread { bus.emit(event) }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "слушатель не начал работу")

        // Пока слушатель висит, подписка и отписка с чужого потока обязаны
        // пройти мгновенно. На старой реализации они ждали бы монитор.
        val subscribeMs = timeOf { bus.on(CapeEvent.Type.FRAME_BUILT) { } }
        val unsubscribeMs = timeOf { bus.on(event.type) { }.invoke() }

        release.countDown()
        emitter.join(10_000)

        assertTrue(subscribeMs < 1000, "подписка ждала $subscribeMs мс вместо мгновенной")
        assertTrue(unsubscribeMs < 1000, "отписка ждала $unsubscribeMs мс вместо мгновенной")
    }

    @Test
    fun `отписавшийся во время рассылки не ломает текущую рассылку`() {
        val bus = CapeEventBus()
        val second = AtomicInteger()
        var cancel: (() -> Unit)? = null
        cancel = bus.on(event.type) { cancel!!.invoke() } // отписка прямо в полёте
        bus.on(event.type) { second.incrementAndGet() }

        bus.emit(event)
        assertEquals(1, second.get(), "рассылка прервалась на отписке")
    }

    @Test
    fun `emit без подписчиков ничего не делает`() {
        val bus = CapeEventBus()
        bus.emit(event) // не должно бросать
    }

    private fun timeOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
