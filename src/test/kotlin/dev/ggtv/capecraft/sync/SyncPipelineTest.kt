package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Сквозной прогон Sync v2 без Minecraft: два клиента и сервер между ними,
 * всё общение — только через настоящие байты [SyncCodec].
 *
 * Остальные тесты проверяют по кускам: машина состояния сама по себе, codec
 * сам по себе, сервер сам по себе. Эти проверки не ловят главный класс ошибок —
 * рассинхрон между ними: сервер считает, что картинка есть, владелец thinks,
 * что дослал, а получатель собирает из кусков не то. Здесь общение идёт через
 * wire тем же кодом, который пойдёт в игру.
 */
class SyncPipelineTest {

    private fun pngOf(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    private fun localFileProvider(name: String) = Provider(
        name = name,
        source = Source.File("/home/игрок/плащи/$name.png"),
    )

    /** Ровно то, что делает клиент: нарезает недосланное и отдаёт следующий кусок. */
    private fun nextChunk(state: CapeSyncState, hash: ImageHash, bytes: ByteArray): Upload {
        val offset = state.uploadedBytesOf(hash)
        val end = minOf(offset + SyncProtocol.CHUNK_BYTES, bytes.size)
        return Upload(hash, bytes.size, offset, bytes.copyOfRange(offset, end))
    }

    // ── полный круг: объявление → роустер → докачка → чужие байты ────────────

    @Test
    fun `владелец отдаёт чужому клиенту ровно свои байты`() {
        val bytes = pngOf(size = SyncProtocol.CHUNK_BYTES * 3 + 17, seed = 1)
        val hash = ImageHash.compute(bytes)
        val store = RosterStore()
        val images = ImageStore()

        // ── клиент A: свой набор с локальным файлом ──
        val a = CapeSyncState()
        val mine = localFileProvider("мой-плащ").toActiveCape(hash)
        assertNotNull(mine, "локальный файл обязан объявляться, а с хэшем")
        val announce = a.onConfigChanged(listOf(mine))
            .filterIsInstance<SyncOutbound.Announce>()
            .single()

        // Путь наружу не уходит: на проводе только хэш.
        val wireFunction = announce.functions.single()
        assertEquals(ActiveCape.Kind.FILE, wireFunction.kind)
        assertEquals("", wireFunction.primary, "путь локального файла обязан быть стёрт на кодировании")
        assertEquals(hash, wireFunction.imageHash)

        // ── сервер: принял объявление A ──
        val decodedAnnounce = SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(announce.functions)))
        assertTrue(store.announce("игрок-A", decodedAnnounce.functions), "сервер обязан принять объявление")

        // B тоже в сети и тоже объявился: без кого-то в роустере A не знает,
        // что его плащ кому-то нужен, и молча ничего не заливает.
        store.announce("игрок-B", SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(emptyList()))).functions)
        assertEquals(2, store.size(), "в роустере оба игрока")

        // ── клиент B: применил снимок и увидел чужой набор ──
        val b = CapeSyncState()
        val snapshot = store.snapshot()
        assertTrue(b.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(snapshot))), "первый снимок обязан быть принят")

        val aInRoster = b.roster.objects.single { it.id == "игрок-A" }
        val foreign = SyncRosterPolicy.toLocalProviders(aInRoster.functions, allowForeignUrls = false)
        assertEquals(1, foreign.size, "чужой набор должен восстановиться в один провайдер")
        val netSource = foreign.single().source
        assertTrue(netSource is Source.NetImage, "чужой файл обязан прийти по хэшу, а не по пути: $netSource")
        assertEquals(ImageHash.toHex(hash), netSource.hash)

        // ── A: его картинку затребовали, он заливает её кусками ──
        // Что B получит в снимке: хэши из наборов всех, кроме него самого.
        val wantedByB = SyncRosterPolicy.imageHashesOf(
            store.snapshot().objects.filter { it.id != "игрок-B" }.flatMap { it.functions },
        )
        assertEquals(listOf(hash), wantedByB, "в снимке для B обязана быть ссылка именно на эту картинку")
        assertEquals(
            listOf(hash),
            b.wantedHashes(),
            "клиент обязан понять, что ему надо доскачать",
        )

        var sends = 0
        var sawComplete = false
        // Что A считает нужным залить: свои картинки, раз в роустере есть
        // кто-то ещё, плюс то, на что сослались чужие наборы.
        a.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot())))
        val toPublish = a.publishableHashes("игрок-A")
        assertEquals(setOf(hash), toPublish, "свой плащ обязан пойти на сервер, пока в лобби есть кто-то")
        // Именно так работает клиент: каждый тик заново спрашивает, что ещё
        // не дослано. Список не копится, а пересчитывается — иначе вторая
        // половина картинки не ушла бы никогда.
        while (true) {
            val pending = a.pendingUploads(toPublish, setOf(hash))
            if (pending.isEmpty()) break
            val hashToSend = pending.first()
            val wire = SyncCodec.decodeUpload(SyncCodec.encodeUpload(nextChunk(a, hashToSend, bytes)))
            when (val res = images.acceptChunk("игрок-A", wire)) {
                is ImageStore.UploadResult.Progress -> {
                    assertEquals(sends * SyncProtocol.CHUNK_BYTES, wire.offset, "куски должны идти подряд, без дыр")
                    assertTrue(!wire.isLast, "картинка больше чанка — последний кусок ещё не пришёл")
                }

                is ImageStore.UploadResult.Complete -> {
                    assertTrue(wire.isLast, "сервер не должен считать картинку собранной досрочно")
                    sawComplete = true
                }

                is ImageStore.UploadResult.Rejected -> error("сервер отверг корректный кусок: ${res.reason}")
            }
            a.markUploadProgress(wire.hash, wire.offset + wire.bytes.size)
            if (wire.isLast) a.markUploadComplete(wire.hash)
            sends++
        }

        assertEquals(4, sends, "3 чанка по 64 КиБ плюс хвост — четыре отправки")
        assertTrue(sawComplete, "сервер обязан подтвердить сборку картинки")
        assertTrue(images.has(hash), "картинка обязана лежать на сервере")
        assertContentEquals(bytes, images.get(hash), "собранные сервером байты обязаны совпасть с исходными")

        // ── B: забрал куски с сервера и сложил себе ──
        var fetched = 0
        var assembled: ByteArray? = null
        // Клиент дёргает onTick каждый тик, а запросы идут с backoff — за один
        // тик всю картинку не взять, и это нормально, а не баг.
        for (tick in 0 until 6000) {
            b.onTickAdvance()
            for (out in b.onTick(channelReady = true)) {
                val fetch = out as? SyncOutbound.FetchImage ?: continue
                val raw = images.chunk(fetch.hash, fetch.offset, fetch.length)
                assertNotNull(raw, "сервер обязан отдать запрошенный кусок")
                val wire = SyncCodec.decodeChunk(
                    SyncCodec.encodeChunk(Chunk(fetch.hash, bytes.size, fetch.offset, raw)),
                )
                when (val res = b.onChunk(wire)) {
                    is ImageAssembler.Result.Complete -> assembled = res.bytes
                    is ImageAssembler.Result.Rejected -> error("получатель отверг корректные куски: ${res.reason}")
                    is ImageAssembler.Result.Progress -> assertEquals(wire.hash, fetch.hash)
                }
                fetched++
            }
            if (assembled != null) break
        }

        assertEquals(4, fetched, "получатель должен взять столько же кусков, сколько отдал владелец")
        assertContentEquals(
            bytes,
            assembled,
            "второй клиент собрал не те байты — весь смысл синхронизации в этом",
        )
    }

    @Test
    fun `медленная передача не начинается заново`() {
        val bytes = pngOf(size = SyncProtocol.CHUNK_BYTES * 2 + 5, seed = 3)
        val hash = ImageHash.compute(bytes)
        val store = RosterStore()
        val images = ImageStore()

        val a = CapeSyncState()
        a.onConfigChanged(listOf(localFileProvider("плащ").toActiveCape(hash)!!))
        store.announce("A", listOf(ActiveCape("плащ", ActiveCape.Kind.FILE, "", "", 0, null, hash)))
        store.announce("B", emptyList())

        val b = CapeSyncState()
        b.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot())))
        // A тоже видит снимок: только так он понимает, что в лобби есть кто-то
        // ещё и его картинка кому-то нужна.
        a.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot())))

        // Пока владелец не залил ничего, запросы получают отказ и backoff растёт.
        // Именно в этом окне таймаут зависания обязан НЕ сработать: ожидание
        // законное, а не зависание.
        var refusedTicks = 0
        while (images.chunk(hash, 0, SyncProtocol.CHUNK_BYTES) == null && refusedTicks < 4000) {
            b.onTickAdvance()
            for (out in b.onTick(channelReady = true)) {
                val fetch = out as SyncOutbound.FetchImage
                if (images.chunk(hash, fetch.offset, fetch.length) == null) {
                    b.onChunk(Chunk(hash, bytes.size, fetch.offset, ByteArray(0)))
                }
            }
            refusedTicks++
        }
        assertTrue(refusedTicks > 0, "окно отказов должно было отработать, иначе тест ничего не проверяет")

        // Владелец наконец заливает всё.
        var sent = 0
        while (true) {
            val pending = a.pendingUploads(a.publishableHashes("A"), setOf(hash))
            if (pending.isEmpty()) break
            val wire = SyncCodec.decodeUpload(SyncCodec.encodeUpload(nextChunk(a, hash, bytes)))
            images.acceptChunk("A", wire)
            a.markUploadProgress(wire.hash, wire.offset + wire.bytes.size)
            if (wire.isLast) a.markUploadComplete(wire.hash)
            sent++
        }
        assertTrue(images.has(hash), "владелец обязан был дослать остаток")

        // Получатель доходит до конца. Число запрошенных кусков не должно
        // превышать размер картинки: лишние запросы означали бы, что готовое
        // начало выбрасывалось и качалось заново.
        var requested = 0
        var assembled: ByteArray? = null
        for (tick in 0 until 6000) {
            b.onTickAdvance()
            for (out in b.onTick(channelReady = true)) {
                val fetch = out as SyncOutbound.FetchImage
                val raw = images.chunk(fetch.hash, fetch.offset, fetch.length) ?: continue
                val res = b.onChunk(SyncCodec.decodeChunk(SyncCodec.encodeChunk(Chunk(fetch.hash, bytes.size, fetch.offset, raw))))
when (res) {
                    is ImageAssembler.Result.Complete -> assembled = res.bytes
                    is ImageAssembler.Result.Rejected -> error("отвергли верные куски: ${res.reason}")
                    is ImageAssembler.Result.Progress -> Unit
                }
                requested++
            }
            if (assembled != null) break
        }

        assertContentEquals(bytes, assembled, "получатель обязан собрать исходные байты")
        val minimum = (bytes.size + SyncProtocol.CHUNK_BYTES - 1) / SyncProtocol.CHUNK_BYTES
        assertTrue(
            requested <= minimum + 1,
            "скачано $requested кусков при размере ${bytes.size} — начало докачки было выброшено и скачано заново",
        )
    }

    @Test
    fun `здоровая передача идёт ровно с базовым темпом и без лишних запросов`() {
        val chunks = 4
        val bytes = pngOf(size = SyncProtocol.CHUNK_BYTES * chunks, seed = 5)
        val hash = ImageHash.compute(bytes)
        val store = RosterStore()
        val images = ImageStore()

        val a = CapeSyncState()
        a.onConfigChanged(listOf(localFileProvider("плащ").toActiveCape(hash)!!))
        store.announce("A", listOf(ActiveCape("плащ", ActiveCape.Kind.FILE, "", "", 0, null, hash)))
        store.announce("B", emptyList())
        val snapshot = SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot()))
        val b = CapeSyncState()
        b.onRoster(snapshot)
        a.onRoster(snapshot)

        var sent = 0
        while (true) {
            val pending = a.pendingUploads(a.publishableHashes("A"), setOf(hash))
            if (pending.isEmpty()) break
            val wire = SyncCodec.decodeUpload(SyncCodec.encodeUpload(nextChunk(a, hash, bytes)))
            images.acceptChunk("A", wire)
            a.markUploadProgress(wire.hash, wire.offset + wire.bytes.size)
            if (wire.isLast) a.markUploadComplete(wire.hash)
            sent++
        }
        assertEquals(chunks, sent, "владелец должен отдать картинку ровно по частям")

        val at = mutableListOf<Int>()
        var assembled: ByteArray? = null
        for (tick in 0 until 2000) {
            b.onTickAdvance()
            for (out in b.onTick(channelReady = true)) {
                val fetch = out as SyncOutbound.FetchImage
                val raw = images.chunk(fetch.hash, fetch.offset, fetch.length) ?: continue
                at += tick
                val res = b.onChunk(SyncCodec.decodeChunk(SyncCodec.encodeChunk(Chunk(fetch.hash, bytes.size, fetch.offset, raw))))
                if (res is ImageAssembler.Result.Complete) assembled = res.bytes
                if (res is ImageAssembler.Result.Rejected) error("отвергли верные куски: ${res.reason}")
            }
            if (assembled != null) break
        }

        assertContentEquals(bytes, assembled, "получатель обязан собрать исходные байты")
        assertEquals(chunks, at.size, "запрошено кусков ${at.size} вместо $chunks: где-то запрашивается лишнее или недокачивается")
        // Здоровая передача не должна копить интервал: после неудачных попыток
        // backoff обязан вернуться к базовому, иначе 8 МиБ качались бы часами.
        val budget = chunks * SyncProtocol.MIN_INTERVAL_TICKS * 3
        assertTrue(
            at.last() <= budget,
            "передача заняла ${at.last()} тиков при бюджете $budget: интервал копится и на успехе",
        )
        for (i in 1 until at.size) {
            val gap = at[i] - at[i - 1]
            assertTrue(
                gap >= SyncProtocol.MIN_INTERVAL_TICKS,
                "между кусками прошло $gap тиков — быстрее базового интервала сервер заваливается",
            )
        }
    }

    // ── что не должно уезжать наружу ─────────────────────────────────────────

    @Test
    fun `путь локального файла не попадает ни в один байт сообщения`() {
        val hash = ImageHash.compute(byteArrayOf(9))
        val store = RosterStore()
        store.announce(
            "A",
            listOf(
                ActiveCape("плащ", ActiveCape.Kind.FILE, "/home/игрок/плащ.png", "", 0, null, hash),
            ),
        )

        val blob = SyncCodec.encodeRoster(store.snapshot()).decodeToString()
        assertTrue(
            !blob.contains("/home/игрок") && !blob.contains("плащ.png"),
            "в снимке не должно быть ни следа локального пути",
        )
    }

    @Test
    fun `владелец не досылает картинку на которую никто не ссылается`() {
        val hash = ImageHash.compute(byteArrayOf(9))
        val a = CapeSyncState()
        assertTrue(
            a.pendingUploads(emptySet(), setOf(hash)).isEmpty(),
            "никто не просил — владелец молчит и не шлёт лишние мегабайты",
        )
        assertTrue(
            a.pendingUploads(setOf(hash), setOf(hash)).isNotEmpty(),
            "спросили — обязан отдать",
        )
    }

    // ── объявление без файла доезжает как есть ───────────────────────────────

    @Test
    fun `чужой http плащ доезжает и применяется когда владельцу доверяют`() {
        val store = RosterStore()
        store.announce(
            "A",
            listOf(
                ActiveCape(
                    "из-дома",
                    ActiveCape.Kind.URL,
                    "https://example.invalid/cape.png",
                    "",
                    7,
                    null,
                    null,
                ),
            ),
        )
        val b = CapeSyncState()
        b.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot())))

        val aFunctions = b.roster.objects.single().functions
        val local = SyncRosterPolicy.toLocalProviders(aFunctions, allowForeignUrls = true)
        assertEquals(1, local.size)
        assertEquals(7, local.single().priority, "приоритет обязан пережить сеть")
        assertTrue(local.single().source is Source.Url)
        assertTrue(
            SyncRosterPolicy.toLocalProviders(aFunctions, allowForeignUrls = false).isEmpty(),
            "без доверия чужие ссылки не применяются",
        )
    }

    // ── ушедший игрок освобождается ───────────────────────────────────────────

    @Test
    fun `ушедший игрок исчезает из снимка и его картинка больше не нужна`() {
        val bytes = pngOf(1024, 7)
        val hash = ImageHash.compute(bytes)
        val store = RosterStore()
        store.announce("A", listOf(ActiveCape("плащ", ActiveCape.Kind.FILE, "", "", 0, null, hash)))

        val b = CapeSyncState()
        b.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot())))
        assertEquals(listOf(hash), b.wantedHashes(), "пока A в снимке — картинка нужна")

        store.remove("A")
        assertTrue(b.onRoster(SyncCodec.decodeRoster(SyncCodec.encodeRoster(store.snapshot()))))
        assertTrue(b.wantedHashes().isEmpty(), "ушедший игрок не должен оставлять за собой запросы")
    }

    // ── раздача многочанковой картинки ──────────────────────────────────────

    @Test
    fun `раздача объявляет размер всей картинки, а не первого куска`() {
        val bytes = pngOf(size = SyncProtocol.CHUNK_BYTES * 3 + 17, seed = 7)
        val hash = ImageHash.compute(bytes)
        val store = ImageStore()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + SyncProtocol.CHUNK_BYTES, bytes.size)
            store.acceptChunk("A", Upload(hash, bytes.size, offset, bytes.copyOfRange(offset, end)))
            offset = end
        }

        // Ровно то, что делает сервер в onFetch: размер берётся у хранилища,
        // а не складывается из размера куска и его смещения.
        val first = store.chunk(hash, 0, SyncProtocol.CHUNK_BYTES)
        assertNotNull(first)
        val total = store.sizeOf(hash)

        assertEquals(bytes.size, total, "клиенту нужен размер всей картинки")
        assertNotEquals(
            first.size,
            total,
            "размер первого куска не должен выдаваться за размер картинки: " +
                "по такой формуле клиент собирает буфер в 64 КиБ и отбрасывает его по хэшу",
        )
    }

    @Test
    fun `клиент собирает многочанковую картинку, размеченную сервером`() {
        val bytes = pngOf(size = SyncProtocol.CHUNK_BYTES * 3 + 17, seed = 9)
        val hash = ImageHash.compute(bytes)
        val store = ImageStore()
        var sent = 0
        while (sent < bytes.size) {
            val end = minOf(sent + SyncProtocol.CHUNK_BYTES, bytes.size)
            store.acceptChunk("A", Upload(hash, bytes.size, sent, bytes.copyOfRange(sent, end)))
            sent = end
        }

        val published = ActiveCape(
            kind = ActiveCape.Kind.FILE,
            name = "local",
            primary = "",
            extract = "",
            priority = 0,
            condition = null,
            imageHash = hash,
        )
        val b = CapeSyncState()
        b.onRoster(Roster(1, listOf(RosterObject("A", listOf(published)))))

        var assembled: ByteArray? = null
        var rejected = 0
        for (tick in 0 until 6000) {
            b.onTickAdvance()
            for (out in b.onTick(channelReady = true)) {
                val fetch = out as? SyncOutbound.FetchImage ?: continue
                val raw = store.chunk(fetch.hash, fetch.offset, fetch.length)
                if (raw == null) continue
                val chunk = Chunk(fetch.hash, store.sizeOf(fetch.hash) ?: 0, fetch.offset, raw)
                when (val r = b.onChunk(SyncCodec.decodeChunk(SyncCodec.encodeChunk(chunk)))) {
                    is ImageAssembler.Result.Complete -> assembled = r.bytes
                    is ImageAssembler.Result.Rejected -> rejected++
                    is ImageAssembler.Result.Progress -> Unit
                }
            }
            if (assembled != null) break
        }

        assertEquals(0, rejected, "ни один кусок не должен отбрасываться по хэшу")
        assertNotNull(assembled, "картинка из нескольких кусков обязана собраться")
        assertContentEquals(bytes, assembled, "собранные байты обязаны совпасть с исходными")
    }
}
