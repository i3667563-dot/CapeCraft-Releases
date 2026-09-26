package dev.ggtv.capecraft.sync

/**
 * Сборка картинки из присланных кусков на клиенте. Без Minecraft API.
 *
 * ## Почему проверяем хэш и на клиенте
 *
 * Хэш приходит **вместе** с байтами, то есть тоже от сервера. Сервер здесь
 * просто транспорт, а доверять ему нельзя: без сверки злой сервер подставил бы
 * свою картинку под чужой провайдер, и игрок увидел бы чужой (или вовсе не
 * картинку) вместо заявленного. Сверили — и мусор отбрасывается молча, до GL.
 *
 * ## Ограничение одновременных загрузок
 *
 * [maxConcurrent] не даёт начать загрузку сотни картинок разом: в куче
 * означает сотню запросов и сотню буферов в памяти. Очередь идёт по порядку
 * хэшей, то есть детерминированно — иначе при перезаходе набор скачивался бы
 * в случайном порядке и «дёргался» бы при каждом входе.
 */
class ImageAssembler(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {

    /** Что пришло на кусок. */
    sealed interface Result {
        /** Куски приняты, ещё не всё. */
        data class Progress(val received: Int, val total: Int) : Result

        /** Картинка собрана, хэш сошёлся — её можно отдавать реестру. */
        data class Complete(val hash: ImageHash, val bytes: ByteArray) : Result

        /** Кусок отброшен. */
        data class Rejected(val reason: String) : Result
    }

    private val lock = Any()

    private class Partial(val totalSize: Int, val buffer: ByteArray, val received: Int)

    private val partials = LinkedHashMap<ImageHash, Partial>()
    private val done = LinkedHashMap<ImageHash, ByteArray>()

    /**
     * Тик, на котором чанк последний раз продвинул сборку, и счётчик тиков.
     *
     * Живут внутри `lock` вместе с `partials` намеренно: `accept` зовут из
     * сетевого потока, `nextWanted` — из игрового, и общий словарь без
     * синхронизации дал бы гонку именно на пути «приехал кусок / проверили,
     * что он застрял».
     */
    private var tick: Int = 0
    private val lastProgressAt = HashMap<ImageHash, Int>()

    fun advanceTick(): Unit = synchronized(lock) { tick++ }

    fun accept(chunk: Chunk): Result = synchronized(lock) {
        val total = chunk.totalSize
        if (total <= 0 || total > SyncProtocol.MAX_IMAGE_BYTES) {
            return@synchronized Result.Rejected("размер $total, максимум ${SyncProtocol.MAX_IMAGE_BYTES}")
        }
        if (chunk.offset < 0 || chunk.offset + chunk.bytes.size > total) {
            return@synchronized Result.Rejected("кусок ${chunk.offset}..${chunk.offset + chunk.bytes.size} вылезает за $total")
        }
        if (chunk.bytes.size > SyncProtocol.CHUNK_BYTES) {
            return@synchronized Result.Rejected("кусок ${chunk.bytes.size} байт, максимум ${SyncProtocol.CHUNK_BYTES}")
        }
        if (done.containsKey(chunk.hash)) {
            return@synchronized Result.Complete(chunk.hash, done.getValue(chunk.hash))
        }

        val p = partials.getOrPut(chunk.hash) { Partial(total, ByteArray(total), 0) }
        lastProgressAt[chunk.hash] = tick
        if (p.totalSize != total) {
            partials.remove(chunk.hash)
            lastProgressAt.remove(chunk.hash)
            return@synchronized Result.Rejected("размер изменился: ${p.totalSize} → $total")
        }
        chunk.bytes.copyInto(p.buffer, chunk.offset)
        val received = p.received + chunk.bytes.size
        val end = chunk.offset + chunk.bytes.size
        if (end < total) {
            partials[chunk.hash] = Partial(total, p.buffer, received)
            return@synchronized Result.Progress(received.coerceAtMost(total), total)
        }
        partials.remove(chunk.hash)

        val data = p.buffer
        val actual = try {
            ImageHash.compute(data)
        } catch (e: Exception) {
            return@synchronized Result.Rejected("не посчитать хэш: ${e.message.orEmpty()}")
        }
        if (actual != chunk.hash) {
            return@synchronized Result.Rejected(
                "хеш не совпал: заявлен ${ImageHash.shortHex(chunk.hash)}, фактически ${ImageHash.shortHex(actual)}",
            )
        }
        done[chunk.hash] = data
        Result.Complete(chunk.hash, data)
    }

    /** Готовая картинка, если уже собрана. */
    fun ready(hash: ImageHash): ByteArray? = synchronized(lock) { done[hash] }

    fun isReady(hash: ImageHash): Boolean = synchronized(lock) { done.containsKey(hash) }

    /** Сколько байт уже принято по картинке (для прогресса в логе). */
    fun receivedOf(hash: ImageHash): Int = synchronized(lock) { partials[hash]?.received ?: 0 }

    fun inFlightCount(): Int = synchronized(lock) { partials.size }

    fun readyCount(): Int = synchronized(lock) { done.size }

    /**
     * Первая картинка, которую ещё не качаем и которую надо запросить.
     *
     * Возвращает `null`, если либо всё нужное уже есть, либо уже идёт
     * [maxConcurrent] загрузок.
     *
     * [staleAfterTicks] — после скольких тиков без единого продвижения
     * недособранное считается зависшим и сбрасывается. Без этого картинка,
     * которую сервер начал и не довёл, ждала бы вечно: [nextWanted] пропускает
     * то, что уже в работе, а сбросить её мог только разрыв соединения.
     */
    /**
     * Какую картинку качать следующей.
     *
     * Повторные запросы и не чаще [staleAfterTicks] раскладывает вызывающий
     * код через свой счётчик тиков. Своего «ответа ждём» здесь намеренно нет:
     * молчащий сервер обязан приводить к повтору запроса, а не к вечной
     * блокировке докачки.
     */
    fun nextWanted(wanted: List<ImageHash>, staleAfterTicks: Int = Int.MAX_VALUE): ImageHash? = synchronized(lock) {
        if (staleAfterTicks != Int.MAX_VALUE) dropStalled(staleAfterTicks)
        if (partials.size >= maxConcurrent) return@synchronized null
        // Сначала — те, что ещё не начаты: иначе один и тот же первый хэш в
        // wanted занимал бы место до конца, а остальные не начинали бы никогда.
        for (h in wanted) {
            if (done.containsKey(h)) continue
            if (partials.containsKey(h)) continue
            return@synchronized h
        }
        // Теперь все нужные уже в работе — продолжаем одну из них. Раньше здесь
        // был просто `null`, из-за чего картинка больше одного чанка
        // останавливалась на первом куске навсегда: следующий запрос не
        // формировался, потому что partial для этого хэша уже существовал.
        for (h in wanted) {
            if (done.containsKey(h)) continue
            if (partials.containsKey(h)) return@synchronized h
        }
        null
    }

    /** Сбросить недособранное, к которому давно не приходило ни байта. */
    private fun dropStalled(staleAfterTicks: Int) {
        val stalled = lastProgressAt.entries.filter { tick - it.value >= staleAfterTicks }.map { it.key }
        for (hash in stalled) {
            partials.remove(hash)
            lastProgressAt.remove(hash)
        }
    }

    /** Забыть недособранное (игрок ушёл с сервера). Готовые остаются. */
    fun dropInFlight(): Unit = synchronized(lock) {
        partials.clear()
        lastProgressAt.clear()
    }

    /** Забыть всё — например после `/cp clear`. */
    fun clear(): Unit = synchronized(lock) {
        partials.clear()
        lastProgressAt.clear()
        done.clear()
    }

    companion object {
        /**
         * Сколько картинок качать одновременно.
         *
         * 8: на экране редко больше десятка игроков, а каждый кусок — это
         * отдельный пакет. Больше — трафик впустую, меньше — загрузка
         * растягивается на несколько секунд.
         */
        const val DEFAULT_MAX_CONCURRENT: Int = 8
    }
}
