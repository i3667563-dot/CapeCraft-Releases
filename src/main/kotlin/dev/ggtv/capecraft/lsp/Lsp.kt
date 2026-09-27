package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.ide.CompletionKind
import dev.ggtv.capecraft.ide.CrenAnalyzer
import dev.ggtv.capecraft.ide.CrenDiagnostic
import dev.ggtv.capecraft.ide.CrenDocument
import dev.ggtv.capecraft.ide.CrenSeverity
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

    fun range(doc: CrenDocument, r: TextRange): J.JObj {
        val from = doc.spanOf(doc.offsetOf(r.start))
        val to = doc.spanOf(doc.offsetOf(r.end))
        return J.JObj(
            listOf(
                "start" to position(from.line - 1, from.col - 1),
                "end" to position(to.line - 1, to.col - 1),
            ),
        )
    }

    fun diagnostic(doc: CrenDocument, d: CrenDiagnostic): J.JObj {
        val fields = mutableListOf<Pair<String, J>>(
            "range" to range(doc, d.range),
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
                                    "range" to range(doc, fix.range),
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
    fun codeAction(doc: CrenDocument, uri: String, d: CrenDiagnostic): J.JArr {
        val actions = d.fixes.map { fix ->
            J.JObj(
                listOf(
                    "title" to J.JStr(fix.title),
                    // quickfix: «исправить это» в меню лампочки. Другой вид
                    // клиенту показывать нечего: действие одно и очевидное.
                    "kind" to J.JStr("quickfix"),
                    "diagnostics" to J.JArr(listOf(diagnostic(doc, d))),
                    "edit" to J.JObj(
                        listOf(
                            "changes" to J.JObj(
                                listOf(
                                    uri to J.JArr(
                                        listOf(
                                            J.JObj(
                                                listOf(
                                                    "range" to range(doc, fix.range),
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

    fun diagnostics(doc: CrenDocument, list: List<CrenDiagnostic>): J.JArr =
        J.JArr(list.map { diagnostic(doc, it) })

    fun hover(text: String): J.JObj = J.JObj(
        listOf(
            "contents" to J.JObj(
                listOf("kind" to J.JStr("markdown"), "value" to J.JStr(text)),
            ),
        ),
    )

    fun analyze(doc: CrenDocument): List<CrenDiagnostic> = CrenAnalyzer.diagnostics(doc)
}
