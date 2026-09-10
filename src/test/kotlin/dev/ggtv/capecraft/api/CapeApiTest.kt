package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues
import dev.ggtv.capecraft.api.image.CapeDecoder
import dev.ggtv.capecraft.api.CapeDecoderSpec
import dev.ggtv.capecraft.api.placeholder.CapePlaceholder
import dev.ggtv.capecraft.api.placeholder.PlaceholderContext
import dev.ggtv.capecraft.api.CapePlaceholderSpec
import dev.ggtv.capecraft.image.ImageDecodeException
import dev.ggtv.capecraft.api.event.CapeEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class CapeApiTest {

    private lateinit var api: CapeApi

    @BeforeEach
    fun setup() {
        api = CapeApi()
    }

    @Test
    fun `sourceTypes register and lookup`() {
        val type = CapeSourceType("custom") { values ->
            CapeSource { "custom:${values.name}".toByteArray() }
        }
        api.sourceTypes.register(type)

        assertNotNull(api.sourceTypes["custom"])
        assertNull(api.sourceTypes["other"])
        assertEquals(listOf("custom"), api.sourceTypes.ids())
    }

    @Test
    fun `sourceTypes returns null on unknown`() {
        assertNull(api.sourceTypes["nonexistent"])
    }

    @Test
    fun `decoders register and lookup`() {
        val spec = CapeDecoderSpec(
            id = "bmp",
            detect = { data -> data.size >= 2 && data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte() },
            decoder = CapeDecoder { data, _ -> throw ImageDecodeException("stub", null) },
        )
        api.decoders.register(spec)

        assertTrue(api.decoders.ids().contains("bmp"))
        assertFalse(api.decoders.ids().contains("png"))
    }

    @Test
    fun `decoders detect matching decoder`() {
        val spec = CapeDecoderSpec(
            id = "sig",
            detect = { data -> data.size >= 3 && data[0] == 0xAA.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xCC.toByte() },
            decoder = CapeDecoder { data, _ -> throw ImageDecodeException("stub", null) },
        )
        api.decoders.register(spec)
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x01)
        val found = api.decoders.decoderFor(data)
        assertNotNull(found)
        assertEquals("sig", found!!.id)
    }

    @Test
    fun `decoders returns null when nothing matches`() {
        assertNull(api.decoders.decoderFor(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `placeholders register and resolve`() {
        val spec = CapePlaceholderSpec(
            name = "level",
            resolver = CapePlaceholder { ctx -> "${ctx.name}-level" },
        )
        api.placeholders.register(spec)

        assertTrue(api.placeholders.names().contains("level"))
        val ctx = PlaceholderContext(username = "user1", uuid = "uuid123", name = "testPlayer", root = "/root")
        val result = api.placeholders["level"]!!.resolver.resolve(ctx)
        assertEquals("testPlayer-level", result)
    }

    @Test
    fun `placeholders returns null on unknown`() {
        assertNull(api.placeholders["nope"])
    }

    @Test
    fun `eventBus emit and receive`() {
        val loadFailed = mutableListOf<CapeEvent>()
        val providerNotFound = mutableListOf<CapeEvent>()
        api.events.on(CapeEvent.Type.CAPE_LOAD_FAILED) { loadFailed += it }
        api.events.on(CapeEvent.Type.PROVIDER_NOT_FOUND) { providerNotFound += it }

        api.events.emit(CapeEvent.CapeLoadFailed("uuid1", "player1", "reason"))
        api.events.emit(CapeEvent.ProviderNotFound("uuid2", "player2", "not found"))

        assertEquals(1, loadFailed.size)
        assertEquals(1, providerNotFound.size)
        assertEquals("uuid1", (loadFailed[0] as CapeEvent.CapeLoadFailed).uuid)
        assertEquals("uuid2", (providerNotFound[0] as CapeEvent.ProviderNotFound).uuid)
    }

    @Test
    fun `eventBus multiple listeners`() {
        var count = 0
        api.events.on(CapeEvent.Type.CAPE_LOAD_FAILED) { count++ }
        api.events.on(CapeEvent.Type.CAPE_LOAD_FAILED) { count++ }
        api.events.emit(CapeEvent.CapeLoadFailed("a", "b", "reason"))
        assertEquals(2, count)
    }

    @Test
    fun `eventBus listener exception does not crash`() {
        api.events.on(CapeEvent.Type.CAPE_LOAD_FAILED) { throw RuntimeException("boom") }
        api.events.emit(CapeEvent.CapeLoadFailed("a", "b", "reason"))
    }
}
