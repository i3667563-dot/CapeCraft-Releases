package dev.ggtv.capecraft.ide

import dev.ggtv.kjen.Span
import dev.ggtv.kjen.TextRange

/**
 * Как записан ключ: чем он отделён от значения.
 *
 * Различать надо, потому что в `.kn` оба варианта равнозначны, а опечатка в
 * разделителе — частая: человек потерял `=` и получил `server.token 1`.
 */
enum class EntryForm {
    /** `key = value` — короткая запись. */
    ASSIGN,

    /** `key: value` — запись в словаре. */
    COLON,

    /** `key { ... }` — именованный блок. */
    BLOCK,

    /** Ключ без значения: `key` и перевод строки. */
    BARE,
}

/** Часть пути ссылки: имя или индекс в квадратных скобках. */
sealed interface PathPart {
    /** Диапазон части в исходнике. */
    val range: TextRange

    /** Имя вида `server` или `token`. */
    data class Name(override val range: TextRange, val name: String) : PathPart

    /** Индекс вида `[1]`, без скобок. */
    data class Index(override val range: TextRange, val index: Int) : PathPart
}

/** Узел разобранного конфига. У каждого есть диапазон в исходнике. */
sealed interface CrenNode {
    val range: TextRange

    /** Короткое представление для сообщений и подсказок. */
    val preview: String
}

/** Значение записи. */
sealed interface CrenValue : CrenNode

/** Строка, число, `true` или имя без кавычек. */
data class CrenLeaf(override val range: TextRange, val lexeme: CrenLexeme) : CrenValue {
    override val preview: String get() = lexeme.text
}

/** Словарь в фигурных скобках. */
data class CrenDict(override val range: TextRange, val entries: List<CrenEntry>) : CrenValue {
    override val preview: String get() = "{}"

    /** Запись по имени; при повторе берётся первая, как и при разборе. */
    fun get(name: String): CrenEntry? = entries.firstOrNull { it.keyText == name }
}

/** Массив в квадратных скобках. */
data class CrenArray(override val range: TextRange, val items: List<CrenValue>) : CrenValue {
    override val preview: String get() = "[${items.size}]"
}

/** Ссылка на другой ключ: `server.token[1]`. */
data class CrenRef(override val range: TextRange, val parts: List<PathPart>) : CrenValue {
    override val preview: String get() = parts.joinToString("")

    /** Имена без индексов — для сравнения с [dev.ggtv.capecraft.schema.ConfigSchema]. */
    val names: List<String> get() = parts.filterIsInstance<PathPart.Name>().map { it.name }
}

/**
 * Имя ключа вместе с его диапазоном.
 *
 * Ключ бывает в кавычках, а бывает без, и для подсказок это важно: человек
 * набирает `"про` — и ждёт список полей.
 *
 * @property quoted был ли ключ написан в кавычках
 * @property typeText явный тип после `key type = value`, если он был
 */
data class CrenKey(
    val text: String,
    override val range: TextRange,
    val quoted: Boolean = false,
    val typeText: String? = null,
    val typeRange: TextRange? = null,
) : CrenNode {
    override val preview: String get() = if (quoted) "\"$text\"" else text
}

/**
 * Одна запись: ключ и, если есть, значение.
 *
 * @property children записи вложенного словаря, когда [form] — [EntryForm.BLOCK]
 * @property value значение; `null`, если человек написал только ключ
 */
data class CrenEntry(
    val key: CrenKey,
    val value: CrenValue?,
    val form: EntryForm,
    override val range: TextRange,
    val children: List<CrenEntry> = emptyList(),
) : CrenNode {
    /** Имя ключа без кавычек. */
    val keyText: String get() = key.text

    /** Диапазон ключа — по нему подчёркиваются неизвестные и устаревшие ключи. */
    val keyRange: TextRange get() = key.range

    /**
     * Записи, которые видны на этом уровне.
     *
     * Именованный блок `server { ... }` хранит содержимое в [children], а
     * обычный словарь — в [value]. Подсказкам и проверкам нужно единое
     * представление, иначе пришлось бы в каждом месте спрашивать, а где мы.
     */
    val body: List<CrenEntry>
        get() = when {
            form == EntryForm.BLOCK -> children
            value is CrenDict -> value.entries
            else -> emptyList()
        }

    /** Словарь на этом уровне, если он есть. */
    val dict: CrenDict?
        get() = when {
            form == EntryForm.BLOCK -> null
            value is CrenDict -> value
            else -> null
        }

    override val preview: String get() = keyText
}

/** Синтаксическая проблема, найденная разбором. */
data class CrenProblem(val message: String, val range: TextRange)

/**
 * Разобранный файл.
 *
 * @property root записи верхнего уровня; синтетический словарь без скобок
 * @property problems синтаксические ошибки; при разборе они **не** мешают
 */
data class CrenTree(val root: List<CrenEntry>, val problems: List<CrenProblem>)

/**
 * Терпимый разбор поверх [CrenLexer].
 *
 * ## Чем отличается от `KorenConfig.fromString`
 *
 * Тем, что не бросает. Ошибка попадает в [CrenTree.problems], разбор
 * продолжается, и подсказки по остальному файлу остаются. Причина в
 * `docs/kjen-koren-bugs.md`, пункт 5: у парсера мода одна ошибка убивает весь
 * файл, а в редакторе это неприемлемо.
 *
 * ## Восстановление
 *
 * На неожиданной лексеме разбор не падает, а поднимается до ближайшего
 * перевода строки: у ключей граница всегда конец строки, и это надёжнее
 * попытки угадать, где человек имел в виду продолжение.
 */
object CrenParser {
    /** Разобрать файл; бросить не может. */
    fun parse(doc: CrenDocument): CrenTree = Parser(doc).run()

    /**
     * Разбор с явным состоянием; [problems] копится по ходу.
     *
     * Документ нужен ради одного: превратить смещение внутри лексемы в
     * позицию. Сканер считает границы по позициям, а части пути ссылки
     * считать удобнее по смещениям.
     */
    private class Parser(private val doc: CrenDocument) {
        private val lexemes: List<CrenLexeme> = doc.lexemes()
        private var k = 0
        private val problems = ArrayList<CrenProblem>()

        fun run(): CrenTree {
            val entries = parseEntries(null, commasAllowed = false)
            return CrenTree(entries, problems)
        }

        private fun peek(): CrenLexeme? = lexemes.getOrNull(k)

        private fun next(): CrenLexeme? = lexemes.getOrNull(k)?.also { k += 1 }

        private fun atNewLine(): Boolean =
            peek() == null || peek()?.kind == CrenLexKind.NEWLINE

        private fun skipNewLines() {
            while (peek()?.kind == CrenLexKind.NEWLINE) k += 1
        }

        /**
         * Разбирать записи, пока не встретим [closer] или конец файла.
         *
         * @param closer закрывающая пунктуация, например `}`; `null` для верха
         * @param commasAllowed разрешена ли запятая между записями. В словаре
         *   (`key = { a: 1, b: 2 }`) и в массиве — да, в блоке (`limits { ... }`)
         *   — нет: там записи разделяет новая строка, и игра на запятой в конце
         *   строки отказывается грузить конфиг целиком. Разница проверяется
         *   `CrenKorenAgreementTest` против настоящего парсера koren.
         */
        private fun parseEntries(closer: String?, commasAllowed: Boolean): List<CrenEntry> {
            val out = ArrayList<CrenEntry>()
            while (true) {
                skipNewLines()
                val t = peek() ?: break
                if (closer != null && t.kind == CrenLexKind.PUNCT && t.text == closer) break

                if (t.kind == CrenLexKind.PUNCT) {
                    // Закрывающая скобка не та, которую ждали: это ошибка
                    // человека, а не наш разбор, и молча её съесть нельзя.
                    if (t.text == "}" || t.text == "]") {
                        problems += CrenProblem("лишняя «${t.text}»", t.range)
                        k += 1
                        continue
                    }
                    if (t.text == ",") {
                        if (!commasAllowed) {
                            problems += CrenProblem(
                                "запятая лишняя: в блоке записи пишутся с новой строки",
                                t.range,
                            )
                        } else if (lexemes.getOrNull(k + 1)?.let { it.kind == CrenLexKind.PUNCT && it.text == "," } == true) {
                            problems += CrenProblem("две запятые подряд", lexemes[k + 1].range)
                        }
                        k += 1
                        continue
                    }
                }
                if (t.kind == CrenLexKind.COMMENT) {
                    k += 1
                    continue
                }

                val before = k
                val entry = parseEntry()
                if (entry != null) out += entry
                if (k == before) k += 1 // страховка от бесконечного цикла
                complainAboutMissingSeparator()
            }
            return out
        }

        /**
         * Две записи в одной строке без разделителя: `maxFrames = 1 maxBytes = 2`.
         *
         * Игра на такой строке отказывается грузить конфиг, а раньше мы молчали
         * и показывали подсказки, будто всё в порядке.
         */
        private fun complainAboutMissingSeparator() {
            val next = peek() ?: return
            if (next.kind != CrenLexKind.WORD && next.kind != CrenLexKind.STR) return
            // Явный тип `key str = 1` — не начало новой записи, а продолжение
            // текущей, но сюда мы попадаем уже после разбора значения, так что
            // достаточно проверить, что после ключа действительно «=».
            problems += CrenProblem(
                "между записями нужна новая строка${if (peekIsColonOrComma()) " или запятая" else ""}",
                next.range,
            )
        }

        private fun peekIsColonOrComma(): Boolean =
            peek()?.let { it.kind == CrenLexKind.PUNCT && (it.text == "," || it.text == ":") } == true

        private fun parseEntry(): CrenEntry? {
            val key = parseKey() ?: return null
            var form = EntryForm.BARE
            var value: CrenValue? = null
            val children = ArrayList<CrenEntry>()
            var end = key.range.end

            when {
                peekIs("=") -> {
                    k += 1
                    form = EntryForm.ASSIGN
                    value = parseValue()
                    value?.let { end = it.range.end }
                }

                peekIs(":") -> {
                    k += 1
                    form = EntryForm.COLON
                    value = parseValue()
                    value?.let { end = it.range.end }
                }

                peekIs("{") -> {
                    val from = lexemes.getOrNull(k)?.range?.start ?: key.range.end
                    k += 1
                    form = EntryForm.BLOCK
                    children += parseEntries("}", commasAllowed = false)
                    val closed = expectCloser("}", from)
                    if (closed != null) end = closed
                }

                // `providers [ ... ]` — массив без разделителя. Форма из
                // README и из парсера мода: там после ключа ждут «=», «{» или
                // «[», и третий вариант без знака — самый частый.
                peekIs("[") -> {
                    val array = parseArray()
                    form = EntryForm.ASSIGN
                    value = array
                    end = array.range.end
                }

                atNewLine() -> problems += CrenProblem(
                    "после «${key.text}» нужно значение, «=» или «:»",
                    key.range,
                )
            }
            return CrenEntry(
                key = key,
                value = value,
                form = form,
                range = key.range.merge(TextRange(key.range.start, end)),
                children = children,
            )
        }

        private fun peekIs(text: String): Boolean =
            peek()?.let { it.kind == CrenLexKind.PUNCT && it.text == text } == true

        /**
         * Ключ записи: слово или строка в кавычках.
         *
         * Перед разделителем может стоять явный тип `key str = 1`; он забирается
         * здесь же, иначе он вылез бы отдельной записью без ключа.
         */
        private fun parseKey(): CrenKey? {
            val t = peek() ?: return null
            if (t.kind == CrenLexKind.STR) {
                k += 1
                return CrenKey(t.value, t.range, quoted = true)
            }
            if (t.kind != CrenLexKind.WORD) return null

            k += 1
            val next = peek()
            val isType = next?.let {
                it.kind == CrenLexKind.WORD && lexemes.getOrNull(k + 1)?.text == "="
            } == true
            if (!isType) return CrenKey(t.value, t.range)

            k += 1
            val typeLex = lexemes[k - 1]
            return CrenKey(t.value, t.range, typeText = typeLex.value, typeRange = typeLex.range)
        }

        private fun parseValue(): CrenValue? {
            val t = peek() ?: return null
            return when {
                t.kind == CrenLexKind.STR -> {
                    k += 1
                    CrenLeaf(t.range, t)
                }

                t.kind == CrenLexKind.WORD -> parseWordValue(t)
                peekIs("{") -> parseDict()
                peekIs("[") -> parseArray()
                else -> {
                    problems += CrenProblem("ожидалось значение, а не «${t.text}»", t.range)
                    null
                }
            }
        }

        /**
         * Слово как значение: ссылка либо обычное слово.
         *
         * Ссылка — это слово с точкой или квадратными скобками: `server.token[1]`.
         * Простое слово без скобок — это `true` или имя, и ссылкой быть не может.
         */
        private fun parseWordValue(first: CrenLexeme): CrenValue {
            val looksRef = first.value.contains('.') || peekIs("[")
            if (!looksRef) {
                k += 1
                return CrenLeaf(first.range, first)
            }
            k += 1
            val parts = ArrayList<PathPart>()

            // Имя целиком лежит в лексеме, поэтому каждая часть пути
            // отмеряется от её начала: у `a.b.c` третья часть начинается
            // после двух точек, а не в начале слова.
            var segStart = first.startOffset
            for (seg in first.value.split('.')) {
                val segEnd = segStart + seg.length
                if (seg.isEmpty()) {
                    problems += CrenProblem("пустая часть пути в ссылке", first.range)
                } else {
                    parts += PathPart.Name(
                        TextRange(doc.spanOf(segStart), doc.spanOf(segEnd)),
                        seg,
                    )
                }
                segStart = segEnd + 1 // перескакиваем разделитель
            }

            while (peekIs("[")) {
                val open = lexemes[k]
                k += 1
                val idx = peek()?.takeIf { it.kind == CrenLexKind.WORD && it.value.toIntOrNull() != null }
                if (idx == null) {
                    problems += CrenProblem("в квадратных скобках ожидался индекс", open.range)
                    // Закрывающую скобку всё равно съедаем, иначе она потом
                    // превратится в «лишнюю }» в диагностике.
                    if (peekIs("]")) k += 1
                } else {
                    k += 1
                    val close = if (peekIs("]")) {
                        val end = lexemes[k].range.end
                        k += 1
                        end
                    } else {
                        problems += CrenProblem("не хватает «]»", open.range)
                        idx.range.end
                    }
                    // Диапазон накрывает и скобки: подчёркивать `[1]` целиком
                    // полезнее, чем одно `1`.
                    parts += PathPart.Index(
                        TextRange(open.range.start, close),
                        idx.value.toInt(),
                    )
                }
            }
            val last = lexemes.getOrNull(k - 1)
            return CrenRef(
                TextRange(first.range.start, last?.range?.end ?: first.range.end),
                parts,
            )
        }

        private fun parseDict(): CrenValue {
            val open = lexemes[k]
            k += 1
            val entries = parseEntries("}", commasAllowed = true)
            val closed = expectCloser("}", open.range.start)
            val end = closed ?: lexemes.getOrNull(k - 1)?.range?.end ?: open.range.end
            return CrenDict(TextRange(open.range.start, end), entries)
        }

        private fun parseArray(): CrenValue {
            val open = lexemes[k]
            k += 1
            val items = ArrayList<CrenValue>()
            while (true) {
                skipNewLines()
                val t = peek() ?: break
                if (t.kind == CrenLexKind.PUNCT && t.text == "]") break
                if (t.kind == CrenLexKind.COMMENT) {
                    k += 1
                    continue
                }
                if (t.kind == CrenLexKind.PUNCT && t.text == ",") {
                    k += 1
                    continue
                }
                if (t.kind == CrenLexKind.PUNCT && t.text == "}") {
                    problems += CrenProblem("в массиве ожидалась «]»", t.range)
                    break
                }
                val before = k
                parseValue()?.let { items += it }
                if (k == before) k += 1
            }
            val closed = expectCloser("]", open.range.start)
            val end = closed ?: lexemes.getOrNull(k - 1)?.range?.end ?: open.range.end
            return CrenArray(TextRange(open.range.start, end), items)
        }

        /**
         * Съесть закрывающую пунктуацию, если она есть.
         *
         * Если её нет — это ошибка человека (забыл скобку), и мы её
         * показываем, а не молча закрываем файл. Возвращается позиция сразу
         * за закрывающей лексемой, чтобы диапазон родителя её накрыл.
         *
         * @return конец закрывающей лексемы или `null`, если её не было
         */
        private fun expectCloser(text: String, openedAt: Span): Span? {
            val t = peek()
            if (t != null && t.kind == CrenLexKind.PUNCT && t.text == text) {
                k += 1
                return t.range.end
            }
            val from = openedAt
            val to = lexemes.getOrNull(k - 1)?.range?.end ?: openedAt
            problems += CrenProblem("не хватает «$text»", TextRange(from, to))
            return null
        }
    }
}
