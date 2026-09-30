package dev.ggtv.capecraft.condition

import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

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
            "<=63" to Op.Le, "!63" to Op.NotEq,
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
    fun `строка на числовом поле больше не принимается`() {
        // Раньше `time.tick: "!rain"` разбиралось: это то же самое, что
        // `health.current: "низко"` — опечатка, которая тихо не срабатывала.
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("time.tick" to str("!rain")))
        }
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

    // Диапазон на строковом поле больше не «просто не срабатывает»: разбор
    // падает. Проверяется отдельно, в `ConditionValueTest` — здесь важно лишь
    // то, что строка поля не проходит как ложь молча.
    @Test
    fun `range against non-number fails`() {
        val world = FakeWorld(mapOf("weather.condition" to str("rain")))
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("weather.condition" to str("1..2")))
        }
        // Мир при этом отвечает «rain» — то есть провайдер с таким условием
        // был бы рабочим, если бы условие вообще собралось.
        assertEquals(str("rain"), world.field(WorldRoot.WEATHER, "condition", "weather.condition"))
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

/**
 * Проверка значений условия: то, что мир отдаёт, и то, что человек написал,
 * обязаны совпадать.
 *
 * Тесты появились из-за тихой поломки: `armor.chest: "plate"` разбирался без
 * ошибок, провайдер грузился, подсказка такое значение не предлагала — и
 * совпадение не наступало никогда. Нигде не было ни ошибки, ни сообщения, по
 * которому можно понять, что условие не сработает. Разбор падает вместо этого.
 */
class ConditionValueTest {

    @Test
    fun `значение из списка принимается`() {
        for (v in listOf("none", "leather", "diamond", "netherite", "turtle")) {
            val p = Condition.parse(dictOf("armor.chest" to str(v))).predicates.single()
            assertEquals(Expected.Str(v), p.expected, "для «$v»")
        }
    }

    @Test
    fun `опечатка в значении поля падает при разборе`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("armor.chest" to str("plate")))
        }
        val msg = e.message!!
        assertTrue(msg.contains("plate"), "сообщение должно называть написанное значение: $msg")
        assertTrue(msg.contains("netherite"), "сообщение должно перечислять допустимые: $msg")
    }

    @Test
    fun `опечатка в позе и осадках падает`() {
        for (key in listOf("state.pose", "biome.precipitation", "time.period", "dimension.type")) {
            assertFailsWith<IllegalArgumentException>("ключ $key") {
                Condition.parse(dictOf(key to str("stаnding")))
            }
        }
    }

    @Test
    fun `не-логическое значение булева поля падает`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("state.inWater" to str("yes")))
        }
        assertTrue(e.message!!.contains("true"), "перечислить true/false: ${e.message}")
    }

    @Test
    fun `unknown принимается везде, где принимается значение`() {
        // Мир отдаёт «unknown» вместо того, что не прочитал: нестандартная
        // броня, отсутствие игрока. Это законное условие, а не опечатка.
        for (key in listOf("armor.chest", "state.pose", "weather.condition", "time.period")) {
            val p = Condition.parse(dictOf(key to str("unknown"))).predicates.single()
            assertEquals(Expected.Str("unknown"), p.expected, "ключ $key")
        }
    }

    @Test
    fun `отрицание остаётся законным`() {
        // `!` — сравнение строк, а не «неизвестное значение»: им ловят всё,
        // кроме одного тира, и это должно работать и дальше работало.
        val p = Condition.parse(dictOf("armor.chest" to str("!diamond"))).predicates.single()
        assertEquals(Op.NotEq, p.op)
        assertEquals(Expected.Str("diamond"), p.expected)
        assertTrue(
            Condition.parse(dictOf("armor.chest" to str("!diamond")))
                .matches(FakeWorld(mapOf("armor.chest" to str("iron")))),
        )
    }

    @Test
    fun `отрицание опечатки тоже падает`() {
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("armor.chest" to str("!plate")))
        }
    }

    @Test
    fun `свободные поля не ограничены`() {
        // `biome.id` и `dimension.id` приходят из реестра модов: их значения
        // заранее неизвестны, и ругаться на них нельзя.
        for (key in listOf("biome.id", "dimension.id")) {
            val p = Condition.parse(dictOf(key to str("some_mod:whatever"))).predicates.single()
            assertEquals(Expected.Str("some_mod:whatever"), p.expected, "ключ $key")
        }
    }

    @Test
    fun `числовые поля принимают числа и диапазоны`() {
        for (text in listOf("20", ">=20", "<10", "5..15")) {
            Condition.parse(dictOf("health.current" to str(text)))
        }
    }

    @Test
    fun `число на строковом поле падает`() {
        // `state.inWater: 5` — это не «не совпадёт», это опечатка: человек
        // думал, что пишет число, а поле ждёт «true».
        val e = assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("state.inWater" to num(5.0)))
        }
        assertTrue(e.message!!.contains("true"), "перечислить true/false: ${e.message}")
    }

    @Test
    fun `строковое значение на числовом поле падает`() {
        assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("health.current" to str("низко")))
        }
    }

    @Test
    fun `оператор сравнения на строковом поле падает`() {
        for (text in listOf(">diamond", ">=1", "<9", "1..2")) {
            assertFailsWith<IllegalArgumentException>("значение «$text»") {
                Condition.parse(dictOf("armor.chest" to str(text)))
            }
        }
    }

    @Test
    fun `список WhenSchema и мод — один и тот же`() {
        // Редактор обещает подсказкой ровно то, что мод теперь принимает.
        // Разойтись они могут только молча, поэтому сверяемся напрямую.
        for ((root, fields) in dev.ggtv.capecraft.schema.WhenSchema.VALUES) {
            for ((field, values) in fields) {
                assertEquals(
                    values,
                    Condition.valuesOf(root, field),
                    "список значений ${root.segment}.$field разошёлся между схемой и модом",
                )
                for (v in values) {
                    Condition.parse(dictOf("${root.segment}.$field" to str(v)))
                }
            }
        }
    }
}

class ProviderSelectorTest {
    private val world = FakeWorld(mapOf("weather.condition" to str("rain"), "time.period" to str("night")))

    // `if` считается против переменных; в тестах подбора `when` значение не важно,
    // но контекст обязан быть настоящим, иначе тест врал бы про новую сигнатуру.
    private val vars = dev.ggtv.capecraft.schema.Placeholders.Context(
        username = "Steve", uuid = "u", name = "x", root = "/root",
    )

    private fun provider(
        name: String,
        condition: Condition? = null,
        ifCondition: VarCondition? = null,
        priority: Int = 0,
    ) = dev.ggtv.capecraft.provider.Provider(
        name = name,
        source = dev.ggtv.capecraft.provider.Source.Url("https://x/$name"),
        condition = condition,
        ifCondition = ifCondition,
        priority = priority,
    )

    @Test
    fun `matching condition beats defaults`() {
        val p = provider(
            "rainy",
            condition = Condition.parse(dictOf("weather.condition" to str("rain"))),
        )
        val selected = ProviderSelector.select(listOf(provider("default"), p), world, vars)
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
        val selected = ProviderSelector.select(listOf(low, high, provider("default")), world, vars)
        assertEquals(listOf("high", "low", "default"), selected.map { it.name })
    }

    @Test
    fun `equal priority keeps list order`() {
        val a = provider("a", condition = Condition.parse(dictOf("weather.condition" to str("rain"))), priority = 1)
        val b = provider("b", condition = Condition.parse(dictOf("time.period" to str("night"))), priority = 1)
        val selected = ProviderSelector.select(listOf(a, b), world, vars)
        assertEquals(listOf("a", "b"), selected.map { it.name })
    }

    @Test
    fun `unmatched condition provider is skipped`() {
        val other = provider(
            "other",
            condition = Condition.parse(dictOf("weather.condition" to str("clear"))),
        )
        val selected = ProviderSelector.select(listOf(other, provider("default")), world, vars)
        assertEquals(listOf("default"), selected.map { it.name })
    }

    @Test
    fun `only defaults when no condition matched`() {
        val selected = ProviderSelector.select(listOf(provider("d1"), provider("d2")), world, vars)
        assertEquals(listOf("d1", "d2"), selected.map { it.name })
    }

    @Test
    fun `приоритет работает и на провайдерах без условия`() {
        // Раньше безусловные шли в порядке списка, и `priority` у них был
        // ключом в конфиге, который подсвечивает редактор и не делает ничего.
        val selected = ProviderSelector.select(
            listOf(provider("d-low", priority = 1), provider("d-high", priority = 9)),
            world,
            vars,
        )
        assertEquals(listOf("d-high", "d-low"), selected.map { it.name })
    }

    @Test
    fun `безусловные с равным приоритетом держат порядок списка`() {
        val selected = ProviderSelector.select(
            listOf(provider("a", priority = 3), provider("b", priority = 3), provider("c", priority = 3)),
            world,
            vars,
        )
        assertEquals(listOf("a", "b", "c"), selected.map { it.name })
    }

    @Test
    fun `условные всё равно идут ahead безусловных несмотря на приоритет`() {
        // Группы не перемешиваются: подошедшее условие — это осознанный выбор
        // человека, и безусловный провайдер не должен его перебивать числом.
        val selected = ProviderSelector.select(
            listOf(
                provider("always-high", priority = 100),
                provider("matched-low", condition = Condition.parse(dictOf("weather.condition" to str("rain"))), priority = 1),
            ),
            world,
            vars,
        )
        assertEquals(listOf("matched-low", "always-high"), selected.map { it.name })
    }

    @Test
    fun `безусловные сортируются между собой даже когда условные подошли`() {
        val selected = ProviderSelector.select(
            listOf(
                provider("d1", priority = 2),
                provider("m", condition = Condition.parse(dictOf("weather.condition" to str("rain")))),
                provider("d2", priority = 8),
            ),
            world,
            vars,
        )
        assertEquals(listOf("m", "d2", "d1"), selected.map { it.name })
    }

    @Test
    fun `провайдер с одним лишь if тоже попадает в приоритетную группу`() {
        val selected = ProviderSelector.select(
            listOf(
                provider("no-condition-high", priority = 50),
                provider("if-low", ifCondition = VarCondition.parse(dictOf("username" to str("Steve"))), priority = 1),
            ),
            world,
            vars,
        )
        assertEquals(listOf("if-low", "no-condition-high"), selected.map { it.name })
    }

    @Test
    fun `if не подошёл — провайдер не сдвигает безусловные вниз`() {
        val selected = ProviderSelector.select(
            listOf(
                provider("no-condition", priority = 0),
                provider("if-miss", ifCondition = VarCondition.parse(dictOf("username" to str("Другой"))), priority = 99),
            ),
            world,
            vars,
        )
        assertEquals(listOf("no-condition"), selected.map { it.name })
    }

    @Test
    fun `empty world selects only defaults`() {
        val c = provider("c", condition = Condition.parse(dictOf("weather.condition" to str("rain"))))
        val selected = ProviderSelector.select(listOf(c, provider("d")), EmptyWorld(), vars)
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

    @Test
    fun `time alias dawn and dusk map to real periods`() {
        // «dawn»/«dusk» — человеческие слова, а мир отдаёт sunrise/sunset.
        // Пока алиас не переводил их, условие молча не совпадало никогда.
        val dawn = Condition.parse(dictOf("time" to str("dawn"))).predicates.single()
        assertEquals(Expected.Str("sunrise"), dawn.expected)

        val dusk = Condition.parse(dictOf("time" to str("dusk"))).predicates.single()
        assertEquals(Expected.Str("sunset"), dusk.expected)

        assertTrue(
            Condition.parse(dictOf("time" to str("dawn")))
                .matches(FakeWorld(mapOf("time.period" to str("sunrise")))),
        )
        assertTrue(
            Condition.parse(dictOf("time" to str("dusk")))
                .matches(FakeWorld(mapOf("time.period" to str("sunset")))),
        )
    }

    @Test
    fun `live fields list what world contexts really serve`() {
        // Этот список — документация в коде. Если в world-контексте появится
        // новое поле, тест напомнит внести его сюда.
        assertEquals(
            listOf("id", "temperature", "precipitation"),
            Condition.LIVE_FIELDS[WorldRoot.BIOME],
        )
        assertEquals(listOf("x", "y", "z"), Condition.LIVE_FIELDS[WorldRoot.LOCATION])
    }

    @Test
    fun `location without field lists real fields instead of inventing one`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            Condition.parse(dictOf("location" to str("y")))
        }
        // Раньше в тексте ошибки было «location.id» — такого поля не бывает.
        assertFalse(e.message!!.contains("location.id"))
        assertTrue(e.message!!.contains("x, y, z"))
    }

    @Test
    fun `biome conditions match against real fields`() {
        val snowy = FakeWorld(
            mapOf(
                "biome.id" to str("minecraft:snowy_plains"),
                "biome.precipitation" to str("snow"),
            ),
        )
        assertTrue(Condition.parse(dictOf("biome" to str("snowy"))).matches(snowy))
        assertTrue(Condition.parse(dictOf("biome" to str("snow"))).matches(snowy))
        assertTrue(Condition.parse(dictOf("biome.id" to str("minecraft:snowy_plains"))).matches(snowy))
        assertFalse(Condition.parse(dictOf("biome" to str("rain"))).matches(snowy))
    }
}

/** [WorldContext], где ничего нет. */
private class EmptyWorld : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value =
        throw dev.ggtv.kjen.CrenError.NotFound(path)
}