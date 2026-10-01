package dev.ggtv.capecraft

import dev.ggtv.capecraft.schema.WhenSchema
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сверка подсказок с тем, что мир отдаёт на самом деле.
 *
 * Подсказка `period: "sunset"` — это обещание. Если версия Minecraft вернёт
 * `"dusk"`, провайдер молча перестанет совпадать: ни ошибки, ни
 * предупреждения, просто картинка не та. Поэтому значения из
 * [WhenSchema.VALUES] проверяются не «ещё одним таким же списком в тесте», а
 * по исходникам всех версий и по enum Minecraft.
 */
class WorldContextAgreementTest {
    /**
     * Поля, значения которых мир отдаёт литералом — их видно в исходнике.
     *
     * `biome.precipitation` сюда не входит: оно приходит из
     * `Biome.Precipitation` и переводится в нижний регистр, литералов в
     * исходнике нет — его сверяет [осадки совпадают с enum Minecraft].
     *
     * Про `armor` и `state` стоит сказать отдельно: они тоже приходят не из
     * enum Minecraft напрямую, а собираются вручную — тир выводится из id
     * предмета через белый список `ARMOR_TIERS`, поза — явным `when` по
     * `EntityPose`. Поэтому значения обязаны быть литералами в исходнике: если
     * подсказка обещает `"netherite"`, а рантайм его не отдаст, провайдер
     * молча перестанет совпадать.
     */
    private val literalFields = listOf(
        "condition", "period", "type",
        "head", "chest", "legs", "feet",
        "inWater", "sneaking", "sprinting", "onGround", "pose",
    )

    @Test
    fun `значения полей when есть в исходнике каждой версии`() {
        val closed = WhenSchema.VALUES.filterKeys { WorldRoot.BIOME != it }
        assertTrue(closed.isNotEmpty(), "в схеме не должно быть пустого списка значений")

        for ((root, fields) in closed) {
            for ((field, values) in fields) {
                assertTrue(
                    field in literalFields,
                    "поле ${root.segment}.$field не помечено как сверяемое: добавь его " +
                        "в literalFields или выясни, откуда мир берёт значение",
                )
                for (value in values) {
                    for (version in versions()) {
                        val file = worldContext(version)
                        assertTrue(
                            file.readText().contains("\"$value\""),
                            "версия $version: поле ${root.segment}.$field обещает значение" +
                                " \"$value\", но его нет в ${file.name}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `осадки совпадают с enum Minecraft`() {
        val promised = WhenSchema.valuesOf(WorldRoot.BIOME, "precipitation")
        assertEquals(
            precipitationConstants().map { it.lowercase() }.sorted(),
            promised.sorted(),
            "подсказка обещает осадки, которых нет у Minecraft: $promised",
        )
    }

    @Test
    fun `каждое поле when отдаётся миром в каждой версии`() {
        for (version in versions()) {
            val text = worldContext(version).readText()
            for (root in WhenSchema.roots()) {
                for (field in WhenSchema.fieldsOf(root)) {
                    assertTrue(
                        text.contains("\"$field\""),
                        "версия $version: поле ${root.segment}.$field не отдаётся миром",
                    )
                }
            }
        }
    }

    @Test
    fun `числовые поля when не пересекаются со списком значений`() {
        for ((root, numeric) in WhenSchema.NUMERIC_FIELDS) {
            for (field in numeric) {
                assertEquals(
                    emptyList(),
                    WhenSchema.valuesOf(root, field),
                    "поле ${root.segment}.$field числовое, список значений ему противоречит",
                )
            }
        }
    }

    @Test
    fun `поле по умолчанию не числовое`() {
        // Короткая запись `when { time: ... }` разворачивается в равенство
        // строке. Если полем по умолчанию окажется число, короткая запись
        // станет бессмысленной: `when { time: 24000 }` не сработает никогда.
        for (root in WorldRoot.entries) {
            val field = WhenSchema.defaultFieldOf(root) ?: continue
            assertTrue(
                field in WhenSchema.fieldsOf(root),
                "поле по умолчанию ${root.segment} -> $field мир не отдаёт",
            )
            assertTrue(
                !WhenSchema.isNumeric(root, field),
                "поле по умолчанию ${root.segment} -> $field числовое: короткая запись" +
                    " без точки станет бессмысленной",
            )
        }
    }

    /**
     * Константы `Biome.Precipitation` из classpath тестируемой версии.
     *
     * Имена классов отличаются: до 1.21 — Yarn, дальше Mojang. Если при
     * очередной смене маппингов класс переедет, тест упадёт с «не найдено» —
     * это правильно: именно в этот момент стоит заново убедиться, что
     * подсказка про осадки ещё в силе.
     */
    private fun precipitationConstants(): List<String> {
        val candidates = listOf(
            "net.minecraft.world.biome.Biome\$Precipitation",
            "net.minecraft.world.level.biome.Biome\$Precipitation",
        )
        for (name in candidates) {
            val cls = runCatching { Class.forName(name) }.getOrNull() ?: continue
            return cls.enumConstants.map { (it as Enum<*>).name }
        }
        error("не нашёлся Biome.Precipitation среди $candidates — проверь маппинги")
    }

    private fun versions(): List<String> =
        File("versions").list()
            ?.filter { File("versions/$it/src/main/kotlin/dev/ggtv/capecraft/render").isDirectory }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: error("каталог versions не найден: тест запускают из корня проекта")

    private fun worldContext(version: String) =
        File("versions/$version/src/main/kotlin/dev/ggtv/capecraft/render/MinecraftWorldContext.kt")
}
