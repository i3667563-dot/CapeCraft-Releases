package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.sync.ActiveCape
import dev.ggtv.capecraft.schema.WhenSchema
import dev.ggtv.capecraft.sync.WireCondition
import dev.ggtv.capecraft.sync.WireRoot
import dev.ggtv.capecraft.sync.toActiveCape
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Условия брони, здоровья и состояния.
 *
 * Проверяются в двух слоях. Первый — разбор и сравнение: корректно ли
 * `armor.chest: "diamond"` и `health.current: "<10"`. Второй, и важный, —
 * self-only: условие по `state` должно делать провайдер локальным, иначе
 * плащ, который человек включил себе «когда я в воде», уехал бы в сеть и
 * показался тем, кто этой воды не касался.
 */
class PlayerStateConditionTest {

    private val world = FakeWorld(
        mapOf(
            "armor.head" to str("diamond"),
            "armor.chest" to str("netherite"),
            "armor.legs" to str("none"),
            "armor.feet" to str("leather"),
            "health.current" to num(7.0),
            "health.max" to num(20.0),
            "state.inWater" to str("true"),
            "state.sneaking" to str("false"),
            "state.sprinting" to str("true"),
            "state.onGround" to str("false"),
            "state.pose" to str("swimming"),
        ),
    )

    // ── броня ─────────────────────────────────────────────────────────────

    @Test
    fun `armor matches by slot`() {
        assertTrue(Condition.parse(dictOf("armor.head" to str("diamond"))).matches(world))
        assertTrue(Condition.parse(dictOf("armor.chest" to str("netherite"))).matches(world))
        assertFalse(Condition.parse(dictOf("armor.chest" to str("diamond"))).matches(world))
    }

    @Test
    fun `armor without field means chestplate`() {
        val c = Condition.parse(dictOf("armor" to str("netherite")))
        assertEquals("chest", c.predicates[0].field)
        assertTrue(c.matches(world))
    }

    @Test
    fun `empty armor slot reads as none`() {
        assertTrue(Condition.parse(dictOf("armor.legs" to str("none"))).matches(world))
    }

    @Test
    fun `armor has no numeric fields so range makes no sense`() {
        // Тир — строка: `armor.chest: "..netherite"` не имеет смысла и
        // разбираться не должен.
        assertTrue(WhenSchema.isNumeric(WorldRoot.ARMOR, "chest").not())
    }

    // ── здоровье ───────────────────────────────────────────────────────────

    @Test
    fun `health compares numerically`() {
        assertTrue(Condition.parse(dictOf("health.current" to str("<10"))).matches(world))
        assertTrue(Condition.parse(dictOf("health.current" to str("7"))).matches(world))
        assertFalse(Condition.parse(dictOf("health.current" to str(">10"))).matches(world))
    }

    @Test
    fun `health max is separate from current`() {
        assertTrue(Condition.parse(dictOf("health.max" to str("20"))).matches(world))
        assertFalse(Condition.parse(dictOf("health.max" to str("<10"))).matches(world))
    }

    @Test
    fun `health range works like any other numeric field`() {
        assertTrue(Condition.parse(dictOf("health.current" to str("1..10"))).matches(world))
        assertFalse(Condition.parse(dictOf("health.current" to str("10..20"))).matches(world))
    }

    @Test
    fun `health without field asks for one`() {
        // `health: "<10"` было бы бессмысленно: короткая запись сравнивает со
        // строкой, а оба поля числовые. Ошибка обязана называть реальные поля.
        val e = runCatching { Condition.parse(dictOf("health" to str("<10"))) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "ожидалась ошибка, получено: $e")
        val message = e!!.message!!
        assertTrue(message.contains("current, max"), "в ошибке должны быть реальные поля: $message")
    }

    // ── состояние (self-only) ──────────────────────────────────────────────

    @Test
    fun `state booleans match as strings`() {
        assertTrue(Condition.parse(dictOf("state.inWater" to str("true"))).matches(world))
        assertTrue(Condition.parse(dictOf("state.sprinting" to str("true"))).matches(world))
        assertFalse(Condition.parse(dictOf("state.sneaking" to str("true"))).matches(world))
    }

    @Test
    fun `state negation works like any string field`() {
        // Булево поле — обычная строка, поэтому `!` работает как везде.
        // `onGround` в [world] равен "false", значит `!true` совпадает
        // («на земле»), а `!false` — нет.
        assertTrue(Condition.parse(dictOf("state.onGround" to str("!true"))).matches(world))
        assertFalse(Condition.parse(dictOf("state.onGround" to str("!false"))).matches(world))
    }

    @Test
    fun `state negation reads naturally for the common case`() {
        val grounded = FakeWorld(mapOf("state.onGround" to str("true")))
        assertTrue(Condition.parse(dictOf("state.onGround" to str("!false"))).matches(grounded))
        assertFalse(Condition.parse(dictOf("state.onGround" to str("!true"))).matches(grounded))
    }

    @Test
    fun `state pose matches`() {
        assertTrue(Condition.parse(dictOf("state.pose" to str("swimming"))).matches(world))
        assertFalse(Condition.parse(dictOf("state.pose" to str("crouching"))).matches(world))
    }

    // ── self-only ──────────────────────────────────────────────────────────

    @Test
    fun `state condition marks provider self-only`() {
        val c = Condition.parse(dictOf("state.inWater" to str("true")))
        assertTrue(c.hasSelfOnly)
    }

    @Test
    fun `armor and health are not self-only`() {
        assertFalse(Condition.parse(dictOf("armor.chest" to str("netherite"))).hasSelfOnly)
        assertFalse(Condition.parse(dictOf("health.current" to str("<10"))).hasSelfOnly)
    }

    @Test
    fun `self-only condition is not advertised`() {
        val provider = Provider(
            name = "wet",
            source = Source.Url("https://example.invalid/wet.png"),
            condition = Condition.parse(dictOf("state.inWater" to str("true"))),
        )
        assertTrue(provider.isSelfOnly)
        assertNull(provider.toActiveCape(), "self-only провайдер не должен попасть в сеть")
    }

    @Test
    fun `explicit self flag hides a provider with public conditions`() {
        // Смысл флага: у провайдера публичные условия, но показывать его
        // всё равно только мне. Единственный способ — выставить `self`.
        val provider = Provider(
            name = "mine",
            source = Source.Url("https://example.invalid/mine.png"),
            condition = Condition.parse(dictOf("armor.chest" to str("netherite"))),
            selfOnly = true,
        )
        assertTrue(provider.isSelfOnly)
        assertNull(provider.toActiveCape())
    }

    @Test
    fun `public provider with armor is advertised`() {
        val provider = Provider(
            name = "armored",
            source = Source.Url("https://example.invalid/a.png"),
            condition = Condition.parse(dictOf("armor.chest" to str("netherite"))),
        )
        assertFalse(provider.isSelfOnly)
        val cape: ActiveCape = requireNotNull(provider.toActiveCape())
        assertEquals(1, cape.condition?.predicates?.size)
    }

    @Test
    fun `self-only root has no wire tag`() {
        // Пустой тег означал бы «это состояние одинаково у всех» — ровно то,
        // от чего отказывались. `WireRoot.of` обязан отказать.
        assertNull(WireRoot.of(WorldRoot.STATE))
        assertNull(WireCondition.from(Condition.parse(dictOf("state.pose" to str("crouching")))))
    }

    @Test
    fun `armor and health do have wire tags`() {
        assertTrue(WireRoot.of(WorldRoot.ARMOR) != null)
        assertTrue(WireRoot.of(WorldRoot.HEALTH) != null)
    }

    @Test
    fun `self-only provider is conditional even without when`() {
        // Иначе `self = true` без условий попал бы в группу «без условий»
        // и выигрывал только за счёт приоритета, то есть вёл себя как
        // обычный default-провайдер.
        val provider = Provider(
            name = "mine",
            source = Source.Url("https://example.invalid/mine.png"),
            selfOnly = true,
        )
        assertTrue(provider.hasConditions)
    }

    @Test
    fun `self-only provider outranks defaults`() {
        val vars = dev.ggtv.capecraft.schema.Placeholders.Context("", "", "")
        val conditional = Provider(
            name = "mine",
            source = Source.Url("https://example.invalid/mine.png"),
            selfOnly = true,
            priority = 0,
        )
        val fallback = Provider(
            name = "fallback",
            source = Source.Url("https://example.invalid/b.png"),
            priority = 100,
        )
        val selected = ProviderSelector.select(listOf(fallback, conditional), world, vars)
        assertEquals(listOf("mine", "fallback"), selected.map { it.name })
    }
}
