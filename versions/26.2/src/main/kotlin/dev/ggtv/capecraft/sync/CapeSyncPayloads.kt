package dev.ggtv.capecraft.sync

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Сетевые payload'ы CapeCraft Sync (Fabric custom payload, play-канал).
 *
 * Формат байт описан в [SyncCodec]; здесь — только обёртка в типы Minecraft.
 * Разделение намеренное: протокол проверяется обычными тестами без игры, а
 * здесь остаётся склейка с API версии (в 1.21.x/yarn это `CustomPayload` +
 * `PacketCodec` + `RegistryByteBuf`, в 26.2/Mojang — `CustomPacketPayload` +
 * `StreamCodec` + `RegistryFriendlyByteBuf`; форма одинаковая).
 *
 * Оба payload'а кодируются ОДНИМ И ТЕМ ЖЕ [SyncCodec]: так клиент и сервер
 * гарантированно проверяют версию протокола и лимиты одинаково, и в сеть не
 * уходит «случайный» байтовый формат в обход валидации.
 *
 * Буфер — [RegistryFriendlyByteBuf]: play-регистры Fabric принимают кодек
 * именно с таким типом буфера.
 */
object CapeSyncPayloads {

    /** C2S: клиент спрашивает активный набор плащей. */
    data class Request(val requestId: Int) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Request> = TYPE
        fun toSyncState(): SyncRequest = SyncRequest(requestId)

        companion object {
            val TYPE: CustomPacketPayload.Type<Request> = CustomPacketPayload.createType(SyncProtocol.REQUEST_CHANNEL)
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Request> =
                object : StreamCodec<RegistryFriendlyByteBuf, Request> {
                    override fun encode(buf: RegistryFriendlyByteBuf, value: Request) {
                        buf.writeBytes(value.toSyncState().encode())
                    }

                    override fun decode(buf: RegistryFriendlyByteBuf): Request {
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
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Response> = TYPE
        fun toSyncState(): SyncResponse = SyncResponse(requestId, providers, truncated)

        companion object {
            val TYPE: CustomPacketPayload.Type<Response> = CustomPacketPayload.createType(SyncProtocol.RESPONSE_CHANNEL)
            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Response> =
                object : StreamCodec<RegistryFriendlyByteBuf, Response> {
                    override fun encode(buf: RegistryFriendlyByteBuf, value: Response) {
                        buf.writeBytes(value.toSyncState().encode())
                    }

                    override fun decode(buf: RegistryFriendlyByteBuf): Response {
                        val bytes = ByteArray(buf.readableBytes())
                        buf.readBytes(bytes)
                        val decoded = SyncCodec.decodeResponse(bytes)
                        return Response(decoded.requestId, decoded.providers, decoded.truncated)
                    }
                }
        }
    }
}
