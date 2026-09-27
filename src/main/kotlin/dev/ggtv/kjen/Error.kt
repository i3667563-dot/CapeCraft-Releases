package dev.ggtv.kjen

/**
 * Позиция в исходном тексте конфига (1-based).
 */
data class Span(val line: Int, val col: Int) {
    companion object {
        val ZERO = Span(0, 0)
    }
}

/**
 * Диапазон исходного текста: от [start] до [end].
 *
 * ## Зачем, если есть [Span]
 *
 * [Span] — это точка, и для лога мода её хватает: «строка 12, колонка 5».
 * Редактору точки мало: подчёркивание нулевой ширины не видно, и «вы здесь
 * опечатались» без указания **какое именно слово** бесполезно. Поэтому у
 * токенов, записей и значений лежит диапазон, а не точка.
 *
 * ## Границы
 *
 * [start] включительно, [end] **не** включительно — это позиция сразу за
 * последним символом, ровно та, которая получается у токенизатора после
 * сканирования. Соглашение как у `String.subSequence`, поэтому
 * `end - start` даёт длину, а [Span] последнего символа получается шагом назад.
 *
 * Диапазон может быть пустым ([isEmpty]) — например у пустой строки `""` или
 * если токен собрался в ноль символов. Пустой диапазон не ошибка: редактор
 * покажет по нему курсор.
 */
data class TextRange(val start: Span, val end: Span) {
    /** Начало как точка — для сообщений об ошибках. */
    val span: Span get() = start

    /** Диапазон пуст, если начало и конец совпали. */
    val isEmpty: Boolean get() = start == end

    /**
     * Наименьший диапазон, покрывающий оба.
     *
     * Сравнение построчное: многострочные токены бывают (строки и комментарии с
     * переводом строки), и строку сравнивают раньше колонки, а не наоборот.
     */
    fun merge(other: TextRange): TextRange = TextRange(
        start = if (other.start.isBefore(start)) other.start else start,
        end = if (end.isBefore(other.end)) other.end else end,
    )

    /** Позиция сразу за последним символом диапазона. */
    fun endExclusive(): Span = end

    companion object {
        /** Пустой диапазон в точке. */
        fun emptyAt(span: Span): TextRange = TextRange(span, span)
    }
}

/** Строка раньше другой: сначала сравниваем строки, потом колонки. */
internal fun Span.isBefore(other: Span): Boolean =
    line < other.line || (line == other.line && col < other.col)

/**
 * Единый тип ошибки всего формата .crn.
 *
 * Наследует [Exception] — в Kotlin ошибки принято бросать и ловить,
 * при этом вся информация (позиция, путь, тип) сохраняется в полях,
 * чтобы показывать пользователю человеческие сообщения.
 */
sealed class CrenError(message: String) : Exception(message) {

    /** Синтаксическая ошибка: что ждали, что нашли, где. */
    class Parse(val messageText: String, val span: Span) :
        CrenError("ошибка парсинга (строка ${span.line}, колонка ${span.col}): $messageText")

    /** Ошибка файловой системы (файл не найден, нет доступа и т.п.). */
    class Io(val messageText: String) :
        CrenError("ошибка ввода-вывода: $messageText")

    /** Ссылка ведёт на несуществующий ключ/номер. */
    class NotFound(val path: String) :
        CrenError("значение по пути «$path» не найдено")

    /** Ссылка на повторяющийся ключ без номера: `server.host` при двух `server`. */
    class Ambiguous(val path: String, val count: Int) :
        CrenError("неоднозначная ссылка: «$path» встречается $count раз — укажите номер, например «${path}1»")

    /** Циклическая ссылка: значение ссылается само на себя. */
    class Cycle(val path: String) :
        CrenError("циклическая ссылка: «$path» ссылается сама на себя")

    /** Явный тип (`token str = ...`) не совпал с реальным значением. */
    class TypeMismatch(val expected: String, val found: String, val span: Span) :
        CrenError("несоответствие типа (строка ${span.line}, колонка ${span.col}): ожидалось $expected, найдено $found")
}