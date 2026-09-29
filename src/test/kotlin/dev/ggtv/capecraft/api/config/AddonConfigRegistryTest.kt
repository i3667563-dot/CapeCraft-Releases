package dev.ggtv.capecraft.api.config

import dev.ggtv.kjen.Value
import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Гонка между [CapeAddonConfigRegistry.loadFrom] и чтением через `get`.
 *
 * `loadFrom` перечитывает конфиг при `/cp reload`, а `get` дёргает рендер-поток.
 * Пока снимок не публиковался целиком, читатель успевал увидеть `null` для
 * аддона, чей конфиг только что загрузился: `clear()` отрабатывал раньше, чем
 * первое заполнение. Тест не «стреляет» по гонке вслепую — он делает окно
 * между `clear()` и заполнением широким (тысячи аддонов) и проверяет, что
 * читатель не видит НИКОГДА частичного снимка.
 */
class AddonConfigRegistryTest {

    private fun cfgFor(addonId: String, value: String) = KorenConfig.fromString(
        """
        capeCraft {
            addons {
                $addonId {
                    apiKey = "$value"
                }
            }
        }
        """.trimIndent(),
    )

    private fun schema(addonId: String) = CapeAddonConfig(
        addonId = addonId,
        defaults = mapOf("apiKey" to Value.VStr("default")),
    )

    @Test
    fun `читатель никогда не видит частичный снимок при перезагрузке`() {
        val registry = CapeAddonConfigRegistry()
        // Много аддонов: цикл loadFrom становится достаточно длинным, чтобы
        // частичное состояние при старой реализации наблюдалось.
        val count = 2000
        repeat(count) { registry.register(schema("addon$it")) }

        registry.loadFrom(cfgFor("addon0", "v0"))
        assertNotNull(registry["addon0"])

        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val started = CountDownLatch(1)

        val reader = thread(name = "reader") {
            started.countDown()
            var samples = 0L
            while (!stop.get() && failure.get() == null) {
                // Снимок либо прежний, либо новый — но не пустой и не частичный.
                for (i in 0 until count) {
                    val cfg = registry["addon$i"]
                    if (cfg == null) {
                        failure.set("addon$i исчез из снимка на $samples-м замере")
                        return@thread
                    }
                }
                // Секция есть только у addon0, у остальных defaults — проверяем
                // именно стабильность значений при перечитывании конфига.
                if (registry["addon0"]!!.getString("apiKey") != "v0") {
                    failure.set("addon0 вернул «${registry["addon0"]!!.getString("apiKey")}» вместо v0")
                    return@thread
                }
                if (registry["addon7"]!!.getString("apiKey") != "default") {
                    failure.set("addon7 потерял defaults: «${registry["addon7"]!!.getString("apiKey")}»")
                    return@thread
                }
                samples++
            }
        }
        started.await(2, TimeUnit.SECONDS)

        val writer = thread(name = "writer") {
            repeat(300) { registry.loadFrom(cfgFor("addon0", "v0")) }
        }
        writer.join(60_000)
        stop.set(true)
        reader.join(60_000)

        assertNull(failure.get(), "гонка воспроизведена: ${failure.get()}")
    }

    @Test
    fun `после загрузки значения видны всем аддонам`() {
        val registry = CapeAddonConfigRegistry()
        registry.register(schema("a"))
        registry.register(schema("b"))
        registry.loadFrom(cfgFor("a", "value-a"))

        assertEquals("value-a", registry["a"]!!.getString("apiKey"))
        // У аддона без секции — defaults, но он тоже обязан существовать.
        assertEquals("default", registry["b"]!!.getString("apiKey"))
        assertNull(registry["не-registered"])
    }

    @Test
    fun `ids не падают при регистрации во время чтения`() {
        val registry = CapeAddonConfigRegistry()
        repeat(500) { registry.register(schema("addon$it")) }
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        var lastSeen = 0
        val reader = thread {
            while (!stop.get()) {
                try {
                    val ids = registry.ids()
                    // Регистрация только добавляет: размер не падает и не
                    // выходит за верхнюю границу, а сам вызов не бросает.
                    if (ids.size < lastSeen || ids.size > 700) {
                        failure.set("ids вернул ${ids.size} (до этого $lastSeen)")
                        return@thread
                    }
                    lastSeen = ids.size
                } catch (e: Exception) {
                    failure.set("ids бросил ${e.javaClass.simpleName}: ${e.message}")
                    return@thread
                }
            }
        }
        repeat(200) { registry.register(schema("late$it")) }
        stop.set(true)
        reader.join(10_000)
        assertNull(failure.get(), "гонка воспроизведена: ${failure.get()}")
        assertEquals(700, registry.ids().size, "после всех регистраций должны быть все ID")
    }

    @Test
    fun `повторная регистрация схемы не выбрасывает загруженные значения`() {
        val registry = CapeAddonConfigRegistry()
        registry.register(schema("a"))
        registry.loadFrom(cfgFor("a", "v1"))
        assertEquals("v1", registry["a"]!!.getString("apiKey"))

        // Регистрация схемы не трогает снимок значений: он пересобирается только
        // в loadFrom. Новые defaults схемы подхватятся на следующей перезагрузке.
        registry.register(schema("a"))
        assertEquals("v1", registry["a"]!!.getString("apiKey"))
    }
}
