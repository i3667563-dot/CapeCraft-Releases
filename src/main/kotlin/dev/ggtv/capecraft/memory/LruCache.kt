package dev.ggtv.capecraft.memory

import java.util.LinkedHashMap

/**
 * LRU-кэш с вытеснением по суммарной памяти (этап 5.2).
 *
 * Хранит пары ключ → значок, где у каждого элемента известен «вес» в байтах.
 * Когда суммарный вес превышает [maxBytes] (или после [put] вручную вызван
 * [evict]), удаляются самые давно не использованные элементы, пока не
 * вернёмся под лимит.
 *
 * Управление потокобезопасностью — на вызывающей стороне (клиентский рендер
 * идёт в один поток; при необходимости обернуть в synchronized).
 *
 * Намеренно без внешних зависимостей: LinkedHashMap + собственная
 * аккуратность с порядком доступа (accessOrder=true даёт LRU-порядок).
 */
class LruCache<K, V>(
    private val maxBytes: Long,
    /** Вернуть размер элемента в байтах (для учёта памяти). */
    private val sizeOf: (K, V) -> Long,
) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private var weight = 0L

    /** Текущий суммарный вес всех элементов, байт. */
    val size: Long get() = weight

    // accessOrder=true: обычный map[key] уже двигает ключ в конец (недавние).
    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        // Замена существующего ключа: сначала убрать старый вес.
        val old = map.remove(key)
        if (old != null) {
            weight -= sizeOf(key, old)
        }
        val w = sizeOf(key, value)
        map[key] = value
        weight += w
        evict()
    }

    fun remove(key: K): V? = map.remove(key)?.also { weight -= sizeOf(key, it) }

    fun clear() {
        map.clear()
        weight = 0
    }

    fun contains(key: K): Boolean = map.containsKey(key)

    /** Количество элементов. */
    val count: Int get() = map.size

    /** Ключи в LRU-порядке (самый старый первым). */
    val keys: Set<K> get() = map.keys

    /**
     * Выбросить элементы до тех пор, пока вес <= [maxBytes].
     * Выбрасываются самые давние (недавно не используемые).
     */
    fun evict() {
        if (map.isEmpty()) return
        val it = map.entries.iterator()
        while (weight > maxBytes && it.hasNext()) {
            val e = it.next()
            weight -= sizeOf(e.key, e.value)
            it.remove()
        }
    }
}
