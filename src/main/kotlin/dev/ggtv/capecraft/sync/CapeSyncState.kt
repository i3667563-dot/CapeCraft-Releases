package dev.ggtv.capecraft.sync

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Что клиент хочет отправить серверу на этом шаге. */
sealed interface SyncOutbound {
    /** «Вот мой набор провайдеров». */
    data class Announce(val functions: List<ActiveCape>) : SyncOutbound

    /** «Дай кусок картинки с этого хэша». */
    data class FetchImage(val hash: ImageHash, val offset: Int, val length: Int) : SyncOutbound

    /** «Вот кусок моей картинки, она под хэшем H». */
    data class UploadImage(
        val hash: ImageHash,
        val totalSize: Int,
        val offset: Int,
        val bytes: ByteArray,
    ) : SyncOutbound {
        override fun equals(other: Any?): Boolean =
            other is UploadImage && hash == other.hash && totalSize == other.totalSize &&
                offset == other.offset && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (hash.hashCode() * 31 + totalSize) * 31 + offset * 31 + bytes.contentHashCode()
    }
}

/**
 * Клиентский конечный автомат Sync v2. Без Minecraft API — тестируется напрямую.
 *
 * ## Опроса больше нет
 *
 * В v1 клиент каждые N тиков слал запрос и ждал ответ, отслеживая таймаут по
 * `requestId`. В v2 это не нужно: клиент **пушит** своё объявление один раз, а
 * сервер сам присылает снимок роустера при входе, выходе и смене набора.
 * Вместе с опросом ушли `requestId`, таймаут и проблема «стад-herd» — после
 * рестарта лобби все клиенты били в сервер в одни и те же тики, и их
 * приходилось разводить джиттером.
 *
 * ## Что осталось от прежней машины
 *
 * Только backoff — но теперь для **докачки картинок**. Сервер может не иметь
 * картинку (владелец ещё не залил) или отдать её не с первого раза, и тогда
 * `fetch` надо повторять, не превращая это в поток запросов. Джиттер и
 * удвоение интервала — те же самые, что были проверены в v1, только теперь
 * ограничивают одну конкретную картинку, а не весь опрос.
 *
 * ## Порядок в очереди
 *
 * Нужные картинки берутся в порядке хэшей ([SyncRosterPolicy.imageHashesOf]
 * по порядку провайдеров), поэтому при перезаходе набор скачивается так же,
 * а не «как вышло».
 */
class CapeSyncState(
    var enabled: Boolean = true,
    var backoff: Boolean = true,
    val maxRetryTicks: Int = SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS,
    val jitterSeed: Long = Random.nextLong(),
) {

    /** Сборщик входящих картинок. */
    val images = ImageAssembler()

    /** Применённый снимок. */
    var roster: Roster = Roster(0, emptyList())
        private set

    /** Ревизия последнего применённого снимка. */
    var revision: Long = 0
        private set

    /** Отправлено объявлений. */
    var announcesSent: Int = 0
        private set

    /** Отправлено `fetch`. */
    var fetchesSent: Int = 0
        private set

    /** Принято кусков. */
    var chunksReceived: Int = 0
        private set

    /** Применено снимков (с учётом отбракованных по устареванию). */
    var rostersApplied: Int = 0
        private set

    /** Снимков отброшено как устаревших. */
    var rostersStale: Int = 0
        private set

    /** Человекочитаемая причина последней ошибки — для лога и `/cp status`. */
    var lastError: String? = null
        private set

    /** Счётчик тиков для интервалов и backoff. */
    private var ticks: Int = 0
    private var rng = jitterSeed
    private var nextRetryTick: Int = 0
    private var retryDelayTicks: Int = SyncProtocol.MIN_INTERVAL_TICKS
    private var wanted: List<ImageHash> = emptyList()

    /** Что я последний раз объявил — нужно, чтобы знать свои хэши. */
    private var declared: List<ActiveCape> = emptyList()

    /**
     * Клиент применил новый набор провайдеров — надо объявить серверу.
     *
     * Зовётся при входе на сервер и после `/cp reload`. Набор сюда приходит
     * уже посчитанным на клиенте: [ActiveCape] для `file` уже с хэшем, который
     * клиент посчитал, прочитав файл.
     */
    fun onConfigChanged(functions: List<ActiveCape>): List<SyncOutbound> {
        if (!enabled) return emptyList()
        val accepted = SyncRosterPolicy.acceptDetailed(functions)
        if (accepted.rejected.isNotEmpty()) {
            lastError = "свои функции отброшены: ${accepted.rejected.first()}"
        }
        announcesSent++
        declared = accepted.providers
        return listOf(SyncOutbound.Announce(accepted.providers))
    }

    /** Вход на сервер: сброс состояния, дальше клиент зовёт [onConfigChanged]. */
    fun onJoin() {
        reset()
    }

    /**
     * Применить новые настройки, **не теряя** уже принятый снимок.
     *
     * Зовётся на `/cp reload` и при подхвате изменённого конфига. Раньше здесь
     * создавался новый [CapeSyncState], а это обнуляло и [revision], и `wanted`.
     * Сервер шлёт снимок только когда чей-то набор действительно изменился, так
     * что клиент с ревизией 0 больше не получал бы ростер до чужого reload —
     * плащи молча пропадали бы, включая собственный `file`-плащ, который иначе
     * неоткуда взять: байты лежат на сервере, а в свой кэш клиент их не кладёт.
     *
     * [reset] тут неуместен намеренно: он для смены сервера, где прежний снимок
     * действительно чужой.
     */
    fun reconfigure(enabled: Boolean, backoff: Boolean) {
        this.enabled = enabled
        this.backoff = backoff
        // Таймеры докачки относятся к прежним настройкам — пересчитываем от нуля,
        // но картинки в полёте и принятые байты остаются: они по хэшу, не по позиции.
        resetRetry()
    }

    /**
     * Пришёл снимок роустера.
     *
     * Устаревший (ревизия не больше текущей) игнорируется — иначе два
     * перемешанных в сети снимка могли бы «качать назад» набор. Равная
     * ревизия — тоже мусор: ревизия меняется на каждое изменение, значит
     * повтор с той же ревизией = дубликат.
     */
    fun onRoster(incoming: Roster): Boolean {
        if (incoming.revision <= revision) {
            rostersStale++
            return false
        }
        val cleaned = ArrayList<RosterObject>(incoming.objects.size)
        for (o in incoming.objects) {
            if (o.id.isEmpty()) continue
            if (ActiveCape.utf8Len(o.id) > SyncProtocol.MAX_OBJECT_ID_BYTES) continue
            cleaned += o.copy(functions = SyncRosterPolicy.accept(o.functions))
        }
        revision = incoming.revision
        roster = Roster(incoming.revision, cleaned, incoming.truncated)
        wanted = collectWanted(cleaned)
        rostersApplied++
        // Новый снимок = новые картинки; сбрасываем докачку, но уже готовые
        // из images() остаются — они по хэшу, а не по позиции.
        resetRetry()
        return true
    }

    /** Хэши всех `file`-провайдеров в роустере, в стабильном порядке. */
    private fun collectWanted(objects: List<RosterObject>): List<ImageHash> {
        val seen = LinkedHashSet<ImageHash>()
        for (o in objects) {
            for (h in SyncRosterPolicy.imageHashesOf(o.functions)) {
                if (images.isReady(h)) continue
                seen += h
            }
        }
        return seen.toList()
    }

    /**
     * Тик. Возвращает что отправить — обычно `fetch` очередной картинки.
     *
     * [channelReady] гасит попытки, пока канал ещё не поднят: слать в
     * неготовый канал бессмысленно, и это сдвинуло бы backoff без причины.
     */
    fun onTick(channelReady: Boolean): List<SyncOutbound> {
        if (!enabled) return emptyList()
        if (!channelReady) return emptyList()
        if (wanted.isEmpty()) return emptyList()
        if (ticks < nextRetryTick) return emptyList()

        val hash = images.nextWanted(wanted, stallTicks()) ?: run {
            // Всё нужное либо скачивается, либо уже скачано.
            if (wanted.all { images.isReady(it) }) wanted = emptyList()
            return emptyList()
        }
        val total = expectedSizeOf(hash)
        val length = SyncProtocol.CHUNK_BYTES
        // Запрашиваем с места обрыва, а не с нуля: картинка больше чанка всегда
        // приходит несколькими кусками, и запрос с нуля на каждом шаге означал
        // бы, что второй кусок не запрашивается никогда — докачка стояла бы на
        // первом куске, пока сервер не отдаст его ещё раз.
        val from = images.receivedOf(hash)
        if (from >= total && total > 0) return emptyList()
        fetchesSent++
        growBackoff()
        reschedule()
        return listOf(SyncOutbound.FetchImage(hash, from, length.coerceAtMost(total - from)))
    }

    /**
     * Размер картинки по хэшу: нужен, чтобы `fetch` не запросил больше, чем есть.
     *
     * Берём из остатка снимка — по проводу размер идёт в кусках, а не в
     * роустере. Не нашли — берём максимум, сервер обрежет по своему размеру.
     */
    private fun expectedSizeOf(hash: ImageHash): Int {
        for (o in roster.objects) {
            for (f in o.functions) {
                if (f.imageHash == hash) return SyncProtocol.MAX_IMAGE_BYTES
            }
        }
        return SyncProtocol.MAX_IMAGE_BYTES
    }

    /** Пришёл кусок картинки. */
    /** Хэши, чьи куски я уже отдал целиком. */
    private val uploadsComplete = LinkedHashSet<ImageHash>()

    /** Сколько байт каждой своей картинки уже отдано — для докачки с места обрыва. */
    private val uploadOffset = ConcurrentHashMap<ImageHash, Int>()

    /**
     * Какие из моих картинок надо залить на сервер.
     *
     * Не «все мои», а те, на которые **ссылаются чужие наборы**: если никто не
     * показал мой плащ, тащить байты незачем, и личный локальный файл не уедет
     * просто потому, что он есть.
     *
     * @param referencedByOthers хэши, встречающиеся в чужих объявлениях
     * @param owned хэши картинок, байты которых у меня есть и я их разрешил
     * @return хэши, которые ещё не залиты целиком
     */
    fun pendingUploads(
        referencedByOthers: Set<ImageHash>,
        owned: Set<ImageHash>,
    ): List<ImageHash> = (referencedByOthers intersect owned).filter { it !in uploadsComplete }

    /** Отметить, что кусок картинки отдан, и вернуть, сколько отдано всего. */
    fun markUploadProgress(hash: ImageHash, sentTotal: Int): Int {
        val current = uploadOffset[hash] ?: 0
        val next = maxOf(current, sentTotal)
        uploadOffset[hash] = next
        return next
    }

    /** Отметить картинку залитой целиком. */
    fun markUploadComplete(hash: ImageHash) {
        uploadsComplete.add(hash)
        uploadOffset.remove(hash)
    }

    /** Сколько байт своей картинки уже отдано. */
    fun uploadedBytesOf(hash: ImageHash): Int = uploadOffset[hash] ?: 0

    /**
     * Хэши картинок, на которые кто-то ссылается, кроме моих собственных.
     *
     * Ссылка на себя не считается запросом: свой файл я и так знаю, а сервер
     * без нужды получит от меня лишние мегабайты при каждом входе. Исключение
     * безвредно — если на мой хэш сослётся ещё кто-то, ссылка от него учтётся.
     *
     * [exceptId] — мой id в роустере. Пустой `null` означает «я не в роустере»
     * (ещё не объявился): тогда исключать нечего и берутся все.
     */
    /**
     * Хэши, которые мне пора залить на сервер.
     *
     * Свои объявленные — когда в роустере есть кто-то ещё: снимок уходит всем
     * подряд, значит моя картинка кому-то из них нужна. Раньше здесь брались
     * только чужие ссылки, а своя ссылка на свой же файл исключалась — и
     * выходило, что владелец не заливает свой плащ никогда, никому: картинка
     * навсегда оставалась у него на диске, а сервер отдавал «нечего».
     *
     * Плюс хэши, на которые сослались чужие наборы: кто-то переиспользовал
     * мою картинку, и её надо дослать даже когда я сам её не объявлял.
     */
    fun publishableHashes(exceptId: String? = null): Set<ImageHash> {
        val out = LinkedHashSet(referencedHashes(exceptId))
        val someoneElse = roster.objects.any { exceptId == null || it.id != exceptId }
        if (someoneElse) {
            for (cape in declared) cape.imageHash?.let { out += it }
        }
        return out
    }

    fun referencedHashes(exceptId: String? = null): Set<ImageHash> {
        val out = LinkedHashSet<ImageHash>()
        for (obj in roster.objects) {
            if (exceptId != null && obj.id == exceptId) continue
            for (cap in obj.functions) out += cap.imageHash ?: continue
        }
        return out
    }

    fun onChunk(chunk: Chunk): ImageAssembler.Result {
        chunksReceived++
        val before = images.receivedOf(chunk.hash)
        val result = images.accept(chunk)
        if (result is ImageAssembler.Result.Rejected) {
            lastError = "картинка отброшена: ${result.reason}"
            return result
        }
        if (result is ImageAssembler.Result.Progress && result.received > before) {
            // Байты реально пришли — снимаем backoff. Иначе успешная передача
            // шла бы с накопленным интервалом: после нескольких неудачных
            // попыток картинка в 8 МиБ качалась бы по чанку в минуту.
            resetBackoff()
        }
        if (result is ImageAssembler.Result.Complete) {
            lastError = null
            // Картинка готова — если это была последняя нужная, снимаем очередь.
            if (wanted.isNotEmpty() && wanted.all { images.isReady(it) }) {
                wanted = emptyList()
                resetRetry()
            }
        }
        return result
    }

    /** Нужные картинки прямо сейчас — для диагностики. */
    fun wantedHashes(): List<ImageHash> = wanted

    /** Картинки, которых ещё нет. */
    fun missingHashes(): List<ImageHash> = wanted.filter { !images.isReady(it) }

    fun tickCount(): Int = ticks

    fun currentRetryDelay(): Int = retryDelayTicks

    fun onTickAdvance(): Unit {
        ticks++
        // Ассемблер считает тики свои: по ним он понимает, что сборка
        // перестала продвигаться, и отпускает зависшую картинку на повтор.
        images.advanceTick()
    }

    /** Соединение закрыто: забываем всё временное. */
    fun onDisconnect() {
        uploadsComplete.clear()
        uploadOffset.clear()
        reset()
    }

    fun reset() {
        declared = emptyList()
        roster = Roster(0, emptyList())
        revision = 0
        announcesSent = 0
        fetchesSent = 0
        chunksReceived = 0
        rostersApplied = 0
        rostersStale = 0
        lastError = null
        ticks = 0
        rng = jitterSeed
        retryDelayTicks = SyncProtocol.MIN_INTERVAL_TICKS
        wanted = emptyList()
        resetRetry()
        images.dropInFlight()
    }

    private fun resetRetry() {
        nextRetryTick = ticks
    }

    /**
     * Интервал возвращается к базовому: неудач в очереди больше нет.
     *
     * Следующая попытка всё равно не раньше базового интервала, иначе успешная
     * передача шла бы кусок за куском в один тик и заваливала сервер.
     */
    private fun resetBackoff() {
        retryDelayTicks = SyncProtocol.MIN_INTERVAL_TICKS
        nextRetryTick = ticks + SyncProtocol.MIN_INTERVAL_TICKS
    }

    /**
     * Следующая попытка — от момента последней отправки, а не от «сейчас».
     *
     * Интервал здесь ограничивает частоту повторов, то есть «не чаще, чем
     * раз в N тиков ПОСЛЕ отправки». Отсчёт от текущего тика сдвинул бы
     * повторы на величину задержки обработки — и каденция тихо разъехалась бы
     * с задуманной.
     */
    private fun reschedule() {
        nextRetryTick = ticks + waitTicks()
    }

    /**
     * Сколько тиков реально ждём до следующей попытки.
     *
     * Разброс входит внутрь потолка, а не прибавляется к нему: иначе фактическое
     * ожидание оказывалось длиннее [SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS], и
     * таймаут зависания, посчитанный от этого потолка, срабатывал на нормальной
     * передаче.
     */
    private fun waitTicks(): Int =
        (retryDelayTicks + jitter())
            .coerceAtLeast(SyncProtocol.MIN_INTERVAL_TICKS)
            .coerceAtMost(maxRetryTicks)

    /**
     * Таймаут зависания для текущего интервала ожидания.
     *
     * Считается от худшего случая (верхняя граница разброса) и НЕ зовёт
     * [jitter]: тот двигает ГПСЧ, и геттер сдвинул бы последовательность
     * разброса, сделав её невоспроизводимой.
     */
    private fun stallTicks(): Int {
        val worstWait = retryDelayTicks +
            retryDelayTicks * SyncProtocol.BACKOFF_JITTER_PERCENT / 100
        return SyncProtocol.stallTicks(worstWait)
    }

    private fun growBackoff() {
        if (!backoff) return
        if (retryDelayTicks >= maxRetryTicks) return
        retryDelayTicks = (retryDelayTicks * 2).coerceAtMost(maxRetryTicks)
    }

    /**
     * Детерминированный разброс вверх, LCG вместо [Random] — чтобы тесты
     * воспроизводились. Смещение только вверх: базовый интервал ограничивает
     * скорость, и случайный «ранний» повтор превращал бы его в rate-limit,
     * который срабатывает по чужому таймеру.
     */
    private fun jitter(): Int {
        if (!backoff) return 0
        if (retryDelayTicks <= SyncProtocol.MIN_INTERVAL_TICKS) return 0
        val span = retryDelayTicks * SyncProtocol.BACKOFF_JITTER_PERCENT / 100
        if (span <= 0) return 0
        rng = rng * LCG_MULTIPLIER + LCG_INCREMENT
        return ((rng ushr 1) % (2L * span + 1L)).toInt()
    }

    companion object {
        private const val LCG_MULTIPLIER: Long = 6364136223846793005L
        private const val LCG_INCREMENT: Long = 1442695040888963407L
    }
}
