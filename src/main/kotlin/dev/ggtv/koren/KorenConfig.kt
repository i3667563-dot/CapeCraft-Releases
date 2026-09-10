package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenConfig
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Entry
import dev.ggtv.kjen.Path
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Value
import java.nio.file.Files
import java.nio.file.Path as JPath

/**
 * Высокоуровневый API конфига `.kn`: надстройка над Kjen-ядром.
 *
 * Полностью совместим с `.crn` (см. [CrenConfig]) и добавляет:
 * - вызовы функций `clamp(...)`, `hash(...)` и т.д.;
 * - живые корни мира `biome.temperature`, `weather.condition` ...
 *   через [WorldContext].
 *
 * Динамика: каждый [get] создаёт свежий [KorenResolver], поэтому
 * функции и поля мира переоцениваются при каждом запросе.
 */
class KorenConfig private constructor(
    private val root: Block,
    private val context: WorldContext,
) {

    companion object {
        /** Разобрать конфиг из строки. */
        fun fromString(input: String, context: WorldContext = EmptyWorldContext): KorenConfig {
            val tokens = KorenTokenizer.tokenize(input)
            val root = KorenParser.parse(tokens)
            return KorenConfig(root, context)
        }

        /** Прочитать конфиг из файла `.kn`. */
        fun load(path: JPath, context: WorldContext = EmptyWorldContext): KorenConfig {
            val text = try {
                Files.readString(path)
            } catch (e: Exception) {
                throw CrenError.Io("не могу прочитать файл «$path»: ${e.message}")
            }
            return fromString(text, context)
        }

        /** Прочитать конфиг из файла `.kn` по строковому пути. */
        fun load(path: String, context: WorldContext = EmptyWorldContext): KorenConfig =
            load(JPath.of(path), context)

        /** Обновить контекст мира без пересборки конфига (динамическая переоценка). */
        fun withContext(config: KorenConfig, context: WorldContext): KorenConfig =
            KorenConfig(config.root, context)
    }

    /** Получить значение по пути, раскрыв ссылки, функции и корни мира. */
    fun get(path: String): Value = KorenResolver(root, context).resolve(Path.parse(path))

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

    /** Сохраняемый комментарий записи: `# текст` перед/после значения. */
    fun getComment(path: String): String? {
        val p = Path.parse(path)
        return findEntry(p).comment
    }

    /** Ключи внутри блока — в порядке появления, без повторов. */
    fun keys(path: String): List<String> {
        val p = Path.parse(path)
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (e in findBlock(p).entries) {
            if (seen.add(e.key)) out += e.key
        }
        return out
    }

    /** Спуск по всем сегментам, кроме последнего: блок, в котором живёт лист. */
    private fun walk(path: Path): Block {
        if (path.segments.isEmpty()) throw CrenError.NotFound(path.toString())
        var block = root
        val label = path.toString()
        for ((i, seg) in path.segments.dropLast(1).withIndex()) {
            val index = path.indices.getOrNull(i)
            val entry = dev.ggtv.kjen.Resolver.resolveSegment(block, seg, index, label)
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
        return dev.ggtv.kjen.Resolver.resolveSegment(parent, last, index, path.toString())
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