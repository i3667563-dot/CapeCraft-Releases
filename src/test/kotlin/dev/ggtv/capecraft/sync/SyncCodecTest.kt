package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wire-формат Sync v2 и его защита от битых пакетов.
 *
 * Второе важнее первого. Кодеку приходит всё, что прислал клиент, а клиент
 * может быть чем угодно, поэтому каждая длина проверяется **до** аллокации:
 * иначе пакет с `length = 0x7FFFFFFF` заставит клиент выделить два гигабайта
 * и упасть. Здесь это проверяется явно, а не подразумевается.
 */
class SyncCodecTest {

    private fun url(name: String, priority: Int = 0) = ActiveCape(
        kind = ActiveCape.Kind.URL,
        name = name,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        condition = null,
        imageHash = null,
    )

    // ── круговые преобразования ─────────────────────────────────────────────

    @Test
    fun `объявление переживает круг без потерь`() {
        val functions = listOf(url("a", priority = 3), url("b", priority = 10))
        val decoded = SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(functions)))

        assertEquals(2, decoded.functions.size)
        assertEquals(3, decoded.functions[0].priority)
        assertEquals(10, decoded.functions[1].priority)
    }

    @Test
    fun `пустое объявление допустимо`() {
        val decoded = SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(emptyList())))
        assertTrue(decoded.functions.isEmpty())
    }

    @Test
    fun `роустер переживает круг вместе с ревизией и флагом обрезки`() {
        val roster = Roster(
            revision = 123456789L,
            objects = listOf(RosterObject("obj-1", listOf(url("a"))), RosterObject("obj-2", emptyList())),
            truncated = 7,
        )
        val decoded = SyncCodec.decodeRoster(SyncCodec.encodeRoster(roster))
        assertEquals(123456789L, decoded.revision)
        assertEquals(2, decoded.objects.size)
        assertEquals("obj-1", decoded.objects[0].id)
        // На проводе флаг один байт: «обрезали или нет». Сколько именно отвалилось
        // — не едет, и это не потеря: получатель всё равно не знает, кого
        // именно не дослали, поэтому считать нечего. Клиент по флагу обязан
        // беречь последнееKnown-состояние, а не считать, что список полный.
        assertTrue(decoded.truncated > 0, "флаг обрезки обязан пережить сеть")
        assertEquals(0, SyncCodec.decodeRoster(SyncCodec.encodeRoster(roster.copy(truncated = 0))).truncated)
    }

    @Test
    fun `запрос картинки переживает круг`() {
        val hash = ImageHash.compute(byteArrayOf(1, 2, 3))
        val decoded = SyncCodec.decodeFetch(SyncCodec.encodeFetch(Fetch(hash, offset = 1024, length = 64)))
        assertEquals(hash, decoded.hash)
        assertEquals(1024, decoded.offset)
        assertEquals(64, decoded.length)
    }

    @Test
    fun `чанк переживает круг вместе с байтами`() {
        val hash = ImageHash.compute(byteArrayOf(4, 5))
        val payload = ByteArray(300) { (it % 251).toByte() }
        // totalSize — размер ВСЕЙ картинки, а не этого куска: 128 + 300 = 428.
        val decoded = SyncCodec.decodeUpload(SyncCodec.encodeUpload(Upload(hash, 428, 128, payload)))
        assertEquals(hash, decoded.hash)
        assertEquals(428, decoded.totalSize)
        assertEquals(128, decoded.offset)
        assertTrue(decoded.bytes.contentEquals(payload), "байты чанка изменились по дороге")
    }

    // ── версия и тип ────────────────────────────────────────────────────────

    @Test
    fun `пакет другой версии отвергается`() {
        val bytes = SyncCodec.encodeAnnounce(Announce(listOf(url("a"))))
        bytes[0] = (SyncProtocol.VERSION + 1).toByte()
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(bytes) }
    }

    @Test
    fun `пакет, отправленный в чужую сторону, не декодируется`() {
        // Ростер — серверное сообщение. Клиент не должен принять его как C2S
        // (и наоборот): иначе один узел может навязать другому чужую роль.
        val rosterBytes = SyncCodec.encodeRoster(Roster(1, listOf(RosterObject("a", listOf(url("x"))))))
        assertFailsWith<Exception> { SyncCodec.decodeC2S(rosterBytes) }
    }

    @Test
    fun `мусор вместо сообщения отвергается`() {
        for (garbage in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(2, 3), ByteArray(4) { 0xFF.toByte() })) {
            assertFailsWith<Exception>("мусор ${garbage.toList()} не должен приниматься") {
                SyncCodec.decodeC2S(garbage)
            }
        }
    }

    @Test
    fun `хвост после сообщения отвергается`() {
        val bytes = SyncCodec.encodeAnnounce(Announce(listOf(url("a")))) + byteArrayOf(0x7F, 0x7F)
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(bytes) }
    }

    // ── лимиты до аллокации ─────────────────────────────────────────────────

    @Test
    fun `заявленное число функций сверх лимита отвергается, а не читается`() {
        // Считаем «список из 60000 функций» в 12 байтах. Наивный кодек выделил
        // бы под них память и упал бы на первом же чтении; наш обязан отказать
        // сразу, опираясь только на заголовок.
        val out = java.io.ByteArrayOutputStream()
        out.write(SyncProtocol.VERSION)
        out.write(SyncProtocol.MSG_ANNOUNCE)
        out.write(60000 shr 8)
        out.write(60000 and 0xFF)
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(out.toByteArray()) }
    }

    @Test
    fun `функция с безумной длиной имени отвергается`() {
        val name = "x".repeat(100_000)
        val cape = ActiveCape(
            kind = ActiveCape.Kind.URL,
            name = name,
            primary = "https://example.invalid/a.png",
            extract = "",
            priority = 0,
            condition = null,
            imageHash = null,
        )
        assertFailsWith<Exception> { SyncCodec.encodeAnnounce(Announce(listOf(cape))) }
    }

    @Test
    fun `функция с безумной длиной URL отвергается`() {
        val cape = ActiveCape(
            kind = ActiveCape.Kind.URL,
            name = "a",
            primary = "https://example.invalid/" + "p".repeat(200_000),
            extract = "",
            priority = 0,
            condition = null,
            imageHash = null,
        )
        assertFailsWith<Exception> { SyncCodec.encodeAnnounce(Announce(listOf(cape))) }
    }

    @Test
    fun `чанк больше лимита не кодируется`() {
        val tooBig = ByteArray(SyncProtocol.MAX_IMAGE_BYTES + 1)
        assertFailsWith<Exception> {
            SyncCodec.encodeUpload(Upload(ImageHash.compute(byteArrayOf(1)), tooBig.size, 0, tooBig))
        }
    }

    @Test
    fun `смещение за пределами картинки отвергается`() {
        val hash = ImageHash.compute(byteArrayOf(1))
        val declaredTotal = 10
        val bytes = ByteArray(100)
        assertFailsWith<Exception> {
            SyncCodec.encodeUpload(Upload(hash, declaredTotal, declaredTotal + 50, bytes))
        }
    }

    @Test
    fun `условие глубже лимита предикатов не кодируется`() {
        val preds = (0 until SyncProtocol.MAX_PREDICATES + 5).map {
            WirePredicate(WireRoot.BIOME, "temperature", WireOp.GT, WireExpected.Str("0.5"))
        }
        val cape = url("a").copy(condition = WireCondition(preds))
        assertFailsWith<Exception> { SyncCodec.encodeAnnounce(Announce(listOf(cape))) }
    }

    @Test
    fun `id объекта длиннее лимита не декодируется`() {
        // Формат по SyncCodec.encodeRoster: версия, тип, revision (long),
        // число объектов (short), флаг обрезки, затем id объекта.
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeByte(SyncProtocol.VERSION)
        d.writeByte(SyncProtocol.MSG_ROSTER)
        d.writeLong(1L)
        d.writeShort(1)
        d.writeByte(0)
        val longId = "i".repeat(500)
        // Длина UTF-8 = 500 байт, намеренно больше MAX_OBJECT_ID_BYTES.
        d.writeShort(longId.length)
        d.write(longId.toByteArray())
        assertFailsWith<Exception> { SyncCodec.decodeRoster(out.toByteArray()) }
    }
}
