package dev.ggtv.capecraft.ide

import dev.ggtv.koren.KorenConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Редактор и игра обязаны решать одно и то же.
 *
 * Формат `.kn` разбирают дважды: игра — вендоренным `koren`, редактор — своим
 * [CrenParser]. Своя копия нужна, чтобы разбор не падал на недописанном файле
 * (`koren` бросает исключение на каждой второй ошибке, см.
 * `docs/kjen-koren-bugs.md`), но вместе с ней приходит риск: редактор может
 * начать говорить то, чего игра не скажет.
 *
 * ## Что здесь проверяется
 *
 * Не «какой вывод правильный», а **согласие** — на каждом кусочке конфига:
 *
 * - если `koren` разобрал файл, наш разбор не должен жаловаться. Иначе человек
 *   правит рабочий конфиг и ловит красную ошибку, которой нет;
 * - если `koren` отказался, наш разбор обязан найти проблему. Иначе редактор
 *   молчит, а игра не стартует.
 *
 * Проверять agreement дешевле, чем искать расхождения глазами, и он ломается
 * сам: починим мы `koren` — тест упадёт и напомнит про обновление.
 */
class CrenKorenAgreementTest {

    /** Оба разбора обязаны закончить без жалоб. */
    private val valid = listOf(
        "пустой файл" to "",
        "только комментарий" to "# привет\n",
        "пустые блоки" to "capeCraft { }\n",
        "одна запись" to "capeCraft {\n    limits {\n        maxFrames = 100\n    }\n}\n",
        "многострочный блок" to
            "capeCraft {\n    limits {\n        maxFrames = 100\n        maxBytesTotal = 5_000_000\n    }\n}\n",
        "словарь с запятыми" to "capeCraft {\n    limits { extra = { a: 1, b: 2 } }\n}",
        "массив через запятую" to "capeCraft {\n    providers [ { name = \"a\" }, { name = \"b\" } ]\n}",
        "массив без разделителя после ключа" to
            "capeCraft {\n    providers [ { name = \"a\", type = \"url\" } ]\n}",
        "when со сравнением" to
            "capeCraft {\n    providers [ { name = \"a\", when = { location.y: \"<= -20\" } } ]\n}",
        "when с несколькими условиями" to
            "capeCraft {\n    providers [ { name = \"a\", when = { weather: \"rain\", time.period: \"day\" } } ]\n}",
        "явный тип" to "capeCraft {\n    limits { maxFrames int = 100 }\n}",
        "запятая в конце строки внутри словаря" to
            "capeCraft {\n    providers [ { name = \"a\", when = { weather: \"rain\", } } ]\n}",
        "ссылка на другой ключ" to "capeCraft {\n    limits { maxFrames = limits.maxBytesTotal }\n}",
        "подстановка окружения" to
            "capeCraft {\n    providers [ { name = \"a\", url = \"https://${'$'}{CAPE_HOST}/x.png\" } ]\n}",
    )

    /** `koren` обязан отказаться, и мы обязаны это увидеть. */
    private val broken = listOf(
        "when без разделителя" to
            "capeCraft {\n    providers [ { name = \"a\", when { location.y: \"<= -20\" } } ]\n}",
        "не закрыт массив" to "capeCraft {\n    providers [\n",
        "не закрыт блок" to "capeCraft {\n    limits {\n",
        "нет значения" to "capeCraft {\n    limits { maxFrames = }\n}",
        "незакрытая строка" to "capeCraft {\n    limits { name = \"abc\n}",
        "лишняя запятая в блоке" to "capeCraft {\n    limits { maxFrames = 1, }\n}",
        "две запятые в словаре" to "capeCraft {\n    limits { extra = { a: 1,, b: 2 } }\n}",
        "запятая в начале блока" to "capeCraft {\n    limits { , maxFrames = 1 }\n}",
        "две записи в строке без запятой" to
            "capeCraft {\n    limits { maxFrames = 1 maxBytesTotal = 2 }\n}",
        "две записи в строке с запятой" to
            "capeCraft {\n    limits { maxFrames = 1, maxBytesTotal = 2 }\n}",
        "число вместо ключа" to "capeCraft {\n    limits { 5 = 2 }\n}",
        "блок без фигурных скобок" to "capeCraft {\n    limits\n    maxFrames = 1\n}",
    )

    @Test
    fun `на файлах, которые игра читает, редактор не жалуется на разбор`() {
        for ((name, text) in valid) {
            // Если koren такой файл не берёт, тест врёт: он проверял бы не
            // согласие, а нашу фантазию о том, что игра допускает.
            korenAccepts(name, text)
            val problems = CrenParser.parse(CrenDocument(text)).problems
            assertTrue(
                problems.isEmpty(),
                "игра читает «$name», а редактор ругается: ${problems.map { it.message }}",
            )
        }
    }

    @Test
    fun `на файлах, которые игра не читает, редактор что-то находит`() {
        for ((name, text) in broken) {
            assertTrue(
                !korenAccepts(name, text),
                "«$name» игра принимает — перенеси его из broken в valid",
            )
            // Проверяется весь редактор, а не один разбор: `when { ... }` без
            // `=` на синтаксис неотличим от законного `limits { ... }` (оба —
            // «ключ, потом фигурная скобка»), и поймать его может только слой со
            // схемой. Редактор состоит из обоих слоёв, поэтому и проверять
            // надо его целиком.
            val diagnostics = CrenAnalyzer.diagnostics(CrenDocument(text))
            assertTrue(
                diagnostics.isNotEmpty(),
                "игра не читает «$name», а редактор молчит: файл не пройдёт проверку схемы",
            )
        }
    }

    @Test
    fun `наши примеры из README игра принимает целиком`() {
        val readme = java.io.File("README.md").readText()
        val configs = Regex("```[a-z]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(readme)
            .map { it.groupValues[1] }
            .filter {
                it.trimStart().startsWith("capeCraft {") ||
                    it.trimStart().startsWith("limits {") ||
                    it.trimStart().startsWith("serverSync {")
            }
            .toList()
        assertTrue(configs.isNotEmpty(), "в README не нашлось ни одного конфиг-примера")
        for (config in configs) {
            val env = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)")
                .findAll(config)
                .map { it.groupValues[1] }
                .associateWith { "capes.example.com" }
            // KorenConfig, а не Parser: он подставляет окружение, иначе
            // пример с ${CAPE_HOST} не разберётся на машине без переменной.
            KorenConfig.fromStringWithEnv(config, env)
            val problems = CrenParser.parse(CrenDocument(config)).problems
            assertTrue(
                problems.isEmpty(),
                "пример из README редактор считает битым: ${problems.map { it.message }}\n$config",
            )
        }
    }

    private fun korenAccepts(name: String, text: String): Boolean = try {
        KorenConfig.fromStringWithEnv(text, mapOf("CAPE_HOST" to "caps.example.com"))
        true
    } catch (e: Exception) {
        if (name.isEmpty()) throw e
        false
    }
}
