package dev.ggtv.capecraft

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Сторожевой тест на инвариант текстурного реестра.
 *
 * `TextureManager.getTexture()` — не «спросить, есть ли текстура», а операция
 * с побочным эффектом: на отсутствующем id он создаёт `SimpleTexture`,
 * **регистрирует** его и пытается загрузить ресурс. Поэтому «есть ли у нас
 * плащ?» нельзя спрашивать у менеджера — только у собственного реестра.
 *
 * Чем это кончалось в логе: `Missing resource capecraft:cape/... referenced
 * from itself`, а следом `Failed to close texture`, когда `release` закрывает
 * уже не нашу, сломанную текстуру. И то, и другое — из одной строки, поэтому
 * проверять придётся исходник: в юнит-тесте `TextureManager` недоступен.
 *
 * Тест ловит именно возврат этого вызова в любую из поддерживаемых версий.
 */
class CapeTextureOwnershipTest {

    @Test
    fun `текстура не читается у TextureManager через getTexture`() {
        for (version in versions) {
            val offenders = capeTexture(version).codeLines().filter { ".getTexture(" in it }
            assertTrue(
                offenders.isEmpty(),
                "в $version текстура читается через TextureManager.getTexture(), а он " +
                    "регистрирует SimpleTexture вместо ответа — это и есть " +
                    "`Missing resource`: $offenders",
            )
        }
    }

    @Test
    fun `у каждой версии есть собственный реестр текстур`() {
        for (version in versions) {
            val code = capeTexture(version).codeLines().joinToString("\n")
            assertTrue(
                "owned" in code,
                "в $version нет своего реестра текстур (owned) — проверка наличия " +
                    "текстуры снова уедет в TextureManager",
            )
        }
    }

    @Test
    fun `освобождение текстуры уходит на рендер-поток`() {
        for (version in versions) {
            val code = capeTexture(version).codeLines().joinToString("\n")
            // Имена различаются: в Mojang-версиях isSameThread, в Yarn isOnThread.
            val threadCheck = "isSameThread" in code || "isOnThread" in code
            assertTrue(
                threadCheck && "execute(" in code,
                "в $version release() не маршалит освобождение на рендер-поток: " +
                    "close() освобождает GL-ресурсы, а зовут его и с сетевого потока",
            )
        }
    }

    /** Версии, у которых есть свой код текстур. */
    private val versions: List<String> =
        File("versions").list()
            ?.filter { capeTexture(it).isFile }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: error("каталог versions не найден: тест запускают из корня проекта")

    private fun capeTexture(version: String) =
        File("versions/$version/src/main/kotlin/dev/ggtv/capecraft/CapeTexture.kt")

    /** Код без комментариев: иначе проверка ловила бы и текст в KDoc. */
    private fun File.codeLines(): List<String> =
        readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
}
