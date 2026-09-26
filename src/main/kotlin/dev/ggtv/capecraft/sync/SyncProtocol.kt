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
}

/** Нарушение протокола: битый/чужой payload, превышены лимиты, несовместимая версия. */
class SyncProtocolException(message: String) : Exception(message)
