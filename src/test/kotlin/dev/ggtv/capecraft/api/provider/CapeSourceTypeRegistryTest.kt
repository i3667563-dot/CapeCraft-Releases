package dev.ggtv.capecraft.api.provider

import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.capecraft.api.CapeSourceType
import dev.ggtv.capecraft.api.provider.CapeSource
import dev.ggtv.capecraft.api.provider.CapeValues
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CapeSourceTypeRegistry.checkCompatibility] обязан что-то проверять, а не
 * только форматировать. Проверяем инвариант «мод поддерживает ту версию API,
 * которую объявляет» — именно он делает слово «совместимо» осмысленным.
 */
class CapeSourceTypeRegistryTest {

    @Test
    fun `проверка совместимости проходит на своей версии API`() {
        val r = CapeSourceTypeRegistry()
        r.register(CapeSourceType("mycloud") { CapeSource { byteArrayOf(1) } })

        val report = r.checkCompatibility()
        assertTrue(report.startsWith("совместимо"), "было: $report")
        assertTrue(report.contains("аддон-типов 1"), "было: $report")
        assertTrue(report.contains("API $CAPE_RUNTIME_API_VERSION"), "было: $report")
    }

    @Test
    fun `мод объявляет поддержку собственной версии API`() {
        // Если бы CAPE_RUNTIME_API_VERSION выехал за SUPPORTED_API_VERSIONS,
        // checkCompatibility начал бы кричать о несовместимости сам с собой.
        assertTrue(
            CAPE_RUNTIME_API_VERSION in CapeSourceTypeRegistry.SUPPORTED_API_VERSIONS,
            "версия $CAPE_RUNTIME_API_VERSION не входит в ${CapeSourceTypeRegistry.SUPPORTED_API_VERSIONS}",
        )
    }

    @Test
    fun `пустой реестр тоже считается совместимым`() {
        val report = CapeSourceTypeRegistry().checkCompatibility()
        assertTrue(report.startsWith("совместимо"), "было: $report")
        assertTrue(report.contains("аддон-типов 0"), "было: $report")
    }

    @Test
    fun `регистрация и поиск типа работают`() {
        val r = CapeSourceTypeRegistry()
        assertNull(r["не-известен"])
        r.register(CapeSourceType("mycloud") { CapeSource { byteArrayOf(1) } })
        val found = r["mycloud"]
        assertNotNull(found)
        assertArrayEquals(byteArrayOf(1), found!!.source(CapeValues("n", "mycloud", emptyMap())).fetch(CapeValues("n", "mycloud", emptyMap())))
        assertEquals(listOf("mycloud"), r.ids())

        // Повторная регистрация перезаписывает, а не дублирует.
        r.register(CapeSourceType("mycloud") { CapeSource { byteArrayOf(2) } })
        assertEquals(listOf("mycloud"), r.ids())
        assertArrayEquals(byteArrayOf(2), r["mycloud"]!!.source(CapeValues("n", "mycloud", emptyMap())).fetch(CapeValues("n", "mycloud", emptyMap())))
    }
}
