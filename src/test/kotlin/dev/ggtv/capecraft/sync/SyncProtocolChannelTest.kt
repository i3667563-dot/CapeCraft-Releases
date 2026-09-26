package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Инварианты id каналов Fabric.
 *
 * Регрессия: в 26.2 `CustomPacketPayload.createType("capecraft:sync_req")`
 * падал на старте с `IdentifierException` — `Identifier.withDefaultNamespace`
 * в 26.2 не проверяет наличие неймспейса, а безусловно подставляет
 * `minecraft` и берёт ВСЮ строку как path. На yarn `CustomPayload.id()`
 * такого не делает, поэтому баг проявлялся только на 26.2 и только в игре:
 * компиляция и 519 тестов были зелёными.
 *
 * Тест фиксирует то, из-за чего это вылезло: id канала — это всегда
 * `namespace:path` с одним двоеточием и с символами, которые Mojang-ный
 * `Identifier` принимает в обеих частях.
 */
class SyncProtocolChannelTest {

    /** Чейсет Mojang-ного Identifier: path и namespace. */
    private val allowed = "abcdefghijklmnopqrstuvwxyz0123456789._-/".toSet()

    private fun assertValidIdentifier(channel: String) {
        val (namespace, path) = SyncProtocol.channelParts(channel)
        assertTrue(namespace.isNotEmpty(), "пустой namespace в «$channel»")
        assertTrue(path.isNotEmpty(), "пустой path в «$channel»")
        val bad = (namespace + path).toSet() - allowed
        assertTrue(bad.isEmpty(), "недопустимые символы $bad в «$channel»")
    }

    @Test
    @DisplayName("каналы протокола — валидные Identifier для Mojang и Yarn")
    fun channelsAreValidIdentifiers() {
        assertValidIdentifier(SyncProtocol.REQUEST_CHANNEL)
        assertValidIdentifier(SyncProtocol.RESPONSE_CHANNEL)
    }

    @Test
    @DisplayName("разбор канала даёт те же части, что и у Identifier")
    fun partsRoundTrip() {
        for (channel in listOf(SyncProtocol.REQUEST_CHANNEL, SyncProtocol.RESPONSE_CHANNEL)) {
            val (namespace, path) = SyncProtocol.channelParts(channel)
            assertEquals(namespace, SyncProtocol.channelNamespace(channel))
            assertEquals(path, SyncProtocol.channelPath(channel))
            assertEquals(channel, "$namespace:$path")
        }
        assertEquals("capecraft", SyncProtocol.channelNamespace(SyncProtocol.REQUEST_CHANNEL))
        assertEquals("sync_req", SyncProtocol.channelPath(SyncProtocol.REQUEST_CHANNEL))
    }

    @Test
    @DisplayName("path канала не содержит двоеточия — иначе 26.2 упадёт на старте")
    fun pathHasNoColon() {
        // Ровно тот случай, что ломал 26.2: если бы канал целиком ушёл в path.
        for (channel in listOf(SyncProtocol.REQUEST_CHANNEL, SyncProtocol.RESPONSE_CHANNEL)) {
            assertTrue(
                !SyncProtocol.channelPath(channel).contains(':'),
                "path канала «$channel» содержит двоеточие",
            )
        }
    }

    @Test
    @DisplayName("канал без namespace или с лишним двоеточием отвергается")
    fun malformedChannelRejected() {
        // Строки, которые нельзя превратить в Identifier: упасть должно
        // рано и понятно, а не IdentifierException в игре.
        for (bad in listOf("sync_req", ":sync_req", "capecraft:", "a:b:c", "")) {
            assertThrows(IllegalArgumentException::class.java) { SyncProtocol.channelParts(bad) }
        }
    }
}
