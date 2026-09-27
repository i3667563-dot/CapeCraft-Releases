package dev.ggtv.capecraft.ide

import dev.ggtv.kjen.Span

/**
 * Текст файла вместе с индексом строк.
 *
 * Считается один раз на версию документа и переиспользуется анализатором
 * для всех трёх возможностей: подсветки, диагностик и подсказок. В
 * редакторе это критично — на каждый символ приходит запрос, и если индекс
 * строк строится заново на каждый запрос, файл в 10 тысяч строк будет
 * подвисать на каждом нажатии.
 *
 * ## Смещения
 *
 * Везде внутри IDE используется смещение в строке (индекс символа), потому
 * что так работают и курсор редактора, и протокол LSP. [Span] из `kjen`
 * остаётся 1-based, как в логах мода, и на границе эти два мира
 * пересчитываются в [spanOf] и [offsetOf].
 */
class CrenDocument(val text: String) {
    /** Начало каждой строки, [lineStarts][0] — всегда 0. */
    private val lineStarts: IntArray = IntArray(text.count { it == '\n' } + 1).also { arr ->
        var n = 1
        arr[0] = 0
        for (k in text.indices) {
            if (text[k] == '\n') arr[n++] = k + 1
        }
    }

    /** Число строк; последняя может быть пустой, если файл кончился переводом. */
    val lineCount: Int get() = lineStarts.size

    private val lexemes: List<CrenLexeme> by lazy { CrenLexer.lex(text) }

    /** Лексемы файла; разбираются один раз. */
    fun lexemes(): List<CrenLexeme> = lexemes

    /**
     * Начало строки по её номеру, 0-based.
     *
     * @param line 0-based номер строки; за пределами файла обрезается
     */
    fun lineStart(line: Int): Int {
        if (line <= 0) return 0
        if (line >= lineStarts.size) return text.length
        return lineStarts[line]
    }

    /** Конец строки по её номеру, 0-based, без перевода строки. */
    fun lineEnd(line: Int): Int {
        val start = lineStart(line)
        val nextStart = if (line + 1 < lineStarts.size) lineStarts[line + 1] else text.length
        var end = nextStart
        if (end > start && end <= text.length && end - 1 < text.length && text[end - 1] == '\n') end -= 1
        if (end > start && end - 1 < text.length && text[end - 1] == '\r') end -= 1
        return end
    }

    /** Текст строки без перевода строки. */
    fun lineText(line: Int): String = text.substring(lineStart(line), lineEnd(line))

    /** Смещение по номеру строки (0-based) и колонке (0-based). */
    fun offsetOf(line: Int, character: Int): Int {
        val start = lineStart(line)
        val end = lineEnd(line)
        return (start + character).coerceIn(start, end)
    }

    /**
     * Смещение по позиции, присланной клиентом (LSP).
     *
     * Отдельный метод, а не [offsetOf], потому что считает по-другому.
     * Внутренние смещения — свои, из своих же Span, и там «колонка» это
     * всегда кодпоинт. Клиент же считает колонку в той кодировке, о которой
     * договорились в `initialize` (см. [PositionEncoding]), и по умолчанию это
     * UTF-16 code unit, как велит спецификация: символ за пределами BMP (эмодзи,
     * редкие иероглифы) там это две единицы, а здесь один. Сложив их
     * напрямую, мы сдвинули бы курсор на символ назад — подсказка пришла бы не
     * на тот ключ, и выглядело бы как «анализатор путает ключи».
     *
     * Колонка за концом строки зажимается на [lineEnd], а не считается ошибкой:
     * так требует спецификация, и клиенты на это натыкаются штатно.
     */
    fun offsetOfClientPosition(
        line: Int,
        character: Int,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): Int? {
        if (line < 0 || character < 0 || line >= lineCount) return null
        val start = lineStart(line)
        val end = lineEnd(line)
        if (character == 0) return start
        val slice = text.substring(start, end)
        var units = 0
        var i = 0
        while (i < slice.length && units < character) {
            val cp = slice.codePointAt(i)
            val size = Character.charCount(cp)
            units += unitsOf(cp, encoding)
            i += size
        }
        return (start + i).coerceIn(start, end)
    }

    /**
     * Колонка смещения в кодировке клиента — обратная сторона
     * [offsetOfClientPosition].
     *
     * Считается от начала строки по [lineStart], а не из [spanOf]: `col` в
     * [Span] это кодпоинты, а клиенту нужны именно его единицы.
     */
    fun clientColumnOf(
        offset: Int,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): Int {
        val k = offset.coerceIn(0, text.length)
        val start = lineStart(spanOf(k).line - 1)
        val prefix = text.substring(start, k)
        return when (encoding) {
            // Kotlin String — это UTF-16, поэтому её длина уже в code unit.
            PositionEncoding.UTF16 -> prefix.length
            PositionEncoding.UTF32 -> prefix.codePointCount(0, prefix.length)
            PositionEncoding.UTF8 -> utf8Length(prefix)
        }
    }

    /**
     * Заменить кусок текста по позициям клиента — для `didChange` с `range`.
     *
     * Возвращает null, если позиции не сошлись с текстом: вызывающий обязан
     * оставить документ как есть, а не записать в него обрывок. Подставлять
     * кусок вместо всего документа молча — худший баг в LSP-сервере, потому
     * что после него ломаются все позиции сразу и без диагностики.
     */
    fun replaceClientRange(
        line: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
        replacement: String,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): String? {
        val from = offsetOfClientPosition(line, startCharacter, encoding) ?: return null
        val to = offsetOfClientPosition(endLine, endCharacter, encoding) ?: return null
        if (to < from) return null
        return text.substring(0, from) + replacement + text.substring(to)
    }

    /** Смещение в 1-based [Span] — так уже считает сканер. */
    fun offsetOf(span: Span): Int = offsetOf(span.line - 1, span.col - 1)

    /** Позиция смещения как [Span], 1-based. */
    fun spanOf(offset: Int): Span {
        val k = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= k) lo = mid else hi = mid - 1
        }
        return Span(lo + 1, k - lineStarts[lo] + 1)
    }

    /** Смещение, которым заканчивается диапазон. */
    fun endOffsetOf(range: dev.ggtv.kjen.TextRange): Int = offsetOf(range.end)

    /**
     * Кусок текста по диапазону.
     *
     * Диапазон из сканера всегда валиден, но из вставленного текста в
     * подсказках может прийти что угодно, поэтому границы зажимаются.
     */
    fun slice(range: dev.ggtv.kjen.TextRange): String {
        val from = offsetOf(range.start)
        val to = offsetOf(range.end).coerceAtLeast(from)
        return text.substring(from, to.coerceAtMost(text.length))
    }

    /**
     * Попадает ли смещение внутрь диапазона.
     *
     * Границы включаются с обеих сторон: курсор может стоять и перед первым
     * символом, и сразу за последним — и в обоих случаях человек как будто
     * «внутри» этого ключа.
     */
    fun contains(range: dev.ggtv.kjen.TextRange, offset: Int): Boolean =
        offsetOf(range.start) <= offset && offset <= offsetOf(range.end)

    /** Длина диапазона в символах — для выбора самого узкого из вложенных. */
    fun lengthOf(range: dev.ggtv.kjen.TextRange): Int =
        offsetOf(range.end) - offsetOf(range.start)

    /** Текст строки, в которой находится смещение, для сообщений. */
    fun lineAtOffset(offset: Int): String = lineText(spanOf(offset).line - 1)

    private fun unitsOf(cp: Int, encoding: PositionEncoding): Int = when (encoding) {
        PositionEncoding.UTF16 -> Character.charCount(cp)
        PositionEncoding.UTF32 -> 1
        PositionEncoding.UTF8 -> utf8Length(cp)
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return n
    }
}

/**
 * Кодировка колонок в позициях протокола, о которой договорились с клиентом.
 *
 * Спецификация требует UTF-16 по умолчанию, и это то, что выбирает Neovim,
 * VS Code и большинство остальных. Но клиент вправе предложить своё в
 * `capabilities.general.positionEncodings` (rust-analyzer и gopls так делают),
 * и если сервер молча продолжит считать в UTF-16, диагностики и подсказки
 * съедут на одну позицию от курсора — ровно на файлах с эмодзи и иероглифами.
 * Поэтому кодировка хранится в сессии и передаётся в обе стороны перевода.
 */
enum class PositionEncoding(val protocolName: String) {
    UTF16("utf-16"),
    UTF32("utf-32"),
    UTF8("utf-8");

    companion object {
        /** Кодировка по имени из протокола; null — клиент не предложил. */
        fun of(protocolName: String?): PositionEncoding? = when (protocolName?.lowercase()) {
            "utf-8" -> UTF8
            "utf-32" -> UTF32
            "utf-16" -> UTF16
            else -> null
        }
    }
}
