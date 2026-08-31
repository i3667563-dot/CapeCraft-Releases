package dev.ggtv.capecraft.cren

/**
 * Позиция в исходном тексте конфига (1-based).
 */
data class Span(val line: Int, val col: Int) {
    companion object {
        val ZERO = Span(0, 0)
    }
}

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