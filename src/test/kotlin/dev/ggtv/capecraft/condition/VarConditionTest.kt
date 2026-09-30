package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.koren.EmptyWorldContext
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Разбор и вычисление `if { ... }` — блока по переменным.
 *
 * Проверяется то, что тихо ломается: подстановку вместо имён полей мира,
 * числовые операции над строкой из окружения (переменные окружения всегда
 * строки, и молча не сработавшее `>8000` не выглядит ошибкой) и то, что
 * неизвестная переменная — это «не подошло», а не падение.
 */
class VarConditionParseTest {

    private val vars = Placeholders.Context(
        username = "Eonixx",
        uuid = "0123456789abcdef",
        name = "",
        root = "/games/mc",
    )

    /** Окружение для тестов: `$ИМЯ` читается из этой карты. */
    private var env: Map<String, String> = emptyMap()

    /**
     * Посчитать условие против `vars` с подменённым окружением.
     *
     * Дублирует логику [VarPredicate.matches] с другим источником env: так
     * проверяется и разбор, и резолв, без глобальной подмены `System.getenv`.
     */
    private fun matches(cond: VarCondition): Boolean =
        cond.predicates.all { p ->
            val raw = VarSource.resolveWith(p.name, vars, { env[it] }, { null }) ?: return@all false
            p.op.apply(VarSource.asValue(raw), p.expected)
        }

    @Test
    fun `ключ это имя переменной целиком а не путь по корням мира`() {
        val c = VarCondition.parse(dictOf("username" to str("Eonixx")))
        val p = c.predicates.single()
        assertEquals("username", p.name)
        assertEquals(Op.Eq, p.op)
        assertEquals(Expected.Str("Eonixx"), p.expected)
    }

    @Test
    fun `точка в имени переменной не разбирается на корень и поле`() {
        // В `when` «weather.condition» — это корень и поле. В `if` точка
        // значащая: это либо `$ИМЯ`, либо аддонный плейсхолдер.
        val c = VarCondition.parse(dictOf("\$PROFILE" to str("prod")))
        assertEquals("\$PROFILE", c.predicates.single().name)
    }

    @Test
    fun `корень мира в if не проходит молча`() {
        // `weather` — не переменная, а корень мира. Принимать его молча нельзя:
        // пользователь перенесёт рабочее `when` в `if` и получит вечное «не
        // подошло» вместо ошибки.
        val e = assertThrows(IllegalArgumentException::class.java) {
            VarCondition.parse(dictOf("weather" to str("rain")))
        }
        assertTrue(e.message!!.contains("\$"), "в ошибке должно быть сказано про env: ${e.message}")
    }

    @Test
    fun `пустое имя переменной отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            VarCondition.parse(dictOf("   " to str("x")))
        }
    }

    @Test
    fun `операторы те же что у when`() {
        val c = VarCondition.parse(
            dictOf(
                "uuid" to str(">10"),
                "\$PORT" to str(">=8000"),
                "root" to str("<100"),
                "name" to str("<=5"),
                "\$TIER" to str("!dev"),
                "\$Z" to str("1..9"),
            ),
        )
        assertEquals(
            listOf(Op.Gt, Op.Ge, Op.Lt, Op.Le, Op.NotEq, Op.Range),
            c.predicates.map { it.op },
        )
    }

    @Test
    fun `все предикаты соединяются по AND`() {
        val c = VarCondition.parse(dictOf("username" to str("Eonixx"), "uuid" to str("wrong")))
        assertFalse(c.matches(vars), "один несовпавший предикат обнуляет всё условие")

        val both = VarCondition.parse(dictOf("username" to str("Eonixx"), "root" to str("/games/mc")))
        assertTrue(both.matches(vars))
    }

    @Test
    fun `неизвестная переменная это не подошло а не ошибка`() {
        val c = VarCondition.parse(dictOf("addonIHaveNotGot" to str("x")))
        assertFalse(c.matches(vars), "нет переменной → условие не выполнено, а не исключение")
    }

    @Test
    fun `env читает сырое имя переменной а не CAPECRAFT_ префикс`() {
        env = mapOf("PROFILE" to "prod")
        assertTrue(matches(VarCondition.parse(dictOf("\$PROFILE" to str("prod")))))
        assertFalse(matches(VarCondition.parse(dictOf("\$PROFILE" to str("dev")))))
    }

    @Test
    fun `env не берётся из плейсхолдеров`() {
        // `$username` — это переменная окружения, а не `{username}`.
        // Смешивать их нельзя: иначе `$X` тайно означал бы «плейсхолдер X».
        env = emptyMap()
        assertFalse(matches(VarCondition.parse(dictOf("\$username" to str("Eonixx")))))
    }

    @Test
    fun `несуществующая env переменная это не подошло`() {
        env = emptyMap()
        assertFalse(matches(VarCondition.parse(dictOf("\$NOPE" to str("x")))))
    }

    @Test
    fun `пустое имя после $ это ошибка загрузки а не вечное не подошло`() {
        // Молчаливое «никогда не совпадёт» — худший вид поломки: условие
        // выглядит живым, а провайдер не выберется никогда. Опечатку видно сразу.
        env = mapOf("" to "x")
        val e = assertThrows(IllegalArgumentException::class.java) {
            VarCondition.parse(dictOf("\$" to str("x")))
        }
        assertTrue(e.message!!.contains("не имя переменной окружения"), "сообщение: ${e.message}")
    }

    @Test
    fun `имя с точкой после $ это ошибка а не тихое не подошло`() {
        // Точка в `$`-имени недопустима (в аддонном плейсхолдере — да), поэтому
        // `$my.var` почти наверняка опечатка, а не переменная окружения.
        val e = assertThrows(IllegalArgumentException::class.java) {
            VarCondition.parse(dictOf("\$my.var" to str("x")))
        }
        assertTrue(e.message!!.contains("не имя переменной окружения"), "сообщение: ${e.message}")
    }

    @Test
    fun `число из окружения сравнивается как число а не как строка`() {
        // Переменная окружения всегда строка. Без приведения к числу `>8000`
        // не сработало бы никогда и выглядело бы как «условие мимо».
        env = mapOf("PORT" to "9000")
        assertTrue(matches(VarCondition.parse(dictOf("\$PORT" to str(">8000")))))
        assertTrue(matches(VarCondition.parse(dictOf("\$PORT" to str("9000")))))
        assertTrue(matches(VarCondition.parse(dictOf("\$PORT" to str("8000..9100")))))
        assertFalse(matches(VarCondition.parse(dictOf("\$PORT" to str(">9100")))))
    }

    @Test
    fun `нечисловая env переменная не совпадает с числовым условием`() {
        env = mapOf("PORT" to "not-a-number")
        assertFalse(matches(VarCondition.parse(dictOf("\$PORT" to str(">8000")))))
        // Строковое сравнение при этом продолжает работать.
        assertTrue(matches(VarCondition.parse(dictOf("\$PORT" to str("not-a-number")))))
    }

    @Test
    fun `дробная env переменная сравнивается как дробная`() {
        env = mapOf("THRESHOLD" to "0.25")
        assertTrue(matches(VarCondition.parse(dictOf("\$THRESHOLD" to str(">0.2")))))
        assertFalse(matches(VarCondition.parse(dictOf("\$THRESHOLD" to str(">0.3")))))
    }

    @Test
    fun `боевой resolve читает настоящее окружение`() {
        // Остальные тесты подменяют источники, поэтому проверяют разбор и
        // сравнение, но не сам `System.getenv`. Сломанная лямбда источника
        // прошла бы их все: имя переменной сравнивалось бы само с собой.
        // Здесь идёт настоящий путь, а вместо окружения — `-D` с тем же
        // именем, что мод читает вторым источником.
        val key = "capecraft_test_${System.nanoTime()}"
        val ctx = dev.ggtv.capecraft.schema.Placeholders.Context(
            username = "Steve", uuid = "u", name = "x", root = "/root",
        )
        try {
            assertNull(
                VarSource.resolve("\$$key", ctx),
                "переменной нет — значение не должно появляться из воздуха",
            )

            System.setProperty(key, "gold")
            assertEquals("gold", VarSource.resolve("\$$key", ctx))
            assertTrue(
                VarCondition.parse(dictOf("\$$key" to str("gold"))).matches(ctx),
                "с настоящим источником условие обязано совпасть",
            )
            assertFalse(VarCondition.parse(dictOf("\$$key" to str("silver"))).matches(ctx))

            // Число приходит строкой, но обязано пониматься как число.
            System.setProperty(key, "9000")
            assertTrue(VarCondition.parse(dictOf("\$$key" to str(">8000"))).matches(ctx))
        } finally {
            System.clearProperty(key)
        }
        assertNull(VarSource.resolve("\$$key", ctx), "переменную убрали — условие должно погаснуть")
    }

    @Test
    fun `переменная окружения не путается с самой собой`() {
        // Регрессия: источник возвращал само имя переменной вместо значения,
        // и `$TIER: "TIER"` совпадало бы при любом окружении.
        val ctx = dev.ggtv.capecraft.schema.Placeholders.Context(
            username = "Steve", uuid = "u", name = "x", root = "/root",
        )
        val key = "capecraft_test_same_${System.nanoTime()}"
        try {
            System.setProperty(key, "TIER")
            assertFalse(
                VarCondition.parse(dictOf("\$$key" to str(key))).matches(ctx),
                "имя переменной не должно совпадать со своим же значением",
            )
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun `условие по env гасит и зажигает провайдера вместе с окружением`() {
        // Требование буквально: провайдер активен ровно тогда, когда
        // переменная есть и равна. Без переменной его нет в выдаче вовсе.
        val key = "capecraft_test_gate_${System.nanoTime()}"
        val provider = dev.ggtv.capecraft.provider.Provider(
            name = "gated",
            source = dev.ggtv.capecraft.provider.Source.Url("https://e/g.png"),
            ifCondition = VarCondition.parse(dictOf("\$$key" to str("on"))),
        )
        val other = dev.ggtv.capecraft.provider.Provider(
            name = "always",
            source = dev.ggtv.capecraft.provider.Source.Url("https://e/a.png"),
        )
        fun ctx() = dev.ggtv.capecraft.schema.Placeholders.Context(
            username = "Steve", uuid = "u", name = "x", root = "/root",
        )
        try {
            assertEquals(
                listOf("always"),
                ProviderSelector.select(listOf(provider, other), EmptyWorldContext, ctx()).map { it.name },
                "без переменной условный провайдер не участвует",
            )

            System.setProperty(key, "off")
            assertEquals(
                listOf("always"),
                ProviderSelector.select(listOf(provider, other), EmptyWorldContext, ctx()).map { it.name },
                "переменная есть, но не равна — провайдер всё равно не участвует",
            )

            System.setProperty(key, "on")
            assertEquals(
                listOf("gated", "always"),
                ProviderSelector.select(listOf(provider, other), EmptyWorldContext, ctx()).map { it.name },
                "как только переменная появилась нужного значения — провайдер включился",
            )
        } finally {
            System.clearProperty(key)
        }
        assertEquals(
            listOf("always"),
            ProviderSelector.select(listOf(provider, other), EmptyWorldContext, ctx()).map { it.name },
            "переменную убрали — провайдер должен погаснуть",
        )
    }

    @Test
    fun `uuid сравнивается со значением без дефисов`() {
        // В URL-шаблонах `{uuid}` подставляется без дефисов, и `if` обязан
        // вести себя так же, иначе одно и то же имя работало бы по-разному.
        assertTrue(VarCondition.parse(dictOf("uuid" to str("0123456789abcdef"))).matches(vars))
        assertFalse(VarCondition.parse(dictOf("uuid" to str("01234567-89ab-cdef"))).matches(vars))
    }

    @Test
    fun `пустой блок это всегда и в if и в when`() {
        assertTrue(VarCondition.parse(dictOf()).matches(vars))
        assertTrue(VarCondition.parse(dictOf()).isEmpty)
    }
}

/**
 * Отбор провайдеров с учётом `if` рядом с `when`.
 *
 * Ключевое поведение: `if` **не заменяет** `when`, а соединяется с ним по
 * И. Провайдер с одним лишь `if` обязан попасть в условные, иначе он молча
 * стал бы fallback'ом и показывался бы всем подряд.
 */
class ProviderSelectorIfTest {

    private val world = FakeWorld(mapOf("weather.condition" to str("rain")))
    private val vars = Placeholders.Context(username = "Eonixx", uuid = "u", name = "", root = "/root")
    private val other = Placeholders.Context(username = "Кто-то", uuid = "v", name = "", root = "/root")

    private fun provider(
        name: String,
        condition: Condition? = null,
        ifCondition: VarCondition? = null,
        priority: Int = 0,
    ) = Provider(
        name = name,
        source = Source.Url("https://x/$name"),
        condition = condition,
        ifCondition = ifCondition,
        priority = priority,
    )

    @Test
    fun `провайдер только с if попадает в условные а не в default`() {
        val p = provider("e", ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))))
        val selected = ProviderSelector.select(listOf(p, provider("d")), world, vars)
        assertEquals(listOf("e", "d"), selected.map { it.name })
    }

    @Test
    fun `if считается против того кого видно`() {
        val p = provider("e", ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))))
        assertEquals(listOf("e"), ProviderSelector.select(listOf(p), world, vars).map { it.name })
        assertEquals(
            emptyList<String>(),
            ProviderSelector.select(listOf(p), world, other).map { it.name },
            "чужому игроку условие выполниться не должно",
        )
    }

    @Test
    fun `when и if соединяются по И`() {
        val p = provider(
            "both",
            condition = Condition.parse(dictOf("weather.condition" to str("rain"))),
            ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))),
        )
        // Оба совпали.
        assertEquals(listOf("both"), ProviderSelector.select(listOf(p), world, vars).map { it.name })
        // `when` совпал, `if` — нет.
        assertEquals(emptyList<String>(), ProviderSelector.select(listOf(p), world, other).map { it.name })
    }

    @Test
    fun `if не проигнорирован когда совпал а когда нет`() {
        val p = provider(
            "p",
            condition = Condition.parse(dictOf("weather.condition" to str("clear"))),
            ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))),
        )
        assertEquals(
            emptyList<String>(),
            ProviderSelector.select(listOf(p), world, vars).map { it.name },
            "совпавший if не должен вытащить провайдера с несовпавшим when",
        )
    }

    @Test
    fun `провайдер без условий попадает в список ровно один раз`() {
        // Регрессия: без проверки hasConditions провайдер без условий проходит
        // «оба условия выполнены» (оба null) и попадает и в matched, и в
        // defaults — то есть дважды.
        val selected = ProviderSelector.select(listOf(provider("d1"), provider("d2")), world, vars)
        assertEquals(listOf("d1", "d2"), selected.map { it.name })
    }

    @Test
    fun `условные по if сортируются по приоритету как и when`() {
        val low = provider("low", ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))), priority = 1)
        val high = provider("high", ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))), priority = 9)
        val selected = ProviderSelector.select(listOf(low, high, provider("d")), world, vars)
        assertEquals(listOf("high", "low", "d"), selected.map { it.name })
    }

    @Test
    fun `if работает и при недоступном мире`() {
        // Мир недоступен → `when` не совпадает, но `if` на переменных — вполне.
        val p = provider("p", ifCondition = VarCondition.parse(dictOf("username" to str("Eonixx"))))
        assertEquals(listOf("p"), ProviderSelector.select(listOf(p), EmptyWorldContext, vars).map { it.name })
    }
}
