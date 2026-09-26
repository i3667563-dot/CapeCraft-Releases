package dev.ggtv.capecraft.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Серверная сторона Sync v2: [RosterStore] без Minecraft API, поэтому тестируется
 * напрямую.
 *
 * Проверяем ровно то, на чём сервер держится: что он запоминает, когда имеет
 * право не двигать ревизию, кого выкидывает при переполнении и кого — нет.
 */
class RosterStoreTest {

    private fun url(name: String, priority: Int = 0) = ActiveCape(
        name = name,
        kind = ActiveCape.Kind.URL,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        condition = null,
        imageHash = null,
    )

    // ── запоминание и ревизия ────────────────────────────────────────────────

    @Test
    fun `первое объявление двигает ревизию и попадает в снимок`() {
        val store = RosterStore()
        assertTrue(store.announce("a", listOf(url("u"))))
        assertEquals(1, store.size())

        val snap = store.snapshot()
        assertEquals(1, snap.objects.size)
        assertEquals("a", snap.objects.single().id)
        assertEquals(0, snap.truncated, "снимок изначально полный, усечения быть не должно")
        assertEquals(1, snap.revision)
    }

    @Test
    fun `переобъявление того же набора не двигает ревизию`() {
        val store = RosterStore()
        store.announce("a", listOf(url("u")))

        assertFalse(
            store.announce("a", listOf(url("u"))),
            "тот же набор после /cp reload или реконнекта — не повод рассылать снимок заново",
        )
        assertEquals(1, store.currentRevision())
    }

    @Test
    fun `изменение набора двигает ревизию`() {
        val store = RosterStore()
        store.announce("a", listOf(url("u")))
        val before = store.currentRevision()

        assertTrue(store.announce("a", listOf(url("u"), url("v", priority = 3))))
        assertTrue(store.currentRevision() > before, "набор изменился — ревизия обязана вырасти")
        assertEquals(2, store.functionsOf("a").size)
    }

    @Test
    fun `уход объекта двигает ревизию и убирает его из снимка`() {
        val store = RosterStore()
        store.announce("a", listOf(url("u")))
        store.announce("b", listOf(url("v")))
        val before = store.currentRevision()

        assertTrue(store.remove("a"))
        assertEquals(before + 1, store.currentRevision())
        assertEquals(listOf("b"), store.snapshot().objects.map { it.id })
    }

    @Test
    fun `повторный уход не двигает ревизию`() {
        val store = RosterStore()
        store.announce("a", listOf(url("u")))
        store.remove("a")
        val after = store.currentRevision()

        assertFalse(store.remove("a"), "второго раза удалять нечего")
        assertEquals(after, store.currentRevision())
    }

    // ── порядок и усечение ───────────────────────────────────────────────────

    @Test
    fun `в снимке сначала самые свежие объявления`() {
        val store = RosterStore()
        store.announce("старик", listOf(url("u")))
        store.announce("новичок", listOf(url("v")))
        store.announce("средний", listOf(url("w")))

        assertEquals(
            listOf("средний", "новичок", "старик"),
            store.snapshot().objects.map { it.id },
            "усечение бьёт по хвосту, поэтому порядок входа и есть порядок выживания",
        )
    }

    @Test
    fun `переобъявление возвращает объект в конец и он становится свежим`() {
        val store = RosterStore()
        store.announce("a", listOf(url("u")))
        store.announce("b", listOf(url("v")))
        store.announce("a", listOf(url("u"), url("u2"))) // пересоздание

        assertEquals(listOf("a", "b"), store.snapshot().objects.map { it.id })
    }

    @Test
    fun `снимок всегда влезает в лимит протокола даже когда данных много`() {
        val store = RosterStore()
        // Записи заведомо крупные: лимит тут упирается в байты, а не в число
        // игроков. Проверяем инвариант, а не конкретные числа разбора.
        // Записи валидные, но объёмные: 128 игроков × 32 функции с именем
        // впритык к лимиту — это заведомо больше 512 КиБ. Имя ASCII, потому
        // что лимит задан в БАЙТАХ и кириллица съедает по два.
        val fat = { p: Int -> url("cape$p" + "x".repeat(110), priority = p) }
        repeat(SyncProtocol.MAX_ROSTER_PLAYERS) {
            store.announce("игрок$it", (0 until SyncProtocol.MAX_PROVIDERS).map(fat))
        }

        val snap = store.snapshot()
        assertTrue(
            SyncCodec.encodeRoster(snap).size <= SyncProtocol.MAX_ROSTER_BYTES,
            "обрезанный снимок всё равно обязан влезать в полезную нагрузку",
        )
        assertTrue(snap.truncated > 0, "такого объёма хватать не должно, усечение ожидаемо")
        assertEquals(
            SyncProtocol.MAX_ROSTER_PLAYERS - snap.objects.size,
            snap.truncated,
            "счётчик усечения обязан совпадать с числом потерянных записей",
        )
    }

    @Test
    fun `лимит по числу игроков вытесняет самых давно не переобъявлявших`() {
        val store = RosterStore(maxPlayers = 3)
        store.announce("старик", listOf(url("u1")))
        store.announce("средний", listOf(url("u2")))
        store.announce("новичок", listOf(url("u3")))
        assertEquals(3, store.size())

        store.announce("четвёртый", listOf(url("u4")))

        assertEquals(3, store.size(), "потолок держится, а не разъезжается")
        assertEquals(
            listOf("четвёртый", "новичок", "средний"),
            store.snapshot().objects.map { it.id },
            "на освободившееся место вытесняется самый старый",
        )
        assertEquals(emptyList(), store.functionsOf("старик"))
    }

    @Test
    fun `переобъявление существующего не вытесняет никого`() {
        val store = RosterStore(maxPlayers = 3)
        store.announce("a", listOf(url("u1")))
        store.announce("b", listOf(url("u2")))
        store.announce("c", listOf(url("u3")))

        // Новый игрок, но уже в пределах потолка: вытеснения быть не должно.
        store.announce("a", listOf(url("u1"), url("u1b")))

        assertEquals(3, store.size())
        assertEquals(2, store.functionsOf("a").size, "существующая запись обновилась, а не заменила собой чужую")
        assertTrue(store.snapshot().objects.any { it.id == "b" }, "остальные не должны пострадать")
    }

    // ── что сервер отдаёт клиенту для скачивания ────────────────────────────

    @Test
    fun `сервер отдаёт хэши файлов но не чужие ссылки`() {
        val store = RosterStore()
        val hash = ImageHash.compute(byteArrayOf(1, 2, 3))
        store.announce(
            "a",
            listOf(
                ActiveCape("файл", ActiveCape.Kind.FILE, "", "", 0, null, hash),
                url("чужая-ссылка"),
            ),
        )

        assertEquals(listOf(hash), store.imageHashesOf("a"), "качать имеет смысл только то, что сервер отдаёт байтами")
    }

    @Test
    fun `неизвестный объект не считается замеченным`() {
        val store = RosterStore()
        assertEquals(emptyList(), store.imageHashesOf("не-такого"))
        assertEquals(emptyList(), store.functionsOf("не-такого"))
    }

    // ── битый вход недоверенного клиента ────────────────────────────────────

    @Test
    fun `слишком много функций отбрасывается на входе`() {
        val store = RosterStore()
        val many = (0..SyncProtocol.MAX_PROVIDERS).map { url("плащ$it") }
        store.announce("a", many)

        val kept = store.functionsOf("a")
        assertEquals(
            SyncProtocol.MAX_PROVIDERS,
            kept.size,
            "клиент не должен уметь залить больше, чем сервер готов разослать",
        )
        assertTrue(
            kept.none { it.name == "плащ${SyncProtocol.MAX_PROVIDERS}" },
            "лишние отбрасываются с хвоста, а не с головы",
        )
    }
}
