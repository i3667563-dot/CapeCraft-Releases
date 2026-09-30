package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Отчёт `/cp list`: показывает, что выбрано и почему остальное отсеялось.
 *
 * Главное свойство отчёта — **невозможность разойтись с боевым выбором**.
 * Диагностика, которая иногда врет, хуже её отсутствия: человек будет верить
 * ей и полезет чинить не то.
 */
class ProviderSelectorReportTest {

    private val vars = Placeholders.Context(
        username = "Steve", uuid = "u", name = "", root = "/root",
    )

    /** Мир с заранее заданными значениями полей. */
    private class StubWorld(private val values: Map<String, Value>) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value =
            values["${root.segment}.${field}"] ?: Value.VStr("unknown")
    }

    private fun whenC(vararg pairs: Pair<String, String>): Condition =
        Condition.parse(
            Value.VDict(
                pairs.map { (k, v) ->
                    k to when (val n = v.toDoubleOrNull()) {
                        null -> Value.VStr(v)
                        else -> Value.VFloat(n)
                    }
                },
            ),
        )

    private fun provider(
        name: String,
        priority: Int = 0,
        whenCond: Condition? = null,
        ifCond: VarCondition? = null,
    ) = Provider(
        name = name,
        source = Source.Url("https://example.invalid/$name.png"),
        condition = whenCond,
        ifCondition = ifCond,
        priority = priority,
    )

    @Test
    fun `порядок совпадает с ожиданием на всех сочетаниях`() {
        val providers = listOf(
            provider("armored", 14, whenCond = whenC("armor.chest" to "netherite")),
            provider("deep", 10, whenCond = whenC("location.y" to "<= -20")),
            provider("hurt", 12, whenCond = whenC("health.current" to "<= 8")),
            provider("plain"),
            provider("second-plain"),
        )
        // Ожидания выписаны руками, а не посчитаны тем же кодом, что и
        // проверяемый: сверка `select` с `evaluate` ничего не проверяет,
        // потому что `select` сам всего лишь вызывает `evaluate`. Такая
        // «проверка» проходит и с перепутанной сортировкой.
        val cases = listOf(
            Triple("netherite", -30.0, listOf("armored", "hurt", "deep", "plain", "second-plain")),
            Triple("netherite", 0.0, listOf("armored", "hurt", "plain", "second-plain")),
            Triple("diamond", -30.0, listOf("hurt", "deep", "plain", "second-plain")),
            Triple("diamond", 0.0, listOf("hurt", "plain", "second-plain")),
            Triple("unknown", -30.0, listOf("hurt", "deep", "plain", "second-plain")),
            Triple("unknown", 0.0, listOf("hurt", "plain", "second-plain")),
        )
        for ((chest, y, expected) in cases) {
            val world = StubWorld(
                mapOf(
                    "armor.chest" to Value.VStr(chest),
                    "location.y" to Value.VFloat(y),
                    // 4.0 — ранен, поэтому `hurt` подходит всегда: без него
                    // проверка приоритетов выродилась бы на два условия.
                    "health.current" to Value.VFloat(4.0),
                ),
            )
            val report = ProviderSelector.evaluate(providers, world, vars)
            val label = "chest=$chest y=$y"
            assertEquals(expected, report.ordered.map { it.name }, "отчёт: $label")
            assertEquals(expected, ProviderSelector.select(providers, world, vars).map { it.name }, "выбор: $label")
            // Победитель обязан быть первым в цепочке — это то, что видно на
            // экране, и именно его печатает `/cp list`.
            assertEquals(expected.first(), report.winner?.provider?.name, "победитель: $label")
            // Условие, по которому провайдер отсеялся, обязано быть видно.
            val armored = report.reports.first { it.provider.name == "armored" }
            assertEquals(chest == "netherite", armored.matched, "armored: $label")
        }
    }

    @Test
    fun `отчёт показывает, какое именно поле не сошлось`() {
        // Ровно случай из жизни: элитры вместо нагрудника. `netherite`
        // требует четыре слота, и сорван ровно один — грудь.
        val providers = listOf(
            provider(
                "netherite",
                14,
                whenCond = whenC(
                    "armor.chest" to "netherite",
                    "armor.head" to "netherite",
                    "armor.legs" to "netherite",
                    "armor.feet" to "netherite",
                ),
            ),
            provider("fallback"),
        )
        val world = StubWorld(
            mapOf(
                "armor.chest" to Value.VStr("unknown"),
                "armor.head" to Value.VStr("none"),
                "armor.legs" to Value.VStr("netherite"),
                "armor.feet" to Value.VStr("netherite"),
            ),
        )
        val report = ProviderSelector.evaluate(providers, world, vars)
        val netherite = report.reports.first { it.provider.name == "netherite" }

        assertFalse(netherite.matched, "элитры в груди — полный незерит не подходит")
        assertFalse(netherite.selected)
        // Причина обязана быть видна поимённо, а не «условие не выполнено».
        val chest = netherite.whenChecks.single { it.path == "armor.chest" }
        assertEquals("unknown", chest.actual)
        assertEquals("= netherite", chest.expected)
        assertFalse(chest.ok)
        // Не сходится ровно два слота, и оба по разным причинам: грудь занята
        // элитрами, голову пуста. Если бы отчёт молчал «условие не выполнено»,
        // невозможно было бы отличить одно от другого.
        val failed = netherite.whenChecks.filterNot { it.ok }
        assertEquals(listOf("armor.chest", "armor.head"), failed.map { it.path })
        assertEquals("none", failed.single { it.path == "armor.head" }.actual)
        assertEquals(listOf("fallback"), report.ordered.map { it.name })
    }

    @Test
    fun `незаданная переменная в if видна как незаданная`() {
        val providers = listOf(
            provider("secret", ifCond = VarCondition.parse(Value.VDict(listOf("\$SECRET" to Value.VStr("1234"))))),
            provider("fallback"),
        )
        val report = ProviderSelector.evaluate(providers, StubWorld(emptyMap()), vars)
        val secret = report.reports.first { it.provider.name == "secret" }
        assertFalse(secret.matched)
        val check = secret.ifChecks.single()
        assertEquals("\$SECRET", check.name)
        assertEquals("не задана", check.actual)
        // Число печатается как Double — в отчёте честнее сказать «1234.0»,
        // чем притворяться строкой: с «1234» его легко спутать с текстом.
        assertEquals("= 1234.0", check.expected)
    }

    @Test
    fun `провайдер без условий помечен как запасной и попадает в цепочку`() {
        val providers = listOf(
            provider("conditional", 5, whenCond = whenC("armor.head" to "netherite")),
            provider("fallback"),
        )
        val report = ProviderSelector.evaluate(providers, StubWorld(emptyMap()), vars)
        val fallback = report.reports.first { it.provider.name == "fallback" }
        assertTrue(fallback.isFallback)
        assertTrue(fallback.selected)
        // «На экране» — первая в цепочке, а не первый в списке конфига.
        assertEquals("fallback", report.winner?.provider?.name)
    }
}
