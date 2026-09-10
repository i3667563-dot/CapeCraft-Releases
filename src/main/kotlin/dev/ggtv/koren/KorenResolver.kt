package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Path
import dev.ggtv.kjen.Resolver
import dev.ggtv.kjen.Value

/**
 * Резолвер `.kn`: как у Kjen, плюс вызовы функций ([VFunc]) и
 * живые корни мира через [WorldContext].
 *
 * Каждый вызов `KorenConfig.get(...)` создаёт новый резолвер —
 * значения функций и мира переоцениваются динамически, без
 * пересборки AST (см. SPEC.md, «Динамическая переоценка»).
 */
class KorenResolver(
    private val root: Block,
    private val context: WorldContext,
) {

    private val visiting = HashSet<Path>()
    private val cache = HashMap<Path, Value>()

    /** Раскрыть путь до конкретного значения (с функциями и миром). */
    fun resolve(path: Path): Value {
        if (path.segments.isEmpty()) {
            throw CrenError.NotFound(path.toString())
        }

        // Живой корень мира: конфиг в корне имеет приоритет над миром.
        if (path.absolute && path.segments.size == 2 && path.indices.all { it == null }) {
            val world = WorldRoot.bySegment(path.segments[0])
            if (world != null && !rootHas(world.segment)) {
                return context.field(world, path.segments[1], path.toString())
            }
        }

        cache[path]?.let { return it }

        if (!visiting.add(path)) {
            throw CrenError.Cycle(path.toString())
        }

        val result = resolveUncached(path)
        visiting.remove(path)
        cache[path] = result
        return result
    }

    private fun rootHas(segment: String): Boolean = root.entries.any { it.key == segment }

    private fun resolveUncached(path: Path): Value {
        var current: Value = Value.VBlock(root)
        val last = path.segments.lastIndex
        var basePath = Path(emptyList(), emptyList(), true)

        for ((i, seg) in path.segments.withIndex()) {
            val index = path.indices.getOrNull(i)

            val block = (current as? Value.VBlock)?.block
                ?: throw CrenError.NotFound("${path}.$seg")

            val entry = Resolver.resolveSegment(block, seg, index, path.toString())

            when (val v = entry.value) {
                is Value.VBlock -> {
                    current = v
                    basePath = Path(basePath.segments + seg, basePath.indices + index, true)
                }
                is Value.VRef -> {
                    val abs = if (v.path.absolute) {
                        v.path
                    } else {
                        Path(basePath.segments + v.path.segments, basePath.indices + v.path.indices, true)
                    }
                    current = resolve(abs)
                    basePath = abs
                }
                is VFunc -> {
                    // Функция переоценивается при каждом разрешении пути.
                    current = evalFunc(v, basePath)
                    if (i != last) {
                        // Функция в середине пути — спускаемся, если это блок.
                        basePath = Path(basePath.segments + seg, basePath.indices + index, true)
                    }
                }
                else -> {
                    if (i == last) {
                        return v
                    }
                    throw CrenError.NotFound(path.toString())
                }
            }
        }

        return current
    }

    /** Вычислить функцию: все аргументы (ссылки и вложенные функции) разрешаются. */
    private fun evalFunc(f: VFunc, basePath: Path): Value {
        val args = f.args.map { arg -> evalArg(arg, basePath) }
        return Functions.call(f.name, args, f.span)
    }

    private fun evalArg(v: Value, basePath: Path): Value = when (v) {
        is Value.VRef -> {
            val abs = if (v.path.absolute) {
                v.path
            } else {
                Path(basePath.segments + v.path.segments, basePath.indices + v.path.indices, true)
            }
            resolve(abs)
        }
        is VFunc -> evalFunc(v, basePath)
        else -> v
    }
}

/** Корни мира в `.kn`: блоки, которых нет в конфиге, берутся из контекста мира. */
enum class WorldRoot(val segment: String) {
    BIOME("biome"),
    WEATHER("weather"),
    TIME("time"),
    DIMENSION("dimension"),
    LOCATION("location");

    companion object {
        fun bySegment(seg: String): WorldRoot? = entries.firstOrNull { it.segment == seg }
    }
}

/**
 * SPI живой информации о мире для `.kn`.
 *
 * Игра (Fabric-мод) реализует этот интерфейс и передаёт в [KorenConfig]:
 * при каждом `get()` значения корней мира читаются заново — конфиг не
 * требует пересборки, когда игрок переместился/сменил биом или время.
 */
interface WorldContext {
    /**
     * Значение поля живого корня мира: `biome.temperature`,
     * `dimension.type` и т.д. Если поле неизвестно — [CrenError.NotFound].
     */
    fun field(root: WorldRoot, field: String, path: String): Value
}

/** [WorldContext] без живой информации: любой запрос — [CrenError.NotFound]. */
object EmptyWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value {
        throw CrenError.NotFound(path)
    }
}