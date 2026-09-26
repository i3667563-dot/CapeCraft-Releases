package dev.ggtv.capecraft.sync

/**
 * Хранилище картинок `file`-провайдеров на сервере: принимает заливку по
 * кускам, отдаёт по запросу. Без Minecraft API — тестируется напрямую.
 *
 * ## Зачем сервер вообще хранит байты
 *
 * Файл лежит на диске владельца. Второй игрок с тем же плащом не может его
 * скачать — у него нет ни этого диска, ни этого каталога. Единственный способ
 * показать ему тот же плащ — передать байты через сервер. Поэтому сервер
 * здесь не «истина», а транспорт и кэш: он не выбирает плащ, он перекладывает
 * картинку, которую отдал владелец.
 *
 * ## Проверка хэша обязательна
 *
 * Клиент присылает `хэш + байты`. Если хэш не сверить, любой клиент вставит
 * в кэш произвольные байты под любым хэшем — и картинка одного игрока
 * покажется другому (кэш общий на всех). Поэтому собранное изображение
 * принимается только если sha-256 совпал с заявленным, а при несовпадении
 * заливка отбрасывается целиком.
 *
 * ## Лимиты
 *
 * [maxTotalBytes] ограничивает кэш целиком: 128 игроков × 8 МиБ — это гигабайт
 * в памяти сервера, что недопустимо. При переполнении вытесняется самое
 * старое неиспользуемое (LRU по обращениям, не по записи — «свежий» плащ
 * должен жить дольше).
 */
class ImageStore(
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
) {

    /** Что пришло на кусок заливки. */
    sealed interface UploadResult {
        /** Кусок принят, ждём следующий. */
        data class Progress(val received: Int, val total: Int) : UploadResult

        /** Картинка собрана и прошла проверку хэша. */
        data class Complete(val hash: ImageHash, val bytes: Int) : UploadResult

        /** Кусок отброшен, загрузка сорвана. */
        data class Rejected(val reason: String) : UploadResult
    }

    private val lock = Any()

    /** Готовые картинки: хэш → байты. */
    private val ready = LinkedHashMap<ImageHash, ByteArray>()

    /** Хэш → когда последний раз просили (для LRU). */
    private val lastUsed = HashMap<ImageHash, Long>()

    /** Незавершённые заливки: (владелец, хэш) → сборщик. */
    private val pending = HashMap<String, Pending>()

    private var clock: Long = 0
    private var usedBytes: Int = 0

    private class Pending(
        val hash: ImageHash,
        val totalSize: Int,
        val buffer: ByteArray,
        val received: Int,
    )

    /** Начать заливку. Повторный старт с тем же хэшем — перезапуск сборки. */
    fun beginUpload(owner: String, hash: ImageHash, totalSize: Int): UploadResult = synchronized(lock) {
        clock++
        if (totalSize <= 0 || totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
            return@synchronized UploadResult.Rejected(
                "размер $totalSize, максимум ${SyncProtocol.MAX_IMAGE_BYTES}",
            )
        }
        if (ready.containsKey(hash)) {
            // Уже есть — заливать незачем, но владельцу это не ошибка.
            return@synchronized UploadResult.Complete(hash, ready.getValue(hash).size)
        }
        val key = uploadKey(owner, hash)
        pending[key] = Pending(hash, totalSize, ByteArray(totalSize), 0)
        UploadResult.Progress(0, totalSize)
    }

    /** Принять кусок. Сборщик создаётся по [beginUpload] либо лениво. */
    fun acceptChunk(owner: String, chunk: Upload): UploadResult = synchronized(lock) {
        clock++
        val key = uploadKey(owner, chunk.hash)
        val p = pending[key]
        if (p == null) {
            // Куски могут прийти без beginUpload только если клиент начал
            // с середины (переподключился) — создаём сборщик с запасом.
            if (chunk.totalSize <= 0 || chunk.totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
                return@synchronized UploadResult.Rejected(
                    "размер ${chunk.totalSize}, максимум ${SyncProtocol.MAX_IMAGE_BYTES}",
                )
            }
            if (chunk.offset + chunk.bytes.size > chunk.totalSize) {
                return@synchronized UploadResult.Rejected("кусок вылезает за ${chunk.totalSize}")
            }
            val created = Pending(chunk.hash, chunk.totalSize, ByteArray(chunk.totalSize), 0)
            pending[key] = created
            return@synchronized apply(created, chunk, key)
        }
        if (p.totalSize != chunk.totalSize) {
            pending.remove(key)
            return@synchronized UploadResult.Rejected("размер изменился: ${p.totalSize} → ${chunk.totalSize}")
        }
        apply(p, chunk, key)
    }

    private fun apply(p: Pending, chunk: Upload, key: String): UploadResult {
        val end = chunk.offset + chunk.bytes.size
        if (chunk.offset < 0 || end > p.totalSize) {
            pending.remove(key)
            return UploadResult.Rejected("кусок ${chunk.offset}..$end вылезает за ${p.totalSize}")
        }
        // Куски могут прийти не по порядку (переотправка после обрыва) —
        // это нормально, просто пишем по своему оффсету.
        chunk.bytes.copyInto(p.buffer, chunk.offset)
        val received = p.received + chunk.bytes.size
        if (end < p.totalSize) {
            return UploadResult.Progress(received.coerceAtMost(p.totalSize), p.totalSize)
        }
        pending.remove(key)

        val data = p.buffer
        val actual = try {
            ImageHash.compute(data)
        } catch (e: Exception) {
            return UploadResult.Rejected("не посчитать хэш: ${e.message.orEmpty()}")
        }
        if (actual != p.hash) {
            return UploadResult.Rejected(
                "хеш не совпал: заявлен ${ImageHash.shortHex(p.hash)}, фактически ${ImageHash.shortHex(actual)}",
            )
        }
        store(p.hash, data)
        return UploadResult.Complete(p.hash, data.size)
    }

    private fun store(hash: ImageHash, data: ByteArray) {
        if (ready.containsKey(hash)) {
            lastUsed[hash] = clock
            return
        }
        evictFor(data.size)
        ready[hash] = data
        lastUsed[hash] = clock
        usedBytes += data.size
    }

    /** Освободить место под [incoming], вытесняя давно не использованное. */
    private fun evictFor(incoming: Int) {
        if (incoming > maxTotalBytes) {
            // Не влезет ни при каком вытеснении — не мучаемся, вытесним всё.
            ready.clear()
            lastUsed.clear()
            usedBytes = 0
            return
        }
        val victims = ready.keys.sortedBy { lastUsed[it] ?: 0L }
        for (v in victims) {
            if (usedBytes + incoming <= maxTotalBytes) break
            usedBytes -= ready.remove(v)?.size ?: 0
            lastUsed.remove(v)
        }
    }

    /** Есть ли картинка. */
    fun has(hash: ImageHash): Boolean = synchronized(lock) { ready.containsKey(hash) }

    /** Целые байты картинки (для тестов и проверки целостности). */
    fun get(hash: ImageHash): ByteArray? = synchronized(lock) {
        ready[hash]?.also {
            clock++
            lastUsed[hash] = clock
        }
    }

    /**
     * Кусок картинки для ответа на `fetch`.
     *
     * `null` если такой картинки нет или оффсет/длина за пределами — клиент
     * в этом случае просто не получит картинку, а не получит мусор.
     */
    fun chunk(hash: ImageHash, offset: Int, length: Int): ByteArray? = synchronized(lock) {
        clock++
        val data = ready[hash] ?: return@synchronized null
        if (offset < 0 || offset > data.size) return@synchronized null
        if (length < 0 || length > SyncProtocol.CHUNK_BYTES) return@synchronized null
        val end = (offset + length).coerceAtMost(data.size)
        lastUsed[hash] = clock
        data.copyOfRange(offset, end)
    }

    /**
     * Реальный размер целой картинки.
     *
     * Отдельный метод, а не вычисление на стороне раздачи: размер картинки нельзя
     * вывести из размера куска и его смещения — это разные вещи, и подмена одного
     * другим объявляла клиенту картинку размером в первый же кусок.
     */
    fun sizeOf(hash: ImageHash): Int? = synchronized(lock) { ready[hash]?.size }

    fun totalBytes(): Int = synchronized(lock) { usedBytes }


    fun count(): Int = synchronized(lock) { ready.size }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Сбросить незавершённые заливки (игрок вышел). */
    fun dropUploads(owner: String): Unit = synchronized(lock) {
        pending.keys
            .filter { it.startsWith("$owner\u0000") }
            .forEach { pending.remove(it) }
    }

    private fun uploadKey(owner: String, hash: ImageHash): String = "$owner\u0000${ImageHash.toHex(hash)}"

    companion object {
        /**
         * Потолок кэша по умолчанию: 64 МиБ.
         *
         * Достаточно для тысячи стоковых плащей и при этом не даёт серверу
         * уйти в гигабайты. Считается от [SyncProtocol.MAX_IMAGE_BYTES]:
         * 8 картинок на «максимального» игрока.
         */
        const val DEFAULT_MAX_TOTAL_BYTES: Int = 64 * 1024 * 1024
    }
}
