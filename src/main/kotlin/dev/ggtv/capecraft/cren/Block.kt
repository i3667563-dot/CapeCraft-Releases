package dev.ggtv.capecraft.cren

/**
 * Блок — скоуп с записями в порядке появления.
 *
 * Порядок важен: он даёт нумерацию мульти-ключей
 * (`token = "..."` два раза → номера 1 и 2 сверху вниз).
 */
class Block {
    val entries: MutableList<Entry> = mutableListOf()

    /** Запись с ключом [key] под номером [index] (1-based), как в `server.token[1]`. */
    fun get(key: String, index: Int): Entry? {
        if (index <= 0) return null
        return entries.filter { it.key == key }.getOrNull(index - 1)
    }
}

/** Одна запись `key [type] = value` внутри блока. */
data class Entry(
    val key: String,
    /** Явный тип (`token str = ...`) — опционально, иначе подхватится сам. */
    val ty: Type?,
    val value: Value,
    /** Комментарий `# ...` — сохраняется при парсинге. */
    val comment: String?,
    val span: Span,
)