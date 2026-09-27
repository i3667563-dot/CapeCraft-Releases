package dev.ggtv.capecraft.api.event

import dev.ggtv.capecraft.CapeCraftLog

/**
 * Шина событий жизненного цикла плаща для аддонов.
 *
 * Слушатели — лямбды, без выделения под каждый тип (один набор ключей).
 * Listeners держатся сильно (не WeakReference): аддон живёт весь запуск игры.
 *
 * Все события вызываются с фонового воркер-потока реестра (подробности
 * конкретные в [CapeEvent]). Не блокировать игровой/рендер-поток там негде —
 * события приходят из фоновой загрузки. Исключения слушателя НЕ роняют
 * загрузку: логируется и пропускается (мод не падает из-за аддона).
 */
class CapeEventBus {
    private val listeners = mutableMapOf<CapeEvent.Type, MutableList<(CapeEvent) -> Unit>>()

    /** Подписаться на событие [type]. Возвращает функцию отписки. */
    @Synchronized
    fun on(type: CapeEvent.Type, listener: (CapeEvent) -> Unit): () -> Unit {
        listeners.getOrPut(type) { mutableListOf() }.add(listener)
        return { cancel(type, listener) }
    }

    /** Разослать событие всем подписчикам [type]. Вызывается только модом. */
    fun emit(event: CapeEvent) {
        // Снимок под монитором, вызовы — вне. Иначе один долгий слушатель
        // (сеть, диск) держал бы на этом мониторе и подписку с другого потока:
        // emit ждал бы сам себя, а аддон не смог бы ни отписаться, ни
        // подписаться. Реентрантность Java-монитора от самоблокировки не
        // спасает — мешает именно чужая работа под локом.
        val snapshot = synchronized(this) { listeners[event.type]?.toList() } ?: return
        for (l in snapshot) {
            try {
                l(event)
            } catch (e: Exception) {
                CapeCraftLog.LOGGER.warn(
                    "CapeCraft: аддон-слушатель ${event.type} упал: ${e.message}", e,
                )
            }
        }
    }

    @Synchronized
    private fun cancel(type: CapeEvent.Type, listener: (CapeEvent) -> Unit) {
        listeners[type]?.remove(listener)
    }
}