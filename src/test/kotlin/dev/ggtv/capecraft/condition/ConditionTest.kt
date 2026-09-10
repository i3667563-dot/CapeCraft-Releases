package dev.ggtv.capecraft.condition

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConditionParseTest {

    @Test
    fun `short field uses default field per root`() {
        val c = Condition.parse(dictOf("biome" to str("minecraft:snowy_plains")))
        assertEquals(1, c.predicates.size)
        assertEquals(WorldRoot.BIOME, c.predicates[0].root)
        assertEquals("id", c.predicates[0].field)
    }

    @Test
    fun `full path picks root and field`() {
        val c = Condition.parse(dictOf("weather.condition" to str("rain")))
        val p = c.predicates.single()
        assertEquals(WorldRoot.WEATHER, p.root)
        assertEquals("condition", p.field)
    }

    @Test
    fun `unknown root fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("chunk.loaded" to str("true")))
        }
    }

    @Test
    fun `too deep path fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("location.x.snap" to str("0")))
        }
    }

    @Test
    fun `plain number becomes numeric equality`() {
        val p = Condition.parse(dictOf("location.y" to num(63.0))).predicates.single()
        assertEquals(Op.Eq, p.op)
        assertEquals(Expected.Num(63.0), p.expected)
    }

    @Test
    fun `string number becomes numeric equality`() {
        val p = Condition.parse(dictOf("location.y" to str("63"))).predicates.single()
        assertEquals(Expected.Num(63.0), p.expected)
    }

    @Test
    fun `operator prefixes parse`() {
        val cases = mapOf(
            ">63" to Op.Gt, ">=63" to Op.Ge, "<63" to Op.Lt,
            "<=63" to Op.Le, "!63" to Op.NotEq, "!rain" to Op.NotEq,
        )
        for ((text, op) in cases) {
            val p = Condition.parse(dictOf("time.tick" to str(text))).predicates.single()
            assertEquals(op, p.op, "для «$text»")
        }
        val strOp = Condition.parse(dictOf("weather.condition" to str("!rain"))).predicates.single()
        assertEquals(Op.NotEq, strOp.op)
        assertEquals(Expected.Str("rain"), strOp.expected)
    }

    @Test
    fun `range parses`() {
        val p = Condition.parse(dictOf("location.y" to str("63..80"))).predicates.single()
        assertEquals(Op.Range, p.op)
        assertEquals(Expected.Range(63.0, 80.0), p.expected)
    }

    @Test
    fun `empty when yields true condition`() {
        val c = Condition.parse(dictOf())
        assertTrue(c.predicates.isEmpty())
    }

    @Test
    fun `biome alias snowy maps to snow precipitation`() {
        val p = Condition.parse(dictOf("biome" to str("snowy"))).predicates.single()
        assertEquals(WorldRoot.BIOME, p.root)
        assertEquals("precipitation", p.field)
        assertEquals(Expected.Str("snow"), p.expected)
    }

    @Test
    fun `full biome id stays id field`() {
        val p = Condition.parse(dictOf("biome" to str("minecraft:snowy_plains"))).predicates.single()
        assertEquals("id", p.field)
        assertEquals(Expected.Str("minecraft:snowy_plains"), p.expected)
    }

    @Test
    fun `dimension alias nether maps to type`() {
        val p = Condition.parse(dictOf("dimension" to str("nether"))).predicates.single()
        assertEquals(WorldRoot.DIMENSION, p.root)
        assertEquals("type", p.field)
        assertEquals(Expected.Str("nether"), p.expected)
    }

    @Test
    fun `time alias night maps to period`() {
        val p = Condition.parse(dictOf("time" to str("night"))).predicates.single()
        assertEquals(WorldRoot.TIME, p.root)
        assertEquals("period", p.field)
        assertEquals(Expected.Str("night"), p.expected)
    }
}

class ConditionMatchTest {

    @Test
    fun `string field matches by equality`() {
        val c = Condition.parse(dictOf("weather.condition" to str("rain")))
        assertTrue(c.matches(FakeWorld(mapOf("weather.condition" to str("rain")))))
        assertFalse(c.matches(FakeWorld(mapOf("weather.condition" to str("clear")))))
    }

    @Test
    fun `not equal matches other strings`() {
        val c = Condition.parse(dictOf("weather.condition" to str("!rain")))
        assertTrue(c.matches(FakeWorld(mapOf("weather.condition" to str("clear")))))
        assertFalse(c.matches(FakeWorld(mapOf("weather.condition" to str("rain")))))
    }

    @Test
    fun `numeric comparisons apply`() {
        val world = FakeWorld(mapOf("location.y" to num(95.0)))
        assertTrue(Condition.parse(dictOf("location.y" to str(">63"))).matches(world))
        assertTrue(Condition.parse(dictOf("location.y" to str(">=95"))).matches(world))
        assertTrue(Condition.parse(dictOf("location.y" to str("<100"))).matches(world))
        assertTrue(Condition.parse(dictOf("location.y" to str("64..99"))).matches(world))
        assertFalse(Condition.parse(dictOf("location.y" to str(">96"))).matches(world))
        assertFalse(Condition.parse(dictOf("location.y" to str("0..50"))).matches(world))
    }

    @Test
    fun `int field compares numerically`() {
        val world = FakeWorld(mapOf("time.tick" to int(18000)))
        assertTrue(Condition.parse(dictOf("time.tick" to str(">12000"))).matches(world))
    }

    @Test
    fun `range against non-number fails`() {
        val world = FakeWorld(mapOf("weather.condition" to str("rain")))
        assertFalse(Condition.parse(dictOf("weather.condition" to str("1..2"))).matches(world))
    }

    @Test
    fun `missing field yields false`() {
        val c = Condition.parse(dictOf("biome.temperature" to str(">0.5")))
        assertFalse(c.matches(FakeWorld()))
    }

    @Test
    fun `all predicates must match`() {
        val c = Condition.parse(
            dictOf(
                "biome.precipitation" to str("snow"),
                "time.period" to str("night"),
                "location.y" to str(">63"),
            ),
        )
        val match = FakeWorld(
            mapOf(
                "biome.precipitation" to str("snow"),
                "time.period" to str("night"),
                "location.y" to num(70.0),
            ),
        )
        assertTrue(c.matches(match))
    }

    @Test
    fun `one failing predicate breaks condition`() {
        val c = Condition.parse(dictOf("weather.condition" to str("rain"), "time.period" to str("day")))
        val world = FakeWorld(mapOf("weather.condition" to str("rain"), "time.period" to str("night")))
        assertFalse(c.matches(world))
    }
}

class ProviderSelectorTest {
    private val world = FakeWorld(mapOf("weather.condition" to str("rain"), "time.period" to str("night")))

    private fun provider(name: String, condition: Condition? = null, priority: Int = 0) =
        dev.ggtv.capecraft.provider.Provider(
            name = name,
            source = dev.ggtv.capecraft.provider.Source.Url("https://x/$name"),
            condition = condition,
            priority = priority,
        )

    @Test
    fun `matching condition beats defaults`() {
        val p = provider(
            "rainy",
            condition = Condition.parse(dictOf("weather.condition" to str("rain"))),
        )
        val selected = ProviderSelector.select(listOf(provider("default"), p), world)
        assertEquals(listOf("rainy", "default"), selected.map { it.name })
    }

    @Test
    fun `higher priority wins regardless of list order`() {
        val low = provider(
            "low",
            condition = Condition.parse(dictOf("time.period" to str("night"))),
            priority = 5,
        )
        val high = provider(
            "high",
            condition = Condition.parse(dictOf("weather.condition" to str("rain"))),
            priority = 20,
        )
        // high стоит позже в списке, но приоритет выше — он первый.
        val selected = ProviderSelector.select(listOf(low, high, provider("default")), world)
        assertEquals(listOf("high", "low", "default"), selected.map { it.name })
    }

    @Test
    fun `equal priority keeps list order`() {
        val a = provider("a", condition = Condition.parse(dictOf("weather.condition" to str("rain"))), priority = 1)
        val b = provider("b", condition = Condition.parse(dictOf("time.period" to str("night"))), priority = 1)
        val selected = ProviderSelector.select(listOf(a, b), world)
        assertEquals(listOf("a", "b"), selected.map { it.name })
    }

    @Test
    fun `unmatched condition provider is skipped`() {
        val other = provider(
            "other",
            condition = Condition.parse(dictOf("weather.condition" to str("clear"))),
        )
        val selected = ProviderSelector.select(listOf(other, provider("default")), world)
        assertEquals(listOf("default"), selected.map { it.name })
    }

    @Test
    fun `only defaults when no condition matched`() {
        val selected = ProviderSelector.select(listOf(provider("d1"), provider("d2")), world)
        assertEquals(listOf("d1", "d2"), selected.map { it.name })
    }

    @Test
    fun `empty world selects only defaults`() {
        val c = provider("c", condition = Condition.parse(dictOf("weather.condition" to str("rain"))))
        val selected = ProviderSelector.select(listOf(c, provider("d")), EmptyWorld())
        assertEquals(listOf("d"), selected.map { it.name })
    }
}

class ResolveCapeWorldTest {
    private val ctx = dev.ggtv.capecraft.schema.Placeholders.Context(username = "Steve", uuid = "u", name = "x")
    private val world = FakeWorld(mapOf("weather.condition" to str("rain")))

    private fun provider(name: String, template: String, condition: Condition? = null, priority: Int = 0) =
        dev.ggtv.capecraft.provider.Provider(
            name = name,
            source = dev.ggtv.capecraft.provider.Source.Url(template),
            condition = condition,
            priority = priority,
        )

    @Test
    fun `world-aware resolve picks matching high priority provider`() {
        val providers = listOf(
            provider("def", "https://def/{username}"),
            provider(
                "rain",
                "https://rain/{username}",
                condition = Condition.parse(dictOf("weather.condition" to str("rain"))),
                priority = 10,
            ),
        )
        val byProvider = mutableListOf<String>()
        val bytes = dev.ggtv.capecraft.provider.resolveCape(
            providers,
            ctx,
            "/root",
            dev.ggtv.capecraft.provider.CapeFetcher { r ->
                val url = (r as dev.ggtv.capecraft.provider.Resolved.Url).url
                byProvider += url
                if (url == "https://rain/Steve") byteArrayOf(2) else byteArrayOf(1)
            },
            world,
        )
        assertEquals(2, bytes[0])
        assertEquals(listOf("https://rain/Steve"), byProvider)
    }

    @Test
    fun `without world only defaults resolve`() {
        val providers = listOf(
            provider("rain", "https://rain", condition = Condition.parse(dictOf("weather.condition" to str("rain")))),
            provider("def", "https://def"),
        )
        val byProvider = mutableListOf<String>()
        dev.ggtv.capecraft.provider.resolveCape(
            providers,
            ctx,
            "/root",
            dev.ggtv.capecraft.provider.CapeFetcher { r ->
                byProvider += (r as dev.ggtv.capecraft.provider.Resolved.Url).url
                byteArrayOf(1)
            },
        )
        assertEquals(listOf("https://def"), byProvider)
    }
}

/** [WorldContext], где ничего нет. */
private class EmptyWorld : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value =
        throw dev.ggtv.kjen.CrenError.NotFound(path)
}