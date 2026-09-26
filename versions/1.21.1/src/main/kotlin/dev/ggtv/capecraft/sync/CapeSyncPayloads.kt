package dev.ggtv.capecraft.sync

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * Сетевые payload'ы CapeCraft Sync (Fabric custom payload, play-канал).
 *
 * Формат байт описан в [SyncCodec]; здесь — только обёртка в типы Minecraft.
 * Разделение намеренное: протокол проверяется обычными тестами без игры, а
 * здесь остаётся склейка с API версии (в 1.21.x/yarn и в 26.2/Mojang API
 * называется по-разному, но форма одинаковая).
 *
 * Оба payload'а кодируются ОДНИМ И ТЕМ ЖЕ [SyncCodec]: так клиент и сервер
 * гарантированно проверяют версию протокола и лимиты одинаково, и в сеть не
 * уходит «случайный» байтовый формат в обход валидации.
 *
 * Буфер — [RegistryByteBuf]: play-регистры Fabric принимают кодек именно с
 * таким типом буфера.
 */
object CapeSyncPayloads {

    /** C2S: клиент спрашивает активный набор плащей. */
    data class Request(val requestId: Int) : CustomPayload {
        override fun getId(): CustomPayload.Id<Request> = TYPE
        fun toSyncState(): SyncRequest = SyncRequest(requestId)

        companion object {
            val TYPE: CustomPayload.Id<Request> = CustomPayload.id(SyncProtocol.REQUEST_CHANNEL)
            val CODEC: PacketCodec<RegistryByteBuf, Request> = object : PacketCodec<RegistryByteBuf, Request> {
                override fun encode(buf: RegistryByteBuf, value: Request) {
                    buf.writeBytes(value.toSyncState().encode())
                }

                override fun decode(buf: RegistryByteBuf): Request {
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    return Request(SyncCodec.decodeRequest(bytes).requestId)
                }
            }
        }
    }

    /** S2C: сервер присылает ТОЛЬКО активные провайдеры для этого игрока. */
    data class Response(
        val requestId: Int,
        val providers: List<ActiveCape>,
        val truncated: Int = 0,
    ) : CustomPayload {
        override fun getId(): CustomPayload.Id<Response> = TYPE
        fun toSyncState(): SyncResponse = SyncResponse(requestId, providers, truncated)

        companion object {
            val TYPE: CustomPayload.Id<Response> = CustomPayload.id(SyncProtocol.RESPONSE_CHANNEL)
            val CODEC: PacketCodec<RegistryByteBuf, Response> = object : PacketCodec<RegistryByteBuf, Response> {
                override fun encode(buf: RegistryByteBuf, value: Response) {
                    buf.writeBytes(value.toSyncState().encode())
                }

                override fun decode(buf: RegistryByteBuf): Response {
                    val bytes = ByteArray(buf.readableBytes())
                    buf.readBytes(bytes)
                    val decoded = SyncCodec.decodeResponse(bytes)
                    return Response(decoded.requestId, decoded.providers, decoded.truncated)
                }
            }
        }
    }
}
