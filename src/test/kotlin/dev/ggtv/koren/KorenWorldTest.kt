package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Живые корни мира через [WorldContext]: biome, weather, time, dimension, location. */
class KorenWorldTest {

    /** Тестовый мир: фиксированные значения, меняются через mutable. */
    private class FakeWorld(
        var temperature: Double = 0.7,
        var condition: String = "clear",
        var day: Long = 12,
        var dimension: String = "minecraft:overworld",
        var x: Double = 128.5,
    ) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value = when (root) {
            WorldRoot.BIOME -> when (field) {
                "temperature" -> Value.VFloat(temperature)
                else -> throw CrenError.NotFound(path)
            }
            WorldRoot.WEATHER -> when (field) {
                "condition" -> Value.VStr(condition)
                else -> throw CrenError.NotFound(path)
            }
            WorldRoot.TIME -> when (field) {
                "day" -> Value.VInt(day)
                else -> throw CrenError.NotFound(path)
            }
            WorldRoot.DIMENSION -> when (field) {
                "id" -> Value.VStr(dimension)
                "type" -> Value.VStr(if (dimension == "minecraft:the_nether") "nether" else "overworld")
                else -> throw CrenError.NotFound(path)
            }
            WorldRoot.LOCATION -> when (field) {
                "x" -> Value.VFloat(x)
                "z" -> Value.VFloat(x * 2) // выдумано для второго поля
                else -> throw CrenError.NotFound(path)
            }
        }
    }

    @Test
    fun `biome temperature from world`() {
        val world = FakeWorld(temperature = 0.9)
        val c = KorenConfig.fromString("a = biome.temperature\n", world)
        assertEquals(0.9, c.getFloat("a"))
    }

    @Test
    fun `dynamic re-evaluation without config rebuild`() {
        val world = FakeWorld(temperature = 0.9)
        val c = KorenConfig.fromString("a = biome.temperature\n", world)
        assertEquals(0.9, c.getFloat("a"))

        // Игрок перешёл в другой биом — конфиг не пересобирается.
        world.temperature = -1.2
        assertEquals(-1.2, c.getFloat("a"))
    }

    @Test
    fun `world field inside function`() {
        val world = FakeWorld(temperature = 2.5)
        val c = KorenConfig.fromString("a = clamp(biome.temperature, 0, 2)\n", world)
        assertEquals(2.0, c.getFloat("a"))
    }

    @Test
    fun `weather condition string`() {
        val world = FakeWorld(condition = "rain")
        val c = KorenConfig.fromString("a = weather.condition\n", world)
        assertEquals("rain", c.getStr("a"))
    }

    @Test
    fun `time and dimension and location`() {
        val world = FakeWorld(day = 5, dimension = "minecraft:the_nether")
        val c = KorenConfig.fromString("""
            d = time.day
            id = dimension.id
            t = dimension.type
            x = location.x
        """, world)
        assertEquals(5L, c.getInt("d"))
        assertEquals("minecraft:the_nether", c.getStr("id"))
        assertEquals("nether", c.getStr("t"))
        assertEquals(128.5, c.getFloat("x"))
    }

    @Test
    fun `config key at root wins over world`() {
        val world = FakeWorld(temperature = 0.9)
        val c = KorenConfig.fromString("""
            biome {
                temperature = 123
            }
            a = biome.temperature
        """, world)
        assertEquals(123L, c.getInt("a"))
    }

    @Test
    fun `relative ref to world is not resolved from world`() {
        val world = FakeWorld(temperature = 0.9)
        val c = KorenConfig.fromString("""
            server {
                a = .biome.temperature
            }
        """, world)
        assertFailsWith<CrenError.NotFound> { c.get("server.a") }
    }

    @Test
    fun `world without context is not found`() {
        val c = KorenConfig.fromString("a = biome.temperature\n")
        assertFailsWith<CrenError.NotFound> { c.get("a") }
    }

    @Test
    fun `unknown world field is not found`() {
        val world = FakeWorld()
        val c = KorenConfig.fromString("a = biome.humidity\n", world)
        assertFailsWith<CrenError.NotFound> { c.get("a") }
    }

    @Test
    fun `dotted world key is block-rooted when config defines it`() {
        // Три сегмента — не корень мира, а настоящее поле конфига.
        val world = FakeWorld(temperature = 0.9)
        val c = KorenConfig.fromString("""
            biome {
                sub {
                    temperature = 777
                }
            }
            a = biome.sub.temperature
        """, world)
        assertEquals(777L, c.getInt("a"))
    }

    @Test
    fun `context can be swapped without rebuild`() {
        val c = KorenConfig.fromString("a = weather.condition\n", FakeWorld(condition = "sunny"))
        assertEquals("sunny", c.getStr("a"))

        val world2 = FakeWorld(condition = "storm")
        val c2 = KorenConfig.withContext(c, world2)
        assertEquals("storm", c2.getStr("a"))
    }
}