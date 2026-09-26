package dev.ggtv.capecraft.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Запрос клиент→сервер: «пришли мой активный набор плащей». */
data class SyncRequest(val requestId: Int) {
    fun encode(): ByteArray = SyncCodec.encodeRequest(this)
}

/** Ответ сервер→клиент: активные провайдеры для конкретного игрока. */
data class SyncResponse(
    val requestId: Int,
    val providers: List<ActiveCape>,
    /** Сколько провайдеров сервер отсёк по лимиту (для лога/диагностики). */
    val truncated: Int = 0,
) {
    fun encode(): ByteArray = SyncCodec.encodeResponse(this)
}

/**
 * Бинарный кодек CapeCraft Sync v1. Без Minecraft API — чистые байты,
 * поэтому тестируется без запуска игры.
 *
 * ## Формат
 * Запрос (C2S):
 * ```
 * u8  версия протокола
 * i32 requestId
 * ```
 * Ответ (S2C):
 * ```
 * u8   версия протокола
 * i32  requestId        (эхо — клиент отбрасывает чужие/устаревшие ответы)
 * u8   флаг «список обрезан по лимиту»
 * u8   число провайдеров (0..[SyncProtocol.MAX_PROVIDERS])
 * провайдер × N:
 *   u8   вид (0=url, 1=file, 2=json, 3=addon)
 *   i32  приоритет
 *   str  имя        (u16 длина + UTF-8)
 *   str  основной   (u16 длина + UTF-8)
 *   str  extract    (u16 длина + UTF-8, обычно пусто)
 * ```
 *
 * Целые — big-endian (`DataOutputStream`), длины строк — в байтах UTF-8.
 * Разбор СТРОГИЙ: версия, лимиты и обязательность полей проверяются, любое
 * нарушение — [SyncProtocolException], а не «попробуем потерпим».
 */
object SyncCodec {

    fun encodeRequest(req: SyncRequest): ByteArray {
        val out = ByteArrayOutputStream(8)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeInt(req.requestId)
        }
        return out.toByteArray()
    }

    fun decodeRequest(bytes: ByteArray): SyncRequest {
        requirePayloadSize(bytes)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val version = d.readUnsignedByte()
                requireVersion(version)
                val requestId = d.readInt()
                if (d.available() != 0) {
                    throw SyncProtocolException("в запросе ${d.available()} лишних байт")
                }
                return SyncRequest(requestId)
            }
        } catch (e: SyncProtocolException) {
            throw e
        } catch (e: Exception) {
            throw SyncProtocolException("не удалось разобрать запрос: ${e.message.orEmpty()}")
        }
    }

    fun encodeResponse(res: SyncResponse): ByteArray {
        if (res.providers.size > SyncProtocol.MAX_PROVIDERS) {
            throw SyncProtocolException(
                "в ответе ${res.providers.size} провайдеров, максимум ${SyncProtocol.MAX_PROVIDERS}",
            )
        }
        val out = ByteArrayOutputStream(64)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeInt(res.requestId)
            d.writeByte(if (res.truncated > 0) 1 else 0)
            d.writeByte(res.providers.size)
            for (p in res.providers) {
                d.writeByte(p.kind.tag)
                d.writeInt(p.priority)
                d.writeString(p.name, SyncProtocol.MAX_NAME_BYTES)
                d.writeString(p.primary, SyncProtocol.MAX_STRING_BYTES)
                d.writeString(p.extract, SyncProtocol.MAX_STRING_BYTES)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size > SyncProtocol.MAX_PAYLOAD_BYTES) {
            throw SyncProtocolException("ответ ${bytes.size} байт, максимум ${SyncProtocol.MAX_PAYLOAD_BYTES}")
        }
        return bytes
    }

    fun decodeResponse(bytes: ByteArray): SyncResponse {
        requirePayloadSize(bytes)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val version = d.readUnsignedByte()
                requireVersion(version)
                val requestId = d.readInt()
                val truncated = if (d.readUnsignedByte() != 0) 1 else 0
                val count = d.readUnsignedByte()
                if (count > SyncProtocol.MAX_PROVIDERS) {
                    throw SyncProtocolException("в ответе $count провайдеров, максимум ${SyncProtocol.MAX_PROVIDERS}")
                }
                val providers = ArrayList<ActiveCape>(count)
                repeat(count) {
                    val kind = ActiveCape.Kind.byTag(d.readUnsignedByte())
                        ?: throw SyncProtocolException("неизвестный вид провайдера в ответе")
                    val priority = d.readInt()
                    val name = d.readString(SyncProtocol.MAX_NAME_BYTES)
                    val primary = d.readString(SyncProtocol.MAX_STRING_BYTES)
                    val extract = d.readString(SyncProtocol.MAX_STRING_BYTES)
                    providers += ActiveCape(name, kind, primary, extract, priority)
                }
                if (d.available() != 0) {
                    throw SyncProtocolException("в ответе ${d.available()} лишних байт")
                }
                return SyncResponse(requestId, providers, truncated)
            }
        } catch (e: SyncProtocolException) {
            throw e
        } catch (e: Exception) {
            throw SyncProtocolException("не удалось разобрать ответ: ${e.message.orEmpty()}")
        }
    }

    private fun requireVersion(version: Int) {
        if (version != SyncProtocol.VERSION) {
            throw SyncProtocolException(
                "версия протокола $version не поддерживается (ожидалась ${SyncProtocol.VERSION})",
            )
        }
    }

    private fun requirePayloadSize(bytes: ByteArray) {
        if (bytes.size > SyncProtocol.MAX_PAYLOAD_BYTES) {
            throw SyncProtocolException("payload ${bytes.size} байт, максимум ${SyncProtocol.MAX_PAYLOAD_BYTES}")
        }
    }

    /** Записать строку с префиксом-длиной; длина — в байтах UTF-8. */
    private fun DataOutputStream.writeString(value: String, max: Int) {
        val data = value.toByteArray(Charsets.UTF_8)
        if (data.size > max) {
            throw SyncProtocolException("строка ${data.size} байт, максимум $max")
        }
        writeShort(data.size)
        write(data)
    }

    /** Прочитать строку, проверяя лимит ДО аллокации. */
    private fun DataInputStream.readString(max: Int): String {
        val len = readUnsignedShort()
        if (len > max) {
            throw SyncProtocolException("строка $len байт, максимум $max")
        }
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}
