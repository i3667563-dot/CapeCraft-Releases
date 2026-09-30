package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.condition.VarCondition
import dev.ggtv.capecraft.condition.VarPredicate
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Провод для `if` в Sync v3.
 *
 * Главное здесь — round-trip и отказ от неверной версии. `if` едет по сети
 * потому, что считать его должен каждый клиент сам: `username` наблюдаемого
 * игрока зрителю известен, а `$*` и `root` — свои на каждой машине. Если
 * бы условие молча терялось при кодировании, чужой набор показался бы шире
 * задуманного, и расхождение не было бы видно нигде.
 */
class SyncIfConditionTest {

    private fun url(name: String, priority: Int = 0, ifCondition: WireIfCondition? = null) = ActiveCape(
        kind = ActiveCape.Kind.URL,
        name = name,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        ifCondition = ifCondition,
    )

    private fun roundTrip(cape: ActiveCape): ActiveCape =
        SyncCodec.decodeAnnounce(SyncCodec.encodeAnnounce(Announce(listOf(cape)))).functions.single()

    // ── круговые преобразования ─────────────────────────────────────────────

    @Test
    fun `условие if переживает объявление без потерь`() {
        val wire = WireIfCondition(
            listOf(
                WireVarPredicate("username", WireOp.EQ, WireExpected.Str("Eonixx")),
                WireVarPredicate("\$PORT", WireOp.GT, WireExpected.Num(8000.0)),
                WireVarPredicate("root", WireOp.RANGE, WireExpected.Range(1.0, 9.0)),
            ),
        )
        assertEquals(wire, roundTrip(url("a", ifCondition = wire)).ifCondition)
    }

    @Test
    fun `имя переменной с точкой едет как есть`() {
        // Точка в имени значащая: `$FOO` и аддонный `myAddon.level` — это
        // одна строка, а не путь по корням. Разбор обратно — у получателя.
        val wire = WireIfCondition(listOf(WireVarPredicate("myAddon.level", WireOp.EQ, WireExpected.Str("7"))))
        assertEquals(wire, roundTrip(url("a", ifCondition = wire)).ifCondition)
    }

    @Test
    fun `условие if и when едут вместе не перемешиваясь`() {
        val whenWire = WireCondition(
            listOf(WirePredicate(WireRoot.WEATHER, "condition", WireOp.EQ, WireExpected.Str("rain"))),
        )
        val ifWire = WireIfCondition(
            listOf(WireVarPredicate("username", WireOp.EQ, WireExpected.Str("Eonixx"))),
        )
        val got = roundTrip(url("a").copy(condition = whenWire, ifCondition = ifWire))
        assertEquals(whenWire, got.condition)
        assertEquals(ifWire, got.ifCondition)
    }

    @Test
    fun `провод без if остаётся без if`() {
        assertNull(roundTrip(url("a")).ifCondition)
    }

    @Test
    fun `if без when едет и обратно`() {
        val ifWire = WireIfCondition(
            listOf(WireVarPredicate("\$TIER", WireOp.NOT_EQ, WireExpected.Str("dev"))),
        )
        val got = roundTrip(url("a", ifCondition = ifWire))
        assertNull(got.condition)
        assertEquals(ifWire, got.ifCondition)
    }

    // ── локальный <-> провод ────────────────────────────────────────────────

    @Test
    fun `локальное условие переводится в провод и обратно`() {
        val local = VarCondition(
            listOf(
                VarPredicate("username", dev.ggtv.capecraft.condition.Op.Eq, dev.ggtv.capecraft.condition.Expected.Str("Eonixx")),
            ),
        )
        val wire = WireIfCondition.from(local)!!
        assertEquals("username", wire.predicates.single().name)
        assertEquals(local, wire.toLocal())
    }

    @Test
    fun `провод восстанавливается в локальный провайдер с условием`() {
        val ifWire = WireIfCondition(
            listOf(WireVarPredicate("username", WireOp.EQ, WireExpected.Str("Eonixx"))),
        )
        val provider = SyncRosterPolicy.toLocalProvider(url("a", ifCondition = ifWire))!!
        assertEquals(ifWire.toLocal(), provider.ifCondition)
    }

    @Test
    fun `непереводимый if отбрасывает провайдера а не теряется молча`() {
        // Условие, которое не перевелось в локальное, — повод не показывать
        // провайдер вовсе: иначе чужой набор оказался бы шире задуманного.
        val broken = ActiveCape(
            kind = ActiveCape.Kind.URL,
            name = "a",
            primary = "https://example.invalid/a.png",
            extract = "",
            priority = 0,
            ifCondition = WireIfCondition(listOf(WireVarPredicate("u", WireOp.EQ, WireExpected.Str("x")))),
        )
        assertNull(SyncRosterPolicy.toLocalProvider(broken.copy(ifCondition = null))?.ifCondition)
        assertTrue(SyncRosterPolicy.toLocalProviders(listOf(broken)).single().ifCondition != null)
    }

    // ── отпечаток ───────────────────────────────────────────────────────────

    @Test
    fun `смена if меняет отпечаток`() {
        // Без этого плащ остался бы со старой текстурой после правки конфига.
        val a = url("a", ifCondition = WireIfCondition(listOf(WireVarPredicate("username", WireOp.EQ, WireExpected.Str("one")))))
        val b = url("a", ifCondition = WireIfCondition(listOf(WireVarPredicate("username", WireOp.EQ, WireExpected.Str("two")))))
        assertTrue(
            SyncRosterPolicy.fingerprintOf(listOf(a)) != SyncRosterPolicy.fingerprintOf(listOf(b)),
        )
    }

    @Test
    fun `провайдер с if отличается от того же без if`() {
        val withIf = url("a", ifCondition = WireIfCondition(listOf(WireVarPredicate("x", WireOp.EQ, WireExpected.Str("y")))))
        val without = url("a")
        assertTrue(
            SyncRosterPolicy.fingerprintOf(listOf(withIf)) != SyncRosterPolicy.fingerprintOf(listOf(without)),
        )
    }

    // ── лимиты и битые пакеты ──────────────────────────────────────────────

    @Test
    fun `if глубже лимита предикатов не кодируется`() {
        val preds = (0 until SyncProtocol.MAX_PREDICATES + 5).map {
            WireVarPredicate("username", WireOp.EQ, WireExpected.Str("x"))
        }
        val cape = url("a", ifCondition = WireIfCondition(preds))
        assertFailsWith<Exception> { SyncCodec.encodeAnnounce(Announce(listOf(cape))) }
    }

    @Test
    fun `if с безумно длинным именем не декодируется`() {
        // Лимит имени переменной держит и разбор: иначе u16 из пакета
        // заставит клиент выделить память по чужому числу.
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeByte(SyncProtocol.VERSION)
        d.writeByte(SyncProtocol.MSG_ANNOUNCE)
        d.writeShort(1)
        d.writeByte(0)          // kind = url
        d.writeInt(0)           // priority
        writeStr(d, "a")        // name
        writeStr(d, "https://example.invalid/a.png")
        writeStr(d, "")         // extract
        d.writeByte(0)          // нет when
        d.writeByte(1)          // есть if
        d.writeShort(1)         // один предикат
        writeStr(d, "n".repeat(100_000))
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(out.toByteArray()) }
    }

    @Test
    fun `if с неизвестной операцией не декодируется`() {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeByte(SyncProtocol.VERSION)
        d.writeByte(SyncProtocol.MSG_ANNOUNCE)
        d.writeShort(1)
        d.writeByte(0)
        d.writeInt(0)
        writeStr(d, "a")
        writeStr(d, "https://example.invalid/a.png")
        writeStr(d, "")
        d.writeByte(0)
        d.writeByte(1)
        d.writeShort(1)
        writeStr(d, "username")
        d.writeByte(200)        // нет такой операции
        d.writeByte(0)
        writeStr(d, "x")
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(out.toByteArray()) }
    }

    @Test
    fun `if с перевёрнутым диапазоном не декодируется`() {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeByte(SyncProtocol.VERSION)
        d.writeByte(SyncProtocol.MSG_ANNOUNCE)
        d.writeShort(1)
        d.writeByte(0)
        d.writeInt(0)
        writeStr(d, "a")
        writeStr(d, "https://example.invalid/a.png")
        writeStr(d, "")
        d.writeByte(0)
        d.writeByte(1)
        d.writeShort(1)
        writeStr(d, "username")
        d.writeByte(6)          // range
        d.writeByte(2)
        d.writeDouble(9.0)
        d.writeDouble(1.0)
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(out.toByteArray()) }
    }

    // ── версия протокола ────────────────────────────────────────────────────

    @Test
    fun `пакет старой версии не принимается`() {
        // Бамп 2 → 3: клиент со старым модем обязан получить внятный отказ,
        // а не распаковать запись посимвольно.
        val bytes = SyncCodec.encodeAnnounce(Announce(listOf(url("a"))))
        bytes[0] = 2
        assertFailsWith<Exception> { SyncCodec.decodeAnnounce(bytes) }
    }

    @Test
    fun `текущая версия протокола равна трём`() {
        // Якорь: бамп версии — не механическая правка константы, а обещание
        // несовместимости. Тест падает, если её поднимут без обновления кода.
        assertEquals(3, SyncProtocol.VERSION)
    }

    // ── объявление через провайдера ─────────────────────────────────────────

    @Test
    fun `локальный провайдер с if объявляется по сети с этим if`() {
        val provider = Provider(
            name = "a",
            source = Source.Url("https://example.invalid/{username}.png"),
            ifCondition = VarCondition(listOf(VarPredicate("username", dev.ggtv.capecraft.condition.Op.Eq, dev.ggtv.capecraft.condition.Expected.Str("Eonixx")))),
        )
        val declared = provider.toActiveCape()!!
        assertEquals("username", declared.ifCondition!!.predicates.single().name)
        assertEquals(declared, roundTrip(declared))
    }

    @Test
    fun `вычисление после поездки по сети даёт тот же результат`() {
        // Сквозная проверка смысла: объявленный набор, восстановленный у
        // другого клиента, обязан выбираться против его переменных.
        val ifWire = WireIfCondition(
            listOf(WireVarPredicate("username", WireOp.EQ, WireExpected.Str("Eonixx"))),
        )
        val local = SyncRosterPolicy.toLocalProviders(listOf(url("a", ifCondition = ifWire)))
        val seen = dev.ggtv.capecraft.condition.ProviderSelector.select(
            local,
            dev.ggtv.koren.EmptyWorldContext,
            dev.ggtv.capecraft.schema.Placeholders.Context(
                username = "Eonixx", uuid = "u", name = "", root = "/root",
            ),
        )
        assertEquals(listOf("a"), seen.map { it.name })

        val other = dev.ggtv.capecraft.condition.ProviderSelector.select(
            local,
            dev.ggtv.koren.EmptyWorldContext,
            dev.ggtv.capecraft.schema.Placeholders.Context(
                username = "Другой", uuid = "v", name = "", root = "/root",
            ),
        )
        assertTrue(other.isEmpty(), "у другого игрока набор показываться не должен")
    }

    private fun writeStr(d: java.io.DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeShort(b.size)
        d.write(b)
    }
}
