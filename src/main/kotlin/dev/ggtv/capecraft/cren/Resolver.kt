package dev.ggtv.capecraft.cren

/**
 * Резолвер ссылок: второй проход по AST.
 *
 * Работает после парсинга: парсеру неважно, где стоит ссылка —
 * резолвер раскрывает их, когда все значения уже в памяти,
 * как в HOCON. Позиция ссылки не имеет значения: вперёд-ссылки работают.
 *
 * Циклы детектятся через visiting: узел, который прямо сейчас
 * в процессе раскрытия, при повторной встрече — [CrenError.Cycle].
 * Раскрытые значения кэшируются — каждая ссылка раскрывается один раз.
 */
class Resolver(private val root: Block) {

    /** Узлы, которые прямо сейчас в процессе раскрытия → детект циклов. */
    private val visiting = HashSet<Path>()

    /** Кэш: каждая ссылка раскрывается один раз. */
    private val cache = HashMap<Path, Value>()

    /** Раскрыть ссылку по пути до конкретного значения. */
    fun resolve(path: Path): Value {
        // Пустой путь — NotFound, а не ошибка индексации.
        if (path.segments.isEmpty()) {
            throw CrenError.NotFound(path.toString())
        }

        // 1. Кэш.
        cache[path]?.let { return it }

        // 2. Цикл: путь уже раскрывается прямо сейчас.
        if (!visiting.add(path)) {
            throw CrenError.Cycle(path.toString())
        }

        // 3. Раскрытие.
        val result = resolveUncached(path)
        visiting.remove(path)
        cache[path] = result
        return result
    }

    /** Сам спуск по сегментам пути. */
    private fun resolveUncached(path: Path): Value {
        // Текущее значение: начинаем с корня, на каждом сегменте спускаемся.
        // Конфиги маленькие; блоки иммутабельны после парсинга — клонируем ссылками.
        var current: Value = Value.VBlock(root)
        val last = path.segments.lastIndex

        // Абсолютный путь к блоку, в котором ищем текущий сегмент.
        // Нужен для относительных ссылок: `.x` внутри блока превращается
        // в абсолютный эквивалент «base_path.x» — кэш и детект циклов
        // работают по нему, и разные блоки не путаются.
        var basePath = Path(emptyList(), emptyList(), true)

        for ((i, seg) in path.segments.withIndex()) {
            // Явный номер `[n]` — только на последнем сегменте (парсер гарантирует);
            // у промежуточных сегментов индекс может прийти только суффиксом
            // в имени (`server1`) — разбирает resolveSegment.
            val index = path.indices.getOrNull(i)

            val block = (current as? Value.VBlock)?.block
                // Спускаемся в не-блок: путь ведёт в никуда.
                ?: throw CrenError.NotFound("${path}.$seg")

            val entry = resolveSegment(block, seg, index, path.toString())

            when (val v = entry.value) {
                is Value.VBlock -> {
                    current = v
                    // Спуск в подблок: он становится базой для относительных.
                    basePath = Path(
                        basePath.segments + seg,
                        basePath.indices + index,
                        true,
                    )
                }
                // Ссылка в середине или в конце пути — раскрываем.
                is Value.VRef -> {
                    val abs = if (v.path.absolute) {
                        v.path
                    } else {
                        // Относительная ссылка: от текущего блока (base_path),
                        // сложив в абсолютный эквивалент.
                        Path(basePath.segments + v.path.segments, basePath.indices + v.path.indices, true)
                    }
                    current = resolve(abs)
                    basePath = abs
                }
                else -> {
                    if (i == last) {
                        // Последний сегмент — лист, это ответ.
                        return v
                    }
                    throw CrenError.NotFound(path.toString())
                }
            }
        }

        // Путь закончился на блоке — возвращаем его целиком.
        return current
    }

    companion object {
        /**
         * Найти запись в блоке по одному сегменту пути.
         *
         * Правила выбора записи:
         * - index = Some(n) — прямая n-я запись ключа;
         * - index = None: уникальный ключ → берём; повтор → [CrenError.Ambiguous];
         *   ключа нет, но имя заканчивается цифрами (`server1`) — суффикс-номер;
         *   иначе — [CrenError.NotFound].
         */
        fun resolveSegment(block: Block, seg: String, index: Int?, pathLabel: String): Entry {
            // Подсчёт вхождений и выбор записи делаем за один проход.
            var count = 0
            var first: Entry? = null
            var numbered: Entry? = null
            for (e in block.entries) {
                if (e.key == seg) {
                    count++
                    if (first == null) first = e
                    if (count == index) numbered = e
                }
            }

            if (index != null) {
                return numbered ?: throw CrenError.NotFound(pathLabel)
            }
            return when (count) {
                0 -> {
                    // Литерального ключа нет — пробуем суффикс-номер (`server1`).
                    // Литерал приоритетнее: он уже поймался бы веткой count>0.
                    val suffixed = splitDigitSuffix(seg)
                    if (suffixed != null) {
                        block.get(suffixed.first, suffixed.second)
                            ?: throw CrenError.NotFound(pathLabel)
                    } else {
                        throw CrenError.NotFound(pathLabel)
                    }
                }
                1 -> first!!
                else -> throw CrenError.Ambiguous(seg, count)
            }
        }

        /** «server1» → ("server", 1); «server» → null; «123» → null (пустая база). */
        fun splitDigitSuffix(s: String): Pair<String, Int>? {
            var digitStart = s.length
            while (digitStart > 0 && s[digitStart - 1].isDigit()) digitStart--
            if (digitStart == s.length) return null // цифр в конце нет
            val base = s.substring(0, digitStart)
            if (base.isEmpty()) return null
            val digits = s.substring(digitStart).toIntOrNull() ?: return null
            return base to digits
        }
    }
}