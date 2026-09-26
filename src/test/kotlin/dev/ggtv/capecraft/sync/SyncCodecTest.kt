package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Протокол v1: круговой прогон и строгий разбор. Ключевое свойство —
 * битый/чуждой payload обязан падать, а не молча разбираться «как получится».
 */
class SyncCodecTest {

    private fun provider(name: String, kind: ActiveCape.Kind, priority: Int = 0) = ActiveCape(
        name = name,
        kind = kind,
        primary = when (kind) {
            ActiveCape.Kind.URL -> "https://example.com/$name.png"
            ActiveCape.Kind.FILE -> "{root}/capes/{username}.png"
            ActiveCape.Kind.JSON -> "https://example.com/a.json"
            ActiveCape.Kind.ADDON -> "kjen"
        },
        extract = if (kind == ActiveCape.Kind.JSON) "data[0].url" else "",
        priority = priority,
    )

    @Test
    fun `request survives a round trip`() {
        val req = SyncRequest(0x01020304)
        assertEquals(req, SyncCodec.decodeRequest(SyncCodec.encodeRequest(req)))
    }

    @Test
    fun `request with negative id survives a round trip`() {
        val req = SyncRequest(-1)
        assertEquals(req, SyncCodec.decodeRequest(SyncCodec.encodeRequest(req)))
    }

    @Test
    fun `response survives a round trip`() {
        val res = SyncResponse(
            requestId = 42,
            providers = listOf(
                provider("url", ActiveCape.Kind.URL, 10),
                provider("file", ActiveCape.Kind.FILE),
                provider("json", ActiveCape.Kind.JSON),
                provider("addon", ActiveCape.Kind.ADDON),
            ),
        )
        assertEquals(res, SyncCodec.decodeResponse(SyncCodec.encodeResponse(res)))
    }

    @Test
    fun `empty response is a valid no-capes answer, not an error`() {
        val res = SyncResponse(requestId = 7, providers = emptyList())
        assertEquals(res, SyncCodec.decodeResponse(SyncCodec.encodeResponse(res)))
    }

    @Test
    fun `truncated flag is one bit on the wire`() {
        val bytes = SyncCodec.encodeResponse(SyncResponse(1, emptyList(), truncated = 3))
        // u8 version + i32 id + u8 flag + u8 count
        assertEquals(1, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(1, SyncCodec.decodeResponse(bytes).truncated)
    }

    @Test
    fun `non-ascii provider names are measured in utf-8 bytes`() {
        val res = SyncResponse(1, listOf(provider("плащ-😀", ActiveCape.Kind.URL)))
        val decoded = SyncCodec.decodeResponse(SyncCodec.encodeResponse(res))
        assertEquals("плащ-😀", decoded.providers.single().name)
    }

    @Test
    fun `wrong protocol version is rejected`() {
        val bytes = SyncCodec.encodeRequest(SyncRequest(1)).also { it[0] = 99 }
        val e = assertThrows<SyncProtocolException> { SyncCodec.decodeRequest(bytes) }
        assertTrue(e.message!!.contains("версия"), e.message)
    }

    @Test
    fun `unknown provider kind is rejected`() {
        val head = header(count = 1)
        val body = head + byteArrayOf(0x7F) // несуществующий вид провайдера
        assertThrows<SyncProtocolException> { SyncCodec.decodeResponse(body) }
    }

    @Test
    fun `provider count above the protocol limit is rejected`() {
        val head = header(count = SyncProtocol.MAX_PROVIDERS + 1)
        assertThrows<SyncProtocolException> { SyncCodec.decodeResponse(head) }
    }

    @Test
    fun `oversized name is rejected before allocation`() {
        // kind=URL, priority=0, затем длина имени 0xFFFF при лимите 128.
        val body = header(count = 1) + byteArrayOf(0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())
        val e = assertThrows<SyncProtocolException> { SyncCodec.decodeResponse(body) }
        assertTrue(e.message!!.contains("максимум"), e.message)
    }

    @Test
    fun `trailing garbage in a request is rejected`() {
        val bytes = SyncCodec.encodeRequest(SyncRequest(1)) + byteArrayOf(9)
        assertThrows<SyncProtocolException> { SyncCodec.decodeRequest(bytes) }
    }

    @Test
    fun `truncated request body is rejected instead of reading past the end`() {
        assertThrows<SyncProtocolException> { SyncCodec.decodeRequest(byteArrayOf(SyncProtocol.VERSION.toByte(), 0)) }
    }

    @Test
    fun `too many providers cannot be encoded`() {
        val many = List(SyncProtocol.MAX_PROVIDERS + 1) {
            provider("p$it", ActiveCape.Kind.URL)
        }
        assertThrows<SyncProtocolException> { SyncCodec.encodeResponse(SyncResponse(1, many)) }
    }

    @Test
    fun `oversized field cannot be encoded`() {
        val huge = ActiveCape("n", ActiveCape.Kind.URL, "https://e.com/${"a".repeat(SyncProtocol.MAX_STRING_BYTES)}")
        assertThrows<SyncProtocolException> { SyncCodec.encodeResponse(SyncResponse(1, listOf(huge))) }
    }

    @Test
    fun `payload above the size cap is rejected`() {
        assertThrows<SyncProtocolException> {
            SyncCodec.decodeRequest(ByteArray(SyncProtocol.MAX_PAYLOAD_BYTES + 1))
        }
    }

    @Test
    fun `provider limit fits into one byte on the wire`() {
        assertTrue(SyncProtocol.MAX_PROVIDERS <= 255, "счётчик провайдеров — u8")
    }

    @Test
    fun `maximum sized response stays inside the payload cap`() {
        val max = List(SyncProtocol.MAX_PROVIDERS) {
            ActiveCape(
                name = "n$it",
                kind = ActiveCape.Kind.URL,
                primary = "a".repeat(SyncProtocol.MAX_STRING_BYTES),
            )
        }
        val bytes = SyncCodec.encodeResponse(SyncResponse(1, max))
        assertTrue(bytes.size <= SyncProtocol.MAX_PAYLOAD_BYTES, "${bytes.size} байт")
        assertContentEquals(max, SyncCodec.decodeResponse(bytes).providers)
    }

    /** Заголовок ответа: версия + requestId + флаг + счётчик провайдеров. */
    private fun header(count: Int, truncated: Boolean = false): ByteArray = byteArrayOf(
        SyncProtocol.VERSION.toByte(),
        0, 0, 0, 1,
        (if (truncated) 1 else 0).toByte(),
        count.toByte(),
    )
}
