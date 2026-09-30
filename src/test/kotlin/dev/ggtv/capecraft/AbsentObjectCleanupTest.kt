package dev.ggtv.capecraft

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Сторожевой тест: ушевший игрок не должен оставлять GPU-текстуру.
 *
 * Раньше `CapeRegistry.forget(uuid)` существовал, но **не вызывался нигде** —
 * игрок покидал сервер, а текстура плаща и её `NativeImage` оставались в
 * видеопамяти до конца процесса. На длинной сессии с большим онлайном это
 * копится до нехватки видеопамяти, и страдает уже не мод, а игра.
 *
 * Проверяется исходником, а не вызовом: `CapeTexture` работает через
 * `TextureManager` и GL, в юнит-тесте их нет. Поэтому ловится именно
 * *восстановление* утечки — возврат `forget` в мёртвое состояние.
 */
class AbsentObjectCleanupTest {

    @Test
    fun `ушедшие объекты освобождаются`() {
        for (version in versions) {
            val registry = registry(version)
            val client = File("versions/$version/src/main/kotlin/dev/ggtv/capecraft/CapeCraftClient.kt")
            assertTrue(
                "forgetAbsentObjects(" in client.readText(),
                "в $version никто не зовёт forgetAbsentObjects: GPU-текстуры ушедших " +
                    "игроков останутся в видеопамяти до конца процесса",
            )
            assertTrue(
                "forgetAbsentObjects" in registry.readText(),
                "в $version в реестре нет forgetAbsentObjects",
            )
        }
    }

    @Test
    fun `причины ухода игрока отслеживаются`() {
        for (version in versions) {
            val code = registry(version).readText()
            // Отметка «объект только что был в кадре»: без неё подчистка была бы
            // мгновенной и выкидывала бы текстуры у игроков за стеной.
            assertTrue(
                "lastSeenAt[uuid] = nowMs()" in code,
                "в $version ensureLoading не отмечает, что объект только что был в кадре",
            )
            assertTrue(
                "ABSENT_TIMEOUT_MS" in code,
                "в $version нет порога невидимости: мгновенная подчистка выбросила бы " +
                    "текстуры у живых игроков",
            )
        }
    }

    @Test
    fun `локального игрока подчистка не трогает`() {
        for (version in versions) {
            val body = registry(version).readText()
                .substringAfter("fun forgetAbsentObjects")
                .substringBefore("\n    }")
            assertTrue(
                "localPlayerId" in body,
                "в $version forgetAbsentObjects выбросит и локального игрока: его плащ " +
                    "нужен в каждом кадре, а между кадрами он не виден",
            )
        }
    }

    @Test
    fun `очистка не оставляет записи в картах`() {
        for (version in versions) {
            val code = registry(version).readText()
            val forget = code.substringAfter("fun forget(uuid: String)")
                .substringBefore("\n    }")
            // Каждая per-uuid карта обязана чиститься в том же месте, иначе
            // записи переживают disconnect и растут без ограничения.
            for (map in listOf("usernames", "appliedOrder", "lastSeenAt", "firstSeenAt")) {
                assertTrue(
                    "$map.remove(uuid)" in forget,
                    "в $version forget() не убирает $map: записи переживают выход с сервера",
                )
            }
        }
    }

    private val versions: List<String> =
        File("versions").list()
            ?.filter { registry(it).isFile }
            ?.sorted()
            ?: error("каталог versions не найден: тест запускают из корня проекта")

    private fun registry(version: String) =
        File("versions/$version/src/main/kotlin/dev/ggtv/capecraft/CapeRegistry.kt")
}
