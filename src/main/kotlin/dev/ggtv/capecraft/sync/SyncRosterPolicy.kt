package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source

/**
 * Правила приёма и отбраковки наборов провайдеров в Sync v2.
 *
 * ## Кто тут во что не верит
 *
 * Сервер **не доверяет** входу: клиент может прислать что угодно, поэтому
 * [accept] режет всё, что не проходит [ActiveCape.validate] или превышает
 * лимиты. Это защита от кривого/вредного клиента и от опечатки в конфиге —
 * не «политика доверия», а разбор входа.
 *
 * Клиент **не доверяет** серверу: тот же [accept] прогоняется по каждой записи
 * роустера, потому что сервер тоже может быть чужим или сломанным. То, что
 * не прошло, не применяется — но и не роняет весь снимок: один кривой игрок
 * не должен стирать плащи всем остальным.
 *
 * ## Про `file`
 *
 * `file`-провайдер приходит без пути и только с хэшем (см. [ActiveCape]), так
 * что проверки «небезопасный путь / чужой диск» больше не существует и быть не
 * может: путь наружу не отдаётся в принципе, а картинку клиент получает от
 * владельца через сервер.
 */
object SyncRosterPolicy {

    /** Сколько провайдеров оставить и что отбросить — с причинами для лога. */
    data class Accepted(
        val providers: List<ActiveCape>,
        val rejected: List<String>,
    ) {
        val truncated: Int get() = rejected.size
    }

    /**
     * Проверить и почистить набор.
     *
     * Порядок сохраняется: [SyncProtocol.MAX_PROVIDERS] отбрасываются с конца,
     * потому что приоритет — это порядок в списке, и «первые N» = «самые
     * приоритетные», а не «случайные N».
     */
    fun accept(providers: List<ActiveCape>): List<ActiveCape> = acceptDetailed(providers).providers

    fun acceptDetailed(providers: List<ActiveCape>): Accepted {
        val out = ArrayList<ActiveCape>(minOf(providers.size, SyncProtocol.MAX_PROVIDERS))
        val rejected = ArrayList<String>()
        for (p in providers) {
            if (out.size >= SyncProtocol.MAX_PROVIDERS) {
                rejected += "«${p.name}»: сверх лимита ${SyncProtocol.MAX_PROVIDERS} провайдеров"
                continue
            }
            val problems = p.validate()
            if (problems.isNotEmpty()) {
                rejected += "«${p.name}»: ${problems.first()}"
                continue
            }
            out += p
        }
        return Accepted(out, rejected)
    }

    /**
     * Отпечаток набора — «менялось ли то, что мы показываем».
     *
     * Порядок в отпечатке значим: он и есть цепочка приоритетов, и перестановка
     * двух провайдеров может поменять картинку. Условие `when` в отпечаток
     * **входит**: смена условия меняет то, когда плащ показывается, и без неё
     * смена `when` была бы не видна и плащ не перерисовался бы.
     */
    fun fingerprintOf(providers: List<ActiveCape>): String = providers.joinToString("") { p ->
        buildString {
            append(p.kind.tag)
            append('')
            append(p.name)
            append('')
            append(p.primary)
            append('')
            append(p.extract)
            append('')
            append(p.priority)
            append('')
            append(p.imageHash?.let { ImageHash.toHex(it) } ?: "")
            append('')
            append(conditionSignature(p.condition))
            append('\u0002')
            append(ifConditionSignature(p.ifCondition))
        }
    }

    private fun conditionSignature(c: WireCondition?): String {
        if (c == null) return ""
        return c.predicates.joinToString("") { pr ->
            "${pr.root.tag}:${pr.field}:${pr.op.tag}:" + when (val e = pr.expected) {
                is WireExpected.Str -> "s${e.s}"
                is WireExpected.Num -> "n${e.d}"
                is WireExpected.Range -> "r${e.from}..${e.to}"
            }
        }
    }

    private fun ifConditionSignature(c: WireIfCondition?): String {
        if (c == null) return ""
        return c.predicates.joinToString("") { pr ->
            "${pr.op.tag}:${pr.name}:" + when (val e = pr.expected) {
                is WireExpected.Str -> "s${e.s}"
                is WireExpected.Num -> "n${e.d}"
                is WireExpected.Range -> "r${e.from}..${e.to}"
            }
        }
    }

    /** Хэши картинок набора — что клиенту надо скачать у сервера. */
    fun imageHashesOf(providers: List<ActiveCape>): List<ImageHash> =
        providers.mapNotNull { if (it.kind == ActiveCape.Kind.FILE) it.imageHash else null }

    /**
     * Восстановить локальный [Provider] из проводного описания чужого игрока.
     *
     * Здесь и есть то, ради чего едет [ActiveCape.condition] и
     * [ActiveCape.priority]: набор другого игрока восстанавливается **с его
     * условиями и приоритетами**, а не «просто список картинок». Условие
     * пересобирается в локальный [dev.ggtv.capecraft.condition.Condition] и
     * вычисляется против контекста **этого** игрока (см. `CapeRegistry`).
     *
     * [ActiveCape.Kind.FILE] восстанавливается в [Source.NetImage]: пути на
     * проводе нет и не бывает, вместо пути хэш, а байты лежат в кэше,
     * собранном из кусков, присланных владельцем.
     *
     * Аддон-провайдеры **не** восстанавливаются: у аддона свои параметры и
     * свой код, и выполнять чужой аддонный код с параметрами, выдуманными на
     * стороне объявившего, — это запуск чужого кода по чужому описанию.
     * Такой провайдер пропускается, и владелец на своём клиенте всё равно
     * покажет свой плащ обычным локальным путём.
     */
    fun toLocalProvider(cape: ActiveCape, allowForeignUrls: Boolean = true): Provider? {
        if (cape.kind == ActiveCape.Kind.ADDON) return null
        // Чужой http/json без разрешения владельца не трогаем: это его выбор —
        // не заставлять меня ходить по ссылкам, которые придумал кто-то другой.
        // `file` сюда не попадает: там на проводе только хэш, и картинку я беру
        // у сервера, а не у чужого адреса.
        if (!allowForeignUrls && (cape.kind == ActiveCape.Kind.URL || cape.kind == ActiveCape.Kind.JSON)) {
            return null
        }
        val localCondition = cape.condition?.toLocal()
        val localIf = cape.ifCondition?.toLocal()
        // Непереведённое условие — не «условие не выполнилось», а повод
        // отбросить провайдера: иначе чужой набор показался бы шире своего.
        if (cape.condition != null && localCondition == null) return null
        if (cape.ifCondition != null && localIf == null) return null
        return when (cape.kind) {
            ActiveCape.Kind.URL -> Provider(
                name = cape.name,
                source = Source.Url(cape.primary),
                condition = localCondition,
                ifCondition = localIf,
                priority = cape.priority,
            )

            ActiveCape.Kind.JSON -> Provider(
                name = cape.name,
                source = Source.Json(cape.primary, cape.extract),
                condition = localCondition,
                ifCondition = localIf,
                priority = cape.priority,
            )

            ActiveCape.Kind.FILE -> {
                val hash = cape.imageHash ?: return null
                Provider(
                    name = cape.name,
                    source = Source.NetImage(ImageHash.toHex(hash)),
                    condition = localCondition,
                ifCondition = localIf,
                    priority = cape.priority,
                )
            }

            ActiveCape.Kind.ADDON -> null
        }
    }

    /**
     * Весь набор чужого игрока → локальные провайдеры, готовые к
     * [dev.ggtv.capecraft.condition.ProviderSelector].
     *
     * Порядок и приоритеты сохраняются как пришли: [ProviderSelector] сам
     * разложит список по приоритету, а перестановка здесь означала бы, что
     * цепочка fallback'ов у чужого плаща собрана не как её задумал владелец.
     */
    fun toLocalProviders(capes: List<ActiveCape>, allowForeignUrls: Boolean = true): List<Provider> =
        capes.mapNotNull { toLocalProvider(it, allowForeignUrls) }
}
