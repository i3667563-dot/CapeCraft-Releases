package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.ide.CompletionKind
import dev.ggtv.capecraft.ide.CrenAnalyzer
import dev.ggtv.capecraft.ide.CrenArray
import dev.ggtv.capecraft.ide.CrenDiagnostic
import dev.ggtv.capecraft.ide.CrenDict
import dev.ggtv.capecraft.ide.CrenDocument
import dev.ggtv.capecraft.ide.CrenEntry
import dev.ggtv.capecraft.ide.CrenLeaf
import dev.ggtv.capecraft.ide.CrenLexKind
import dev.ggtv.capecraft.ide.CrenRef
import dev.ggtv.capecraft.ide.CrenSeverity
import dev.ggtv.capecraft.ide.CrenValue
import dev.ggtv.capecraft.ide.EntryForm
import dev.ggtv.capecraft.ide.PositionEncoding
import dev.ggtv.capecraft.schema.J
import dev.ggtv.kjen.TextRange

/**
 * Перевод типов анализатора в типы протокола LSP.
 *
 * Отдельный слой нужен, чтобы `CrenAnalyzer` не знал про LSP: иначе редактор
 * протянет в мод-общий код, а мод начнёт зависеть от формата сообщений,
 * которого он никогда не видел. Здесь же живёт перевод позиций.
 */
object Lsp {
    /** Severity из LSP: 1 ошибка, 2 предупреждение, 3 подсказка, 4 информации. */
    private fun severity(s: CrenSeverity): Long = when (s) {
        CrenSeverity.ERROR -> 1
        CrenSeverity.WARNING -> 2
        CrenSeverity.HINT -> 3
    }

    /** CompletionItemKind: имена полей — 5, значений — 12, типов — 7. */
    private fun completionKind(k: CompletionKind): Long = when (k) {
        CompletionKind.KEY -> 5
        CompletionKind.VALUE -> 12
        CompletionKind.TYPE -> 7
        CompletionKind.WHEN_ROOT -> 17 // Keyword
        CompletionKind.OPERATOR -> 13 // Enum
    }

    fun position(line: Int, character: Int): J.JObj = J.JObj(
        listOf(
            "line" to J.JNum(line.toDouble(), true, line.toLong()),
            "character" to J.JNum(character.toDouble(), true, character.toLong()),
        ),
    )

    /**
     * Точка по смещению в тексте, в кодировке клиента.
     *
     * Колонку берёт [CrenDocument.clientColumnOf], а не `Span.col`: `Span`
     * считает кодпоинты, а клиент ждёт свои единицы (в UTF-16 эмодзи — это 2,
     * здесь 1).
     */
    fun point(
        doc: CrenDocument,
        offset: Int,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JObj {
        val line = doc.spanOf(offset).line - 1
        val character = doc.clientColumnOf(offset, encoding)
        return position(line, character)
    }

    fun range(
        doc: CrenDocument,
        r: TextRange,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JObj = J.JObj(
        listOf(
            "start" to point(doc, doc.offsetOf(r.start), encoding),
            "end" to point(doc, doc.offsetOf(r.end), encoding),
        ),
    )

    fun diagnostic(
        doc: CrenDocument,
        d: CrenDiagnostic,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JObj {
        val fields = mutableListOf<Pair<String, J>>(
            "range" to range(doc, d.range, encoding),
            "severity" to J.JNum(severity(d.severity).toDouble(), true, severity(d.severity)),
            "code" to J.JStr(d.code),
            "source" to J.JStr("capecraft"),
            "message" to J.JStr(d.message),
        )
        // «Исправить» дороже всего для клиента в коде, но экономит человеку
        // больше всего: без него правильное написание приходится вспоминать.
        if (d.fixes.isNotEmpty()) {
            fields += "data" to J.JObj(
                listOf(
                    "fixes" to J.JArr(
                        d.fixes.map { fix ->
                            J.JObj(
                                listOf(
                                    "title" to J.JStr(fix.title),
                                    "newText" to J.JStr(fix.newText),
                                    "range" to range(doc, fix.range, encoding),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        return J.JObj(fields)
    }

    fun completionItem(doc: CrenDocument, c: dev.ggtv.capecraft.ide.CrenCompletion): J.JObj {
        val fields = mutableListOf<Pair<String, J>>(
            "label" to J.JStr(c.label),
            "kind" to J.JNum(completionKind(c.kind).toDouble(), true, completionKind(c.kind)),
            "sortText" to J.JStr(c.sortText),
        )
        c.detail?.let { fields += "detail" to J.JStr(it) }
        // Сниппет важнее insertText: у корня `when` он достраивает фигурные
        // скобки и ставит курсор внутрь. insertText без скобок оставил бы
        // `biome: ` без тела — и ошибка была бы уже в конфиге, а не в списке
        // подсказок.
        val text = c.snippet ?: c.insertText
        fields += "insertText" to J.JStr(text)
        if (c.snippet != null) {
            // 2 = Snippet. Без этой метки клиент вставит `{\n\t$0\n}` как есть,
            // с долларом и номером, и файл станет невалидным.
            fields += "insertTextFormat" to J.JNum(2.0, true, 2L)
        }
        return J.JObj(fields)
    }

    /**
     * Быстрое исправление для диагностики.
     *
     * Правки лежат в самой диагностике (`CrenFix`), но у клиента их не видно:
     * поле `data` он не показывает. Поэтому `textDocument/codeAction` собирает
     * здесь действие с готовым TextEdit — редактору остаётся только показать
     * его в меню и применить.
     */
    fun codeAction(
        doc: CrenDocument,
        uri: String,
        d: CrenDiagnostic,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JArr {
        val actions = d.fixes.map { fix ->
            J.JObj(
                listOf(
                    "title" to J.JStr(fix.title),
                    // quickfix: «исправить это» в меню лампочки. Другой вид
                    // клиенту показывать нечего: действие одно и очевидное.
                    "kind" to J.JStr("quickfix"),
                    "diagnostics" to J.JArr(listOf(diagnostic(doc, d, encoding))),
                    "edit" to J.JObj(
                        listOf(
                            "changes" to J.JObj(
                                listOf(
                                    uri to J.JArr(
                                        listOf(
                                            J.JObj(
                                                listOf(
                                                    "range" to range(doc, fix.range, encoding),
                                                    "newText" to J.JStr(fix.newText),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        return J.JArr(actions)
    }

    fun diagnostics(
        doc: CrenDocument,
        list: List<CrenDiagnostic>,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JArr = J.JArr(list.map { diagnostic(doc, it, encoding) })

    fun hover(text: String): J.JObj = J.JObj(
        listOf(
            "contents" to J.JObj(
                listOf("kind" to J.JStr("markdown"), "value" to J.JStr(text)),
            ),
        ),
    )

    fun analyze(doc: CrenDocument): List<CrenDiagnostic> = CrenAnalyzer.diagnostics(doc)

    // --- outline, сворачивание, pull-диагностика ---------------------------------

    /** SymbolKind: Object 19, Array 18, String 15, Number 16, Boolean 17, Variable 13. */
    private fun symbolKindOf(v: CrenValue?): Long = when (v) {
        is CrenDict -> 19
        is CrenArray -> 18
        is CrenLeaf -> when {
            v.lexeme.text == "true" || v.lexeme.text == "false" -> 17
            v.lexeme.kind == CrenLexKind.STR -> 15
            else -> 16
        }
        is CrenRef -> 13
        null -> 19
    }

    /**
     * Дерево символов для outline.
     *
     * Иерархию строим по [CrenEntry.body], а не «как попалось»: у named-блока
     * (`server { ... }`) содержимое лежит в `children`, у словаря — в `value`.
     * Сплющивание в один список убрало бы из outline половину структуры.
     *
     * @param flat клиент не умеет `DocumentSymbol` — отдаём плоский
     *   `SymbolInformation`, как требует старая часть спецификафикации
     */
    fun documentSymbol(
        doc: CrenDocument,
        entries: List<CrenEntry>,
        uri: String,
        encoding: PositionEncoding = PositionEncoding.UTF16,
        flat: Boolean = false,
    ): J.JArr {
        if (!flat) {
            return J.JArr(entries.map { treeSymbol(doc, it, encoding) })
        }
        val out = mutableListOf<J>()
        for (e in entries) collectFlat(doc, e, uri, encoding, out)
        return J.JArr(out)
    }

    private fun treeSymbol(
        doc: CrenDocument,
        e: CrenEntry,
        encoding: PositionEncoding,
    ): J.JObj {
        val children = mutableListOf<J>()
        for (child in e.body) children += treeSymbol(doc, child, encoding)
        for (item in arrayItems(e.value)) children += valueSymbol(doc, item, encoding)
        val fields = mutableListOf<Pair<String, J>>(
            "name" to J.JStr(e.keyText),
            "kind" to J.JNum(symbolKindOf(e.value).toDouble(), true, symbolKindOf(e.value)),
            "range" to range(doc, e.range, encoding),
            "selectionRange" to range(doc, e.keyRange, encoding),
        )
        detail(e)?.let { fields += "detail" to J.JStr(it) }
        if (children.isNotEmpty()) fields += "children" to J.JArr(children)
        return J.JObj(fields)
    }

    private fun valueSymbol(
        doc: CrenDocument,
        v: CrenValue,
        encoding: PositionEncoding,
    ): J.JObj {
        val fields = mutableListOf<Pair<String, J>>(
            "name" to J.JStr(v.preview),
            "kind" to J.JNum(symbolKindOf(v).toDouble(), true, symbolKindOf(v)),
            "range" to range(doc, v.range, encoding),
            "selectionRange" to range(doc, v.range, encoding),
        )
        val children = mutableListOf<J>()
        when (v) {
            is CrenDict -> for (child in v.entries) children += treeSymbol(doc, child, encoding)
            is CrenArray -> for (item in v.items) children += valueSymbol(doc, item, encoding)
            else -> Unit
        }
        if (children.isNotEmpty()) fields += "children" to J.JArr(children)
        return J.JObj(fields)
    }

    /**
     * Плоский `SymbolInformation` — для клиентов без иерархии.
     *
     * Иерархии здесь нет, поэтому набор вложенных записей теряться не должен:
     * разворачиваем всё дерево в плоский список, иначе клиент увидит только
     * `capeCraft`. `location.uri` обязателен по протоколу, `selectionRange` у
     * `SymbolInformation` не существует — только `location`.
     */
    private fun collectFlat(
        doc: CrenDocument,
        e: CrenEntry,
        uri: String,
        encoding: PositionEncoding,
        out: MutableList<J>,
    ) {
        out += flatSymbol(doc, e, uri, encoding)
        for (child in e.body) collectFlat(doc, child, uri, encoding, out)
        for (item in arrayItems(e.value)) collectFlatValue(doc, item, uri, encoding, out)
    }

    private fun collectFlatValue(
        doc: CrenDocument,
        v: CrenValue,
        uri: String,
        encoding: PositionEncoding,
        out: MutableList<J>,
    ) {
        out += J.JObj(
            listOf(
                "name" to J.JStr(v.preview),
                "kind" to J.JNum(symbolKindOf(v).toDouble(), true, symbolKindOf(v)),
                "location" to J.JObj(
                    listOf(
                        "uri" to J.JStr(uri),
                        "range" to range(doc, v.range, encoding),
                    ),
                ),
            ),
        )
        when (v) {
            is CrenDict -> for (child in v.entries) collectFlat(doc, child, uri, encoding, out)
            is CrenArray -> for (item in v.items) collectFlatValue(doc, item, uri, encoding, out)
            else -> Unit
        }
    }

    private fun flatSymbol(
        doc: CrenDocument,
        e: CrenEntry,
        uri: String,
        encoding: PositionEncoding,
    ): J.JObj = J.JObj(
        listOf(
            "name" to J.JStr(e.keyText),
            "kind" to J.JNum(symbolKindOf(e.value).toDouble(), true, symbolKindOf(e.value)),
            "location" to J.JObj(
                listOf(
                    "uri" to J.JStr(uri),
                    "range" to range(doc, e.range, encoding),
                ),
            ),
        ),
    )

    /**
     * `detail` для символа: явный тип (`name str = ...`) или размер
     * структуры. Тип полезнее превью значения — по нему видно, что за блок,
     * не открывая его.
     */
    private fun detail(e: CrenEntry): String? = when {
        e.key.typeText != null -> e.key.typeText
        e.value is CrenArray -> "${e.value.items.size}"
        e.value is CrenDict -> "${e.value.entries.size}"
        else -> null
    }

    private fun arrayItems(v: CrenValue?): List<CrenValue> = (v as? CrenArray)?.items ?: emptyList()

    /**
     * Сворачиваемые диапазоны: каждый словарь, массив и named-блок, который
     * занял больше одной строки.
     *
     * Однострочные пропускаем намеренно: клиент всё равно не сможет их свернуть,
     * а лишние полоски в gutter только шумят.
     */
    fun foldingRange(
        doc: CrenDocument,
        entries: List<CrenEntry>,
        encoding: PositionEncoding = PositionEncoding.UTF16,
    ): J.JArr {
        val out = mutableListOf<J>()

        fun visit(offsetStart: Int, offsetEnd: Int) {
            val from = doc.spanOf(offsetStart)
            val to = doc.spanOf(offsetEnd)
            val startLine = from.line - 1
            val endLine = to.line - 1
            if (endLine > startLine) {
                out += J.JObj(
                    listOf(
                        "startLine" to J.JNum(startLine.toDouble(), true, startLine.toLong()),
                        "endLine" to J.JNum(endLine.toDouble(), true, endLine.toLong()),
                        "kind" to J.JStr("region"),
                    ),
                )
            }
        }

        fun walk(list: List<CrenEntry>) {
            for (e in list) {
                if (e.form == EntryForm.BLOCK) visit(doc.offsetOf(e.range.start), doc.offsetOf(e.range.end))
                when (val v = e.value) {
                    is CrenDict -> {
                        visit(doc.offsetOf(v.range.start), doc.offsetOf(v.range.end))
                        walk(v.entries)
                    }
                    is CrenArray -> {
                        visit(doc.offsetOf(v.range.start), doc.offsetOf(v.range.end))
                        for (item in v.items) if (item is CrenDict) walk(item.entries)
                    }
                    else -> {
                        if (e.form == EntryForm.BLOCK) walk(e.children)
                    }
                }
            }
        }

        walk(entries)
        return J.JArr(out)
    }

    /**
     * Pull-диагностика (`textDocument/diagnostic`).
     *
     * Ответ всегда полный: считать дельты нечем без кэша между запросами, а
     * кэш диагностик в этом сервере запрещён намеренно (см. [LspServer]) —
     * второй источник правды означает «анализ устарел», и предъявить это
     * некому.
     */
    fun pullDiagnostics(doc: CrenDocument, list: List<CrenDiagnostic>, encoding: PositionEncoding): J.JObj =
        J.JObj(
            listOf(
                "kind" to J.JStr("full"),
                "items" to diagnostics(doc, list, encoding),
            ),
        )
}
