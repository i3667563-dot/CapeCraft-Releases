package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.api.condition.CapeWhenField
import dev.ggtv.capecraft.api.condition.CapeWhenRegistry
import dev.ggtv.capecraft.api.condition.CapeWhenRoot
import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.capecraft.api.condition.UNKNOWN
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.sync.WireCondition
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Условия `when`, объявленные аддоном.
 *
 * Проверяется не «разобралось», а три вещи, на которых держится вся договорённость:
 * разбор видит аддонный корень, значение читает **контекст** (а не реестр), и на
 * провод такое условие не едет ни под каким видом.
 */
class AddonWhenConditionTest {

    private val registry = CapeWhenRegistry()

    @BeforeEach
    fun register() {
        registry.register(
            CapeWhenRoot(
                root = "fire",
                doc = "Горит ли игрок.",
                defaultField = "burning",
                fields = listOf(
                    CapeWhenField("burning", "Горит ли игрок.", values = listOf("true", "false")),
                    CapeWhenField("ticks", "Сколько тиков горения.", numeric = true),
                ),
                reader = { subject, field ->
                    val entity = subject as? TestEntity ?: throw CrenError.NotFound("when")
                    when (field) {
                        "burning" -> Value.VStr(if (entity.burning) "true" else "false")
                        "ticks" -> Value.VInt(entity.ticks)
                        else -> throw CrenError.NotFound("when")
                    }
                },
            ),
        )
        CapeWhenRoots.registry.clear()
        CapeWhenRoots.registry.register(
            CapeWhenRoot(
                root = "fire",
                defaultField = "burning",
                fields = listOf(
                    CapeWhenField("burning", values = listOf("true", "false")),
                    CapeWhenField("ticks", numeric = true),
                ),
                reader = { subject, field ->
                    val entity = subject as? TestEntity ?: throw CrenError.NotFound("when")
                    when (field) {
                        "burning" -> Value.VStr(if (entity.burning) "true" else "false")
                        "ticks" -> Value.VInt(entity.ticks)
                        else -> throw CrenError.NotFound("when")
                    }
                },
            ),
        )
    }

    @AfterEach
    fun forget() = CapeWhenRoots.clear()

    /** Сущность, ради которой вообще затевался аддонный корень. */
    data class TestEntity(val burning: Boolean, val ticks: Long)

    /**
     * Контекст, который честно отвечает, чья это сущность.
     *
     * Ровно так же ведут себя версионные `EntityWorldContext`: своя сущность
     * читается, чужая — `NotFound`.
     */
    private class SelfWorld(private val entity: TestEntity?) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value =
            throw CrenError.NotFound(path)

        override fun subjectEntity(): Any? = entity

        override fun addonField(root: String, field: String, path: String): Value =
            CapeWhenRoots.read(this, root, field, path)
    }

    /**
     * Контекст наблюдателя, у которого в руках **чужая** сущность.
     *
     * Это ровно тот случай, который ломается, если условие читается из реестра
     * напрямую: сущность у контекста есть ([EntityWorldContext] отдаёт того
     * игрока, чей плащ считаем), и «горящий» плащ владельца начинает
     * показываться каждому, кто стоит рядом с ним горящим. Наблюдатель не
     * знает, горит ли сосед, поэтому `addonField` у него не переопределён и
     * отдаёт `NotFound`.
     */
    private class ForeignWorld(private val entity: TestEntity) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value =
            throw CrenError.NotFound(path)

        override fun subjectEntity(): Any? = entity
    }

    private fun burning(burning: Boolean = true, ticks: Long = 7) =
        SelfWorld(TestEntity(burning, ticks))

    // ---------------------------------------------------------------- разбор

    @Test
    fun `аддонный корень разбирается в предикат`() {
        val p = Condition.parse(dictOf("fire.burning" to str("true"))).predicates.single()
        assertEquals("fire", p.segment)
        assertEquals("burning", p.field)
        assertEquals(Expected.Str("true"), p.expected)
    }

    @Test
    fun `корень без точки берёт поле по умолчанию`() {
        val p = Condition.parse(dictOf("fire" to str("true"))).predicates.single()
        assertEquals("burning", p.field)
    }

    @Test
    fun `ошибка неизвестного корня перечисляет аддонные`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Condition.parse(dictOf("flame.burning" to str("true")))
        }
        assertTrue(e.message!!.contains("fire"), "в списке корней нет аддонного: ${e.message}")
        assertTrue(e.message!!.contains("biome"), "в списке корней нет встроенного: ${e.message}")
    }

    @Test
    fun `неизвестное поле перечисляет поля корня`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Condition.parse(dictOf("fire.wet" to str("true")))
        }
        assertTrue(e.message!!.contains("burning"), "${e.message}")
    }

    @Test
    fun `значение не из списка отвергается`() {
        assertFailsWith<IllegalArgumentException> {
            Condition.parse(dictOf("fire.burning" to str("да")))
        }
    }

    @Test
    fun `unknown допустим`() {
        val p = Condition.parse(dictOf("fire.burning" to str(UNKNOWN))).predicates.single()
        assertEquals(Expected.Str(UNKNOWN), p.expected)
    }

    @Test
    fun `числовое поле принимает сравнение и не принимает слово`() {
        val p = Condition.parse(dictOf("fire.ticks" to str(">5"))).predicates.single()
        assertEquals(Op.Gt, p.op)
        assertEquals(Expected.Num(5.0), p.expected)
        assertFailsWith<IllegalArgumentException> { Condition.parse(dictOf("fire.ticks" to str("много"))) }
    }

    // ------------------------------------------------------------ сопоставление

    @Test
    fun `значение приходит из контекста`() {
        val c = Condition.parse(dictOf("fire.burning" to str("true")))
        assertTrue(c.matches(burning()))
        assertFalse(c.matches(burning(burning = false)))
    }

    @Test
    fun `отрицание тоже работает`() {
        val c = Condition.parse(dictOf("fire.burning" to str("!true")))
        assertFalse(c.matches(burning()))
        assertTrue(c.matches(burning(burning = false)))
    }

    @Test
    fun `числовое условие считается по числу`() {
        val c = Condition.parse(dictOf("fire.ticks" to str(">=7")))
        assertTrue(c.matches(burning(ticks = 7)))
        assertFalse(c.matches(burning(ticks = 6)))
    }

    /**
     * Главное поведение: у наблюдателя условие обязано **молча** не совпасть.
     *
     * Раньше чтение шло напрямую в реестр, минуя контекст, и мой «горящий»
     * плащ показывался каждому, кто стоял рядом. Контекст без своей сущности
     * отдаёт `NotFound`, и `Condition.matches` превращает его в `false`.
     */
    @Test
    fun `у чужого игрока условие не совпадает даже когда сущность под рукой`() {
        val c = Condition.parse(dictOf("fire.burning" to str("true")))
        assertFalse(c.matches(ForeignWorld(TestEntity(burning = true, ticks = 7))))
    }

    @Test
    fun `у контекста без сущности условие не совпадает`() {
        val c = Condition.parse(dictOf("fire.burning" to str("true")))
        assertFalse(c.matches(SelfWorld(null)))
    }

    /**
     * Чтение идёт через `addonField` контекста, а не через реестр напрямую.
     *
     * Контекст ниже отвечает `true` сам, без всякой сущности: если бы условие
     * спрашивало реестр, такой ответ был бы невозможен.
     */
    @Test
    fun `значение берётся у контекста, а не у реестра`() {
        val liar = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                throw CrenError.NotFound(path)

            override fun addonField(root: String, field: String, path: String) =
                Value.VStr("true")
        }
        val c = Condition.parse(dictOf("fire.burning" to str("true")))
        assertTrue(c.matches(liar))
    }

    @Test
    fun `упавший читатель даёт unknown, а не исключение`() {
        CapeWhenRoots.clear()
        CapeWhenRoots.registry.register(
            CapeWhenRoot(
                root = "boom",
                defaultField = "x",
                fields = listOf(CapeWhenField("x", values = listOf("true", "false"))),
                reader = { _, _ -> throw IllegalStateException("аддон сломался") },
            ),
        )
        val c = Condition.parse(dictOf("boom.x" to str("true")))
        assertFalse(c.matches(SelfWorld(TestEntity(true, 0))))
        assertEquals(Value.VStr(UNKNOWN), CapeWhenRoots.read(SelfWorld(null), "boom", "x", "p"))
    }

    @Test
    fun `explain не падает на чужом контексте`() {
        val c = Condition.parse(dictOf("fire.burning" to str("true")))
        assertFalse(c.explain(ForeignWorld(TestEntity(true, 7))).isEmpty())
    }

    // ------------------------------------------------------------------ сеть

    @Test
    fun `на провод аддонное условие не переводится`() {
        assertNull(WireCondition.from(Condition.parse(dictOf("fire.burning" to str("true")))))
    }

    @Test
    fun `смешанное условие тоже не переводится`() {
        // Частично перевести нельзя: объявление уехало бы без аддонной части и
        // получатель применил бы не тот набор, который объявил отправитель.
        val c = Condition.parse(dictOf("biome" to str("plains"), "fire.burning" to str("true")))
        assertNull(WireCondition.from(c))
    }

    @Test
    fun `провайдер с аддонным условием объявляется только себе`() {
        assertTrue(Condition.parse(dictOf("fire.burning" to str("true"))).hasSelfOnly)
        val provider = Provider(
            name = "p",
            source = Source.Url("https://e/{uuid}.png"),
            condition = Condition.parse(dictOf("fire.burning" to str("true"))),
        )
        assertTrue(provider.isSelfOnly)
    }

    // -------------------------------------------------------------- реестр

    @Test
    fun `встроенное имя занять нельзя`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(CapeWhenRoot("biome", fields = listOf(CapeWhenField("id")), reader = { _, _ -> Value.VStr("") }))
        }
    }

    @Test
    fun `точка в имени корня недопустима`() {
        val e = assertFailsWith<IllegalArgumentException> {
            registry.register(CapeWhenRoot("my.mod", fields = listOf(CapeWhenField("x")), reader = { _, _ -> Value.VStr("") }))
        }
        assertTrue(e.message!!.contains("точку"), "${e.message}")
    }

    @Test
    fun `точка в имени поля недопустима`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                CapeWhenRoot("mine", fields = listOf(CapeWhenField("a.b")), reader = { _, _ -> Value.VStr("") }),
            )
        }
    }

    @Test
    fun `пустой корень без полей недопустим`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(CapeWhenRoot("mine", fields = emptyList(), reader = { _, _ -> Value.VStr("") }))
        }
    }

    @Test
    fun `поле по умолчанию должно быть объявлено`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                CapeWhenRoot(
                    "mine",
                    defaultField = "nope",
                    fields = listOf(CapeWhenField("x")),
                    reader = { _, _ -> Value.VStr("") },
                ),
            )
        }
    }

    @Test
    fun `повтор поля недопустим`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                CapeWhenRoot("mine", fields = listOf(CapeWhenField("x"), CapeWhenField("x")), reader = { _, _ -> Value.VStr("") }),
            )
        }
    }

    @Test
    fun `повторная регистрация перезаписывает`() {
        registry.register(CapeWhenRoot("mine", fields = listOf(CapeWhenField("x")), reader = { _, _ -> Value.VStr("v1") }))
        registry.register(CapeWhenRoot("mine", fields = listOf(CapeWhenField("y")), reader = { _, _ -> Value.VStr("v2") }))
        assertEquals(listOf("y"), registry["mine"]!!.fields.map { it.name })
    }
}