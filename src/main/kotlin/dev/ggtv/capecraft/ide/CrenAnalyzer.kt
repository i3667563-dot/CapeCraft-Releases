package dev.ggtv.capecraft.ide

import dev.ggtv.capecraft.schema.ConfigSchema
import dev.ggtv.capecraft.schema.Field
import dev.ggtv.capecraft.schema.SchemaType
import dev.ggtv.capecraft.schema.WhenSchema
import dev.ggtv.koren.WorldRoot
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.TextRange

/** Насколько проблема мешает работе. */
enum class CrenSeverity {
    /** Мод такой файл не примет. */
    ERROR,

    /** Мод примет, но человек, скорее всего, ошибся. */
    WARNING,

    /** Подсказка: не ошибка, но полезно знать. */
    HINT,
}

/**
 * Что можно сделать с проблемой одним нажатием.
 *
 * Подсказки без «исправить» обходятся в полтора раза дороже: человеку
 * приходится самому вспоминать правильное написание.
 */
data class CrenFix(
    val title: String,
    /** Текст, который заменит диапазон. */
    val newText: String,
    /** Диапазон, который заменяется. */
    val range: TextRange,
)

/**
 * Одна найденная проблема.
 *
 * @property code короткий машинный код, чтобы правило можно было отключить по
 *   настройке, не разбирая текст сообщения
 */
data class CrenDiagnostic(
    val range: TextRange,
    val message: String,
    val severity: CrenSeverity,
    val code: String,
    val fixes: List<CrenFix> = emptyList(),
)

/** Род подсказки — от него зависит иконка в списке. */
enum class CompletionKind {
    /** Ключ словаря или блока. */
    KEY,

    /** Допустимое значение ключа. */
    VALUE,

    /** Имя типа из `key str = value`. */
    TYPE,

    /** Корень условия `when`. */
    WHEN_ROOT,

    /** Оператор сравнения. */
    OPERATOR,
}

/** Вариант в списке подсказок. */
data class CrenCompletion(
    val label: String,
    val detail: String? = null,
    /** Что вставить вместо уже набранного начала. */
    val insertText: String = label,
    /** Хвост после курсора, если ключ требует значения. */
    val snippet: String? = null,
    val kind: CompletionKind = CompletionKind.KEY,
    val sortText: String = label,
)

/** Что показать при наведении. */
data class CrenHover(val range: TextRange, val text: String)

/** Вид контейнера в разобранном дереве. */
enum class ContainerKind {
    /** Сам файл: записи верхнего уровня. */
    ROOT,

    /** `{ ... }` как значение. */
    DICT,

    /** `имя { ... }` — именованный блок. */
    BLOCK,

    /** `[ ... ]`. */
    ARRAY,
}

/**
 * Контейнер вместе с путём до него.
 *
 * @property path ключи от корня; у элемента массива на месте ключа индекс
 * @property entries записи, если это не массив
 * @property items элементы, если это массив
 */
data class LocatedContainer(
    val range: TextRange,
    val path: List<String>,
    val kind: ContainerKind,
    val entries: List<CrenEntry> = emptyList(),
    val items: List<CrenValue> = emptyList(),
    val parent: LocatedContainer? = null,
)

/**
 * Запись вместе с путём и окружением.
 *
 * @property siblings записи того же уровня — нужны, чтобы узнать `type`
 *   провайдера: часть ключей видна только после него
 */
data class LocatedEntry(
    val entry: CrenEntry,
    val path: List<String>,
    val container: LocatedContainer,
    val siblings: List<CrenEntry>,
)

/**
 * Анализатор конфига для редактора: диагностика, подсказки и подсказки при
 * наведении.
 *
 * ## Откуда берутся правила
 *
 * Из [ConfigSchema] и [WhenSchema], а не отсюда. Схема описана по коду мода
 * ([dev.ggtv.capecraft.provider.ProviderLoader],
 * [dev.ggtv.capecraft.condition.Condition]), и правило, живущее в анализаторе,
 * рано или поздно с ним разойдётся. Здесь только перевод: «дерево + схема» →
 * «сообщение человеку».
 *
 * ## Почему не через парсер мода
 *
 * `KorenConfig.fromString` бросает исключение на незаданной переменной
 * окружения и на первой же синтаксической ошибке, а редактор обязан работать
 * и с тем, и с другим. Поэтому разбор здесь терпимый ([CrenParser]).
 * Подробности — в `docs/kjen-koren-bugs.md`.
 *
 * ## Про `when`
 *
 * `when` живёт **внутри провайдера**, а не в корне: `{ when = { weather: "rain" } }`.
 * Внутри ключ условия составной — `time.period`, `location.y` — либо короткая
 * запись с синонимом: `when = { weather: "rain" }`.
 */
object CrenAnalyzer {
    // Коды правил. Короткие и стабильные: по ним настраивают серьёзность.
    const val CODE_SYNTAX = "syntax"
    const val CODE_UNKNOWN_KEY = "unknown-key"
    const val CODE_DEPRECATED = "deprecated"
    const val CODE_MISSING = "missing-key"
    const val CODE_VALUE = "bad-value"
    const val CODE_TYPE = "bad-type"
    const val CODE_APPLIES = "applies-to"
    const val CODE_WHEN = "bad-when"
    const val CODE_UNKNOWN_TYPE = "unknown-type"
    const val CODE_NO_SEPARATOR = "no-separator"

    private val KNOWN_TYPE_WORDS =
        listOf("str", "int", "float", "bool", "dict", "array", "block", "ref")

    private const val MAX_LISTED = 8

    /**
     * Все проблемы файла, в порядке появления.
     *
     * Бросить не может: это единственный метод, который редактор зовёт на
     * каждое изменение, и падение означало бы «подсветка пропала».
     */
    fun diagnostics(doc: CrenDocument): List<CrenDiagnostic> {
        val tree = CrenParser.parse(doc)
        val out = ArrayList<CrenDiagnostic>()

        for (p in tree.problems) {
            out += CrenDiagnostic(p.range, p.message, CrenSeverity.ERROR, CODE_SYNTAX)
        }
        for (l in doc.lexemes().filter { it.kind == CrenLexKind.STR && it.unterminated }) {
            out += CrenDiagnostic(
                l.range,
                "строка не закрыта кавычкой",
                CrenSeverity.ERROR,
                CODE_SYNTAX,
            )
        }

        for (e in locate(tree)) out += checkEntry(doc, e)
        for (c in containersOf(tree)) out += checkMissingKeys(c)
        return out.sortedWith(compareBy({ it.range.start.line }, { it.range.start.col }, { it.code }))
    }

    // ---------------------------------------------------------------- обход

    /** Все записи файла с путями и окружением. */
    fun locate(tree: CrenTree): List<LocatedEntry> {
        val out = ArrayList<LocatedEntry>()
        walkContainer(rootContainer(tree), out)
        return out
    }

    /** Все контейнеры файла, включая корень. */
    fun containersOf(tree: CrenTree): List<LocatedContainer> {
        val out = ArrayList<LocatedContainer>()
        fun visit(c: LocatedContainer) {
            out += c
            childrenOf(c).forEach { visit(it) }
        }
        visit(rootContainer(tree))
        return out
    }

    private fun rootContainer(tree: CrenTree): LocatedContainer {
        val first = tree.root.firstOrNull()
        return LocatedContainer(
            range = if (first != null) {
                TextRange(Span(1, 1), first.range.start)
            } else {
                TextRange.emptyAt(Span(1, 1))
            },
            path = emptyList(),
            kind = ContainerKind.ROOT,
            entries = tree.root,
        )
    }

    /**
     * Вложенные контейнеры.
     *
     * Элементы массива обрабатываются здесь же: у `providers` элементы — словари,
     * и их записи живут в отдельных контейнерах с индексом в пути
     * (`providers.0.url`). Индекс важен: подсказки второго провайдера должны
     * знать, что это он, а не первый.
     */
    private fun childrenOf(c: LocatedContainer): List<LocatedContainer> {
        val out = ArrayList<LocatedContainer>()
        for (e in c.entries) {
            val path = c.path + e.keyText
            when {
                e.form == EntryForm.BLOCK -> out += LocatedContainer(
                    range = e.range,
                    path = path,
                    kind = ContainerKind.BLOCK,
                    entries = e.children,
                    parent = c,
                )

                e.value is CrenDict -> out += LocatedContainer(
                    range = e.value.range,
                    path = path,
                    kind = ContainerKind.DICT,
                    entries = e.value.entries,
                    parent = c,
                )

                e.value is CrenArray -> out += LocatedContainer(
                    range = e.value.range,
                    path = path,
                    kind = ContainerKind.ARRAY,
                    items = e.value.items,
                    parent = c,
                )
            }
        }
        for ((n, item) in c.items.withIndex()) {
            if (item !is CrenDict) continue
            out += LocatedContainer(
                range = item.range,
                path = c.path + n.toString(),
                kind = ContainerKind.DICT,
                entries = item.entries,
                parent = c,
            )
        }
        return out
    }

    private fun walkContainer(c: LocatedContainer, out: MutableList<LocatedEntry>) {
        for (e in c.entries) {
            out += LocatedEntry(e, c.path + e.keyText, c, c.entries)
        }
        childrenOf(c).forEach { walkContainer(it, out) }
    }

    // ------------------------------------------------------------ проверки

    private fun checkEntry(doc: CrenDocument, loc: LocatedEntry): List<CrenDiagnostic> {
        val out = ArrayList<CrenDiagnostic>()
        val e = loc.entry

        checkExplicitType(e)?.let { out += it }

        if (loc.container.kind == ContainerKind.ARRAY) return out

        // В словаре мод требует «:» или «=» после ключа, в блоке — нет.
        if (loc.container.kind == ContainerKind.DICT && e.form !in VALUE_FORMS) {
            out += CrenDiagnostic(
                e.keyRange,
                "в словаре после ключа нужно «:» или «=»",
                CrenSeverity.ERROR,
                CODE_NO_SEPARATOR,
            )
        }

        val key = e.keyText
        if (key == "when" && e.value is CrenDict) {
            out += checkWhen(e.value)
            return out
        }

        // `capeCraft` — само имя корня схемы, а не ключ внутри него. Проверять
        // его как содержимое нельзя: у него нет родителя в схеме, и он всегда
        // был бы «неизвестным». Ошибку в самом корне ловим отдельно — на
        // его содержимом.
        if (loc.container.kind == ContainerKind.ROOT && key == ConfigSchema.ROOT) {
            return out
        }

        val known = ConfigSchema.childrenOf(loc.path.dropLast(1))
        if (known.isEmpty()) {
            // Уровень схеме не известен: это либо аддонский тип провайдера,
            // либо опечатка в разделе, и опечатку поймает верхний уровень.
            // Трогать аддонские ключи нельзя — молчим.
            return out
        }
        val field = known.firstOrNull { it.name == key }
        if (field == null) {
            out += unknownKey(e, known)
            return out
        }

        field.deprecated?.let { why ->
            out += CrenDiagnostic(
                e.keyRange,
                "«$key» больше не читается. Вместо него: $why",
                CrenSeverity.WARNING,
                CODE_DEPRECATED,
                listOf(CrenFix("заменить на «${firstWord(why)}»", firstWord(why), e.keyRange)),
            )
        }
        checkApplies(field, e, loc.siblings)?.let { out += it }
        checkAllowed(field, e)?.let { out += it }
        return out
    }

    /**
     * Явный тип `key str = value`.
     *
     * Мод на несовпадении бросает [dev.ggtv.kjen.CrenError.TypeMismatch], так
     * что здесь это именно ошибка. Набор подходящих типов повторяет
     * [dev.ggtv.kjen.Parser]: `float` принимает целое, `int` — нет. Держать
     * это вровень обязательно, иначе редактор будет ругаться на то, что мод
     * принимает молча.
     */
    private fun checkExplicitType(e: CrenEntry): CrenDiagnostic? {
        val declared = e.key.typeText ?: return null
        val range = e.key.typeRange ?: e.keyRange
        val lower = declared.lowercase()
        if (lower !in KNOWN_TYPE_WORDS) {
            return CrenDiagnostic(
                range,
                "неизвестный тип «$declared» (доступны: ${KNOWN_TYPE_WORDS.joinToString(", ")})",
                CrenSeverity.ERROR,
                CODE_UNKNOWN_TYPE,
            )
        }
        val actual = actualTypeOf(e.value) ?: return null
        if (typeMatches(lower, actual)) return null
        return CrenDiagnostic(
            range,
            "указан тип «$declared», а значение — ${actual.ruName()}",
            CrenSeverity.ERROR,
            CODE_TYPE,
        )
    }

    /**
     * Неизвестный ключ.
     *
     * Показываем похожие: чаще всего это опечатка, и «возможно, вы имели в
     * виду» экономит больше времени, чем сама ошибка.
     */
    private fun unknownKey(e: CrenEntry, known: List<Field>): CrenDiagnostic {
        val key = e.keyText
        val names = known.map { it.name }
        val similar = ConfigSchema.similarTo(key, names)
        val tail = if (names.size > MAX_LISTED) " и ещё ${names.size - MAX_LISTED}" else ""
        val hint = if (similar.isEmpty()) {
            "Доступны: ${names.take(MAX_LISTED).joinToString(", ")}$tail"
        } else {
            "Похоже на: ${similar.take(MAX_LISTED).joinToString(", ")}"
        }
        return CrenDiagnostic(
            e.keyRange,
            "неизвестный ключ «$key». $hint",
            CrenSeverity.ERROR,
            CODE_UNKNOWN_KEY,
            similar.take(MAX_LISTED).map { CrenFix("заменить на «$it»", it, e.keyRange) },
        )
    }

    /**
     * Ключ, который не имеет смысла при текущем `type` провайдера.
     *
     * Например `url` у провайдера типа `file`: мод такое поле просто не читает,
     * и человек неделю будет гадать, почему ссылка игнорируется.
     */
    private fun checkApplies(field: Field, e: CrenEntry, siblings: List<CrenEntry>): CrenDiagnostic? {
        if (field.appliesTo.isEmpty() || field.open) return null
        val type = siblings.firstOrNull { it.keyText == "type" }?.let { leafText(it) } ?: return null
        if (type in field.appliesTo) return null
        return CrenDiagnostic(
            e.keyRange,
            "«${field.name}» не имеет смысла при типе «$type»",
            CrenSeverity.WARNING,
            CODE_APPLIES,
        )
    }

    /**
     * Значение не из списка допустимых.
     *
     * У открытого поля вроде `type` у провайдера чужое значение — не ошибка:
     * аддон вправе зарегистрировать своё, и ругаться на это нельзя. Но
     * промолчать тоже плохо, поэтому это подсказка с перечнем встроенных.
     */
    private fun checkAllowed(field: Field, e: CrenEntry): CrenDiagnostic? {
        val leaf = e.value as? CrenLeaf ?: return null
        if (field.allowed.isEmpty()) return null
        val text = leaf.lexeme.value
        if (text in field.allowed) return null
        val tail = if (field.open) " — если это не тип от аддона" else ""
        return CrenDiagnostic(
            leaf.lexeme.range,
            "«$text» не подходит. Допустимо: ${field.allowed.joinToString(", ")}$tail",
            if (field.open) CrenSeverity.HINT else CrenSeverity.WARNING,
            CODE_VALUE,
            field.allowed.map { CrenFix("заменить на «$it»", it, leaf.lexeme.range) },
        )
    }

    /**
     * Пропущенные обязательные ключи.
     *
     * Мод переживёт молча, но человек либо забыл ключ, либо написал его с
     * опечаткой, и молчаливый дефолт сэкономит ему полчаса на выяснение,
     * почему плащ не грузится.
     */
    private fun checkMissingKeys(c: LocatedContainer): List<CrenDiagnostic> {
        if (c.kind == ContainerKind.ROOT || c.kind == ContainerKind.ARRAY) return emptyList()
        val children = ConfigSchema.childrenOf(c.path)
        if (children.isEmpty()) return emptyList()

        val present = c.entries.map { it.keyText }.toSet()
        val type = c.entries.firstOrNull { it.keyText == "type" }?.let { leafText(it) }
        val anchor = TextRange(c.range.start, c.range.start)
        val out = ArrayList<CrenDiagnostic>()
        for (f in children) {
            if (!f.required || f.name in present) continue
            if (!f.offeredFor(type)) continue
            // Условно обязательный ключ (`path` нужен только для `file`) при
            // неизвестном типе молчит: без `type` нельзя сказать, нужен он
            // здесь или нет, и вместо одной подсказки человек получил бы три.
            // Сам отсутствующий `type` мы сообщаем отдельно.
            if (f.appliesTo.isNotEmpty() && type == null) continue
            out += CrenDiagnostic(
                anchor,
                "«${f.name}» обязателен" + (f.def?.let { ", по умолчанию $it" } ?: ""),
                CrenSeverity.WARNING,
                CODE_MISSING,
                listOf(CrenFix("добавить «${f.name}»", "\"${f.name}\": ", c.range)),
            )
        }
        return out
    }

    /** Проверить содержимое `when`. */
    private fun checkWhen(value: CrenDict): List<CrenDiagnostic> {
        val out = ArrayList<CrenDiagnostic>()
        for (child in value.entries) {
            val key = child.keyText
            val dot = key.indexOf('.')
            val rootName = if (dot < 0) key else key.substring(0, dot)
            val fieldName = if (dot < 0) null else key.substring(dot + 1)

            val root = WorldRoot.bySegment(rootName)
            if (root == null) {
                val roots = WhenSchema.roots().map { it.segment }
                out += CrenDiagnostic(
                    child.keyRange,
                    "неизвестный корень условия «$key». Доступны: ${roots.joinToString(", ")}",
                    CrenSeverity.ERROR,
                    CODE_WHEN,
                    roots.map { CrenFix("заменить на «$it»", it, child.keyRange) },
                )
                continue
            }

            if (fieldName == null) {
                checkWhenShortForm(child, root)?.let { out += it }
                continue
            }

            val fields = WhenSchema.fieldsOf(root)
            if (fieldName !in fields) {
                out += CrenDiagnostic(
                    child.keyRange,
                    "у условия «${root.segment}» нет поля «$fieldName». " +
                        "Доступны: ${fields.joinToString(", ")}",
                    CrenSeverity.ERROR,
                    CODE_WHEN,
                    fields.map { CrenFix("заменить на «$it»", it, child.keyRange) },
                )
                continue
            }
            WhenSchema.docFor(root, fieldName)?.let { doc ->
                out += CrenDiagnostic(child.keyRange, doc, CrenSeverity.HINT, CODE_WHEN)
            }
        }
        return out
    }

    /** Короткая запись `weather: "rain"`: значение должно быть синонимом. */
    private fun checkWhenShortForm(child: CrenEntry, root: WorldRoot): CrenDiagnostic? {
        val leaf = child.value as? CrenLeaf ?: return null
        val text = leaf.lexeme.value
        val aliases = WhenSchema.aliasesOf(root)
        if (aliases.isEmpty() || text in aliases) return null
        val range = leaf.range
        return CrenDiagnostic(
            range,
            "«$text» не подходит для «${root.segment}». " +
                "Допустимо: ${aliases.joinToString(", ")}",
            CrenSeverity.WARNING,
            CODE_WHEN,
            aliases.map { CrenFix("заменить на «$it»", it, range) },
        )
    }

    // ------------------------------------------------------------ подсказки

    /**
     * Подсказки для позиции курсора.
     *
     * Возвращает пустой список там, где подсказывать нечего: выдуманные
     * ключи вредят больше, чем отсутствие подсказок.
     */
    fun complete(doc: CrenDocument, offset: Int): List<CrenCompletion> {
        val tree = CrenParser.parse(doc)
        val located = locate(tree)
        // Курсор в разрыве после ключа проверяем первым: в `{ name | }`
        // диапазон `name` кончается до курсора, и без этого подсказки уехали бы
        // в родительский `providers`.
        val entry = gapEntry(located, doc, offset) ?: entryAt(located, doc, offset)


        if (entry != null) {
            val e = entry.entry
            if (atValuePosition(doc, offset, e)) {
                valuesFor(entry)?.let { return it }
            }
            if (atTypePosition(doc, offset, e)) return typeCompletions()
        }

        val container = containerAt(containersOf(tree), doc, offset) ?: return emptyList()
        return keysFor(container)
    }

    /** Что показать при наведении. */
    fun hover(doc: CrenDocument, offset: Int): CrenHover? {
        val tree = CrenParser.parse(doc)
        val all = locate(tree)
        val entry = entryAt(all, doc, offset) ?: return null
        val e = entry.entry
        val key = e.keyText

        if (key == "when" && e.value is CrenDict) {
            return CrenHover(
                e.keyRange,
                "Условия, при которых провайдер работает. " +
                    "Пустое `when` — «всегда», несколько условий соединяются по И.",
            )
        }

        ConfigSchema.childrenOf(entry.path.dropLast(1))
            .firstOrNull { it.name == key }
            ?.let { return CrenHover(e.keyRange, describe(it)) }

        val dot = key.indexOf('.')
        if (dot > 0) {
            val root = WorldRoot.bySegment(key.substring(0, dot))
            if (root != null) {
                val fieldName = key.substring(dot + 1)
                val text = WhenSchema.docFor(root, fieldName)
                if (text != null) return CrenHover(e.keyRange, "**${root.segment}.$fieldName**\n\n$text")
                val fields = WhenSchema.fieldsOf(root)
                return CrenHover(
                    e.keyRange,
                    "**${root.segment}.$fieldName**\n\nТакого поля нет. Доступны: ${fields.joinToString(", ")}",
                )
            }
        }
        if (dot < 0) {
            val root = WorldRoot.bySegment(key)
            if (root != null) {
                return CrenHover(e.keyRange, rootDoc(root))
            }
        }
        return null
    }

    /** Текст документации поля для всплывающей подсказки. */
    fun describe(f: Field): String = buildString {
        append("`").append(f.name).append("` — ").append(f.type.ruName())
        if (f.required) append(" · **обязательный**")
        f.def?.let { append(" · по умолчанию `").append(it).append("`") }
        f.deprecated?.let { append("\n\n**Устарело.** Вместо этого: ").append(it) }
        if (f.doc.isNotEmpty()) append("\n\n").append(f.doc)
        if (f.allowed.isNotEmpty()) {
            append("\n\nДопустимо: ").append(f.allowed.joinToString(", ") { "`$it`" })
        }
    }

    /** Человеческое описание корня условия. */
    fun rootDoc(root: WorldRoot): String {
        val fields = WhenSchema.fieldsOf(root)
        val sb = StringBuilder("Корень условия `${root.segment}`.")
        if (fields.isNotEmpty()) sb.append("\n\nПоля: ").append(fields.joinToString(", ") { "`$it`" })
        WhenSchema.ALIASES[root]?.takeIf { it.isNotEmpty() }?.let { aliases ->
            sb.append("\n\nКороткая запись: ")
                .append(aliases.entries.joinToString(", ") { (k, v) -> "`$k` → `$v`" })
        }
        return sb.toString()
    }

    // ------------------------------------------------- где находится курсор

    /**
     * Самая узкая запись, накрывающая курсор.
     *
     * Именно самая узкая, а не последняя по порядку: у вложенных ключей
     * диапазоны вложены друг в друга, и «последняя» может оказаться внешней.
     */
    private fun entryAt(
        located: List<LocatedEntry>,
        doc: CrenDocument,
        offset: Int,
    ): LocatedEntry? = located
        .filter { doc.contains(it.entry.range, offset) }
        .minByOrNull { doc.lengthOf(it.entry.range) }

    /**
     * Запись, сразу после ключа которой стоит курсор.
     *
     * Между концом ключа и курсором не должно быть ничего, кроме пробелов: иначе
     * это уже написанное значение или начатый следующий ключ, а не позиция «что
     * писать дальше». Отсюда разница между `{ name | }`, где дальше тип, и
     * `{ name = "a", ty| }`, где дальше ключ, хотя курсор в обоих случаях вне
     * диапазона `name`.
     *
     * [LocatedEntry] идут в порядке документа, поэтому последняя подходящая
     * запись — самая глубокая из них.
     */
    private fun gapEntry(
        located: List<LocatedEntry>,
        doc: CrenDocument,
        offset: Int,
    ): LocatedEntry? {
        val limit = offset.coerceAtMost(doc.text.length)
        return located
            .filter { doc.offsetOf(it.entry.keyRange.end) <= limit }
            .lastOrNull { entry ->
                val between = doc.text.substring(doc.offsetOf(entry.entry.keyRange.end), limit)
                if (between.all { it.isWhitespace() }) return@lastOrNull true
                // `enabled = |`: разделитель уже стоит, а значения ещё нет.
                // Без этой ветки запись не находится вовсе, и подсказки уезжают
                // в соседние ключи блока — то есть предлагают вставить ключ
                // туда, где человек пишет значение.
                if (entry.entry.value != null) return@lastOrNull false
                // Запятая и перевод строки означают, что запись закончилась и
                // курсор стоит уже после неё: там нужны ключи контейнера.
                if (between.contains(',') || between.contains('\n')) return@lastOrNull false
                when (between.trim()) {
                    "=", ":" -> true
                    else -> false
                }
            }
    }

    /** Самый глубокий контейнер, накрывающий курсор. */
    private fun containerAt(
        containers: List<LocatedContainer>,
        doc: CrenDocument,
        offset: Int,
    ): LocatedContainer? {
        var best: LocatedContainer? = null
        var bestDepth = -1
        for (c in containers) {
            if (!doc.contains(c.range, offset)) continue
            val d = depthOf(c)
            if (d > bestDepth) {
                best = c
                bestDepth = d
            }
        }
        // Курсор вне дерева: подойдёт верхний уровень, но только если
        // файл вообще пустой или позиция совсем за его концом.
        return best ?: containers.firstOrNull()
    }

    private fun depthOf(c: LocatedContainer): Int {
        var n = 0
        var cur: LocatedContainer? = c
        while (cur != null) {
            n += 1
            cur = cur.parent
        }
        return n
    }

    /**
     * Стоит ли курсор в позиции значения.
     *
     * Проверяем по разделителю между ключом и курсором, а не по наличию
     * значения: в `name = ` значения ещё нет, а подсказать его всё равно
     * нужно.
     *
     * Курсор должен лежать внутри самой записи. Запись `name` в
     * `{ name = "a", | }` кончается запятой, и её значение тут ни при чём.
     */
    private fun atValuePosition(doc: CrenDocument, offset: Int, e: CrenEntry): Boolean {
        val from = doc.offsetOf(e.keyRange.end)
        if (from > offset) return false
        val between = doc.text.substring(from, offset)
        val v = e.value
        if (v == null) {
            // Значения ещё нет, и запись на этом обрывается. Проверка
            // «внутри диапазона записи» здесь не годится: у записи без
            // значения диапазон кончается на ключе, и `type = |` уезжал в
            // подсказки соседних ключей — то есть предлагал вставить ключ
            // туда, где человек пишет значение.
            //
            // Запятая и перевод строки означают, что запись уже закончилась и
            // курсор стоит между записями, а не внутри значения: там нужны
            // ключи.
            if (between.contains(',') || between.contains('\n')) return false
            return between.contains('=') || between.contains(':')
        }
        if (!doc.contains(e.range, offset)) return false
        if (between.contains('=') || between.contains(':')) return true
        return doc.offsetOf(v.range.start) <= offset
    }

    /**
     * Стоит ли курсор в разрыве между ключом и следующим токеном.
     *
     * Это позиция «ключ написан, что дальше?»: в `.kn` там либо разделитель
     * (`=`, `:`), либо явный тип (`name str = ...`), и подсказывать надо типы.
     *
     * Разрыв ищется **по символам между ключом и курсором**, и в нём не должно
     * быть ничего, кроме пробелов. Пробелов между `name` и `=` может быть
     * сколько угодно, а вот начатый следующий ключ или уже написанное
     * значение превращают позицию в не-типовою: в `{ name = "a", ty| }`
     * предлагаются ключи, а не типы.
     *
     * Курсор на самом ключе (в том числе на его первом символе) — это ещё
     * печатается имя, а не позиция после него: `{ |name` должен предлагать
     * ключи, а не типы.
     */
    private fun atTypePosition(doc: CrenDocument, offset: Int, e: CrenEntry): Boolean {
        val keyEnd = doc.offsetOf(e.keyRange.end)
        if (offset < keyEnd) return false
        val gap = doc.text.substring(keyEnd, offset.coerceAtMost(doc.text.length))
        if (gap.any { it == '=' || it == ':' }) return false
        return gap.all { it.isWhitespace() }
    }

    // ------------------------------------------------- содержимое подсказок

    /** Ключи, которые можно написать в этом контейнере. */
    private fun keysFor(c: LocatedContainer): List<CrenCompletion> {
        if (c.kind == ContainerKind.ARRAY) return emptyList()
        if (c.path.lastOrNull() == "when") return whenRootCompletions()

        val known = ConfigSchema.childrenOf(c.path)
        if (known.isEmpty()) return emptyList()
        val used = c.entries.map { it.keyText }.toSet()
        val type = c.entries.firstOrNull { it.keyText == "type" }?.let { leafText(it) }

        return known
            .filter { it.offeredFor(type) }
            .filter { it.name !in used }
            .map { f ->
                CrenCompletion(
                    label = f.name,
                    detail = f.doc.substringBefore('.').ifEmpty { f.type.ruName() },
                    insertText = f.name,
                    snippet = snippetFor(f),
                    kind = CompletionKind.KEY,
                    // Обязательные вперёд: без них файл не заработает.
                    sortText = if (f.required) "0" + f.name else "1" + f.name,
                )
            }
    }

    /** Что вставить после ключа, чтобы запись была готова. */
    private fun snippetFor(f: Field): String? = when (f.type) {
        SchemaType.STR -> ": \"\""
        SchemaType.INT -> f.def?.let { ": $it" } ?: ": 0"
        SchemaType.FLOAT -> f.def?.let { ": $it" } ?: ": 0.0"
        SchemaType.BOOL -> ": true"
        SchemaType.DICT, SchemaType.BLOCK -> " {\n\t$0\n}"
        SchemaType.ARRAY -> " [\n\t$0\n]"
        else -> null
    }

    /** Подсказки значений для конкретного ключа. */
    private fun valuesFor(owner: LocatedEntry): List<CrenCompletion>? {
        val e = owner.entry
        val key = e.keyText

        // `when` — это словарь, а не значение: курсор внутри его скобок должен
        // получать корни условий, а не пустой список. `null` вместо `emptyList`
        // заставляет complete() спуститься к ключам контейнера.
        if (key == "when") return null
        if (owner.container.path.lastOrNull() == "when") return whenValueCompletions(key)

        val field = ConfigSchema.childrenOf(owner.path.dropLast(1))
            .firstOrNull { it.name == key }
            ?: return null
        if (field.allowed.isNotEmpty()) {
            return field.allowed.map {
                CrenCompletion(
                    label = it,
                    insertText = "\"$it\"",
                    kind = CompletionKind.VALUE,
                )
            }
        }
        // Перечисление есть не у всех полей, но у булева — по сути всегда.
        // Без этого `enabled = |` предлагал соседние ключи блока: полезного
        // там ничего, а `true`/`false` — ровно то, что нужно дописать.
        if (field.type == SchemaType.BOOL) {
            return listOf("true", "false").map {
                CrenCompletion(
                    label = it,
                    detail = "флажок, без кавычек",
                    insertText = it,
                    kind = CompletionKind.VALUE,
                )
            }
        }
        // Остальные типы перечислить нельзя: целое, дробное и строка — это
        // бесконечное множество. `null`, а не пустой список: список уводил бы
        // на ключи контейнера, но хотя бы не врал, что значений нет.
        return null
    }

    private fun typeCompletions(): List<CrenCompletion> = KNOWN_TYPE_WORDS.map {
        CrenCompletion(
            label = it,
            detail = typeWordDoc(it),
            insertText = it,
            kind = CompletionKind.TYPE,
        )
    }

    private fun whenRootCompletions(): List<CrenCompletion> = WhenSchema.roots().map { root ->
        CrenCompletion(
            label = root.segment,
            detail = "условие ${root.segment}: ${WhenSchema.fieldsOf(root).joinToString(", ")}",
            insertText = root.name,
            snippet = " {\n\t$0\n}",
            kind = CompletionKind.WHEN_ROOT,
        )
    }

    /** Значения условия: синонимы корня и операторы сравнения. */
    private fun whenValueCompletions(key: String): List<CrenCompletion> {
        val dot = key.indexOf('.')
        val rootName = if (dot < 0) key else key.substring(0, dot)
        val root = WorldRoot.bySegment(rootName) ?: return emptyList()
        val out = ArrayList<CrenCompletion>()
        for (a in WhenSchema.aliasesOf(root)) {
            out += CrenCompletion(
                label = a,
                detail = "короткая запись",
                insertText = "\"$a\"",
                kind = CompletionKind.VALUE,
                sortText = "0$a",
            )
        }
        for (op in WhenSchema.OPERATORS) {
            out += CrenCompletion(
                label = "$op…",
                detail = operatorDoc(op),
                insertText = "\"$op\"",
                kind = CompletionKind.OPERATOR,
                sortText = "2$op",
            )
        }
        return out
    }

    private fun operatorDoc(op: String): String = when (op) {
        "=" -> "равно"
        "!" -> "не равно"
        else -> "сравнение числа"
    }

    private fun typeWordDoc(word: String): String = when (word) {
        "str" -> "строка"
        "int" -> "целое число"
        "float" -> "дробное число, целое тоже подойдёт"
        "bool" -> "true или false"
        "dict" -> "словарь в фигурных скобках"
        "array" -> "массив в квадратных скобках"
        "block" -> "именованный блок"
        "ref" -> "ссылка на другой ключ"
        else -> ""
    }

    // ------------------------------------------------------ вспомогательное

    private fun leafText(e: CrenEntry): String? = (e.value as? CrenLeaf)?.lexeme?.value

    private fun firstWord(text: String): String = text.substringBefore(' ').substringBefore(',')

    private fun actualTypeOf(v: CrenValue?): SchemaType? = when (v) {
        null -> null
        is CrenDict -> SchemaType.DICT
        is CrenArray -> SchemaType.ARRAY
        is CrenRef -> SchemaType.REF
        is CrenLeaf -> {
            val t = v.lexeme.value
            when {
                v.lexeme.kind == CrenLexKind.STR -> SchemaType.STR
                t == "true" || t == "false" -> SchemaType.BOOL
                t.toIntOrNull() != null -> SchemaType.INT
                t.toFloatOrNull() != null -> SchemaType.FLOAT
                else -> null
            }
        }
    }

    private fun typeMatches(declared: String, actual: SchemaType): Boolean = when (declared) {
        "str" -> actual == SchemaType.STR
        "int" -> actual == SchemaType.INT
        "float" -> actual == SchemaType.FLOAT || actual == SchemaType.INT
        "bool" -> actual == SchemaType.BOOL
        "dict" -> actual == SchemaType.DICT
        "array" -> actual == SchemaType.ARRAY
        "block" -> actual == SchemaType.DICT
        "ref" -> actual == SchemaType.REF
        else -> true
    }

    private fun SchemaType.ruName(): String = when (this) {
        SchemaType.STR -> "строка"
        SchemaType.INT -> "целое число"
        SchemaType.FLOAT -> "дробное число"
        SchemaType.BOOL -> "true или false"
        SchemaType.DICT -> "словарь"
        SchemaType.ARRAY -> "массив"
        SchemaType.BLOCK -> "блок"
        SchemaType.REF -> "ссылка"
        SchemaType.ANY -> "любое значение"
    }

    /** Формы записи, в которых ключ уже отделён от значения. */
    private val VALUE_FORMS = setOf(EntryForm.ASSIGN, EntryForm.COLON)
}
