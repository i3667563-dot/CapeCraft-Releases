# CapeCraft — API (текущее состояние)

> Состояние на момент: скелет+дековдеры+провайдеры+память готовы и покрыты тестами;
> mixin-рендер и команды написаны и **компилируются**, но **в игре ещё не проверены** (нужен runClient).
> Версия мода: **0.1.0** (бета-черновик). Мод полностью клиентский.

---

## 1. Точка входа

`dev.ggtv.capecraft.CapeCraftClient : ClientModInitializer`

- `MOD_ID = "capecraft"`
- `LOGGER` (SLF4J)
- На `onInitializeClient()`: читает конфиг → строит реестр → регистрирует команды.

Объект-синглтон (companion) предоставляет глобальный доступ:

| Член | Тип | Назначение |
|---|---|---|
| `config` | `CapeConfig` | провайдеры + лимиты (из `config/capecraft.crn`) |
| `registry` | `CapeRegistry` | реестр плащей UUID → `AnimatedImage` |
| `replaceRegistry(new)` | `fun` | пересоздать реестр (для `/cp reload`) |
| `MOD_ID` | `const val String` | `"capecraft"` |

---

## 2. Реестр плащей — `CapeRegistry`

`dev.ggtv.capecraft.CapeRegistry(providers, fetcher, memory, root)`

Связывает провайдеры → декодер → память.

| Метод | Сигнатура | Описание |
|---|---|---|
| `get` | `(uuid: String, username: String): AnimatedImage?` | достаёт из кэша или грузит/декодирует; ошибка → `null` + причина в `error()` |
| `dynamicTexture` | `(uuid: String, anim: AnimatedImage? = null): Identifier?` | регистрирует текущий кадр как динамическую текстуру и возвращает её `Identifier` |
| `forget` | `(uuid: String)` | удалить плащ + освободить текстуру |
| `clear` | `()` | очистить кэш и текстуры |
| `error` | `(uuid: String): String?` | причина последней ошибки загрузки |
| `size` | `Int` | число кэшированных плащей |
| `totalBytes` | `Long` | суммарная память под плащи |
| `cachedKeys` | `Set<String>` | ключи (UUID) кэша |
| `providers` | `List<Provider>` | текущий список провайдеров |

---

## 3. Провайдеры — `provider/*`

### `Provider(name: String, source: Source)`
`resolve(ctx: Placeholders.Context, root: String): Resolved` — подставляет плейсхолдеры и возвращает конкретный источник (чистая функция, без I/O).

### `Source` (sealed interface)
| Вариант | Поля | Конфиг |
|---|---|---|
| `Url(template)` | шаблон URL | `type = url` |
| `File(template)` | шаблон пути | `type = file` |
| `Json(template, extract)` | URL + path-инструкция в JSON | `type = json` |

### `Resolved` (sealed interface)
`Url(url)`, `File(path)`, `Json(url, extract)` — конкретные источники после подстановки.

### Плейсхолдеры — `schema/Placeholders`
`Context(username, uuid, name, root)`; `render(template, ctx)`.
- `{username}` — имя профиля
- `{uuid}` — UUID без дефисов (нижний регистр)
- `{name}` — имя провайдера
- `{root}` — корень (папка игры)
- неизвестный плейсхолдер → ошибка (падает на конфиге, не шлёт битый URL)

### Провайдеры и fallback
- Загрузчик провайдеров: `provider/ProviderLoader` (из `.crn`)
- Цепочка: провайдеры проверяются **по порядку**, первый успешный отдаёт плащ.
- Типы фичин: `http: java.net.http` (таймауты, ошибки с контекстом), `file: локально`, JSON-схема через `schema/JsonPath`.

---

## 4. Движок изображений — `image/*`

### `ImageDecoder` (object)
`decode(data: ByteArray, source: String? = null, format: ImageFormat? = null): AnimatedImage`
- формат определяется по **сигнатуре** (не по расширению);
- поддерживает `PNG`, `GIF`, `WEBP` (APNG распознаётся внутри PNG);
- явный `format` пропускает детект.

### `Frame` / `AnimatedImage`
- `Frame(pixels: IntArray /*ARGB 0xAARRGGBB*/, durationMs: Int)`
- `AnimatedImage(width, height, frames, loopCount)`
  - `isAnimated`, `frameCount`, `singlePixels` (быстрый путь для статики)
  - `frameAt(timeMs: Long): IntArray` — кадр по времени (учитывает `loopCount`)
  - `totalDurationMs`, `totalPixels`

---

## 5. Умная память — `memory/*`

### `Limits` (дефолты)
| Поле | Дефолт |
|---|---|
| `maxPixelsPerFrame` | 4 000 000 |
| `maxFrames` | 100 |
| `maxBytesPerCape` | 64 МБ |
| `maxBytesTotal` | 128 МБ |

### `MemoryManager(limits: Limits = Limits())`
| Метод | Описание |
|---|---|
| `store(uuid, image)` | кладёт после деградации, возвращает итог |
| `get(uuid)` | достаёт (LRU вверх), `null` если нет |
| `remove(uuid)` | удалить |
| `clear()` | очистить |
| `degrade(image)` | привести к лимитам (масштаб → байты → скип кадров) |
| `totalBytes`, `capesCount`, `keys` | состояние |

Деградация (two-level): per-cape (area-average сжатие `Scale`, байтовый лимит, скип кадров `FrameThinning`) + глобальный LRU-кэш по `maxBytesTotal`.

---

## 6. Интеграция Minecraft

### Текстуры — `CapeTexture` (object)
| Метод | Описание |
|---|---|
| `register(uuid, w, h, frame): Identifier` | регистрирует кадр как `NativeImageBackedTexture`, id стабилен `capecraft:cape/{uuid}` |
| `release(uuid)` | освободить текстуру |
| `idFor(uuid): Identifier` | стабильный id текстуры |

### Mixin — `mixin/*`
- `CapeFeatureRendererMixin` — `@Inject` на `render` HEAD, cancellable. Если у игрока есть плащ в реестре: отменяет ванильный рендер, берёт кадр по игровому времени, регистрирует динамическую текстуру и рисует модель через `VertexConsumerProvider.Immediate.submitModel(...)` с `RenderLayer.getEntityTranslucent`.
  - ⚠️ **готово/компилируется, но отрисовка в игре не проверена (этап 7).**
- `CapeFeatureRendererAccessor` — `@Accessor("model")` даёт модель `CapeFeatureRenderer`.

> Примечание: `submitModel` в 1.21.10 живёт на `VertexConsumerProvider.Immediate`, а не на самом интерфейсе.

---

## 7. Команды `/cp`

Клиентские команды (Fabric API), работают в одиночке и на сервере без прав.

| Команда | Действие |
|---|---|
| `/cp reload` | перечитать конфиг + пересоздать реестр |
| `/cp list` | список кэшированных плащей и объём памяти |
| `/cp status` | версия, игрок, число провайдеров/плащей, память |
| `/cp clear` | очистить кэш плащей |

---

## 8. Конфиг `config/capecraft.crn`

Создаётся дефолт при отсутствии. Ошибки парсинга не роняют мод: провайдеры/лимиты остаются дефолтными, причина — в `CapeConfig.lastError` (видна в `/cp status`).

```crn
capeCraft {
    providers [
        { name = "example" type = "url"  url  = "https://example.com/capes/{username}.png" }
        # { name = "api"    type = "json" url = "https://api.example.com/cape?u={username}" extract = "$.data.cape_url" }
        # { name = "local"  type = "file" path = "{root}/capes/{uuid}.png" }
    ]
    limits {
        maxPixelsPerFrame = 4000000
        maxFrames         = 100
        maxBytesPerCape   = 67108864
        maxBytesTotal     = 134217728
    }
}
```

**API конфига** (`CapeConfig`): `providers: List<Provider>`, `limits: Limits`, `lastError: String?`, `path: Path`, `reload()`, `rootFor(): String`.

---

## 9. Cren-ядро — `cren/*`

Порт Cren на Kotlin (чистый, без MC): токенизатор, парсер, резолвер, `CrenConfig` (get_str/get_int/keys/get_comment и т.д.). Покрыт тестами (82 + ресурсные).

---

## Changelog (0.1.0)

### Добавлено
- Скелет Fabric 1.21.10 + Kotlin (FLK), JDK 21, loom; сборка и тесты зелёные.
- **Cren-движок** (Этап 2): токенизатор/парсер/резолвер, `CrenConfig` API, 82 теста.
- **Декодеры изображений** (Этап 3): PNG (inflate+фильтры 0-4, Adam7, tRNS, 1/8/16-бит), APNG (калдр/dispose/blend), GIF (LZW, интерлейс, GCE, NETSCAPE2.0), WebP VP8L (трансформы, Huffman, LZ77, цветовой кэш, ANMF-анимация). Автодетект по сигнатуре.
- **Провайдеры и схемы** (Этап 4): `Source` (Url/File/Json), `Resolved`, `Provider.resolve`, плейсхолдеры `{username}/{uuid}/{name}/{root}`, JSON-path (`$.a.b[0]`), HTTP/файловый фичеры, `ProviderLoader` + `resolveCape` fallback.
- **Умная память** (Этап 5): `Limits`, LRU-кэш, area-average `Scale`, `FrameThinning`, деградация (размер кадра → байты → скип кадров). 25 тестов.
- **Интеграция** (Этап 6, черновик): `CapeRegistry`, `CapeConfig`, `CapeTexture` (динамические текстуры), `CapeCommands` (`/cp reload/list/status/clear`), mixin-рендер + аксессор.

### Исправлено в ходе работы
- GIF-декодер: корректная обработка `KwKwK` и `NETSCAPE2.0` loop.
- API 1.21.10: `DynamicTexture(NativeImage)` устарел → используется `NativeImageBackedTexture` + `setImage/upload`; `submitModel` — на `VertexConsumerProvider.Immediate`.

### Известные ограничения (на момент 0.1.0)
- Рендер и анимация плаща **не проверены в игре** (нужен runClient, этап 7).
- Декодеры материализуют все кадры сразу (нет ленивого поштучного декодирования из потока) — покрыто сжатием/скипом кадров.
- Внешних зависимостей нет (кроме Fabric API); всё вшивается в jar.

### Планы (роадмап)
- 0: выпуск беты (проверка в игре, README, релиз).
- 1: формат `.kn` (KoreN — Kotlin + Cren) со спецификой Minecraft (биом/погода/время/ измерение).
- 2: парсер условий выбора провайдера (по биому/погоде и т.п., приоритет выше у специфичного, `default`-fallback).
