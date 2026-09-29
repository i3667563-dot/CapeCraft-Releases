# capecraft-lsp

> **Статус: бета (0.1-beta).** Релизы — в GitHub-релизах репозитория
> `CapeCraft-Releases` (теги `lsp-v*`), обновления частые; как поставить —
> см. «Релиз».

Language Server для `.kn`/`.crn` — конфигов CapeCraft на языке
[KoreN](https://github.com/i3667563-dot/koren) (Minecraft-aware `.kn`) поверх
[Kjen](https://github.com/i3667563-dot/kjen) — Kotlin-порта формата Cren.

Сервер написан на Kotlin, общается по stdio, запускается одним `java -jar` и
не зависит ни от Minecraft, ни от Fabric: в jar нет ни одного игрового класса.
Ему достаточно разбирать конфиг.

## Что он умеет

| Метод | Что делает |
|---|---|
| `initialize` | объявляет capabilities, запоминает `positionEncoding` |
| `initialized` | принимается без ответа |
| `shutdown` / `exit` | корректное завершение; `exit` без `shutdown` — код 1, как требует спецификация |
| `textDocument/didOpen` / `didChange` / `didSave` / `didClose` | синхронизация документа |
| `textDocument/completion` | ключи, значения, типы; у корня `when` и блоков — сниппет с `insertTextFormat = 2` |
| `textDocument/hover` | документация ключа из схемы вместе с дефолтом |
| `textDocument/codeAction` | превращает `fixes` из диагностики в `TextEdit` |
| `textDocument/documentSymbol` | дерево `capeCraft → блоки → ключи`; для клиентов без иерархии — плоский список |
| `textDocument/foldingRange` | сворачивание блоков и списков |
| `textDocument/semanticTokens/full` | подсветка ключей, строк, чисел, комментариев, типов и подстановок `${...}` |
| `textDocument/publishDiagnostics` | рассылается сама при изменении документа |
| `textDocument/diagnostic` | то же по запросу клиента (pull-модель) |

Метод, на который сервер не подписан, возвращает `MethodNotFound`, а не
молчаливый ответ: иначе поломку в отладке не отличить от «всё работает».

## Сборка

```bash
./gradlew lspJar        # -> artifacts/capecraft-lsp.jar
```

Собирается JDK 21 (байт-код 65). Запускается на JRE 21+.

Внутри — ровно три зависимости: `kotlin-stdlib`, `slf4j-api` и
`slf4j-simple`. Простой биндинг нужен, чтобы лог ушёл в stderr: stdout
принадлежит протоколу, и запись в него ломает разбор кадров.

Имя jar'а не содержит версию — путь к серверу живёт в конфиге редактора, и
версия в имени заставила бы править его на каждом релизе.

## Релиз

LSP-сервер — отдельный артефакт в этом же репозитории, со **своей** версией
(`lsp_version` в `gradle.properties`), независимой от `mod_version`. Код
остаётся в дереве мода намеренно: сервер строит только поверх того же
анализатора, что и игра (см. «Единый источник правды» ниже). Привязка кода к
репозиторию не привязывает релиз к релизу мода — они выпускаются раздельно.

Релизы публикуются **только на публичный репозиторий `CapeCraft-Releases`**
(remote `releases`), а не на приватный `CapeCraft` — тот полигон для тестов.
`lsp-release.yml` создаёт релиз лишь когда workflow исполняется в
`CapeCraft-Releases`; тег в приватном репозитории ничего не выпустит.

Процедура:

1. Поднять `lsp_version` в `gradle.properties` (например `0.1-beta` → `0.1.1-beta`).
2. Написать заметки релиза в `release-notes/lsp/<версия>.md` — без этого файла
   workflow упадёт намеренно, список изменений не должен теряться.
3. `git tag lsp-v<версия>` и `git push releases lsp-v<версия>` (remote `releases`,
   не `origin`).
4. Workflow `.github/workflows/lsp-release.yml` в `CapeCraft-Releases` соберёт
   `lspJar`, сверит версию в манифесте с тегом (ошибка, если `lsp_version`
   забыли поднять), прикрепит `capecraft-lsp.jar` к GitHub-релизу
   `capecraft-lsp <версия>`.

Обновление у клиента — через переменные окружения в подключении к серверу
(см. «Подключение к редактору»): `CAPECRAFT_LSP_JAR` указывает на скачанный
из релиза jar, при желании `CAPECRAFT_JAVA` — на свой JDK.

## Запуск

```bash
java -jar artifacts/capecraft-lsp.jar
```

Сервер читает `Content-Length`-кадры из stdin и пишет ответы в stdout.

## Подключение к редактору

### Neovim

```lua
vim.filetype.add({ extension = { kn = "kn", crn = "kn" } })

vim.lsp.config("capecraft", {
  cmd = { "java", "-jar", "/путь/к/capecraft-lsp.jar" },
  filetypes = { "kn" },
  root_dir = function(bufnr, on_dir)
    -- Именно on_dir: в vim.lsp.config результат return игнорируется.
    on_dir(vim.fs.dirname(vim.api.nvim_buf_get_name(bufnr)))
  end,
})
vim.lsp.enable("capecraft")
```

Semantic tokens Neovim 0.12+ включает сам, но группы `@lsp.type.*` там
названы с хвостом filetype (`.kn`) — темы их не заводят, поэтому нужен
`ColorScheme`-хук, связывающий их с обычными.

### Zed

Zed не умеет регистрировать новый язык через `settings.json`: там можно
переопределить свойства уже известного языка, но не завести свой. Нужно
расширение — `tools/zed-koren` в этом репозитории:

```bash
./tools/zed-koren/install.sh
```

Скрипт собирает WASI-компонент под `wasm32-wasip2`, кладёт его в
`~/.local/share/zed/extensions/installed/koren/` и добавляет запись в
`~/.local/share/zed/extensions/index.json`. Пути к java и jar расширение
берёт из переменных окружения, по умолчанию — из машин разработчика:

```bash
export CAPECRAFT_LSP_JAR="$HOME/.local/share/capecraft/capecraft-lsp.jar"   # скачанный из релиза
export CAPECRAFT_JAVA=/usr/lib/jvm/java-26-openjdk/bin/java                  # по желанию
```

В `settings.json` остаётся:

```json
"languages": {
  "KoreN": {
    "semantic_tokens": "full",
    "language_servers": ["capecraft"]
  }
}
```

`full`, а не `combined`: tree-sitter-грамматики у KoreN нет, и всё оформление
приходит из semantic tokens.

### Остальные клиенты

Любой LSP-клиент: `capecraft` как `languageId`, файл отдаётся с
`textDocument/…` как обычно. `languageId` сервер не читает вовсе, так что его
значение не важно. Клиент без поддержки иерархических символов получит плоский
список.

## Какие файлы считаются конфигом CapeCraft

`.kn`/`.crn` — общий формат данных ([KoreN](https://github.com/i3667563-dot/koren)),
и конфиг CapeCraft лишь один файл среди них. Поэтому схема конфига
(`providers`, `limits`, `serverSync`, поля провайдера) применяется не ко всем
`.kn`/`.crn` подряд — иначе в любом чужом файле каждая строка становилась бы
«неизвестным ключом».

Файл считается конфигом CapeCraft, если:

- он называется `capecraft.kn` или `capecraft.crn` — ровно те два имени, которые
  читает мод (`CapeConfigFiles`); даже пустой такой файл проверяется, ведь
  отсутствие блока `capeCraft` в нём — настоящая ошибка;
- либо в тексте уже есть блок `capeCraft` — тогда проверяется копия конфига под
  другим именем и любой файл, который человек пишет с нуля.

Всё остальное проверяется только как язык: синтаксис, незакрытые строки,
подсветка, свёртывание, символы, корни мира в `when` и подстановки `${…}`. Ключи
конфига CapeCraft в чужом файле не предлагаются — там своя схема.

## Особенности протокола

**Кодировки позиций.** Позиции считаются в той кодировке, которую клиент
выбрал в `general.positionEncodings` (`utf-16`, `utf-8`, `utf-32`; по
умолчанию `utf-16`). Эмодзи и другие символы вне BMP не сдвигают ни курсор,
ни подчёркивание диагностики, ни длину цветного токена.

**Диагностика несёт `fixes`, а `codeAction` превращает их в `TextEdit`.**
Клиент поле `data` не показывает, поэтому меню правок собирается на стороне
сервера из диагностики под курсором.

**Сниппеты идут с `insertTextFormat = 2`** — иначе клиент вставит
`{\n\t$0\n}` буквально и файл станет невалидным.

**Semantic tokens.** Легенда: `comment`, `string`, `number`, `enumMember`,
`property`, `type`, `operator`, `variable`. `semanticTokensProvider`
объявляется только клиентам, которые знают хотя бы один из этих типов, а
легенда в ответе — пересечение с возможностями клиента. Delta-запросы не
поддерживаются, кэша токенов нет.

## Язык

Вложенность — отступами, списки — квадратными скобками, комментарии — `#`.

```
# Плащ по погоде и времени суток
capeCraft {
    providers [
        { name = "rain", type = "url", url = "https://example.com/rain.png",
          when = { weather: "rain" } },

        { name = "night", type = "url", url = "https://example.com/night.png",
          when = { time.period: "night", dimension: "overworld" } },

        { name = "deep", type = "url", url = "https://example.com/deep.png",
          when = { location.y: ">-20" }, priority = 10 },

        { name = "api", type = "json", url = "https://api.example.com/cape?u={username}",
          extract = "$.data.cape_url" }
    ]

    limits { maxFrames int = 100 }
}
```

Подстановки `${...}` в строках подсвечиваются отдельно от самой строки.

## Единый источник правды

Словарь ключей, их типы, допустимые значения и дефолты живут в
`ConfigSchema` — один раз, оттуда же берутся подсказки, hover и диагностика.
Второго списка ключей в сервере нет, и появиться он не может: если в моде
поменяется дефолт, подсказка в редакторе разойдётся с модом без всякой ошибки
компиляции — ровно тот случай, ради которого схема вынесена отдельно.

## Тесты

```bash
./gradlew test                          # версия по умолчанию (26.2)
./gradlew test -Pmc=1.21.1              # конкретная версия Minecraft
./gradlew test --tests 'dev.ggtv.capecraft.lsp.*'   # только сервер: 76 тестов
```

Тесты сервера (`lsp/LspServerTest.kt`) идут через настоящий `RpcTransport`:
клиент и сервер в одном процессе, но общаются кадрами по сокетам. Свой
разбор кадров в тесте проскочил бы мимо настоящей ошибки в заголовке.

## Чего нет

- Tree-sitter-грамматики — её и не должно быть: парсер один, в сервере.
- Go-to-definition, референсы, rename, formatting — не реализовано.
- Неизвестный метод возвращает `MethodNotFound` (это осознанно, см. выше).
