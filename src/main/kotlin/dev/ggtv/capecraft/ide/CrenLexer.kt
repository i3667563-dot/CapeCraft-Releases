package dev.ggtv.capecraft.ide

import dev.ggtv.kjen.EnvName
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.TextRange

/**
 * Вид лексемы в терпимом сканере.
 *
 * Намеренно **не** переиспользует [dev.ggtv.koren.KorenTokenKind]: этот
 * сканер живёт по другим правилам и существует по причине, описанной в
 * [CrenLexer].
 */
enum class CrenLexKind {
    /** Ключ, имя без кавычек, `true`, `false`, число. */
    WORD,

    /** Строка в кавычках целиком, вместе с кавычками. */
    STR,

    /** Один из `= { } [ ] : , . ( )`. */
    PUNCT,

    /** Комментарий от `#` до конца строки, без самого перевода. */
    COMMENT,

    /** Перевод строки: формат на нём заканчивает записи, разбирать надо. */
    NEWLINE,

    /** Что-то, чему не нашлось места: не-ASCII кавычка, `;`, мусор. */
    UNKNOWN,
}

/**
 * Кусок строки: либо обычный текст, либо подстановка.
 *
 * Нужно, чтобы подсветить `${HOST}` внутри строки отдельно от текста
 * вокруг. Значение переменной здесь **не** подставляется намеренно: редактор
 * показывает то, что человек написал, а не то, что получится на его машине.
 */
sealed interface StrPart {
    /** Диапазон в исходнике. */
    val range: TextRange

    /** Текст без подстановки. */
    data class Literal(override val range: TextRange) : StrPart

    /**
     * Подстановка: `$NAME` либо `${NAME}`.
     *
     * @property name имя переменной без доллара и фигурных скобок
     * @property braced была ли форма в фигурных скобках
     * @property hasDefault был ли дефолт после `:-`
     */
    data class Substitution(
        override val range: TextRange,
        val name: String,
        val braced: Boolean,
        val hasDefault: Boolean,
    ) : StrPart
}

/**
 * Лексема: вид, диапазон, сырой текст и разобранное значение.
 *
 * @property text ровно то, что лежит в исходнике; у [CrenLexKind.STR]
 *   вместе с кавычками
 * @property value у [CrenLexKind.STR] — содержимое между кавычками, без
 *   раскрытия escape-последовательностей; у [CrenLexKind.WORD] — само слово
 * @property unterminated строка не закрыта: в готовом конфиге это ошибка, в
 *   файле, который печатают прямо сейчас, — обычное дело
 * @property parts содержимое строки по кускам, только для [CrenLexKind.STR]
 * @property startOffset смещение начала в тексте; диапазон тогда считать не надо
 * @property endOffset смещение сразу за концом
 */
data class CrenLexeme(
    val kind: CrenLexKind,
    val range: TextRange,
    val text: String,
    val value: String = text,
    val unterminated: Boolean = false,
    val parts: List<StrPart> = emptyList(),
    val startOffset: Int = 0,
    val endOffset: Int = 0,
)

/**
 * Терпимый сканер `.kn` и `.crn`.
 *
 * ## Почему свой, а не koren-токенизатор
 *
 * Три причины, все записаны в `docs/kjen-koren-bugs.md`:
 *
 * 1. `KorenTokenizer.tokenize` **бросает исключение** на незаданной
 *    переменной окружения. Файл с `${CAPE_HOST}`, которой нет на машине,
 *    нельзя даже прочитать, а редактор обязан его открыть.
 * 2. Он бросает и на незакрытой строке. Для готового конфига это правильно,
 *    для печатаемого файла — невозможно.
 * 3. Подстановка окружения там **съедает длину**: границы лексемы считались бы
 *    по подставленному тексту, а редактору нужны границы по исходнику.
 *
 * ## Что тут есть
 *
 * [lex] не падает **никогда**: незакрытая строка, непарная скобка и мусор
 * становятся лексемой, а не исключением. Именно это делает возможными
 * подсказки посреди ввода.
 *
 * ## Про `$$`
 *
 * `$$` — экранированный доллар, а не начало подстановки. Сканер отдаёт его
 * литералом, и подсветка покажет обычным текстом.
 */
object CrenLexer {
    private const val PUNCT = "={}[]:,.()"

    /**
     * Разобрать текст в лексемы. Бросить не может.
     *
     * @param text весь файл
     * @return лексемы в порядке появления, пробелов в списке нет
     */
    fun lex(text: String): List<CrenLexeme> = Scanner(text).run()

    /**
     * Курсор по тексту.
     *
     * Позиции хранятся в координатах [Span], то есть 1-based, как их ждёт
     * мод. Переводы строк для перехода «индекс в тексте → позиция» считаются
     * один раз в [lineStarts], иначе подстановка внутри строки дала бы
     * квадратичное время на большом файле.
     */
    private class Scanner(private val text: String) {
        private val out = ArrayList<CrenLexeme>()
        private val len = text.length

        /** Индекс начала каждой строки; [lineStarts][0] — всегда 0. */
        private val lineStarts: IntArray = IntArray(text.count { it == '\n' } + 1).also { arr ->
            var n = 1
            arr[0] = 0
            for (k in text.indices) {
                if (text[k] == '\n') arr[n++] = k + 1
            }
        }

        private var i = 0

        /** Начало текущей лексемы. */
        private var start = 0

        fun run(): List<CrenLexeme> {
            while (i < len) {
                val c = text[i]
                start = i
                when {
                    c == ' ' || c == '\t' || c == '\r' -> i += 1
                    c == '\n' -> {
                        i += 1
                        add(CrenLexKind.NEWLINE)
                    }

                    c == '#' -> lexComment()
                    c == '"' -> lexString()
                    c in PUNCT -> {
                        i += 1
                        add(CrenLexKind.PUNCT)
                    }

                    c == '-' && i + 1 < len && text[i + 1] in '0'..'9' -> lexNumber()
                    c in '0'..'9' -> lexNumber()
                    // `$NAME` в позиции ключа — ссылка на переменную
                    // окружения, и разбирает её владелец ключа (например
                    // `if`), а не лексер. Поэтому это слово, а не мусор.
                    // Только когда за `$` идёт имя: одинокий `$` и `${...}`
                    // в значениях остаются как были — их подстановку
                    // показывают отдельно.
                    c == '$' && i + 1 < len && EnvName.isStart(text[i + 1]) -> lexWord()
                    isWordStart(c) -> lexWord()
                    else -> {
                        i += 1
                        add(CrenLexKind.UNKNOWN)
                    }
                }
            }
            return out
        }

        private fun add(
            kind: CrenLexKind,
            value: String? = null,
            unterminated: Boolean = false,
            parts: List<StrPart> = emptyList(),
        ) {
            val raw = text.substring(start, i)
            out += CrenLexeme(
                kind = kind,
                range = TextRange(posOf(start), posOf(i)),
                text = raw,
                value = value ?: raw,
                unterminated = unterminated,
                parts = parts,
                startOffset = start,
                endOffset = i,
            )
        }

        /** Позиция по индексу в тексте, 1-based как [Span]. */
        private fun posOf(index: Int): Span {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lineStarts[mid] <= index) lo = mid else hi = mid - 1
            }
            return Span(lo + 1, index - lineStarts[lo] + 1)
        }

        private fun lexComment() {
            i += 1 // решётка
            val from = i
            while (i < len && text[i] != '\n') i += 1
            add(CrenLexKind.COMMENT, value = text.substring(from, i).trim())
        }

        /**
         * Слово: буквы, цифры, дефис, точка, подчёркивание, слэш.
         *
         * Точка внутри слова не начинает путь ссылки: `server.token` — это
         * одно слово, а не `server`, точка, `token`.
         */
        private fun lexWord() {
            while (i < len && isWordPart(text[i])) i += 1
            add(CrenLexKind.WORD, value = text.substring(start, i))
        }

        /**
         * Число со знаком и дробной частью.
         *
         * Дробная часть есть только когда после точки идёт цифра, иначе это
         * путь ссылки вроде `server.token`, а не число.
         *
         * Подчёркивание внутри числа разрешено (`5_000_000`): игра такой конфиг
         * читает, а без этого разбора редактор ругался бы на правильную запись.
         * Подчёркивание допускается только между цифрами — ведущее `5_` и
         * хвостовое `_` остаются отдельным мусором, как и в Kotlin.
         */
        private fun lexNumber() {
            if (text[i] == '-') i += 1
            while (i < len && isNumberPart(text, i)) i += 1
            if (i + 1 < len && text[i] == '.' && text[i + 1] in '0'..'9') {
                i += 1
                while (i < len && isNumberPart(text, i)) i += 1
            }
            add(CrenLexKind.WORD, value = text.substring(start, i))
        }

        /** Цифра, либо подчёркивание, у которого с обеих сторон цифры. */
        private fun isNumberPart(s: String, at: Int): Boolean {
            val c = s[at]
            if (c in '0'..'9') return true
            return c == '_' && at > 0 && s[at - 1] in '0'..'9' && at + 1 < s.length && s[at + 1] in '0'..'9'
        }

        /**
         * Строка в кавычках.
         *
         * ## Почему не до конца файла
         *
         * Формат разрешает многострочные строки, и закрытая строка
         * разбирается целиком. Но если закрывающей кавычки **нет вообще**,
         * тянуть лексему до конца файла нельзя: `name = "ой` без кавычки
         * посреди файла съел бы весь остаток конфига, и подсказки по нему
         * пропали бы. Поэтому непарная строка заканчивается на своём переводе
         * строки, и разбор продолжается со следующей строки как ни в чём не
         * бывало.
         */
        private fun lexString() {
            val quote = i
            val close = findClosingQuote(quote)
            val bodyFrom = quote + 1
            val bodyTo: Int
            val closed: Boolean

            if (close >= 0) {
                closed = true
                bodyTo = close
            } else {
                closed = false
                // Перевод строки обрывает непарную строку; если файла дальше
                // нет, строка просто идёт до его конца.
                val nl = text.indexOf('\n', bodyFrom)
                bodyTo = if (nl < 0) len else nl
            }

            val parts = scanParts(bodyFrom, bodyTo)
            i = if (closed) close + 1 else bodyTo
            add(
                kind = CrenLexKind.STR,
                value = text.substring(bodyFrom, bodyTo),
                unterminated = !closed,
                parts = parts,
            )
        }

        /**
         * Найти закрывающую кавычку, вернуть её индекс или `-1`.
         *
         * Кавычку прячет только обратный слэш: `\"` — это доллар с кавычкой
         * внутри строки, а не конец строки.
         */
        private fun findClosingQuote(from: Int): Int {
            var k = from + 1
            while (k < len) {
                when (text[k]) {
                    '\\' -> k = if (k + 1 < len) k + 2 else k + 1
                    '"' -> return k
                    else -> k += 1
                }
            }
            return -1
        }

        /**
         * Разрезать содержимое строки `[from, to)` на литералы и подстановки.
         *
         * Значения переменных не подставляются: длина исходника и длина
         * результата не совпадают, и подсветка поехала бы.
         */
        private fun scanParts(from: Int, to: Int): List<StrPart> {
            val parts = ArrayList<StrPart>()
            var litFrom = from
            var k = from

            while (k < to) {
                if (text[k] != '$') {
                    k += 1
                    continue
                }
                val next = if (k + 1 < to) text[k + 1] else null
                if (next == '$') {
                    k += 2 // экранированный доллар
                    continue
                }
                if (litFrom < k) parts += StrPart.Literal(TextRange(posOf(litFrom), posOf(k)))

                val subFrom = k
                val braced: Boolean
                val raw: String
                when {
                    next == '{' -> {
                        braced = true
                        k += 2
                        val nameFrom = k
                        while (k < to && text[k] != '}' && text[k] != '\n') k += 1
                        raw = text.substring(nameFrom, k)
                        if (k < to && text[k] == '}') k += 1
                    }

                    next != null && EnvName.isStart(next) -> {
                        braced = false
                        k += 1
                        val nameFrom = k
                        while (k < to && EnvName.isContinue(text[k])) k += 1
                        raw = text.substring(nameFrom, k)
                    }

                    else -> {
                        // Одинокий `$` — обычный текст, продолжаем с него же.
                        k += 1
                        litFrom = subFrom
                        continue
                    }
                }
                val (name, default) = EnvName.splitDefault(raw)
                parts += StrPart.Substitution(
                    range = TextRange(posOf(subFrom), posOf(k)),
                    name = name.trim(),
                    braced = braced,
                    hasDefault = default != null,
                )
                litFrom = k
            }
            if (litFrom < to) parts += StrPart.Literal(TextRange(posOf(litFrom), posOf(to)))
            return parts
        }

        private fun isWordStart(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '/'

        private fun isWordPart(c: Char): Boolean =
            isWordStart(c) || c == '$' || c == '%'
    }
}
