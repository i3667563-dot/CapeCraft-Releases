package dev.ggtv.capecraft.sync

/**
 * Кэш байтов картинок на клиенте, по хэшу.
 *
 * ## Две разные вещи в одном классе
 *
 * [get] — то, что **пришло** от других: собрано из чанков, проверено по хэшу и
 * уже никто не переделывает.
 *
 * [owned] — то, что **моё**: локальный файл, который я сам посчитал и держу, чтобы
 * отдать его тем, кто меня видел.
 *
 * Разделено намеренно. В одну карту их класть нельзя: тогда объявленный мной
 * плащ и полученный мной плащ отличались бы только тем, кто туда положил байты,
 * и любой забытый вызов [put] молча превратил бы чужую картинку в «мою».
 *
 * ## Границы
 *
 * [maxBytes] на весь кэш, вытеснение по давно не спрашивали. Объём одной картинки
 * ограничен [SyncProtocol.MAX_IMAGE_BYTES], число одновременных загрузок — тоже,
 * так что единственный способ упереться — обслуживать очень много разных
 * плащей; тогда самые старые просто перекачиваются заново.
 */
class NetImageStore(
    private val maxBytes: Int = 64 * 1024 * 1024,
) {
    private val lock = Any()

    /** Принятые по сети картинки: hex-хэш → байты, в порядке обращения. */
    private val received = LinkedHashMap<String, ByteArray>()

    /** Хэш → когда последний раз просили. */
    private val lastUsed = LinkedHashMap<String, Long>()

    /** Мои локальные картинки: hex-хэш → байты. */
    private val owned = LinkedHashMap<String, ByteArray>()

    private var usedBytes = 0
    private var clock = 0L

    /**
     * Положить картинку, полученную по сети.
     *
     * Хэш уже сверен при сборке чанков ([ImageAssembler]), здесь ключ — это
     * лишь индекс; пересчитывать второй раз незачем.
     */
    fun put(hash: String, bytes: ByteArray) = synchronized(lock) {
        if (bytes.size > SyncProtocol.MAX_IMAGE_BYTES) return@synchronized
        val old = received.put(hash, bytes)
        if (old != null) usedBytes -= old.size
        usedBytes += bytes.size
        lastUsed[hash] = ++clock
        evict()
    }

    /** Байты полученной картинки или `null`, если её нет или она вытеснена. */
    fun get(hash: String): ByteArray? = synchronized(lock) {
        if (received.containsKey(hash)) lastUsed[hash] = ++clock
        received[hash]
    }

    fun has(hash: String): Boolean = synchronized(lock) { received.containsKey(hash) }

    /** Запомнить свою локальную картинку, чтобы её потом отдать другим. */
    fun putOwned(hash: String, bytes: ByteArray) = synchronized(lock) {
        owned[hash] = bytes
    }

    /** Байты своей картинки — чтобы отдать их чанками. */
    fun owned(hash: String): ByteArray? = synchronized(lock) { owned[hash] }

    /** Хэши своих картинок. */
    fun ownedHashes(): Set<String> = synchronized(lock) { owned.keys.toSet() }

    fun totalBytes(): Int = synchronized(lock) { usedBytes }

    fun count(): Int = synchronized(lock) { received.size }

    fun ownedCount(): Int = synchronized(lock) { owned.size }

    /** Забыть картинку — например, объявление изменилось и хэш больше не нужен. */
    fun drop(hash: String) = synchronized(lock) {
        received.remove(hash)?.let { usedBytes -= it.size }
        lastUsed.remove(hash)
    }

    /** Сбросить всё: разрыв соединения или полная смена набора. */
    fun clear() = synchronized(lock) {
        received.clear()
        lastUsed.clear()
        owned.clear()
        usedBytes = 0
    }

    /**
     * Вытеснить самые давно не спрашивали.
     *
     * Оставляем [KEEP_AFTER_EVICT] свежих, чтобы активная драка, где плащи
     * меняются каждый кадр, не вытесняла сама себя: у всех наборов разные
     * хэши, и без запаса каждый следующий запрос выбивал предыдущий.
     */
    private fun evict() {
        if (usedBytes <= maxBytes) return
        val byAge = lastUsed.entries.sortedBy { it.value }
        val victims = byAge.dropLast(KEEP_AFTER_EVICT)
        for ((hash, _) in victims) {
            received.remove(hash)?.let { usedBytes -= it.size }
            lastUsed.remove(hash)
        }
    }

    private companion object {
        /** Сколько самых свежих картинок не вытесняем никогда. */
        const val KEEP_AFTER_EVICT = 32
    }
}
