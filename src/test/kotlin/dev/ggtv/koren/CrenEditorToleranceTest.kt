package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Как kjen/koren ведут себя на **недописанном** тексте.
 *
 * Это не тест «правильности», а опись поведения, на которую опирается
 * анализатор редактора. Пока он строится мимо парсера, по этой таблице видно,
 * что именно парсеру мешает и что придётся починить в патче.
 *
 * Каждый пункт здесь соответствует записи в `docs/kjen-koren-bugs.md`.
 * Если после правки поведение изменится — тест упадёт, и это хорошо: значит
 * баг починили и запись можно закрывать.
 */
class CrenEditorToleranceTest {
    private fun tokenize(text: String): Result<List<KorenToken>> =
        runCatching { KorenTokenizer.tokenizeWithEnv(text, emptyMap()) }

    private fun fails(text: String): String? =
        tokenize(text).exceptionOrNull()?.message

    @Test
    fun `незакрытая строка роняет токенизатор`() {
        val e = fails("a = \"ой")
        assertTrue(e != null, "ожидалась ошибка на незакрытой строке")
    }

    @Test
    fun `незаданная переменная окружения больше не роняет токенизатор`() {
        // Пункт 1 в `docs/kjen-koren-bugs.md`, починен. Пока баг жил, тест
        // брал имя `НЕТ_ТАКОЙ` и ждал ошибку — но ошибка приходила не от
        // незаданной переменной, а от кириллицы в имени, и тест проходил
        // вхолостую. Имя теперь ASCII, и проверяется ровно то, что надо:
        // `${НЕТ_ТАКОЙ}` не задан → пустая строка, а не исключение.
        val unset = mutableListOf<String>()
        val ts = runCatching {
            KorenTokenizer.tokenizeWithEnv("a = \"\${NOT_SET_ANYWHERE}\"", emptyMap(), unset::add)
        }
        assertTrue(ts.isSuccess, "незаданная переменная не должна ронять разбор")
        assertEquals(listOf("NOT_SET_ANYWHERE"), unset)

        // Неверное имя — по-прежнему ошибка, и называет само имя.
        val bad = fails("a = \"\${НЕТ_ТАКОЙ}\"")
        assertTrue(
            bad != null && bad.contains("НЕТ_ТАКОЙ"),
            "ожидалась ошибка про неверное имя, получено: $bad",
        )
    }

    @Test
    fun `комментарий без перевода строки в конце файла допустим`() {
        val ts = tokenize("a = 1\n# хвост")
        assertTrue(ts.isSuccess, "хвостовой комментарий без \\n должен быть ок: ${ts.exceptionOrNull()?.message}")
    }

    @Test
    fun `незакрытая скобка не роняет токенизатор`() {
        // Токенизатору всё равно, парсятся скобки или нет, — это лексер.
        assertTrue(tokenize("capeCraft {").isSuccess)
    }

    @Test
    fun `пустой файл допустим`() {
        assertTrue(tokenize("").isSuccess)
    }

    @Test
    fun `недописанный ключ допустим`() {
        assertTrue(tokenize("capeCraft { prov").isSuccess)
    }

    @Test
    fun `парсер роняет файл целиком из-за одной ошибки`() {
        // Ключевое отличие от редакторного анализатора: валидный кусок сверху
        // теряется вместе с битым хвостом, поэтому подсказки по первому блоку
        // при опечатке во втором показать нечем.
        val r = runCatching { KorenConfig.fromStringWithEnv("a = 1\nb = \n", emptyMap()) }
        assertTrue(r.isFailure, "битый хвост должен ронять разбор целиком")
        val e = r.exceptionOrNull()
        assertTrue(
            e is CrenError,
            "ожидалась CrenError, получено ${e?.javaClass?.simpleName}: ${e?.message}",
        )
    }

    @Test
    fun `синтаксическая ошибка несёт позицию, а семантическая нет`() {
        // Parse и TypeMismatch позицию несут, а NotFound/Ambiguous/Cycle — нет.
        // Для редактора это значит «показать ошибку негде»: подчёркивать нечего.
        val withSpan = listOf(
            CrenError.Parse::class,
            CrenError.TypeMismatch::class,
        )
        val without = listOf(
            CrenError.NotFound::class,
            CrenError.Ambiguous::class,
            CrenError.Cycle::class,
        )
        for (k in withSpan) {
            assertTrue(hasSpanField(k), "$k должен нести span")
        }
        for (k in without) {
            assertTrue(
                !hasSpanField(k),
                "$k неожиданно стал нести span — закрой запись в docs/kjen-koren-bugs.md",
            )
        }
    }

    @Test
    fun `у NotFound кроме пути ничего нет`() {
        val fields = CrenError.NotFound::class.java.declaredFields.map { it.name }
        assertEquals(listOf("path"), fields, "NotFound остался с одним полем и без позиции")
    }

    /** Есть ли в классе ошибки поле позиции — по имени, без создания экземпляра. */
    private fun hasSpanField(k: KClass<out CrenError>): Boolean =
        k.java.declaredFields.any { it.name == "span" }
}
