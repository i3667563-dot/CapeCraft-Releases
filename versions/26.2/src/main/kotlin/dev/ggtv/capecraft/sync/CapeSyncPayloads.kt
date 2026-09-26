package dev.ggtv.capecraft.sync

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Сетевые payload'ы CapeCraft Sync v2 (Fabric custom payload, play-канал).
 *
 * Формат байт описан в [SyncCodec]; здесь — только обёртка в типы Minecraft.
 * Разделение намеренное: протокол проверяется обычными тестами без игры, а
 * здесь остаётся склейка с API версии (в 1.21.x/yarn это `CustomPayload` +
 * `PacketCodec` + `RegistryByteBuf`, в 26.2/Mojang — `CustomPacketPayload` +
 * `StreamCodec` + `RegistryFriendlyByteBuf`; форма одинаковая).
 *
 * ## Почему payload'ов всего два, а не по одному на сообщение
 *
 * В v1 их тоже было два, но каждый знал про свою форму: `Request` знал про
 * `requestId`, `Response` — про список провайдеров. В v2 сообщений пять
 * (announce, upload, fetch, roster, chunk), и если бы каждое стало отдельным
 * payload'ом, версионный слой размножился бы и в шести копиях пришлось бы
 * поддерживать пять типов.
 *
 * Здесь payload — просто байты, а разбор по типу из заголовка делает
 * [SyncCodec.decodeC2S]/[SyncCodec.decodeS2C]. Версионная обвязка не знает
 * ни про одно сообщение: её работа — «положить байты в сеть» и «достать байты
 * из сети». Разбор, версия и лимиты — в одном месте, поэтому клиент и сервер
 * проверяют их гарантированно одинаково, и в сеть не уходит «случайный»
 * байтовый формат в обход валидации.
 *
 * Буфер — [RegistryFriendlyByteBuf]: play-регистры Fabric принимают кодек
 * именно с таким типом буфера.
 */
object CapeSyncPayloads {

    /** C2S: любой клиентский пакет — announce / upload / fetch. */
    class ClientMessage(val bytes: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<ClientMessage> = TYPE

        override fun equals(other: Any?): Boolean =
            other is ClientMessage && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        companion object {
            // НЕ createType(): в 26.2 он подставляет неймспейс `minecraft`
            // ко всей строке и ломает `ns:path` — см. SyncProtocol.channelParts.
            val TYPE: CustomPacketPayload.Type<ClientMessage> = CustomPacketPayload.Type(
                Identifier.fromNamespaceAndPath(
                    SyncProtocol.channelNamespace(SyncProtocol.REQUEST_CHANNEL),
                    SyncProtocol.channelPath(SyncProtocol.REQUEST_CHANNEL),
                ),
            )

            val CODEC: StreamCodec<RegistryFriendlyByteBuf, ClientMessage> =
                object : StreamCodec<RegistryFriendlyByteBuf, ClientMessage> {
                    override fun encode(buf: RegistryFriendlyByteBuf, value: ClientMessage) {
                        buf.writeBytes(value.bytes)
                    }

                    override fun decode(buf: RegistryFriendlyByteBuf): ClientMessage {
                        val bytes = ByteArray(buf.readableBytes())
                        buf.readBytes(bytes)
                        return ClientMessage(bytes)
                    }
                }
        }
    }

    /** S2C: любой серверный пакет — roster / chunk. */
    class ServerMessage(val bytes: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<ServerMessage> = TYPE

        override fun equals(other: Any?): Boolean =
            other is ServerMessage && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        companion object {
            val TYPE: CustomPacketPayload.Type<ServerMessage> = CustomPacketPayload.Type(
                Identifier.fromNamespaceAndPath(
                    SyncProtocol.channelNamespace(SyncProtocol.RESPONSE_CHANNEL),
                    SyncProtocol.channelPath(SyncProtocol.RESPONSE_CHANNEL),
                ),
            )

            val CODEC: StreamCodec<RegistryFriendlyByteBuf, ServerMessage> =
                object : StreamCodec<RegistryFriendlyByteBuf, ServerMessage> {
                    override fun encode(buf: RegistryFriendlyByteBuf, value: ServerMessage) {
                        buf.writeBytes(value.bytes)
                    }

                    override fun decode(buf: RegistryFriendlyByteBuf): ServerMessage {
                        val bytes = ByteArray(buf.readableBytes())
                        buf.readBytes(bytes)
                        return ServerMessage(bytes)
                    }
                }
        }
    }

    /**
     * Проверить, что в байтах C2S лежит сообщение, а не обрывок пакета.
     *
     * Отдельный метод, а не `decodeC2S` в обработчике, чтобы версионный слой
     * мог отличить «это не наше» от «наше, но битое» и не логировать второе
     * как ошибку протокола на каждом чужом канале.
     */
    fun isClientMessage(bytes: ByteArray): Boolean = bytes.size >= 2 &&
        (bytes[0].toInt() and 0xFF) == SyncProtocol.VERSION
}
