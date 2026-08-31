package dev.ggtv.capecraft.cren

/**
 * Все типы значений, которые умеет формат .crn.
 *
 * Иммутабельные структуры: парсер строит дерево, резолвер читает —
 * мутаций нет, поэтому блоки можно свободно переиспользовать
 * (резолвер клонирует ссылками, без аллокаций).
 */
sealed interface Value {
    /** Имя вида значения — для сообщений об ошибках («str», «int», ...). */
    val kind: String

    data class VStr(val s: String) : Value {
        override val kind get() = "str"
    }

    data class VInt(val i: Long) : Value {
        override val kind get() = "int"
    }

    data class VFloat(val f: Double) : Value {
        override val kind get() = "float"
    }

    data class VBool(val b: Boolean) : Value {
        override val kind get() = "bool"
    }

    /** Словарь: `token = {name: "bot", value: "..."}` — самостоятельное значение. */
    data class VDict(val pairs: List<Pair<String, Value>>) : Value {
        override val kind get() = "dict"
    }

    /** Массив: `databases [ ... ]`. */
    data class VArray(val items: List<Value>) : Value {
        override val kind get() = "array"
    }

    /** Блок: `server { ... }` — родительский объект для других значений. */
    data class VBlock(val block: Block) : Value {
        override val kind get() = "block"
    }

    /** Ссылка на другое значение: `x = server.token[1]`. */
    data class VRef(val path: Path) : Value {
        override val kind get() = "ref"
    }
}

/** Явные типы для `key type = value`. */
enum class Type(val word: String) {
    STR("str"),
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    DICT("dict"),
    ARRAY("array"),
    BLOCK("block"),
    REF("ref");

    companion object {
        fun fromWord(w: String): Type? = entries.firstOrNull { it.word == w }
    }
}