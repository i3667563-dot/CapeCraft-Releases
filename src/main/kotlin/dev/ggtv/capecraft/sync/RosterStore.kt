package dev.ggtv.capecraft.sync

/**
 * Серверная сторона Sync v2: кто в сети и каким набором провайдеров
 * пользуется. Без Minecraft API — тестируется напрямую.
 *
 * ## Сервер не выбирает, он запоминает
 *
 * Клиент прислал своё объявление — сервер его запомнил и разослал. Никакой
 * фильтрации «по миру», никакой проверки «а точно ли это правильный плащ»:
 * сервер не решает, кто что носит. Он проверяет только форму и лимиты
 * (вход недоверенный) — см. [SyncCodec] и [SyncRosterPolicy].
 *
 * ## Снимок, а не дельта
 *
 * [snapshot] отдаёт **весь** список, а не изменения с прошлого раза. Дельты
 * рассинхронятся: пропущенный join или leave (краш, рестарт, лаг) навсегда
 * оставил бы у клиента чужой набор. Снимок самолечится — при следующей
 * рассылке всё сошлось. [Roster.revision] при этом позволяет клиенту
 * дешёво отбросить устаревшее и не перерисовывать плащи заново.
 *
 * ## Порядок и усечение
 *
 * Записи в порядке входа, самые новые первыми: игрок, на которого смотрят,
 * почти всегда вошедший недавно, а не пять минут назад. Если снимок не влезает
 * в [maxPlayers], вытесняется самый давно не переобъявлявшийся, а затем уже
 * снимок усекается по [SyncProtocol.MAX_ROSTER_BYTES] — хвост отбрасывается,
 * а [Roster.truncated]
 * сообщает клиенту, что список неполный — иначе отсутствие игрока молча
 * выглядело бы как «у него нет плаща».
 */
class RosterStore(
    private val maxPlayers: Int = SyncProtocol.MAX_ROSTER_PLAYERS,
) {

    private val lock = Any()

    private class Entry(
        val id: String,
        val functions: List<ActiveCape>,
        val seenAt: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var nextJoinSeq: Long = 0
    private var revision: Long = 0

    /**
     * Запомнить объявленный набор функций за объектом [id].
     *
     * [id] приходит сюда **от сервера**, а не от клиента: клиент своего id не
     * присылает, поэтому подделать чужой нельзя в принципе.
     *
     * @return `true`, если набор реально изменился и пора рассылать снимок.
     *   Переобъявление того же набора (`/cp reload` без правок, реконнект)
     *   ревизию не двигает — иначе по сети летели бы одинаковые снимки.
     */
    fun announce(id: String, functions: List<ActiveCape>): Boolean = synchronized(lock) {
        val cleaned = SyncRosterPolicy.accept(functions)
        val existing = entries[id]
        if (existing != null) {
            if (existing.functions == cleaned) return@synchronized false
            // Пересоздание возвращает запись в конец карты: объект становится
            // «свежим» в порядке усечения.
            entries.remove(id)
        }
        // Потолок по числу игроков. Снимок всё равно усекается по байтам, но
        // держать в памяти больше, чем сервер готов разослать, незачем: записи
        // сверх лимита всё равно не попадут в снимок, а память и загрузка
        // разгребать их будут вечно. Жертвуем самым давно не переобъявлявшим.
        if (existing == null) {
            while (entries.size >= maxPlayers) {
                val oldest = entries.values.minByOrNull { it.seenAt } ?: break
                entries.remove(oldest.id)
            }
        }
        entries[id] = Entry(id, cleaned, nextJoinSeq++)
        revision++
        true
    }

    /** Объект исчез. @return `true`, если состояние изменилось. */
    fun remove(id: String): Boolean = synchronized(lock) {
        if (entries.remove(id) == null) return@synchronized false
        revision++
        true
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun currentRevision(): Long = synchronized(lock) { revision }

    fun functionsOf(id: String): List<ActiveCape> =
        synchronized(lock) { entries[id]?.functions ?: emptyList() }

    /** Снимок для рассылки. */
    fun snapshot(): Roster = synchronized(lock) {
        val ordered = entries.values.sortedByDescending { it.seenAt }
        val kept = ArrayList<RosterObject>(ordered.size)
        var dropped = 0
        for (e in ordered) {
            val candidate = RosterObject(e.id, e.functions)
            if (dropped > 0) {
                // Уже начали усекать — дальше идти нельзя, иначе размер
                // может вернуться под потолок сменой порядка.
                dropped++
                continue
            }
            val probe = Roster(revision, kept + candidate, 0)
            val size = try {
                SyncCodec.encodeRoster(probe).size
            } catch (ex: SyncProtocolException) {
                // Не влезает даже по числу игроков — тоже усечение.
                SyncProtocol.MAX_ROSTER_BYTES + 1
            }
            if (size > SyncProtocol.MAX_ROSTER_BYTES) {
                dropped++
                continue
            }
            kept += candidate
        }
        Roster(revision, kept, dropped)
    }

    /**
     * Все хэши картинок, которые нужны этому игроку.
     *
     * Отдельный метод, потому что снимок несёт хэши провайдеров, а клиент
     * (а не сервер) решает, что ему скачивать. Сервер отдаёт картинку только
     * если она у него реально есть ([ImageStore.has]).
     */
    fun imageHashesOf(id: String): List<ImageHash> =
        synchronized(lock) { SyncRosterPolicy.imageHashesOf(entries[id]?.functions ?: emptyList()) }
}
