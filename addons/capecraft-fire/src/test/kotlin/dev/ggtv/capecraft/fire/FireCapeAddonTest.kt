package dev.ggtv.capecraft.fire

import dev.ggtv.capecraft.api.CapeApi
import dev.ggtv.capecraft.api.condition.CapeWhenRoots
import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.schema.AddonSchemaParser
import dev.ggtv.capecraft.schema.LspLog
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Аддон и его дескриптор обязаны описывать одно и то же.
 *
 * У корня `when` две независимые формы: значение (то, что аддон регистрирует в
 * рантайме) и описание в `capecraft-addon.kn` (то, из чего редактор строит
 * подсказки). Ни одна из них не проверяет другую, поэтому расхождение
 * выглядит исправно с обеих сторон: в редакторе поле есть, а в игре конфиг с
 * ним падает на загрузке — и наоборот.
 */
class FireCapeAddonTest {

    @BeforeEach
    fun register() {
        CapeWhenRoots.clear()
        FireCapeAddon().register(CapeApi())
    }

    @AfterEach
    fun forget() = CapeWhenRoots.clear()

    private fun descriptor(): dev.ggtv.capecraft.schema.AddonSchema {
        val text = checkNotNull(javaClass.getResourceAsStream("/capecraft-addon.kn")) {
            "в jar аддона нет capecraft-addon.kn"
        }.use { it.readBytes().decodeToString() }
        // Разбор сам молчит и уходит в журнал: тесту нужен текст этого
        // журнала, иначе падение выглядит как «дескриптор сломан» без причины.
        val notes = mutableListOf<String>()
        LspLog.overrideForTest { notes += it }
        try {
            return checkNotNull(AddonSchemaParser.parse(text, "capecraft-fire.jar")) {
                "дескриптор аддона не разобрался:\n$notes\n$text"
            }
        } finally {
            LspLog.reset()
        }
    }

    @Test
    fun `корень регистрируется в рантайме`() {
        val root = CapeWhenRoots.registry["fire"]!!
        assertEquals("burning", root.defaultField)
        assertEquals(listOf("burning", "ticks"), root.fields.map { it.name })
    }

    @Test
    fun `дескриптор и рантайм описывают один корень`() {
        val declared = descriptor().conditions.single()
        val runtime = CapeWhenRoots.registry["fire"]!!
        assertEquals(runtime.root, declared.id)
        assertEquals(runtime.defaultField, declared.defaultField)
        assertEquals(runtime.fields.map { it.name }, declared.fields.map { it.name })
        assertEquals(runtime.fields.map { it.values }, declared.fields.map { it.values })
        assertEquals(runtime.fields.map { it.numeric }, declared.fields.map { it.numeric })
    }

    @Test
    fun `дескриптор объявляет тот же apiVersion, что и мод`() {
        // Мод поднял `CAPE_RUNTIME_API_VERSION` до 2 вместе с разделом
        // `conditions`. Расхождение здесь означает, что аддон собран против
        // мода, который его ещё не понимает, и молча ничего не объявит.
        assertEquals(2, descriptor().apiVersion)
    }

    @Test
    fun `условие разбирается и помечается self-only`() {
        val condition = Condition.parse(
            Value.VDict(listOf("fire.burning" to Value.VStr("true"))),
        )
        assertTrue(condition.hasSelfOnly)
    }

    @Test
    fun `у чужого игрока условие не совпадает`() {
        val condition = Condition.parse(
            Value.VDict(listOf("fire.ticks" to Value.VStr(">10"))),
        )
        val foreign = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                throw CrenError.NotFound(path)

            // Наблюдатель держит чужую сущность, но аддонный корень у него
            // не читается: состояние сущности известно только владельцу.
            override fun subjectEntity(): Any? = null
        }
        assertFalse(condition.matches(foreign))
    }
}