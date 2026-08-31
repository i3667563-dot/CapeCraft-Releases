package dev.ggtv.capecraft.cren

import java.nio.file.Files
import java.nio.file.Path as JPath
import kotlin.io.path.readText

/**
 * Высокоуровневый API конфига .crn: загрузил — и спрашивай значения.
 *
 * Вся сложность спрятана в ядре (токенизатор → парсер → резолвер).
 * Геттеры автоматически раскрывают ссылки, нумерацию мульти-ключей,
 * детектят циклы — разработчик про это не знает.
 */
class CrenConfig private constructor(private val root: Block) {

    companion object {
        /** Разобрать конфиг из строки. */
        fun fromString(input: String): CrenConfig {
            val tokens = Tokenizer.tokenize(input)
            val root = Parser.parse(tokens)
            return CrenConfig(root)
        }

        /** Прочитать конфиг из файла `.crn`. */
        fun load(path: JPath): CrenConfig {
            val text = try {
                Files.readString(path)
            } catch (e: Exception) {
                throw CrenError.Io("не могу прочитать файл «$path»: ${e.message}")
            }
            return fromString(text)
        }

        /** Прочитать конфиг из файла `.crn` по строковому пути. */
        fun load(path: String): CrenConfig = load(JPath.of(path))
    }

    /** Получить значение по пути, автоматически раскрыв ссылки. */
    fun get(path: String): Value = Resolver(root).resolve(parsePath(path))

    /** Типизированный доступ: строка. */
    fun getStr(path: String): String = when (val v = get(path)) {
        is Value.VStr -> v.s
        else -> throw typeMismatch("str", v)
    }

    /** Типизированный доступ: целое число. */
    fun getInt(path: String): Long = when (val v = get(path)) {
        is Value.VInt -> v.i
        else -> throw typeMismatch("int", v)
    }

    /** Типизированный доступ: число с плавающей точкой (int тоже подходит). */
    fun getFloat(path: String): Double = when (val v = get(path)) {
        is Value.VFloat -> v.f
        is Value.VInt -> v.i.toDouble()
        else -> throw typeMismatch("float", v)
    }

    /** Типизированный доступ: булево значение. */
    fun getBool(path: String): Boolean = when (val v = get(path)) {
        is Value.VBool -> v.b
        else -> throw typeMismatch("bool", v)
    }

    /** Типизированный доступ: блок (родительский объект). */
    fun getBlock(path: String): Block = when (val v = get(path)) {
        is Value.VBlock -> v.block
        else -> throw typeMismatch("block", v)
    }

    /** Типизированный доступ: массив. */
    fun getArray(path: String): List<Value> = when (val v = get(path)) {
        is Value.VArray -> v.items
        else -> throw typeMismatch("array", v)
    }

    /** Типизированный доступ: словарь. */
    fun getDict(path: String): List<Pair<String, Value>> = when (val v = get(path)) {
        is Value.VDict -> v.pairs
        else -> throw typeMismatch("dict", v)
    }

    /**
     * Сохраняемый комментарий записи: `# текст` перед/после значения.
     * Ссылки не раскрываются — комментарий ищется прямо по дереву.
     */
    fun getComment(path: String): String? {
        val p = parsePath(path)
        return findEntry(p).comment
    }

    /** Ключи внутри блока — в порядке появления, без повторов. */
    fun keys(path: String): List<String> {
        val p = parsePath(path)
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (e in findBlock(p).entries) {
            if (seen.add(e.key)) out += e.key
        }
        return out
    }

    /** Разобрать строку пути с человеческой ошибкой. */
    private fun parsePath(path: String): Path = Path.parse(path)

    /** Спуск по всем сегментам, кроме последнего: блок, в котором живёт лист. */
    private fun walk(path: Path): Block {
        if (path.segments.isEmpty()) throw CrenError.NotFound(path.toString())
        var block = root
        val label = path.toString()
        for ((i, seg) in path.segments.dropLast(1).withIndex()) {
            val index = path.indices.getOrNull(i)
            val entry = Resolver.resolveSegment(block, seg, index, label)
            block = (entry.value as? Value.VBlock)?.block
                ?: throw CrenError.NotFound(label)
        }
        return block
    }

    /** Запись по пути (без раскрытия ссылок) — для комментариев. */
    private fun findEntry(path: Path): Entry {
        val last = path.segments.lastOrNull()
            ?: throw CrenError.NotFound(path.toString())
        val parent = walk(path)
        val index = path.indices.getOrNull(path.segments.lastIndex)
        return Resolver.resolveSegment(parent, last, index, path.toString())
    }

    /** Блок по пути — для keys(). */
    private fun findBlock(path: Path): Block {
        val entry = findEntry(path)
        return (entry.value as? Value.VBlock)?.block
            ?: throw CrenError.NotFound(path.toString())
    }

    /** Ошибка «ожидалось X, найдено Y» — без позиции, значение пришло по пути. */
    private fun typeMismatch(expected: String, found: Value): CrenError.TypeMismatch =
        CrenError.TypeMismatch(expected, found.kind, Span.ZERO)
}