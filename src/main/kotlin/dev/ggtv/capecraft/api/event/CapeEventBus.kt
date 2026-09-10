package dev.ggtv.capecraft.api.event

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
    @Synchronized
    fun emit(event: CapeEvent) {
        val list = listeners[event.type] ?: return
        for (l in list.toList()) {
            try {
                l(event)
            } catch (e: Exception) {
                dev.ggtv.capecraft.CapeCraftClient.LOGGER.warn(
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