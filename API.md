# CapeCraft Addon API

## Версия
- **API version**: 1 (константа `CAPE_RUNTIME_API_VERSION`)
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

## CapeApiHolder
Глобальный держатель `CapeApi`:
```kotlin
CapeApiHolder.api  // текущий активный CapeApi
```

## Пример аддона
Полный рабочий пример (провайдер, декодер, плейсхолдер, конфиг, модификатор
рендера, события) — в `src/test/kotlin/dev/ggtv/capecraft/examples/ExampleCapeAddon.kt`
плюс интеграционный тест `ExampleCapeAddonTest.kt`, проверяющий каждую
регистрацию. Копия структуры `register()` — готовый скелет для своего аддона.

## Миграция
- Аддоны загружаются ДО чтения конфига
- ProviderLoader видит аддон-типы при парсинге `capeCraft.providers[]`
- `Resolved.Addon` обрабатывается в CompositeFetcher/HttpFetcher/FileFetcher
- Конфиги аддонов читаются после парсинга в `CapeConfig.reload()`
