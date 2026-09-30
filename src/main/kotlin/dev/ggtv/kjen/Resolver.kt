package dev.ggtv.kjen

/**
 * Резолвер ссылок: второй проход по AST.
 *
 * Работает после парсинга: парсеру неважно, где стоит ссылка —
 * резолвер раскрывает их, когда все значения уже в памяти,
 * как в HOCON. Позиция ссылки не имеет значения: вперёд-ссылки работают.
 *
 * Циклы детектятся через visiting: узел, который прямо сейчас
 * в процессе раскрытия, при повторной встрече — [CrenError.Cycle].
 */
class Resolver(private val root: Block) {

    private val visiting = HashSet<Path>()
    private var workRemaining = MAX_EXPANSION_WORK

    fun resolve(path: Path): Value {
        workRemaining = MAX_EXPANSION_WORK
        return resolvePath(path, 0)
    }

    fun resolveRoot(): Value {
        workRemaining = MAX_EXPANSION_WORK
        val basePath = emptyPath()
        return Value.VBlock(resolveBlock(root, basePath, root, 0, 0))
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
            resolveFromBlock(root, root, path, 0, emptyPath(), referenceDepth)
        } finally {
            visiting.remove(path)
        }
    }

    private fun resolveFromBlock(
        rootBlock: Block,
        block: Block,
        path: Path,
        next: Int,
        basePath: Path,
        referenceDepth: Int,
    ): Value {
        if (next == path.segments.size) {
            return Value.VBlock(resolveBlock(
                block,
                basePath,
                rootBlock,
                referenceDepth,
                0,
            ))
        }

        val label = path.toString()
        val segment = path.segments[next]
        val index = path.indices.getOrNull(next)
        val entry = resolveSegment(block, segment, index, label)

        return when (val value = entry.value) {
            is Value.VBlock -> {
                val childBase = childPath(basePath, segment, index)
                resolveFromBlock(
                    rootBlock,
                    value.block,
                    path,
                    next + 1,
                    childBase,
                    referenceDepth,
                )
            }
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
                val result = try {
                    resolvePath(absolute, referenceDepth + 1)
                } catch (error: CrenError.NotFound) {
                    if (next + 1 == path.segments.size) throw error
                    throw CrenError.NotFound(label)
                }
                if (next + 1 == path.segments.size) result else result
            }
            else -> {
                if (next + 1 == path.segments.size) {
                    resolveValue(value, basePath, rootBlock, referenceDepth, 0)
                } else {
                    throw CrenError.NotFound(label)
                }
            }
        }
    }

    private fun resolveValue(
        value: Value,
        basePath: Path,
        rootBlock: Block,
        referenceDepth: Int,
        valueDepth: Int,
    ): Value {
        if (valueDepth >= MAX_VALUE_DEPTH) {
            throw depthError("вложенность значений", MAX_VALUE_DEPTH)
        }
        consumeExpansionWork()

        return when (value) {
            is Value.VRef -> {
                val absolute = absolutePath(basePath, value.path)
                resolvePath(absolute, referenceDepth + 1)
            }
            is Value.VArray -> Value.VArray(value.items.map {
                resolveValue(it, basePath, rootBlock, referenceDepth, valueDepth + 1)
            })
            is Value.VDict -> Value.VDict(value.pairs.map { (key, item) ->
                key to resolveValue(item, basePath, rootBlock, referenceDepth, valueDepth + 1)
            })
            is Value.VBlock -> Value.VBlock(resolveBlock(
                value.block,
                basePath,
                rootBlock,
                referenceDepth,
                valueDepth,
            ))
            else -> value
        }
    }

    private fun resolveBlock(
        block: Block,
        basePath: Path,
        rootBlock: Block,
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
            resolved.entries += Entry(
                key = entry.key,
                ty = entry.ty,
                value = resolveValue(
                    entry.value,
                    entryBase,
                    rootBlock,
                    referenceDepth,
                    valueDepth + 1,
                ),
                comment = entry.comment,
                span = entry.span,
            )
        }
        return resolved
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

        fun resolveSegment(block: Block, seg: String, index: Int?, pathLabel: String): Entry {
            val count = block.entries.count { it.key == seg }
            if (index != null) {
                return block.get(seg, index) ?: throw CrenError.NotFound(pathLabel)
            }
            return when (count) {
                0 -> {
                    val suffixed = splitDigitSuffix(seg)
                    if (suffixed != null) {
                        block.get(suffixed.first, suffixed.second)
                            ?: throw CrenError.NotFound(pathLabel)
                    } else {
                        throw CrenError.NotFound(pathLabel)
                    }
                }
                1 -> block.get(seg, 1) ?: throw CrenError.NotFound(pathLabel)
                // Позиции всех совпадений обязательны: без них сообщение
                // отвечает «сколько», но не «где», а это ровно то, что нужно
                // человеку, чтобы починить конфиг.
                else -> throw CrenError.Ambiguous(
                    seg,
                    count,
                    block.entries.filter { it.key == seg }.map { it.span },
                )
            }
        }

        fun splitDigitSuffix(s: String): Pair<String, Int>? {
            var digitStart = s.length
            while (digitStart > 0 && s[digitStart - 1] in '0'..'9') digitStart--
            if (digitStart == s.length) return null
            val base = s.substring(0, digitStart)
            if (base.isEmpty()) return null
            val digits = s.substring(digitStart).toIntOrNull() ?: return null
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
