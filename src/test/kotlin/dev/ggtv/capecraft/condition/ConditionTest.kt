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

    // ─────────────── self-only корни игрока ───────────────

    /**
     * Горение, руки, еда, опыт и эффекты не уезжают по сети.
     *
     * Проверяется на каждом корне: забытый в [Condition.SELF_ONLY_ROOTS] корень
     * не падает — условие просто начинает ехать к наблюдателю, который считает
     * его против СВОЕГО игрока, и человек получает чужой плащ без единой ошибки
     * в логах. Единственная защита — сам список, поэтому он и сверяется целиком.
     */
    @Test
    fun `все корни игрока self-only`() {
        val mine = listOf(
            WorldRoot.STATE,
            WorldRoot.FIRE,
            WorldRoot.HAND,
            WorldRoot.FOOD,
            WorldRoot.XP,
            WorldRoot.EFFECT,
            WorldRoot.EFFECT_AMPLIFIER,
            WorldRoot.EFFECT_DURATION,
        )
        assertEquals(mine.toSet(), Condition.SELF_ONLY_ROOTS, "набор self-only корней разошёлся")
        for (root in mine) {
            assertTrue(Condition.isSelfOnlyRoot(root), "корень ${root.segment} не self-only")
            // Значение подбирается под тип поля намеренно: числовое поле со
            // строкой падает на разборе, и проверка self-only не дошла бы до
            // смысла — упала бы на чужем основании и замаскировала бы дыру.
            val value = if (Condition.isNumeric(root, "x")) str("1") else str("y")
            assertTrue(
                Condition.parse(dictOf("${root.segment}.x" to value)).hasSelfOnly,
                "условие с корнем ${root.segment} не помечено self-only",
            )
        }
    }

    /**
     * Корни, видные всем, не должны случайно стать self-only.
     *
     * Обратная проверка: `armor` и `health` видно со стороны, и их объявление
     * self-only отключило бы чужие плащи у всех, кто не владелец.
     */
    @Test
    fun `публичные корни остаются публичными`() {
        for (root in listOf(
            WorldRoot.BIOME, WorldRoot.WEATHER, WorldRoot.TIME, WorldRoot.DIMENSION,
            WorldRoot.LOCATION, WorldRoot.ARMOR, WorldRoot.HEALTH,
        )) {
            assertFalse(Condition.isSelfOnlyRoot(root), "корень ${root.segment} не должен быть self-only")
        }
    }

    @Test
    fun `fire без точки это burning`() {
        val burning = FakeWorld(mapOf("fire.burning" to str("true")))
        assertTrue(Condition.parse(dictOf("fire" to str("true"))).matches(burning))
        assertFalse(Condition.parse(dictOf("fire" to str("false"))).matches(burning))
    }

    @Test
    fun `hand без точки это основная рука`() {
        val world = FakeWorld(mapOf("hand.main" to str("shield"), "hand.off" to str("none")))
        assertTrue(Condition.parse(dictOf("hand" to str("shield"))).matches(world))
        // Вторая рука проверяется явно: без точки она не читается никогда.
        assertFalse(Condition.parse(dictOf("hand" to str("none"))).matches(world))
        assertTrue(Condition.parse(dictOf("hand.off" to str("none"))).matches(world))
    }

    @Test
    fun `food и xp сравниваются числами`() {
        val world = FakeWorld(
            mapOf(
                "food.level" to int(4),
                "food.saturation" to num(1.5),
                "xp.level" to int(30),
                "xp.progress" to num(0.25),
            ),
        )
        assertTrue(Condition.parse(dictOf("food.level" to str("<8"))).matches(world))
        assertFalse(Condition.parse(dictOf("food.level" to str(">8"))).matches(world))
        assertTrue(Condition.parse(dictOf("food.saturation" to str("1..2"))).matches(world))
        assertTrue(Condition.parse(dictOf("xp.level" to str(">=30"))).matches(world))
        assertTrue(Condition.parse(dictOf("xp.progress" to str("<0.5"))).matches(world))
    }

    /**
     * Строка в числовом поле — ошибка загрузки, а не «никогда не совпадёт».
     *
     * `food.level: "низко"` разобралось бы в строковое ожидание, для которого
     * у `Op` нет сравнения: условие не сработало бы никогда и не дало бы ошибки.
     */
    @Test
    fun `строка в числовом поле еды и опыта`() {
        for (key in listOf("food.level", "food.saturation", "xp.level", "xp.progress")) {
            val e = assertFailsWith<IllegalArgumentException> {
                Condition.parse(dictOf(key to str("низко")))
            }
            assertTrue(e.message!!.contains(key), "в ошибке нет поля $key")
        }
    }

    // ─────────────── эффекты ───────────────

    @Test
    fun `эффект ищется по своему полю`() {
        val poisoned = FakeWorld(mapOf("effect.poison" to str("active")))
        assertTrue(Condition.parse(dictOf("effect.poison" to str("active"))).matches(poisoned))
        // Другого эффекта нет — поле недоступно, а не «есть ложь».
        assertFalse(Condition.parse(dictOf("effect.speed" to str("active"))).matches(poisoned))
        // Отрицание работает как для строк, а не требует отдельной ветки в Op.
        assertFalse(Condition.parse(dictOf("effect.poison" to str("!active"))).matches(poisoned))

        // Мир отдаёт `inactive`, а не «поля нет»: без этого `!active` нельзя
        // было бы написать вовсе — NotFound даёт «не подошло», и условие
        // «нет яда» работало бы только для тех, у кого это поле объявлено.
        val clean = FakeWorld(
            mapOf("effect.poison" to str("inactive"), "effect.speed" to str("inactive")),
        )
        assertTrue(Condition.parse(dictOf("effect.speed" to str("!active"))).matches(clean))

        // Ключ, которого в мире нет вовсе, обязан давать «не совпало»:
        // иначе опечатка в конфиге печатала бы плащ тому, у кого эффекта нет.
        assertFalse(Condition.parse(dictOf("effect.poison" to str("active"))).matches(clean))
        assertTrue(Condition.parse(dictOf("effect.poison" to str("inactive"))).matches(clean))
    }

    @Test
    fun `усиление и остаток эффекта числа`() {
        val world = FakeWorld(
            mapOf(
                "effect_amplifier.poison" to int(1),
                "effect_duration.jump_boost" to int(40),
            ),
        )
        assertTrue(Condition.parse(dictOf("effect_amplifier.poison" to str(">=1"))).matches(world))
        assertFalse(Condition.parse(dictOf("effect_amplifier.poison" to str(">1"))).matches(world))
        assertTrue(Condition.parse(dictOf("effect_duration.jump_boost" to str("<100"))).matches(world))
    }

    @Test
    fun `усиление эффекта не строка`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Condition.parse(dictOf("effect_amplifier.poison" to str("сильный")))
        }
        assertTrue(e.message!!.contains("effect_amplifier"))
    }

    /**
     * Эффект мода без правок модели.
     *
     * `EFFECT_IDS` — подсказка, а не белый список. Если бы он был белым,
     * подсказка и валидация разошлись бы при первом же моде со своим эффектом,
     * а условие по нему перестало бы работать молча.
     */
    @Test
    fun `эффект из мода принимается`() {
        val world = FakeWorld(mapOf("effect.some_mod_effect" to str("active")))
        assertTrue(
            Condition.parse(dictOf("effect.some_mod_effect" to str("active"))).matches(world),
        )
    }

    @Test
    fun `корень эффекта без поля объясняет что делать`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Condition.parse(dictOf("effect" to str("active")))
        }
        assertTrue(e.message!!.contains("effect."), "в ошибке нет примера: ${e.message}")
        assertFalse(
            e.message!!.contains("null"),
            "в ошибке просочился внутренний null: ${e.message}",
        )
    }
}

/** [WorldContext], где ничего нет. */
private class EmptyWorld : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value =
        throw dev.ggtv.kjen.CrenError.NotFound(path)
}