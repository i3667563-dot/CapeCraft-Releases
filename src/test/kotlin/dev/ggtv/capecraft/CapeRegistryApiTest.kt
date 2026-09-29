package dev.ggtv.capecraft

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Совместимость аддонов на уровне байткода.
 *
 * Мод-сосед (например `capecraft-bedwars`) собран заранее и на месте не
 * пересобирается. Если у CapeCraft исчезнет метод, который он зовёт, это
 * видно только в рантайме: `NoSuchMethodError` в игровом тике, то есть игра
 * падает в первом же матче, а сборка и все тесты зелёные. Раньше так и
 * вышло — параметр контекста добавили как значение по умолчанию, и
 * дескриптор `(String, String)` в байткоде исчез.
 *
 * Поэтому проверяем отражение, а не вызов: компилятор такие вещи не видит.
 */
class CapeRegistryApiTest {

    @Test
    fun `ensureLoading остаётся вызываемым из аддона двумя аргументами`() {
        val m = assertNotNull(
            CapeRegistry::class.java.getMethod(
                "ensureLoading",
                String::class.java,
                String::class.java,
            ),
        ) { "CapeRegistry.ensureLoading(String, String) исчез: аддоны, собранные против прошлой версии, упадут с NoSuchMethodError" }

        assertNotNull(m, "метод должен быть публичным и не synthetic")
    }

    @Test
    fun `ensureLoading с контекстом никуда не делся`() {
        // Значение по умолчанию у Kotlin — это синтетический мост, а не
        // перегрузка. Проверяем, что настоящий метод на месте, иначе
        // исчезнет всё, что зовёт его с явным контекстом.
        assertNotNull(
            CapeRegistry::class.java.getMethod(
                "ensureLoading",
                String::class.java,
                String::class.java,
                dev.ggtv.koren.WorldContext::class.java,
            ),
        )
    }

    @Test
    fun `обе перегрузки публичные и не синтетические`() {
        val methods = CapeRegistry::class.java.declaredMethods.filter { it.name == "ensureLoading" }
        val two = methods.single { it.parameterCount == 2 }
        val three = methods.single { it.parameterCount == 3 }

        for (m in listOf(two, three)) {
            assert(Modifier.isPublic(m.modifiers)) { "${m.name}/${m.parameterCount} должен быть публичным" }
            assert(!m.isSynthetic) { "${m.name}/${m.parameterCount} не должен быть synthetic-мостом: JVM-дескриптора у моста нет" }
        }
    }
}
