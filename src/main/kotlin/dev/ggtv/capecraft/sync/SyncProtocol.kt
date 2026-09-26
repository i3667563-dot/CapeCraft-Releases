package dev.ggtv.capecraft.sync

/**
 * Константы и жёсткие лимиты протокола CapeCraft Sync v1.
 *
 * Протокол полностью сервер-authoritative: клиент сам ничего не решает про
 * набор плащей — он спрашивает сервер и применяет ТОЛЬКО то, что пришло в
 * ответе. Все лимиты зафиксированы здесь, чтобы клиент и сервер проверяли
 * их одинаково, а разбор входящих байтов не мог выделить гигабайт памяти
 * на злонамеренном (или сломанном) сервере.
 */
object SyncProtocol {
    /** Версия протокола. Несовместимые изменения — только с бампом этого числа. */
    const val VERSION: Int = 1

    /** Канал клиент→сервер (Fabric custom payload id). */
    const val REQUEST_CHANNEL: String = "capecraft:sync_req"

    /** Канал сервер→клиент. */
    const val RESPONSE_CHANNEL: String = "capecraft:sync_resp"

    /**
     * Разобрать id канала на `namespace` и `path`.
     *
     * Нужно, потому что API версий трактуют строку канала по-разному:
     * yarn `CustomPayload.id("ns:path")` ждёт полный id, а Mojang
     * `CustomPacketPayload.createType(...)` в 26.2 вызывает
     * `Identifier.withDefaultNamespace(...)`, который НЕ проверяет наличие
     * неймспейса — он безусловно подставляет `minecraft` и берёт всю строку
     * как path. С `capecraft:sync_req` это даёт path с двоеточием и
     * `IdentifierException` на старте мода (поймано запуском сервера 26.2).
     *
     * Поэтому версионная обвязка не «переинтерпретирует» константу, а
     * зовёт этот разбор и собирает `Identifier`/`Id` из готовых частей —
     * так id канала совпадает на всех версиях.
     */
    fun channelParts(channel: String): Pair<String, String> {
        val colon = channel.indexOf(':')
        require(colon > 0 && colon == channel.lastIndexOf(':') && colon < channel.length - 1) {
            "id канала должен быть ровно namespace:path с непустыми частями, а не «$channel»"
        }
        return channel.substring(0, colon) to channel.substring(colon + 1)
    }

    /** Namespace части канала (например `capecraft`). */
    fun channelNamespace(channel: String): String = channelParts(channel).first

    /** Path части канала (например `sync_req`). */
    fun channelPath(channel: String): String = channelParts(channel).second

    /** Максимум провайдеров в одном ответе. */
    const val MAX_PROVIDERS: Int = 64

    /** Максимальная длина одного строкового поля в байтах UTF-8. */
    const val MAX_STRING_BYTES: Int = 1024

    /** Максимальная длина имени провайдера в байтах UTF-8. */
    const val MAX_NAME_BYTES: Int = 128

    /**
     * Максимальный размер payload в байтах. Оценка сверху:
     * 1 (версия) + 4 (requestId) + 1 (счётчик) +
     * [MAX_PROVIDERS] × (2 + [MAX_NAME_BYTES] + 1 + 2 + [MAX_STRING_BYTES] + 2 + [MAX_STRING_BYTES] + 4)
     * ≈ 68 КиБ. Округляем до 128 КиБ — с запасом на будущие поля.
     */
    const val MAX_PAYLOAD_BYTES: Int = 128 * 1024

    /** Максимальный разумный интервал опроса (тиков): 1 час. */
    const val MAX_INTERVAL_TICKS: Int = 72_000

    /** Минимальный интервал опроса (тиков): 1 раз в секунду. */
    const val MIN_INTERVAL_TICKS: Int = 20

    /** Минимальный таймаут ответа (тиков). */
    const val MIN_TIMEOUT_TICKS: Int = 20

    /** Интервал опроса по умолчанию: 40 тиков = 2 секунды. */
    const val DEFAULT_INTERVAL_TICKS: Int = 40

    /** Таймаут ответа по умолчанию: 100 тиков = 5 секунд. */
    const val DEFAULT_TIMEOUT_TICKS: Int = 100

    /**
     * Потолок адаптивного интервала: 1200 тиков = 60 секунд.
     *
     * Больше замедляться незачем: смена плаща — событие редкое (владелец
     * меняет его несколько раз в день), а 60 секунд — цена, которую
     * пользователь ещё не замечает на своём плаще.
     */
    const val MAX_BACKOFF_INTERVAL_TICKS: Int = 1200

    /**
     * Столько ответов подряд «без изменений» нужно, чтобы начать замедляться.
     *
     * Первые ответы идут с базовым интервалом: смена экипировки обычно
     * происходит в первые секунды после входа (мод раздаёт комплект, игрок
     * переодевает кап), и ловить её надо быстро.
     */
    const val STABLE_RESPONSES_BEFORE_BACKOFF: Int = 3

    /**
     * Разброс интервала в процентах, чтобы клиенты не били по серверу
     * синхронно («стад-herd» после рестарта/рестарта лобби: у всех игроков
     * таймер стартует в один тик, и без разброса они бьют запросами в одни
     * и те же тики). Реализация детерминированная — см. [CapeSyncState].
     */
    const val BACKOFF_JITTER_PERCENT: Int = 20
}

/** Нарушение протокола: битый/чужой payload, превышены лимиты, несовместимая версия. */
class SyncProtocolException(message: String) : Exception(message)
