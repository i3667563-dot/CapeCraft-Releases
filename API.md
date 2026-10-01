# CapeCraft Addon API

## Версия
- **API version**: 2 (константа `CAPE_RUNTIME_API_VERSION`)
- **Совместимость**: аддон проверяет `capeApiVersion()` при загрузке

## Точка входа
Аддон регистрирует entrypoint в `fabric.mod.json`:
```json
{
  "entrypoints": {
    "capecraft:addons": ["com.example.MyAddon"]
  }
}
```

Entrypoint должен реализовать `CapeAddon`:
```kotlin
class MyAddon : CapeAddon {
    override fun register(api: CapeApi) {
        // Регистрация типов, декодеров, плейсхолдеров
    }
}
```

## Реестры

### 1. Провайдеры (`api.sourceTypes`)
Регистрирует новый `type = "..."` для `.kn` конфига:
```kotlin
api.sourceTypes.register(CapeSourceType("github") { values: CapeValues ->
    CapeSource { data ->
        // values.entries["repo"] — ключи из словаря .kn
        fetchCapeFromGitHub(values.entries["repo"] as String)
    }
})
```

В конфиге:
```kn
capeCraft {
    providers [
        { name = "my-capes", type = "github", repo = "user/capes" }
    ]
}
```

### 2. Декодеры (`api.decoders`)
Регистрирует декодер нового формата изображения по сигнатуре байтов:
```kotlin
api.decoders.register(CapeDecoderSpec(
    id = "bmp",
    detect = { data -> data.size >= 2 && data[0] == 'B'.code.toByte() },
    decoder = CapeDecoder { data, source -> decodeBmp(data, source) },
))
```

Встроенные форматы (PNG/GIF/WebP) детектируются первыми; аддон-декодеры — после.

### 3. Плейсхолдеры (`api.placeholders`)
Регистрирует `{placeholder}` для шаблонов URL/путей:
```kotlin
api.placeholders.register(CapePlaceholderSpec(
    name = "server-ip",
    resolver = CapePlaceholder { ctx -> getServerIp(ctx.uuid) },
))
```

В конфиге: `url = "https://example.com/{server-ip}/cape.png"`

### 4. События (`api.events`)
Подписка на жизненный цикл плаща:
```kotlin
api.events.on(CapeEvent.Type.CAPE_LOADED) { event ->
    println("Плащ загружен: ${event.uuid}")
}
```

Типы событий:
- `CAPE_LOADED` — плащ успешно загружен
- `CAPE_LOAD_FAILED` — ошибка загрузки
- `FRAME_BUILT` — кадр обновлён
- `PROVIDER_NOT_FOUND` — ни один провайдер не вернул плащ

### 5. Конфиг аддона (`api.config`)
Аддон объявляет свои ключи со значениями по умолчанию:
```kotlin
api.config.register(CapeAddonConfig(
    addonId = "my-addon",
    defaults = mapOf(
        "apiKey" to Value.VStr("default-key"),
        "refreshMs" to Value.VInt(30000),
    ),
))
```

В конфиге:
```kn
capeCraft {
    addons {
        my-addon {
            apiKey = "real-key"
            refreshMs = 60000
        }
    }
}
```

Чтение значений (после загрузки конфига):
```kotlin
val cfg = api.config["my-addon"]
cfg?.getString("apiKey")       // "real-key"
cfg?.getLong("refreshMs")      // 60000L
cfg?.getBool("enabled")        // false (default)
```

### 6. Модификаторы рендера (`api.renderModifiers`)
Аддон изменяет рендер плаща (эффекты, тонирование, замена текстуры, условия KoreN):
```kotlin
api.renderModifiers.register(object : CapeRenderModifier {
    override val condition = CapeCondition(WorldRoot.WEATHER, "condition", "rain")
    override fun beforeRender(ctx: CapeRenderContext): Identifier? {
        // Трансформация, замена текстуры
        ctx.matrices.push()
        ctx.matrices.scale(1.05f, 1.05f, 1.05f)
        return ctx.textureId // вернуть другую текстуру для замены
    }
    override fun afterRender(ctx: CapeRenderContext) {
        ctx.matrices.pop()
    }
})
```

Условие управляет тем, когда модификатор активен. `CapeRenderContext` содержит
`uuid`, `textureId`, `matrices`, `light`, `outlineColor`.

### 7. Условия `when` (`api.whenConditions`)

```kotlin
api.whenConditions.register(
    CapeWhenRoot(
        root = "fire",
        doc = "Горение игрока.",
        defaultField = "burning",
        fields = listOf(
            CapeWhenField("burning", "Горит ли игрок.", values = listOf("true", "false")),
            CapeWhenField("ticks", "Сколько тиков горит.", numeric = true),
        ),
        reader = { subject, field ->
            val entity = subject as? Entity ?: throw CrenError.NotFound("fire.$field")
            when (field) {
                "burning" -> Value.VStr(if (entity.isOnFire) "true" else "false")
                "ticks" -> Value.VInt(entity.remainingFireTicks.toLong())
                else -> throw CrenError.NotFound("fire.$field")
            }
        },
    ),
)
```

Появляется корень `when`, который пишется в конфиге так же, как встроенный:

```kn
when = { fire: "true" }
when = { fire.ticks: ">100" }
```

Три правила, которые важно знать до написания читателя.

**Имя корня не может содержать точку.** Разбор ключа делит строку по первой
точке, и `fire.temp` разъехался бы на корень `fire.temp` и пустое поле — условие
не совпало бы никогда, молча. То же проверяется при регистрации и в дескрипторе.

**Условие всегда локальное.** Наблюдатель не знает, что происходит с сущностью
соседа, поэтому провайдер с аддонным корнем помечается self-only и по сети не
объявляется: иначе я поджёг бы свой плащ, а у соседа он бы появился. Проверка
делается не в читателе, а контекстом — у чужого игрока `addonField` бросает
`CrenError.NotFound`, и условие просто не совпадает.

**`reader` получает то, что отдал контекст.** Для локального игрока это
сущность, для чужого и на сервере — `null`. Приводить к типу без проверки нельзя:
контекст — сторонний код, и `null` упал бы на каждом кадре, а не один раз при
чтении условия.

Числовые поля (`numeric = true`) принимают `>`, `>=`, `<`, `<=`, как
`location.y`; остальные — точное совпадение строки. Пустой список `values`
означает «любая строка», а не «нет значений»: неизвестное значение не должно
ломать конфиг у того, кто поставил аддон не той версии.

## Описание для редактора: `capecraft-addon.kn`

Редактор подсказывает ключи по схеме, а мод её в момент правки конфига ещё нет.
Чтобы подсказки знали про аддонские типы, аддон кладёт в корень своего jar'а
файл `capecraft-addon.kn`. Мод его не читает: единственный потребитель — LSP,
который ищет дескрипторы в папке `mods` рядом с открытым конфигом.

```kn
# Один файл на аддон. Комментарий — `#`, пары в `{}` разделяются запятой.
addon {
    id = "my-addon"
    version = "1.0.0"
    apiVersion = 1

    types = [
        {
            id = "mytype",
            doc = "Что делает этот тип. Показывается в подсказке.",
            keys = [
                { name = "gray", type = "bool", def = "false", doc = "В оттенках серого." }
            ]
        }
    ]

    placeholders = [ { name = "myHash", type = "int", doc = "Число для условия." } ]

    conditions = [
        {
            id = "fire",
            doc = "Горение игрока.",
            defaultField = "burning",
            fields = [
                { name = "burning", doc = "Горит ли игрок.", values = ["true", "false"] },
                { name = "ticks", doc = "Сколько тиков горит.", numeric = true }
            ]
        }
    ]
}
```

Что это даёт:
- `type = "mytype"` перестаёт быть «неизвестным» и появляется в подсказке
  вместе со своим описанием и именем аддона;
- ключи типа предлагаются в автодополнении и не подсвечиваются как лишние;
- `gray` внутри `type = "url"` подсвечивается как лишний, потому что набор ключей
  у `url` известен точно;
- `placeholders` показываются в подсказках `if`;
- корень `fire` из `conditions` появляется среди корней `when`, его поля
  предлагаются в автодополнении, а значения — в подсказке по полю.

`conditions` обязаны совпадать с тем, что аддон регистрирует в
`api.whenConditions`. Формы независимы (значение в коде, описание в файле) и ни
одна не проверяет другую, поэтому расхождение выглядит исправно с обеих сторон:
в редакторе поле есть, а в игре конфиг с ним падает при загрузке. Сверка
выполняется тестом в аддоне — см. `addons/capecraft-fire/src/test`.

Конфликт имён разрешается в пользу встроенного корня, и о нём пишется в stderr
LSP: аддон не может переопределить `weather`. Из двух аддонов с одинаковым
корнем побеждает первый — порядок jar'ов в папке `mods` меняется при
переустановке, поэтому на такое имя полагаться нельзя.

Правила формата — те же, что у обычного конфига (`.kn`, разбор `KorenConfig`):
пары внутри `{ }` разделяются запятой, записи блока верхнего уровня —
переводом строки. Имя типа ключа регистронезависимо (`bool` = `BOOL`).

Обязателен только `id`: без него не сказать, чей это тип. `types`,
`placeholders` и `conditions` можно не писать — аддон, который добавляет только
плейсхолдеры, не объявляет ни одного типа. Одна негодная запись не отменяет остальные:
пропущенная строка пишется в stderr LSP, и подсказки по остальным ключам
работают. Без дескриптора аддон просто не виден редактору — на игру это не
влияет, мод работает как раньше.

## CapeApiHolder
Глобальный держатель `CapeApi`:
```kotlin
CapeApiHolder.api  // текущий активный CapeApi
```

## Пример аддона
Полный рабочий пример (провайдер, декодер, плейсхолдер, конфиг, модификатор
рендера, события) — в `versions/<mc>/src/test/kotlin/dev/ggtv/capecraft/examples/ExampleCapeAddon.kt`
плюс интеграционный тест `ExampleCapeAddonTest.kt` рядом, проверяющий каждую
регистрацию. Копия структуры `register()` — готовый скелет для своего аддона.

Путь версионный, а не общий: `CapeRenderModifier` работает с классами клиента
(`Identifier`, матрицы), которые у каждой версии Minecraft свои. Остальные пять
реестров версионно-независимы, но держать пример в `versions/` удобнее — так он
всегда компилируется вместе с той версией, для которой написан.

## Миграция
- Аддоны загружаются ДО чтения конфига
- ProviderLoader видит аддон-типы при парсинге `capeCraft.providers[]`
- `Resolved.Addon` обрабатывается в CompositeFetcher/HttpFetcher/FileFetcher
- Конфиги аддонов читаются после парсинга в `CapeConfig.reload()`
