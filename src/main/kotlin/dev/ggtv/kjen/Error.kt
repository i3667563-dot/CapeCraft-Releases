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

    /**
     * Ссылка на повторяющийся ключ без номера: `server.host` при двух `server`.
     *
     * Позиции всех совпадений обязательны. «Встречается 2 раза» без указания,
     * где именно, бесполезно: два одинаковых имени в конфиге ищутся глазами,
     * а человек узнаёт о проблеме из чата, где ни файла, ни строки не видно.
     *
     * Одиночного `span` здесь нет намеренно — виноваты все совпадения, а не
     * одно место, и подчёркивать нечего (п. 4 в `docs/kjen-koren-bugs.md`).
     */
    class Ambiguous(val path: String, val count: Int, val locations: List<Span>) : CrenError(
        "неоднозначная ссылка: «$path» встречается $count ${timesWord(count)} " +
            "(${whereText(locations)}). Укажите номер: ${numberedForms(path, count)}. " +
            "Либо переименуйте один из них.",
    )

    /** Циклическая ссылка: значение ссылается само на себя. */
    class Cycle(val path: String) :
        CrenError("циклическая ссылка: «$path» ссылается сама на себя")

    /** Явный тип (`token str = ...`) не совпал с реальным значением. */
    class TypeMismatch(val expected: String, val found: String, val span: Span) :
        CrenError("несоответствие типа (строка ${span.line}, колонка ${span.col}): ожидалось $expected, найдено $found")
}

/** Сколько позиций дубликатов показываем, прежде чем обрезать. */
private const val MAX_LOCATIONS_SHOWN = 4

/**
 * «2 раза», но «5 раз» и «11 раз».
 *
 * Русское «раз» после числа: 1 раз, 2-4 раза, 5-20 раз, 21 раз. Ветка для
 * 11-14 важна: без неё «11 раз» превратилось бы в «11 раза».
 */
private fun timesWord(n: Int): String = when {
    n % 100 in 11..14 -> "раз"
    n % 10 == 1 -> "раз"
    n % 10 in 2..4 -> "раза"
    else -> "раз"
}

/** «строки 12 и 34» — где искать дубликаты. */
private fun linesWord(n: Int): String = when {
    n % 100 in 11..14 -> "строк"
    n % 10 == 1 -> "строка"
    n % 10 in 2..4 -> "строки"
    else -> "строк"
}

/**
 * Список мест, где объявлены дубликаты: «строки 12 и 34».
 *
 * Без этого сообщение отвечает на вопрос «сколько», но не на «где», а без
 * «где» ошибку приходится выискивать глазами по всему конфигу.
 *
 * Обрезанный список не соединяется через «и»: «строки 1, 2, 3 и 4, …»
 * читается как «после четвёртой ещё что-то названное», а не как «показаны
 * первые четыре».
 */
private fun whereText(locations: List<Span>): String {
    if (locations.isEmpty()) return "позиции неизвестны"
    val shown = locations.take(MAX_LOCATIONS_SHOWN).map { it.line }
    val truncated = locations.size > shown.size
    val body = when {
        truncated -> shown.joinToString(", ")
        shown.size == 1 -> "${shown[0]}"
        shown.size == 2 -> "${shown[0]} и ${shown[1]}"
        else -> shown.dropLast(1).joinToString(", ") + " и ${shown.last()}"
    }
    return "${linesWord(shown.size)} $body" + if (truncated) ", …" else ""
}

/**
 * Готовые ссылки с номером, чтобы не пришлось догадываться о суффиксе:
 * ««capeCraft1» или «capeCraft2»».
 *
 * Двух вариантов ровно столько, сколько нужно в типичном опечатке-дубле;
 * дальше список не перечисляет, но и не молчит — многоточие.
 */
private fun numberedForms(path: String, count: Int): String {
    val shown = minOf(count, 3)
    val forms = (1..shown).map { "«$path$it»" }
    val body = when (forms.size) {
        1 -> forms[0]
        2 -> "${forms[0]} или ${forms[1]}"
        else -> forms.joinToString(", ")
    }
    return if (count > shown) "$body, …" else body
}
