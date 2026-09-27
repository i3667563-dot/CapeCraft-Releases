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
}
