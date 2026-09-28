package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.ide.CrenAnalyzer
import dev.ggtv.capecraft.ide.CrenDiagnostic
import dev.ggtv.capecraft.ide.CrenDocument
import dev.ggtv.capecraft.ide.CrenParser
import dev.ggtv.capecraft.ide.PositionEncoding
import dev.ggtv.capecraft.schema.J
import java.io.InputStream
import java.io.OutputStream

/**
 * Сервер LSP для конфигов CapeCraft: диагностика, подсказки, наведение.
 *
 * Намеренно ничего не кэширует между запросами кроме текста документов.
 * Конфиг — это десятки строк, разбор дерётся на ленивых полях
 * [CrenDocument], а лишний кэш диагностик означал бы второй источник правды:
 * Analysis stale — претензия, которую нечем предъявить.
 *
 * ## Про то, что сервер не должен падать
 *
 * Любой разбор недоверенного текста имеет право бросить: нелегальный
 * escape, битый UTF-8, чужой протокол в том же канале. Клиент после этого
 * теряет подсказки и диагностику до перезапуска и не говорит почему. Поэтому
 * каждый запрос обёрнут: ошибка превращается в пустой ответ, а в stderr
 * уходит строка с причиной. Исключение наружу выпускается только в
 * [onShutdown] — там клиент всё равно закрывается.
 */
class LspServer(
    input: InputStream,
    output: OutputStream,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private val transport = RpcTransport(input, output)

    /** Тексты открытых документов: uri -> текст. */
    private val docs = LinkedHashMap<String, String>()

    /**
     * Кодировка колонок, о которой договорились в `initialize`.
     *
     * Хранится в сессии, а не в документе: договор один на всё соединение, и
     * он известен только после `initialize`. До него — UTF-16, как велит
     * спецификация по умолчанию.
     */
    private var encoding: PositionEncoding = PositionEncoding.UTF16

    /**
     * Иерархические символы или плоский список — договор из `initialize`.
     *
     * Как и кодировка, это свойство соединения, а не запроса: клиент шлёт
     * capability один раз, и в параметрах `documentSymbol` её уже нет.
     * Значение по умолчанию — плоский список, потому что `SymbolInformation`
     * понимает любой клиент, а `DocumentSymbol` не понимает никто из старых.
     */
    private var hierarchicalSymbols = false

    private var shutdownRequested = false

    /**
     * Код выхода. Ноль по умолчанию: клиент, закрывший канал, поступил
     * вежливо — именно так заканчивает сессию редактор, и ненулевой код он
     * показывает в своём логе как ошибку сервера. Единица означает только
     * одно: клиент прислал `exit` без `shutdown`.
     */
    private var exitCode = 0

    /** `exit` обязан завершить процесс, а не просто пометить код. */
    private var exitRequested = false

    /**
     * Главный цикл. Возвращает код завершения процесса.
     *
     * Клиент закрывает stdin — это `null` из [RpcTransport.read] и штатный
     * выход, а не падение.
     */
    fun run(): Int {
        while (true) {
            val message = try {
                transport.read() ?: break
            } catch (e: Exception) {
                // Кадр не прочитан — дальше идти некуда: поток рассинхронизирован.
                log("не удалось прочитать кадр: ${e.message}")
                return 1
            }
            try {
                handle(message)
                // Возвращаемся сразу: после `exit` клиент закрывает канал, но
                // ждать этого в цикле нельзя — он зависнет на чтении, а
                // процесс должен был умереть по команде.
                if (exitRequested) return exitCode
            } catch (e: Exception) {
                // Запрос не выполнен, но кадр цел — цикл продолжается, клиент
                // теряет один ответ, а не всю сессию.
                log("метод ${methodOf(message)} не выполнен: $e")
                replyError(message, text = e.message ?: e::class.java.simpleName)
            }
        }
        return exitCode
    }

    private fun methodOf(m: J.JObj): String = (m["method"] as? J.JStr)?.s ?: "?"

    private fun field(m: J.JObj, name: String): J? = m[name]

    private fun handle(message: J.JObj) {
        val method = methodOf(message)
        when (method) {
            "initialize" -> onInitialize(message)
            // `initialized` — уведомление без ответа, отдельного обработчика
            // не требует: сервер и так готов работать сразу после initialize.
            "initialized" -> Unit
            "shutdown" -> onShutdown(message)
            "exit" -> {
                // Без shutdown — код 1: по спецификации клиент, убивший сервер
                // раньше, ошибку считает своей, а не наоборот.
                exitCode = if (shutdownRequested) 0 else 1
                exitRequested = true
            }

            "textDocument/didOpen" -> onDidOpen(message)
            "textDocument/didChange" -> onDidChange(message)
            "textDocument/didSave" -> onDidSave(message)
            "textDocument/didClose" -> onDidClose(message)

            "textDocument/completion" -> onCompletion(message)
            "textDocument/hover" -> onHover(message)
            "textDocument/codeAction" -> onCodeAction(message)
            "textDocument/documentSymbol" -> onDocumentSymbol(message)
            "textDocument/foldingRange" -> onFoldingRange(message)
            "textDocument/semanticTokens/full" -> onSemanticTokens(message)
            "textDocument/diagnostic" -> onPullDiagnostics(message)

            // Методы, на которые сервер не подписан, должны давать MethodNotFound.
            // Молчаливый ответ на неизвестное ломает отладку не хуже краша, но
            // незаметно.
            else -> replyError(message, -32601, "метод не поддерживается: $method")
        }
    }

    // --- жизненный цикл -----------------------------------------------------

    private fun onInitialize(message: J.JObj) {
        val id = field(message, "id")
        encoding = negotiateEncoding(message)
        hierarchicalSymbols = negotiateHierarchicalSymbols(message)
        tokenLegend = negotiateTokenLegend(message)
        val capabilities = mutableListOf<Pair<String, J>>(
            // Полная синхронизация: конфиг меняют вручную и целиком, а
            // инкрементальные правки для него дороже, чем разбор файла в 200
            // строк.
            "textDocumentSync" to J.JNum(1.0, true, 1L),
            // Объявленный формат, а не тот, что выбрал клиент: сервер обязан
            // назвать кодировку, в которой он действительно считает колонки.
            // Иначе клиент, выбравший utf-8, получил бы наши utf-16 координаты
            // и подсветил бы не то место.
            "positionEncoding" to J.JStr(encoding.protocolName),
            "completionProvider" to J.JObj(
                listOf(
                    "resolveProvider" to J.JBool(false),
                    // Свой триггер: после `=` нужен список значений, а пробелом
                    // там ключ, а не значение. Словарь клиента по этому не
                    // отличить, и подсказки прыгали бы.
                    "triggerCharacters" to J.JArr(
                        listOf(
                            J.JStr("="),
                            J.JStr("{"),
                            J.JStr("["),
                            J.JStr(" "),
                            J.JStr("$"),
                            J.JStr("."),
                        ),
                    ),
                ),
            ),
            "hoverProvider" to J.JBool(true),
            // Правки уже есть в диагностиках (см. CrenFix), клиент их только
            // показывает — true вместо списка видов, потому что других не
            // планируется.
            "codeActionProvider" to J.JBool(true),
            // Outline: без него в редакторе нечем обойти конфиг, кроме как
            // поиском по тексту.
            "documentSymbolProvider" to J.JBool(true),
            "foldingRangeProvider" to J.JBool(true),
            // Pull-диагностика. Push тоже шлём: клиенты без pull (старые версии
            // neovim, часть плагинов) не спросят, а лишний повторный ответ им
            // не мешает.
            "diagnosticProvider" to J.JObj(
                listOf(
                    "identifier" to J.JStr("capecraft"),
                    "interFileDependencies" to J.JBool(false),
                    "workspaceDiagnostics" to J.JBool(false),
                ),
            ),
        )
        // Подсветка. Словарь ключей живёт тут, а не в редакторе, поэтому второго
        // списка ключей, который разошёлся бы с игрой, просто не существует.
        //
        // Объявляем только если клиент назвал хотя бы один наш вид слов:
        // токен, которого нет в легенде клиента, редактор не умеет красить, а
        // молчащая подсветка хуже её отсутствия.
        //
        // `range = false`: диапазонный запрос — ускорение на больших файлах, а
        // конфиг помещается в один экран и разбирается целиком быстрее, чем
        // клиент успевает спросить.
        tokenLegend?.let { legend ->
            capabilities += "semanticTokensProvider" to J.JObj(
                listOf(
                    "legend" to J.JObj(
                        listOf(
                            "tokenTypes" to J.JArr(legend.types.map { J.JStr(it) }),
                            "tokenModifiers" to J.JArr(legend.modifiers.map { J.JStr(it) }),
                        ),
                    ),
                    "full" to J.JBool(true),
                    "range" to J.JBool(false),
                ),
            )
        }
        reply(
            id,
            J.JObj(
                listOf(
                    "capabilities" to J.JObj(capabilities),
                    "serverInfo" to J.JObj(
                        listOf(
                            "name" to J.JStr("capecraft-lsp"),
                            "version" to J.JStr(version),
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * Кодировка колонок по предложению клиента.
     *
     * Список `general.positionEncodings` — предпочтения в порядке убывания, и
     * сервер берёт первую свою. Поддерживаем все три, поэтому выбор выпадает
     * на первый узнаваемый; незнакомые (клиент может придумать свой) пропускаем,
     * а когда не узнан ни один — UTF-16 по умолчанию из спецификации.
     */
    private fun negotiateEncoding(message: J.JObj): PositionEncoding {
        val params = field(message, "params") as? J.JObj ?: return PositionEncoding.UTF16
        val general = params["capabilities"]?.let { it as? J.JObj }?.get("general") as? J.JObj
        val offered = (general?.get("positionEncodings") as? J.JArr)?.items.orEmpty()
        for (item in offered) {
            val name = (item as? J.JStr)?.s ?: continue
            PositionEncoding.of(name)?.let { return it }
        }
        return PositionEncoding.UTF16
    }

    /**
     * Умеет ли клиент иерархию символов.
     *
     * Capability приходит один раз в `initialize` — в параметрах самого
     * `textDocument/documentSymbol` её нет. Читать её оттуда нельзя: сервер
     * решит, что клиент иерархию поддерживает, отдаст `DocumentSymbol` с
     * `children`, а старый клиент такой ответ не поймёт и покажет пустой
     * outline.
     *
     * Значение по умолчанию — `false`: клиент, который ничего не объявил,
     * может не знать про `DocumentSymbol` вовсе, а `SymbolInformation`
     * понимают все.
     */
    private fun negotiateHierarchicalSymbols(message: J.JObj): Boolean {
        val params = field(message, "params") as? J.JObj ?: return false
        val capabilities = params["capabilities"] as? J.JObj ?: return false
        val textDocument = capabilities["textDocument"] as? J.JObj ?: return false
        val documentSymbol = textDocument["documentSymbol"] as? J.JObj ?: return false
        return (documentSymbol["hierarchicalDocumentSymbolSupport"] as? J.JBool)?.b ?: false
    }

    /**
     * Легенда подсветки, о которой договорились в `initialize`.
     *
     * `null` — клиент подсветку не просил или не знает ни одного из наших видов
     * слов. Тогда сервер и не объявляет `semanticTokensProvider`: лучше нет
     * подсветки, чем клиент, который ждёт её и не получает.
     */
    private var tokenLegend: TokenLegend? = null

    /**
     * Виды слов, которые клиент готов принять.
     *
     * Читаем из `initialize`, а не из самого запроса: capability приходит один
     * раз за сессию, и её нет в параметрах `textDocument/semanticTokens/full`.
     * Свой список без проверки клиента — это токены с индексами, которых у
     * редактора нет, то есть подсветка вслепую.
     */
    private fun negotiateTokenLegend(message: J.JObj): TokenLegend? {
        val params = field(message, "params") as? J.JObj ?: return null
        val capabilities = params["capabilities"] as? J.JObj ?: return null
        val textDocument = capabilities["textDocument"] as? J.JObj ?: return null
        val semanticTokens = textDocument["semanticTokens"] as? J.JObj ?: return null
        val types = (semanticTokens["tokenTypes"] as? J.JArr)?.items ?: return null
        return semanticTokenLegend(types.mapNotNull { (it as? J.JStr)?.s })
    }

    private fun onShutdown(message: J.JObj) {
        shutdownRequested = true
        // Ошибок в shutdown не бывает: клиенту нужен любой ответ, иначе он
        // ждёт вечно и не завершит сессию.
        reply(field(message, "id"), J.JNull)
    }

    // --- документы ----------------------------------------------------------

    private fun onDidOpen(message: J.JObj) {
        val td = field(message, "params") as? J.JObj ?: return
        val item = td["textDocument"] as? J.JObj ?: return
        val uri = (item["uri"] as? J.JStr)?.s ?: return
        docs[uri] = (item["text"] as? J.JStr)?.s ?: ""
        publish(uri)
    }

    private fun onDidChange(message: J.JObj) {
        val td = field(message, "params") as? J.JObj ?: return
        val item = td["textDocument"] as? J.JObj ?: return
        val uri = (item["uri"] as? J.JStr)?.s ?: return
        val current = docs[uri] ?: return
        val changes = (td["contentChanges"] as? J.JArr)?.items ?: return

        // textDocumentSync = 1 (полный текст), поэтому в changes лежит ровно
        // один элемент с whole text. Но клиент — не мод: при включённой
        // инкрементальной синхронизации в его настройках (или у другого
        // сервера в том же канале) прилетит кусок с `range`. Взять его `text`
        // как весь документ — тихая порча: дальше все позиции едут, а
        // диагностика не показывает ничего. Поэтому куски применяем, а если
        // диапазон не сошёлся с текстом — оставляем документ как был.
        var text = current
        for (change in changes) {
            val obj = change as? J.JObj ?: return
            val replacement = (obj["text"] as? J.JStr)?.s ?: return
            val range = obj["range"] as? J.JObj
            if (range == null) {
                text = replacement
                continue
            }
            val from = range["start"] as? J.JObj ?: return
            val to = range["end"] as? J.JObj ?: return
            val startLine = (from["line"] as? J.JNum)?.i?.toInt() ?: return
            val startChar = (from["character"] as? J.JNum)?.i?.toInt() ?: return
            val endLine = (to["line"] as? J.JNum)?.i?.toInt() ?: return
            val endChar = (to["character"] as? J.JNum)?.i?.toInt() ?: return
            val patched = CrenDocument(text).replaceClientRange(
                startLine, startChar, endLine, endChar, replacement, encoding,
            )
            if (patched == null) {
                log("didChange с неверным range у ${uri.substringAfterLast('/')}: документ оставлен как был")
                return
            }
            text = patched
        }
        docs[uri] = text
        publish(uri)
    }

    private fun onDidSave(message: J.JObj) {
        val td = field(message, "params") as? J.JObj ?: return
        val uri = (td["textDocument"] as? J.JObj)?.get("uri")?.let { (it as? J.JStr)?.s } ?: return
        // Текст мог прийти в save, а мог и нет — не важно, файл уже открыт и
        // актуален после последнего didChange.
        if (docs.containsKey(uri)) publish(uri)
    }

    private fun onDidClose(message: J.JObj) {
        val td = field(message, "params") as? J.JObj ?: return
        val uri = (td["textDocument"] as? J.JObj)?.get("uri")?.let { (it as? J.JStr)?.s } ?: return
        docs.remove(uri)
        // Диагностики надо снять: иначе закрытый файл останется с ошибками
        // в панели проблем, пока не откроют его снова.
        clear(uri)
    }

    // --- запросы ------------------------------------------------------------

    private fun onCompletion(message: J.JObj) {
        val uri = uriOf(message) ?: return reply(field(message, "id"), J.JArr(emptyList()))
        val offset = offsetOf(message, uri) ?: return reply(field(message, "id"), J.JArr(emptyList()))
        val doc = document(uri) ?: return reply(field(message, "id"), J.JArr(emptyList()))

        val items = CrenAnalyzer.complete(doc, offset.coerceIn(0, doc.text.length))
            .map { Lsp.completionItem(doc, it) }
        reply(field(message, "id"), J.JArr(items))
    }

    private fun onHover(message: J.JObj) {
        val id = field(message, "id")
        val uri = uriOf(message)
        val doc = uri?.let { docs[it]?.let { t -> CrenDocument(t) } }
        val offset = uri?.let { offsetOf(message, it) }
        if (doc == null || offset == null) {
            reply(id, J.JNull)
            return
        }
        val hover = CrenAnalyzer.hover(doc, offset.coerceIn(0, doc.text.length))
        reply(id, if (hover == null) J.JNull else Lsp.hover(hover.text))
    }

    private fun onCodeAction(message: J.JObj) {
        val id = field(message, "id")
        fun noActions() = reply(id, J.JArr(emptyList()))
        val uri = uriOf(message)
        val text = uri?.let { docs[it] }
        val range = (field(message, "params") as? J.JObj)?.get("range") as? J.JObj
        if (uri == null || text == null || range == null) {
            noActions()
            return
        }
        val doc = CrenDocument(text)
        val from = clientPosition(doc, range, "start") ?: return noActions()
        val to = clientPosition(doc, range, "end") ?: return noActions()
        val actions = try {
            CrenAnalyzer.diagnostics(doc)
                .filter {
                    it.fixes.isNotEmpty() &&
                        doc.offsetOf(it.range.start) <= to && from <= doc.endOffsetOf(it.range)
                }
                .flatMap { Lsp.codeAction(doc, uri, it, encoding).items }
        } catch (e: Exception) {
            // Меню исправлений — необязательная роскошь: лучше пустое меню,
            // чем обрыв сессии из-за одной неразобранной строки.
            log("codeAction не удался: $e")
            emptyList()
        }
        reply(id, J.JArr(actions))
    }

    /**
     * Дерево символов для outline.
     *
     * Ответ всегда массив, даже для пустого файла: `null` клиенты не любят и
     * показывают ошибку вместо пустого outline. Разбор терпимый и бросить не
     * может, но на всякий случай ошибка превращается в пустой список — как и
     * во всех остальных запросах.
     */
    private fun onDocumentSymbol(message: J.JObj) {
        val id = field(message, "id")
        val uri = uriOf(message)
        val doc = uri?.let { document(it) }
        if (uri == null || doc == null) {
            reply(id, J.JArr(emptyList()))
            return
        }
        reply(
            id,
            try {
                Lsp.documentSymbol(doc, CrenParser.parse(doc).root, uri, encoding, flat = !hierarchicalSymbols)
            } catch (e: Exception) {
                log("documentSymbol не удался: $e")
                J.JArr(emptyList())
            },
        )
    }

    private fun onFoldingRange(message: J.JObj) {
        val id = field(message, "id")
        val doc = uriOf(message)?.let { document(it) }
        if (doc == null) {
            reply(id, J.JArr(emptyList()))
            return
        }
        reply(
            id,
            try {
                Lsp.foldingRange(doc, CrenParser.parse(doc).root, encoding)
            } catch (e: Exception) {
                log("foldingRange не удался: $e")
                J.JArr(emptyList())
            },
        )
    }

    /**
     * Подсветка целиком.
     *
     * Отдельный диапазонный запрос не ловим: клиент его не шлёт, потому что
     * мы объявили `range = false`, а неизвестный метод сервер честно отвергает
     * кодом MethodNotFound.
     */
    private fun onSemanticTokens(message: J.JObj) {
        val id = field(message, "id")
        val legend = tokenLegend
        val doc = uriOf(message)?.let { document(it) }
        if (legend == null || doc == null) {
            reply(id, J.JObj(listOf("data" to J.JArr(emptyList()))))
            return
        }
        reply(
            id,
            try {
                Lsp.semanticTokens(doc, legend, encoding)
            } catch (e: Exception) {
                log("semanticTokens не удался: $e")
                J.JObj(listOf("data" to J.JArr(emptyList())))
            },
        )
    }

    /**
     * Pull-диагностика: клиент спросил, мы пересчитали и отдали.
     *
     * Тот же [Lsp.analyze], что и для push, — два пути к одному разбору, а не
     * две реализации: разойтись им нечем, кроме как по счастливой случайности.
     */
    private fun onPullDiagnostics(message: J.JObj) {
        val id = field(message, "id")
        val doc = uriOf(message)?.let { document(it) }
        if (doc == null) {
            reply(id, Lsp.pullDiagnostics(CrenDocument(""), emptyList(), encoding))
            return
        }
        val diagnostics = try {
            Lsp.analyze(doc)
        } catch (e: Exception) {
            log("анализ для pull не удался: $e")
            emptyList<CrenDiagnostic>()
        }
        reply(id, Lsp.pullDiagnostics(doc, diagnostics, encoding))
    }

    /**
     * Смещение по `range.start` или `range.end` запроса.
     *
     * Клиент шлёт их по одной схеме, но прислать может что угодно: не число,
     * не объект, номер строки за пределами файла. Всё это — «не знаю, где
     * правка», и ответ на такое пустой список, а не исключение в цикл.
     *
     * Документ передаётся явно: в сессии открыто несколько файлов, и брать
     * «первый попавшийся» значило бы измерять позицию в чужом тексте.
     */
    private fun clientPosition(doc: CrenDocument, range: J.JObj, edge: String): Int? {
        val point = range[edge] as? J.JObj ?: return null
        val line = (point["line"] as? J.JNum)?.i ?: return null
        val character = (point["character"] as? J.JNum)?.i ?: return null
        return doc.offsetOfClientPosition(line.toInt(), character.toInt(), encoding)
    }

    // --- helpers ------------------------------------------------------------

    private fun document(uri: String): CrenDocument? = docs[uri]?.let { CrenDocument(it) }

    private fun uriOf(message: J.JObj): String? =
        (field(message, "params") as? J.JObj)
            ?.get("textDocument")
            ?.let { (it as? J.JObj)?.get("uri") }
            ?.let { (it as? J.JStr)?.s }

    /**
     * Позиция курсора в смещение по тексту.
     *
     * Перевод UTF-16/code point живёт в [CrenDocument.offsetOfClientPosition]:
     * правило одно на весь проект, иначе диагностика и подсказки начнут
     * считать колонки по-разному и уедут друг относительно друга.
     */
    private fun offsetOf(message: J.JObj, uri: String): Int? {
        val pos = (field(message, "params") as? J.JObj)?.get("position") as? J.JObj ?: return null
        val line = (pos["line"] as? J.JNum)?.i?.toInt() ?: return null
        val character = (pos["character"] as? J.JNum)?.i?.toInt() ?: return null
        val text = docs[uri] ?: return null
        return CrenDocument(text).offsetOfClientPosition(line, character, encoding)
    }

    private fun publish(uri: String) {
        val text = docs[uri] ?: return
        val doc = CrenDocument(text)
        val diagnostics = try {
            Lsp.analyze(doc)
        } catch (e: Exception) {
            // Анализатор на терпимом разборе бросать не должен, но если
            // выкинет — лучше пустой список ошибок, чем обрыв сессии.
            log("анализ ${uri.substringAfterLast('/')} не удался: $e")
            emptyList<CrenDiagnostic>()
        }
        notify(
            "textDocument/publishDiagnostics",
            J.JObj(
                listOf(
                    "uri" to J.JStr(uri),
                    "diagnostics" to Lsp.diagnostics(doc, diagnostics, encoding),
                ),
            ),
        )
    }

    private fun clear(uri: String) {
        notify(
            "textDocument/publishDiagnostics",
            J.JObj(listOf("uri" to J.JStr(uri), "diagnostics" to J.JArr(emptyList()))),
        )
    }

    private fun reply(id: J?, result: J) {
        if (id == null) return // уведомление, ответа не требуется
        transport.write(
            J.JObj(
                listOf(
                    "jsonrpc" to J.JStr("2.0"),
                    "id" to id,
                    "result" to result,
                ),
            ),
        )
    }

    private fun replyError(message: J.JObj, code: Long = -32603, text: String? = null) {
        val id = field(message, "id") ?: return
        val msg = text ?: field(message, "method")?.let { "внутренняя ошибка" } ?: "внутренняя ошибка"
        transport.write(
            J.JObj(
                listOf(
                    "jsonrpc" to J.JStr("2.0"),
                    "id" to id,
                    "error" to J.JObj(
                        listOf(
                            "code" to J.JNum(code.toDouble(), true, code),
                            "message" to J.JStr(msg),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun notify(method: String, params: J) {
        transport.write(
            J.JObj(
                listOf(
                    "jsonrpc" to J.JStr("2.0"),
                    "method" to J.JStr(method),
                    "params" to params,
                ),
            ),
        )
    }

    override fun close() {
        transport.close()
    }

    companion object {
        /** Версия сервера для `serverInfo`; не влияет на мод. */
        const val version = "1.1.2"
    }
}

private operator fun J.JObj.get(key: String): J? = fields.firstOrNull { it.first == key }?.second
