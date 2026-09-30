package dev.ggtv.koren

import dev.ggtv.kjen.Block
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Entry
import dev.ggtv.kjen.Path
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Type
import dev.ggtv.kjen.Value

class KorenResolver(
    private val root: Block,
    private val context: WorldContext? = null,
) {

    private val visiting = HashSet<Path>()
    private var workRemaining = MAX_EXPANSION_WORK

    fun resolve(path: Path): Value {
        resetWork()
        return resolvePath(path, 0)
    }

    fun resolveRoot(): Value {
        resetWork()
        return Value.VBlock(resolveBlock(root, emptyPath(), 0, 0))
    }

    private fun resetWork() {
        visiting.clear()
        workRemaining = MAX_EXPANSION_WORK
    }

    private fun resolvePath(path: Path, referenceDepth: Int): Value {
        if (path.segments.isEmpty()) {
            throw CrenError.NotFound(path.toString())
        }
        if (path.segments.size > MAX_REFERENCE_DEPTH) {
            throw depthError("длина пути", MAX_REFERENCE_DEPTH)
        }
        if (referenceDepth > MAX_REFERENCE_DEPTH) {
            throw depthError("глубина ссылок", MAX_REFERENCE_DEPTH)
        }
        if (!visiting.add(path)) {
            throw CrenError.Cycle(path.toString())
        }

        return try {
            resolveFromRoot(path, referenceDepth)
        } finally {
            visiting.remove(path)
        }
    }

    private fun resolveFromRoot(path: Path, referenceDepth: Int): Value {
        val first = path.segments.first()
        val rootHasFirst = root.entries.any { it.key == first }
        if (
            !rootHasFirst &&
            path.absolute &&
            path.segments.size == 2 &&
            path.indices.all { it == null } &&
            context != null
        ) {
            val worldRoot = WorldRoot.fromPath(path.segments)
            if (worldRoot != null) {
                return resolveValue(
                    context.field(worldRoot, path.segments[1], path.toString()),
                    emptyPath(),
                    referenceDepth,
                    0,
                )
            }
        }
        return resolveFromBlock(root, path, 0, emptyPath(), referenceDepth)
    }

    private fun resolveFromBlock(
        block: Block,
        path: Path,
        next: Int,
        basePath: Path,
        referenceDepth: Int,
    ): Value {
        if (next == path.segments.size) {
            return Value.VBlock(resolveBlock(block, basePath, referenceDepth, 0))
        }

        val label = path.toString()
        val segment = path.segments[next]
        val index = path.indices.getOrNull(next)
        val entry = resolveSegment(block, segment, index, label)

        return when (val value = entry.value) {
            is Value.VBlock -> resolveFromBlock(
                value.block,
                path,
                next + 1,
                childPath(basePath, segment, index),
                referenceDepth,
            )
            is Value.VRef -> {
                var absolute = absolutePath(basePath, value.path)
                if (absolute.segments.isEmpty()) {
                    return resolvePath(absolute, referenceDepth + 1)
                }
                for (segmentIndex in next + 1 until path.segments.size) {
                    absolute = absolute.copy(
                        segments = absolute.segments + path.segments[segmentIndex],
                        indices = absolute.indices + path.indices.getOrNull(segmentIndex),
                    )
                }
                try {
                    resolvePath(absolute, referenceDepth + 1)
                } catch (error: CrenError.NotFound) {
                    if (next + 1 == path.segments.size) throw error
                    throw CrenError.NotFound(label)
                }
            }
            is VFunc -> {
                if (next + 1 != path.segments.size) {
                    throw CrenError.NotFound(label)
                }
                val resolved = resolveValue(value, basePath, referenceDepth, 0)
                checkFunctionResult(entry, resolved)
                resolved
            }
            else -> {
                if (next + 1 == path.segments.size) {
                    resolveValue(value, basePath, referenceDepth, 0)
                } else {
                    throw CrenError.NotFound(label)
                }
            }
        }
    }

    private fun resolveValue(
        value: Value,
        basePath: Path,
        referenceDepth: Int,
        valueDepth: Int,
    ): Value {
        if (valueDepth >= MAX_VALUE_DEPTH) {
            throw depthError("вложенность значений", MAX_VALUE_DEPTH)
        }
        consumeExpansionWork()

        return when (value) {
            is Value.VRef -> resolvePath(absolutePath(basePath, value.path), referenceDepth + 1)
            is VFunc -> resolveFunction(value, basePath, referenceDepth, valueDepth)
            is Value.VArray -> Value.VArray(
                value.items.map { resolveValue(it, basePath, referenceDepth, valueDepth + 1) },
            )
            is Value.VDict -> Value.VDict(
                value.pairs.map { (key, item) ->
                    key to resolveValue(item, basePath, referenceDepth, valueDepth + 1)
                },
            )
            is Value.VBlock -> Value.VBlock(
                resolveBlock(value.block, basePath, referenceDepth, valueDepth),
            )
            else -> value
        }
    }

    private fun resolveFunction(
        function: VFunc,
        basePath: Path,
        referenceDepth: Int,
        valueDepth: Int,
    ): Value {
        val args = function.args.map {
            resolveValue(it, basePath, referenceDepth, valueDepth + 1)
        }
        return Functions.call(function.name, args, function.span)
    }

    private fun resolveBlock(
        block: Block,
        basePath: Path,
        referenceDepth: Int,
        valueDepth: Int,
    ): Block {
        val resolved = Block()
        val occurrences = HashMap<String, Int>()

        for (entry in block.entries) {
            val index = occurrences.getOrDefault(entry.key, 0) + 1
            occurrences[entry.key] = index
            val entryBase = if (entry.value is Value.VBlock) {
                childPath(basePath, entry.key, index)
            } else {
                basePath
            }
            val value = resolveValue(
                entry.value,
                entryBase,
                referenceDepth,
                valueDepth + 1,
            )
            checkFunctionResult(entry, value)
            resolved.entries += Entry(
                key = entry.key,
                ty = entry.ty,
                value = value,
                comment = entry.comment,
                span = entry.span,
            )
        }
        return resolved
    }

    private fun checkFunctionResult(entry: Entry, resolved: Value) {
        val expected = entry.ty
        if (entry.value !is VFunc || expected == null || expected == Type.REF) return
        val matches = when (expected) {
            Type.STR -> resolved is Value.VStr
            Type.INT -> resolved is Value.VInt
            Type.FLOAT -> resolved is Value.VFloat || resolved is Value.VInt
            Type.BOOL -> resolved is Value.VBool
            Type.DICT -> resolved is Value.VDict
            Type.ARRAY -> resolved is Value.VArray
            Type.BLOCK -> resolved is Value.VBlock
            Type.REF -> true
        }
        if (!matches) {
            throw CrenError.TypeMismatch(expected.word, resolved.kind, entry.span)
        }
    }

    private fun consumeExpansionWork() {
        if (workRemaining == 0) {
            throw CrenError.Parse(
                "превышен предел раскрытия: максимум $MAX_EXPANSION_WORK",
                Span(1, 1),
            )
        }
        workRemaining--
    }

    companion object {
        const val MAX_REFERENCE_DEPTH = 256
        const val MAX_VALUE_DEPTH = 256
        const val MAX_EXPANSION_WORK = 65_536

        fun resolveSegment(block: Block, segment: String, index: Int?, pathLabel: String): Entry {
            val count = block.entries.count { it.key == segment }
            if (index != null) {
                return block.get(segment, index) ?: throw CrenError.NotFound(pathLabel)
            }
            return when (count) {
                0 -> {
                    val suffixed = splitDigitSuffix(segment)
                    if (suffixed != null) {
                        block.get(suffixed.first, suffixed.second)
                            ?: throw CrenError.NotFound(pathLabel)
                    } else {
                        throw CrenError.NotFound(pathLabel)
                    }
                }
                1 -> block.get(segment, 1) ?: throw CrenError.NotFound(pathLabel)
                // Позиции всех совпадений обязательны: без них сообщение
                // отвечает «сколько», но не «где», а это ровно то, что нужно
                // человеку, чтобы починить конфиг. Игра и редактор обязаны
                // говорить одно и то же, поэтому текст ошибки общий.
                else -> throw CrenError.Ambiguous(
                    segment,
                    count,
                    block.entries.filter { it.key == segment }.map { it.span },
                )
            }
        }

        fun splitDigitSuffix(value: String): Pair<String, Int>? {
            var digitStart = value.length
            while (digitStart > 0 && value[digitStart - 1] in '0'..'9') digitStart--
            if (digitStart == value.length) return null
            val base = value.substring(0, digitStart)
            if (base.isEmpty()) return null
            val digits = value.substring(digitStart).toIntOrNull() ?: return null
            return base to digits
        }

        private fun emptyPath(): Path = Path(emptyList(), emptyList(), true)

        private fun absolutePath(basePath: Path, reference: Path): Path {
            if (reference.absolute) return reference
            return Path(
                basePath.segments + reference.segments,
                basePath.indices + reference.indices,
                true,
            )
        }

        private fun childPath(basePath: Path, key: String, index: Int?): Path = Path(
            basePath.segments + key,
            basePath.indices + index,
            true,
        )

        private fun depthError(kind: String, limit: Int): CrenError.Parse = CrenError.Parse(
            "превышен предел $kind: максимум $limit",
            Span(1, 1),
        )
    }
}
