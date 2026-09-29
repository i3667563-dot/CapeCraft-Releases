# AGENTS.md — CapeCraft

## Репозитории (роли не путать)

- `origin` = **`i3667563-dot/CapeCraft`** — приватный полигон: разработка,
  тесты, CI-прогоны. Релизам здесь не место.
- `releases` remote = **`i3667563-dot/CapeCraft-Releases`** — публичный
  релизный репозиторий (полная копия проекта со своим ci.yml). Релизы
  мода (`v*`) и LSP (`lsp-v*`) создаются ТАМ, его ci.yml собирает jar и
  публикует GitHub-релиз на самом CapeCraft-Releases.

Правила:
- теги `v*` и `lsp-v*` пушатся только в `releases`, никогда в `origin`;
- `lsp-release.yml` охраняется условием `github.repository == ...CapeCraft-Releases`,
  чтобы случайный тег на полигоне не создал релиз не там;
- разработочные коммиты идут в `origin`; релизный репозиторий обновляется
  отдельно, полными зеркалами его не переписывать;
- как синхронизировать (проверено 29.09.2026): `origin` — содержательный
  суперсет релизной ветки, единственный уникальный файл там — `RELEASE-1.1.0.md`.
  Синхронизация — слиянием `origin/main` в `releases/main` с разрешением
  конфликтов в сторону origin, контроль: `git diff origin/main` на результате
  должен показать только `RELEASE-1.1.0.md`. Force-push недопустим (в релизной
  ветке есть зеркальные коммиты фиксов, которых нет в origin как объектов).

## Сборка (мультиверс)

Мод собирается под несколько версии Minecraft из одного репозитория.
Версия выбирается флагом **обязательным**:

- `./gradlew build -Pmc=1.21.1`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.4`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.8`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.10`   — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.11`   — yarn (Fabric mod)
- `./gradlew build -Pmc=26.2`      — необфусцированная MC (дефолт при отсутствии `-Pmc`)

Все сборки: BUILD SUCCESSFUL, 727 тестов, 0 failures (проверено 27.09.2026).
Плюс каждая версия проверена запуском `./gradlew runServer -Pmc=<версия>`:
мод инициализируется, каналы регистрируются, сервер доходит до `Done (...)!`.
Только эта проверка ловит падение в версионной обвязке — сборки и тесты проходят.

**`clean` при переключении версии НЕ нужен**: у каждой версии свой каталог
выходов `build/<mc>` (`layout.buildDirectory` в build.gradle), поэтому
incremental-кэш Kotlin разных версий не смешивается. `./gradlew cleanAll`
сносит выходы сразу всех версий. До этого фикса общий `build/` ронял сборку
на соседней версии с «Unresolved reference 'CapeCraftClient'».

## Структура

```
src/main/kotlin/dev/ggtv/                 # ОБЩИЙ версион-независимый код
  koren/  kjen/                           # Вендоренные библиотеки (НЕ менять бесконтрольно)
  capecraft/{cren,image,schema,provider,memory,condition,api}/  # общий движок + addon API
  capecraft/sync/                         # серверная синхронизация плащей (общая, без MC API)
  capecraft/ide/                          # разбор конфига и подсказки для редактора (общие, без MC API)
  capecraft/lsp/                          # Language Server для .kn: stdio, свой JSON-RPC (общий, без MC API)
src/main/resources/                       # общий mixins.json и ресурсы
versions/<mc>/src/main/kotlin/dev/ggtv/capecraft/   # версонно-зависимый код
  CapeCommands|CapeCraftClient|CapeCraftServer|CapeRegistry|CapeTexture|CapeConfig? нет — конфиг общий
  mixin/, render/MinecraftWorldContext.kt, render/ServerWorldContext.kt, api/render/
  sync/                                   # MC-обвязка протокола: payload'ы, клиент, сервер, право
versions/<mc>/src/test/kotlin/            # версионные тесты (RenderModifierTest, examples)
versions/<mc>/src/main/resources/fabric.mod.json
versions/<mc>/gradle.properties           # minecraft_version, loader/fabric/yarn, java_version
```

### Иерархия деплоймента версии
- В корне: Loom 1.17.x **не делится** между версиями — применяется условно в build.gradle:
  - yarn-версии (`verProps.yarn_mappings` есть): `apply plugin: 'fabric-loom'` (legacy, полный remap,
    нужен для `mappings` и modImplementation)
  - 26.2 (без yarn_mappings): `apply plugin: 'net.fabricmc.fabric-loom'` (no-remap)
- **ВАЖНО**: в Loom 1.17 плагин `net.fabricmc.fabric-loom` — это no-remap маркер
  (`LoomNoRemapGradlePlugin`). Он НЕ создаёт `mappings`-конфиг и не регистрирует
  mod-конфигурации (`modImplementation` и т.п.). Поэтому yarn-версии обязаны
  использовать legacy `fabric-loom`, иначе "Configuration with name 'mappings' not found".
- Общая конфигурация loom в build.gradle, версии вытягиваются из `versions/<mc>/gradle.properties`
  через `verProps`, toolchain и `options.release` тоже из версии (21 / 26).
- Kotlin jvmTarget = 21 всегда.

## Версии компонентов

| MC | loom | loader | fabric-api | FLK/Kotlin | JDK | yarn | маппинги |
|---|---|---|---|---|---|---|---|
| 1.21.1 | 1.17.20 (remap `fabric-loom`) | 0.19.5 | 0.116.17+1.21.1 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 21 | 1.21.1+build.3 | да |
| 1.21.4 | 1.17.20 (remap `fabric-loom`) | 0.19.5 | 0.119.4+1.21.4 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 21 | 1.21.4+build.8 | да |
| 1.21.8 | 1.17.20 (remap `fabric-loom`) | 0.19.5 | 0.136.1+1.21.8 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 21 | 1.21.8+build.1 | да |
| 1.21.10 | 1.17.20 (remap `fabric-loom`) | 0.19.5 | 0.138.4+1.21.10 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 21 | 1.21.10+build.3 | да |
| 1.21.11 | 1.17.20 (remap `fabric-loom`) | 0.19.5 | 0.141.6+1.21.11 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 21 | 1.21.11+build.6 | да |
| 26.2 | 1.17.20 (no-remap `net.fabricmc.fabric-loom`) | 0.19.5 | 0.160.0+26.2 | 1.14.1+kotlin.2.4.20 / 2.4.20 | 26 | — | нет (необфусц.) |

## Серверная синхронизация плащей (CapeCraft Sync)

Протокол v1, каналы `capecraft:sync_req` (C2S) и `capecraft:sync_resp` (S2C).
Сервер решает `when`-условия провайдеров у себя (мир настоящий) и отдаёт
**только активные** провайдеры; байты картинок по сети не ходят.

### Разделение на общее и версионное
- **Общее** (`src/main/kotlin/dev/ggtv/capecraft/sync/`, без MC API, тестируется
  обычными JUnit-тестами): `SyncProtocol` (каналы/версия/лимиты), `SyncCodec`
  (строгий бинарный формат), `CapeSyncState` (чистая машина: опрос, таймаут,
  fingerprint, fallback), `SyncPolicy` (URL/file/addon-политика), `ActiveCape`,
  `ServerCapeCatalog`, `ServerSyncSettings` (блок `serverSync` конфига).
- **Версионное** (`versions/<mc>/.../sync/`): `CapeSyncPayloads` (payload'ы
  и кодек), `CapeSyncClient`, `CapeSyncServer`, `CapeServerPermission`,
  `ServerCapeConfig`; плюс `render/ServerWorldContext.kt` и `CapeCraftServer`
  (`main`-entrypoint — мод работает и на выделенном сервере, поэтому
  `fabric.mod.json` у всех версий: `environment: "*"` + entrypoint `main`).

Оба payload'а кодируются ОДНИМ `SyncCodec`, так клиент и сервер проверяют
версию протокола и лимиты симметрично.

### Правила fallback (`CapeSyncState` + `CapeSyncClient`)
- валидный пустой ответ сервера = «плащей нет» (локальный набор отключается);
- таймаут / сервер без мода = локальный набор, но при `requireServer = true` —
  пустой набор;
- первый ответ применяется всегда: fingerprintunset ≠ fingerprint пустого набора.

### Политика источников (`SyncPolicy`)
- серверный `file` разрешён только внутри папки игры (проверка по факту
  подстановки `!SyncPolicy.isInside`), иначе провайдер отбрасывается;
- серверный addon-провайдер восстанавливается ТОЛЬКО из совпадающего
  локального — сервер не может дописать новые значения аддона.

### Политика набора для объекта (`ObjectCapePolicy`)
Общая, **без MC API**, потому что правило «свой набор — свой `file`-плащ» нужно
одновременно в клиентском реестре и в тестах, которые не грузят игру.
- `SELF` — свой объект: локальные провайдеры, без ожидания;
- `DECLARED` — объект объявился: его набор, его `when` и `priority`;
- `WAIT` — объект виден, но не объявился: пустой набор на
  `ANNOUNCE_GRACE_MS` (5 секунд), чтобы успеть пришло объявление;
- `FORCED_LOCAL` — срок вышел: навязываем локальные провайдеры.

`CapeRegistry` держит `firstSeenAt` **по всем** увиденным объектам
(`seenObjectIds`), а не только по объявившимся (`knownObjectIds`): иначе отсчёт
ждать не с чего. `forgetObject`/`forgetSession` обязаны чистить `firstSeenAt`
вместе с остальным состоянием, иначе после перезахода счётчик не сбросится.
`ensureLoading` на пустом наборе выходит сразу — иначе планировщик воет пять
секунд на каждом кадре рендера.

**Пустое объявление — это отсутствие набора, а не «показать нечего».** Клиент
шлёт `Announce` всегда, даже когда `currentFunctions()` пуст, и если считать
это объявлением, игрок без провайдеров навсегда остаётся без плаща, а срок
ожидания не срабатывает — «молчания» не было. Ветка `spokeEmpty` обязана стоять
**до** проверки срока, иначе пустое объявление будет ждать пять секунд впустую.
Поймано живым прогоном на трёх точках входа, не тестом.

## Живой стенд: сервер + два клиента

`./gradlew runServer` / `runClient` / `runClientB -Pmc=<версия>` — три точки
входа с разными папками игры (`run-server`, `run-client-a`, `run-client-b`).
Каждому клиенту нужен свой каталог: общий `run/` затирает `options.txt`, логи и
кэш плащей. Ник в дев-запуске Loom не подставляет, поэтому он передаётся
`--username`; вход в сервер — `--quickPlayMultiplayer`, иначе стенд требует
руки. Ники и адрес переопределяются свойствами: `-PclientAName=...`,
`-PclientBName=...`, `-PtestAddress=host:port`, `-PtestServer=<каталог>`.
Запускать через `setsid`, иначе клиент умирает вместе с терминалом.

### Точки расхождения API между версиями
| Что | 1.21.1–1.21.8 | 1.21.10 | 1.21.11 | 26.2 (Mojang) |
|---|---|---|---|---|
| буфер кодеков | `RegistryByteBuf` | `RegistryByteBuf` | `RegistryByteBuf` | `RegistryFriendlyByteBuf` |
| payload | `CustomPayload` + `getId()` | то же | то же | `CustomPacketPayload` + `type()` |
| id payload | `CustomPayload.Id(Identifier.of(ns, path))` | то же | то же | `CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(ns, path))` |
| codec | `PacketCodec` | то же | то же | `StreamCodec` |
| регистрация каналов | `PayloadTypeRegistry.playC2S/playS2C` | то же | то же | `...serverboundPlay/clientboundPlay` |
| регистрация команд | `CommandRegistrationCallback` (2 арг.) | то же | то же | 3 арг. (`+ Commands.CommandSelection`) |
| команда | `CommandManager.literal` | то же | то же | `Commands.literal` |
| право на `/capecraft` | `hasPermissionLevel(2)` | то же | `permissions.hasPermission(Permission.Level(PermissionLevel.GAMEMASTERS))` | `permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))` |
| мир игрока (сервер) | `player.world` | `player.entityWorld` | `player.entityWorld` | `player.level()` |
| время суток | `world.timeOfDay` | то же | то же | `world.getOverworldClockTime()` (`getDayTime()` больше нет) |
| биом | `world.getBiome(pos)` | то же | то же | `world.biomeManager.getBiome(pos)` |
| ключ измерения | `world.getRegistryKey()` | то же | то же | `world.dimension()` |
| ответ клиенту | `sendFeedback({ Text })` | то же | то же | `sendSuccess({ Component })` |
| соединение (клиент) | `MinecraftClient.networkHandler` | то же | то же | `Minecraft.getConnection()` |

`hasPermissionLevel(2)` в 1.21.11 и уровневые права в 26.2 вынесены в
`CapeServerPermission.GAMEMASTER` — это единственное место, где версии
расходятся по правам; в 26.2 форма `hasPermission(Permission.HasCommandLevel(...))`
работает и для `LevelBasedPermissionSet`, и для `PermissionSetUnion`.

### id канала НЕЛЬЗЯ собирать из строки целиком (регрессия, ловилась только в игре)

Ни `CustomPayload.id("ns:path")`, ни `CustomPacketPayload.createType("ns:path")`
**не понимают** неймспейс. Оба зовут `Identifier.withDefaultNamespace(...)`, а он
в 26.2 и в yarn 1.21.11 устроен так:

```
ldc "minecraft"      // неймспейс — всегда
ldc "minecraft"      // путь для проверки — всегда
ldc <вся строка>     // path
```

То есть `capecraft:sync_req` превращается в path `capecraft:sync_req` с двоеточием
внутри и мод падает на старте:

```
IdentifierException / InvalidIdentifierException:
  Non [a-z0-9/._-] character in path of location: minecraft:capecraft:sync_req
```

Это ломало **все шесть версий** (не только 26.2) и не ловилось ни компиляцией,
ни тестами — падало только при реальном запуске. Канал собирается только через
`SyncProtocol.channelNamespace/channelPath` (в общем коде), а версионная обвязка
собирает `Identifier`/`Id` из готовых частей. Инвариант «ровно одно двоеточие,
символы из `[a-z0-9/._-]`» закрыт тестом `SyncProtocolChannelTest`.

### Stacked PNG отличается от обычного PNG по чанку, а не по высоте (регрессия)

`ImageFormat.detect` смотрел только на сигнатуру, а stacked PNG — это тот же
PNG. Файл разбирался как одна картинка и натягивался на плащ целиком.

`skins.ggshnikk.online` отдаёт 256×512 с чанком:

```
Description Stacked PNG: 4 frames 256x128, order=down
```

Различать приходится по служебным чанкам: `PngMetadata` читает `tEXt`/`iTXt`
без распаковки `IDAT` (сжатый `iTXt` и `zTXt` намеренно игнорируются).
Детект **по метаданным, а не по высоте**: у обычного плаща 256×512 и у четырёх
кадров по 256×128 одинаковые байты, любая эвристика по размеру растянула бы
обычные плащи в анимацию. Тест
`autodetect leaves stacked shaped png as regular png` специально это сторожит.

Требуется именно слово «stacked png» — иначе чужой `Description` вроде
«шляпа 4 кадра» превратит плащ в анимацию. Объявленная автором геометрия
строгая (`высота` должна делиться на кадр, кадров должно хватать), угаданная
(width/2) — как раньше обрезает неполный последний кадр. `order=right` даёт
понятную ошибку, а не молча перевёрнутую картинку.

Грабли при разборе iTXt: флаг сжатия стоит **за NUL ключа**, а не в начале
тела, иначе чанк молча пропускается.

### URL-провайдеры проверяются как ШАБЛОНЫ, а не как готовые ссылки (регрессия)

`ActiveCape.isHttpUrl` проверяет `config`-строку ДО подстановки плейсхолдеров,
а `URI.create` фигурные скобки не принимает:

```
Illegal character in path at index 52:
  https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.png
```

Из-за этого `ServerCapeCatalog` выбрасывал провайдер, в интегрированном сервере
получалось `отдано 0 провайдеров (обрезано 0, отброшено битых 1)`, а в игре —
`Провайдеров: 0 (набор с сервера)`. Ломались ВСЕ `url`/`json`-провайдеры
с `{username}`/`{uuid}`/аддон-плейсхолдерами, включая дефолтный конфиг мода.

Плейсхолдеры подставляются только при загрузке (`Placeholders.render`), поэтому
перед `URI.create` каждый `{...}` заменяется безопасным токеном — для схемы и
хоста это ничего не меняет. Реальная ссылка проверяется ещё раз при запросе
(`HttpFetcher.getBytes` зовёт `URI.create` уже на отрендеренном URL).
Проверка не ослабла: `file:`, `ftp:`, `javascript:` и мусор по-прежнему
отвергаются. Закрыто `ActiveCapeUrlTemplateTest` (на снятой подстановке падают
три теста).

**Правило: любое новое расхождение версий и любая новая схема в конфиге
проверяются запуском игры, а не только сборкой.** Три бага этой сессии (канал,
`include` вместо `modImplementation`, плейсхолдеры в URL) были видны только так:
сборка и 500+ тестов были зелёными.


### Адаптивный интервал опроса (backoff)
Клиент опрашивает сервер каналом `capecraft:sync_req`. Фиксированный интервал
(2 с по умолчанию) — это ~0.5 запроса в секунду на КАЖДОГО игрока, то есть
500 req/s на 1000 игроков, при том что набор плащей меняет сам владелец
(несколько раз в день). Почти все эти запросы возвращают неизменившийся ответ.
`CapeSyncState` поэтому замедляет опрос, пока набор стабилен:

| | было | стало |
|---|---|---|
| интервал | 2 с всегда | 2 с → 4 → 8 → 16 → 32 → 60 с |
| запросов в простое | 30/мин | 1/мин (в 30 раз меньше) |
| реакция на смену капа | 2 с | 2 с (не изменилась) |

Ключевые свойства (все закреплены тестами в `CapeSyncStateBackoffTest`):

- **Рост**: ×2 за шаг после `STABLE_RESPONSES_BEFORE_BACKOFF` (=3) ответов
  подряд «без изменений», до потолка `MAX_BACKOFF_INTERVAL_TICKS` (1200 = 60 с).
- **Сброс на горячий режим**: смена набора возвращает базовый интервал, чтобы
  поймать комплект «пачками» сразу после переодевания.
- **Таймаут тоже замедляет**: не отвечающий сервер нельзя дёргать чаще — чем
  хуже серверу, тем больше от него и так.
- **Горячий режим без джиттера**: пока интервал не вырос, опрос идёт РОВНО
  через `intervalTicks` (старый контракт не ломается).
- **Джиттер только в стационаре**: при выросшем интервале добавляется
  `0 … +2×BACKOFF_JITTER_PERCENT`% (смещение только вверх — базовый интервал
  это ограничение СКОРОСТИ, его нельзя превышать случайно).

### Сид джиттера обязан быть случайным
LCG с общим фиксированным сидом даёт всем клиентам ОДНУ И ТУ ЖЕ
последовательность сдвигов, то есть herd не расходится никогда — просто
сдвигается на одну и ту же константу. Поэтому `jitterSeed` по умолчанию
`Random.nextLong()` на каждый экземпляр, а для тестов инъецируется явно
(иначе тайминги невоспроизводимы). Проверено тестами
`same seed keeps clients in lockstep` / `jitter spreads clients apart`.

### Отсчёт интервала — от момента ОТПРАВКИ
`reschedule()` считает `nextDueTick` от `lastRequestTick`, а не от текущего
тика. Иначе после таймаута ретрай сдвигался бы на величину задержки
обнаружения (а она тем больше, чем хуже серверу) и тихо разъехался бы со
старой каденцией.

### Смена поведения в 3 старых тестах
`CapeSyncStateTest` фиксирует БАЗОВУЮ каденцию, поэтому три теста про
таймауты явно передают `backoff = false`. Новое поведение (замедление после
таймаута) покрыто в `CapeSyncStateBackoffTest`.

## Рендер-пайплайн по версиям

| Версия | Пайплайн CapeFeatureRenderer | Рендер плаща |
|---|---|---|
| 1.21.1 | `render(MatrixStack, VertexConsumerProvider, int, AbstractClientPlayerEntity, float, float, float, float, float, float)` — живая сущность | **НЕ отменять ваниль!** Физика (качание/гравитация/ветер) считается ВНУТРИ `render()`, в самом конце `model.renderCape(...)`. Подменяем только текстуру через `@Redirect` на `AbstractClientPlayerEntity.getSkinTextures()` → возвращаем новый `SkinTextures` (record) с нашим `capeTexture`. `matrices` в ctx = новый пустой `MatrixStack()`. Accessor на `model` НЕ используется. |
| 1.21.4 | `render(MatrixStack, VertexConsumerProvider, int, PlayerEntityRenderState, float, float)` — RenderState + VertexConsumerProvider | `@Inject HEAD` без cancel → `state.skinTextures = SkinTextures(...)` (`client.util.SkinTextures`, конструктор + `model`), текстура подменяется, рендер ванильный |
| 1.21.8 | `render(MatrixStack, VertexConsumerProvider, int, PlayerEntityRenderState, float, float)` | как 1.21.4; без `outlineColor` в state → белый дефолт |
| 1.21.10 | `render(MatrixStack, OrderedRenderCommandQueue, int, PlayerEntityRenderState, float, float)` | `state.skinTextures = SkinTextures(body, cape, elytra, model, secure)` (`entity.player.SkinTextures`); cape = **`AssetInfo.TextureAssetInfo(texture, texture)`** — ВАЖНО: двухаргументный конструктор, иначе `texturePath` автомаппится в `textures/<id>.png` и ваниль не находит текстуру (чёрно-фиолетовая) |
| 1.21.11 | как 1.21.10, но `RenderLayer`/`getEntitySolid` → **`RenderLayers.entitySolid`** (API переименования); текстура тоже через `TextureAssetInfo(texture, texture)` |
| 26.2 (Mojang) | `render` → `submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)` | `state.skin = PlayerSkin(body, ClientAsset.DownloadedTexture(texture, ""), elytra, model, secure)` — `texturePath()` возвращает переданный id как есть (без маппинга) | 

Особенности портов:
- 1.21.1: `CapeTexture` пишет пиксели через `NativeImage.setColor` с конвертацией `argbToAbgr` (в 1.21.1 нет `setColorArgb`; в 1.21.4+ используется `setColorArgb` без конвертации).
- 1.21.1: конструктор `NativeImageBackedTexture(String, int, int, boolean)` отсутствует → использовать `(int, int, boolean)`.
- 1.21.1 (проверено в игре 10.09.2026): при отмене ванильного `render()` плащ застывает в туловище и не двигается — физика живёт в самом `render()`. Решено через `@Redirect getSkinTextures()`.

## yarn → Mojang (таблица из плана 3.5)

| yarn (1.21.10) | Mojang (26.2) |
|---|---|
| `net.minecraft.util.Identifier` | `net.minecraft.resources.Identifier` |
| `net.minecraft.util.Identifier.fromNamespaceAndPath` | `net.minecraft.resources.Identifier.fromNamespaceAndPath` |
| `net.minecraft.text.Text` | `net.minecraft.network.chat.Component` |
| `net.minecraft.client.util.math.MatrixStack` | `com.mojang.blaze3d.vertex.PoseStack` |
| `net.minecraft.client.render.entity.feature.CapeFeatureRenderer` | `net.minecraft.client.renderer.entity.layers.CapeLayer` |
| `net.minecraft.client.render.entity.state.PlayerEntityRenderState` | `net.minecraft.client.renderer.entity.state.PlayerRenderState` |
| `net.minecraft.client.render.entity.model.BipedEntityModel` | `net.minecraft.client.model.PlayerModel` |
| `net.minecraft.client.render.command.OrderedRenderCommandQueue` | `net.minecraft.client.renderer.SequentialBufferBuilder`? — проверить |
| `net.minecraft.client.util.SkinTextures` | — |
| `net.minecraft.entity.Entity` | `net.minecraft.world.entity.Entity` |
| `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
| `net.minecraft.client.world.ClientWorld` | `net.minecraft.client.multiplayer.ClientLevel` |
| `net.minecraft.network.packet.CustomPayload` | `net.minecraft.network.protocol.common.custom.CustomPacketPayload` |
| `net.minecraft.network.PacketByteBuf` / `RegistryByteBuf` | `net.minecraft.network.FriendlyByteBuf` / `RegistryFriendlyByteBuf` |
| `net.minecraft.network.codec.PacketCodec` | `net.minecraft.network.codec.StreamCodec` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `PayloadTypeRegistry.serverboundPlay()/clientboundPlay()` |
| `net.minecraft.server.command.CommandManager` | `net.minecraft.commands.Commands` |
| `net.minecraft.server.command.ServerCommandSource` | `net.minecraft.commands.CommandSourceStack` |
| `net.minecraft.server.network.ServerPlayerEntity` | `net.minecraft.server.level.ServerPlayer` |
| `net.minecraft.server.world.ServerWorld` | `net.minecraft.server.level.ServerLevel` |
| `net.minecraft.world.World` | `net.minecraft.world.level.Level` |
| `world.getRegistryKey()` | `world.dimension()` |
| `world.timeOfDay` | `world.getOverworldClockTime()` |
| `sendFeedback({ Text })` | `sendSuccess({ Component })` |
| `ClientCommandManager` | `ClientCommands` |
| `MinecraftClient.networkHandler` | `Minecraft.getConnection()` |

(таблица неполная — доразбирать при правках кода в каждой версии)

## API обеих версий (render)

`versions/<mc>/src/main/kotlin/dev/ggtv/capecraft/api/render/`:
- `CapeRenderContext(uuid, textureId, matrices, light, outlineColor)`
  — `matrices` тип зависит от версии: `MatrixStack` (yarn) / `PoseStack` (Mojang)
- `CapeRenderModifier.applyBefore(ctx, texture, world) → Identifier` (может заменить текстуру)
- `CapeRenderModifier.applyAfter(ctx, world)`
- `CapeRenderModifierRegistry` (список, AND-композиция)
- `CapeApiHolder.api.renderModifiers`

## Команды проверки

```bash
# Полная сборка версии (компиляция + тесты + remapJar) — clean НЕ обязателен,
# у каждой версии свой build/<mc>.
./gradlew build -Pmc=1.21.1    # и 1.21.4, 1.21.8, 1.21.10, 1.21.11, 26.2
./gradlew cleanAll             # снести выходы всех версий
./gradlew publishArtifact -Pmc=26.2   # только переопубликовать jar в artifacts/
./gradlew lspJar               # пересобрать artifacts/capecraft-lsp.jar (нужно после правок в lsp/)
```

Живая проверка подсветки в настоящем neovim (цвет видно только если группы
`@lsp.type.*` названы — иначе токены есть, а цвета нет):

```bash
nvim --headless -u ~/.config/nvim/init.lua \
  -c 'lua ... vim.api.nvim_get_namespaces()["nvim.lsp.semantic_tokens:" .. client.id] ...' -c 'qa!'
```

`nvim -u NONE` для этой проверки не годится: там нет ни filetype, ни темы, то
есть ровно то, ради чего проверяем.

Все шесть версий: BUILD SUCCESSFUL, 847 тестов на версию (5082 суммарно), 0 failures.

### Тесты, которые читают репозиторий

`ReadmeExamplesTest` разбирает fenced-блоки из `README.md`, поэтому Gradle
обязан видеть этот файл входом задачи — иначе после правки README тесты
молча остаются up-to-date и пропускают регрессию:

```groovy
test {
    inputs.file(file('README.md')).withPropertyName('readmeUnderTest')
}
```

Та же ловушка с тестами на документацию: пример из README должен не просто
разбираться, а падать. A/B обязателен — возвращаем старую форму
(`when { ... }` вместо `when = { ... }`) и убеждаемся, что тесты красные,
иначе тест ничего не проверяет.

### artifacts/ — ставить ТОЛЬКО отсюда

Сборка кладёт jar в `build/<mc>/libs/`, а задача `publishArtifact` (finalizer
`assemble`) копирует его в `artifacts/capecraft-<version>-<mc>.jar`. Ставить в
`mods/` нужно **артефакт из `artifacts/`** — он гарантированно от текущей сборки.

Почему это правило, а не формальность: `artifacts/` долго никто не обновлял, и
в `mods/` попал jar трёхнедельной давности **без `StackedPngDecoder`**. Мод
запускался, показывал плащ, но stacked PNG декодировался как одна картинка —
`CapeTexture: создана 256x1664` вместо кадра `256x128`, то есть стек растягивался
на весь плащ. Сборка при этом была «успешной», диагностика шла только по логу.

Проверка после установки (дёшево, ловит именно этот класс ошибок):

```bash
unzip -l artifacts/capecraft-1.0.0-26.2.jar | grep -c 'capecraft/image/StackedPngDecoder'
md5sum artifacts/capecraft-1.0.0-26.2.jar <mods>/capecraft-1.0.0.jar   # должны совпасть
```

Ожидаемое `CapeTexture: создана WxH` — это размер **одного кадра**, а не файла.
Для `13 frames 256x128` правильное значение `256x128`; `256x1664` означает, что
детект формата не сработал и стек не нарезан.

### CI: артефакт грузится из artifacts/, ничего не переименовывать

Workflow (`.github/workflows/ci.yml`) на каждый `mc` делает
`./gradlew build -Pmc=$mc` и грузит `artifacts/capecraft-*-*.jar` — ровно то, что
делает локальная сборка. Шага переименования нет и добавлять не надо: `build/libs/`
не существует, jar пишется в `build/<mc>/libs/`.

История: в workflow стоял `mv build/libs/capecraft-<version>.jar ...`. Пути не
было, шаг падал после **успешной** сборки, job выглядел упавшим, и релиз не
создавался — так были сломаны прогоны 1.1.0 и первые прогоны 1.1.1. Проверять у
падшего job по шагам, а не по общему `conclusion`: `gh run view --json jobs`
показывает, какой именно шаг сломался.

Тег `v*` гоняет ещё и job `release`: он качает артефакты, берёт описание из
`release-notes/<version>.md` (файл обязателен, пустой релиз хуже падения) и
создаёт релиз. Поэтому при переносе тега: удалить тег локально и на origin,
затем создать заново на исправленном коммите.


## mixins.json

Версионный (в `versions/<mc>/src/main/resources/`), НЕ общий в `src/main/resources/`:
- во всех версиях только `CapeFeatureRendererMixin` (accessor был удалён во всех портах:
  ваниль НЕ отменяется, подменяется лишь текстура плаща в render-state — физика ванильная)

## Конфиг: единственная запись на диск

Мод пишет на диск ровно в одном месте — `CapeConfig.writeDefault()`, и только
если выбранного файла нет. Выбор файла и проверка «надо ли создавать дефолт»
вынесены в `CapeConfigFiles` (общий код), потому что этот же выбор повторялся
в `ServerCapeConfig` на всех шести версиях.

Инвариант: **существующий `config/capecraft.kn` никогда не перезаписывается** —
ни при старте, ни при `/capecraft reload`, ни при `/capecraft reload` на сервере.
Если `.kn` нет, но есть legacy `.crn` — читается `.crn`, дефолт не создаётся.
Закрыто тестом `CapeConfigFilesTest` (на снятой проверке `Files.exists` падают
четыре теста).

Проверено на реальных логах инстанса 26.2: восемь запусков подряд
(11.09–13.09.2026) — файл с 4 пользовательскими провайдерами сохранил
содержимое и mtime, мод каждый раз читал `провайдеров 4`.

## Провайдер: только id-ссылка, никогда не мутабельный алиас

Правило для `providers` в `capecraft.kn`: **не вписывать `.../skins/{username}/cape.png`.**

Этот адрес привязан к НИКУ, а не к id капа, поэтому он физически не меняется
при смене плаща. Cloudflare держит его в кэше (зональное Cache Rule, наблюдалось
`public, max-age=14400`) — старый плащ в игре до 4 часов. Проверено на живых
заголовках: `cape.png` отдавался `HIT` с `age: 2555`, тогда как `cape.json` в тот
же момент отдавался `no-store`.

Правильно — двухуровневая схема через встроенный `type = "json"`:

```kn
{ name = "ggshnikk", type = "json",
  url = "https://skins.ggshnikk.online/api/animated/v1/skins/{username}/cape.json",
  extract = "$.cape" }
```

`cape.json` отдаётся как `no-store` (всегда актуален) и содержит
`{"cape":".../skin-file/<id>.png","animated":true}`. Дальше качается ссылка по id,
которую можно кэшировать вечно: новый плащ = новый id = новый адрес.

Итог: смена плаща видна сразу, и текстура при этом льётся с CDN, а не с Worker.
Проверено сквозным прогоном против прод-домена: `cape.json` → id 16 → HTTP 200,
34131 байт, `cf-cache-status: HIT`.

`Source.Json` требует ровно два ключа — `url` и `extract`
(`ProviderLoader`: иначе `IllegalArgumentException`). `$.поле` парсится и покрыт
`JsonPathTest`.

### Потолок HTTP-ответа и схема URL

`HttpFetcher` читает тело через `ofInputStream()` + `readNBytes(limit + 1)`, а не
`ofByteArray()`. Причина та же, что и в `SyncProtocol`: URL приходит из конфига,
`ofByteArray()` на сервере, отдавшем 4 ГиБ, сводит мод к OOM, а `readNBytes`
аллоцирует не больше `limit + 1` байт даже для бесконечного потока (chunked без
`Content-Length`). Потолки — `HttpFetcher.MAX_CAPE_BYTES` (8 МиБ, равен
`SyncProtocol.MAX_IMAGE_BYTES`; равенство проверяется тестом, а не импортом,
потому что `sync` уже зависит от `provider` и обратный импорт закрыл бы цикл
пакетов) и `MAX_JSON_BYTES` (1 МиБ).

`Content-Length` сознательно **не** используется как решение: заголовок может
соврать, а `java.net.http` и так обрезает тело по объявленной длине. Защита
только одна — фактически прочитанное число байт.

Схему проверяем сами, до `newBuilder`: `URI.create` глотает `file:`, `jar:` и
`data:`, а `newBuilder` на них падает сырым `IllegalArgumentException` мимо
политики «все ошибки — типизированные» (`FetchError`).

Известное и НЕ починенное ограничение: `HttpRequest.timeout` при `send`
сторожит только получение заголовков, а не тело. Сервер, отдающий байты по
одному в минуту, удержит фоновый поток. Лечится watchdog-ом на стороне
загрузки, а не здесь.

### Потокобезопасность API для аддонов

Три правила, каждоя закрывает реальную гонку, а не «на всякий случай»:

- **`CapeAddonConfigRegistry`** публикует значения **снимком**: `loadFrom`
  собирает новый `LinkedHashMap` и присваивает `@Volatile`-полю. Раньше он делал
  `configs.clear()` на месте, а `get` читал без синхронизации — читатель с
  рендер-потока успевал увидеть `null` для аддона, чей конфиг только что
  загрузился. Покрыто `AddonConfigRegistryTest` (окно `clear()` расширено до
  2000 аддонов, чтобы гонка воспроизводилась).
- **`CapeEventBus.emit`** берёт снимок подписчиков под монитором, а зовёт их
  **вне**. Иначе долгий слушатель аддона (сеть, диск) держал бы на мониторе
  и подписку с чужого потока: `emit` ждал бы сам себя. Реентрантность
  Java-монитора от этого не спасает — мешает чужая работа под локом. Покрыто
  `CapeEventBusTest`.
- **`LruCache.contains` не трогает LRU-порядок** — это запрос «есть ли», а не
  использование (`MemoryManager.contains` зовёт его перед сетевой загрузкой, где
  значение всё равно заберут через `get`). Побочный эффект у запроса сбил бы с
  толку и заставил бы читать кэш как будто он пишет.

`CapeSourceTypeRegistry.checkCompatibility` сверяет `CAPE_RUNTIME_API_VERSION` с
`SUPPORTED_API_VERSIONS` и при расхождении **называет его первым**, а не
заканчивает дежурной строкой «всё хорошо»: иначе аддон увидит зелёный статус и
сломается позже, на вызове отсутствующего метода.

Не путать с прогревом кэша: `~/.bash_scripts/warm-skinbase-cache.sh` дёргает
`api/v3/equipped?warm=1` (проект Pages — `skinbase-dov`, не путать с
`skinbase-проект` из wrangler.jsonc).

## `/cp reload` — полная перезагрузка, `/cp clear` — только кэш

Разные команды, разные гарантии. Путать их нельзя.

| | `/cp reload` | `/cp clear` |
|---|---|---|
| конфиг с диска | да | нет |
| дефолт при отсутствии файла | да | нет |
| новый набор провайдеров | да | нет |
| CPU-кэш плащей | новый `MemoryManager` | `clear()` |
| GPU-текстуры и их id | сброшены | сброшены |
| авторитетность сервера | снята | не трогается |
| запрос синхронизации | сразу | нет |

`/cp reload` — это «перезагрузить мод», поэтому он сбрасывает ВСЁ состояние,
включая GPU-текстуры: плащ на время загрузки исчезает, рисуется ванильный.
`/cp clear` — «сбросить кэш», мод продолжает работать ровно как работал: набор
провайдеров, лимиты и авторитетность сервера не меняются, плащи просто
перекачиваются при следующем обращении к рендеру.

В реестре это `reloadAll()` против `reload()`. `reloadAll` = `reload` плюс
`textureReady`/`textureIds`/`uploadedFrame` и снятие `isServerAuthoritative` с
`guard`. Общая часть вынесена в приватный `refreshLocked()`.

**Почему `/cp reload` сбрасывает авторитетность сервера:** команда — это явное
«возьми мой локальный конфиг». Если сервер нужен, он пришлёт свой набор
следующим ответом.

**Почему reload шлёт запрос синхронизации сразу:** с адаптивным интервалом
следующий запрос может быть через минуту, и после полной перезагрузки мод целую
минуту работал бы на локальном конфиге.

**Общее правило:** команда не имеет права рапортовать об успехе, если она
ничего не сделала. Всегда проверяй результат по логу, а не по тексту ответа.

## Зависимости: FLK вложен в jar (JiJ)

`fabric-language-kotlin` у пользователя не должен быть — кладём его внутрь
своего jar через `include` в build.gradle:

```groovy
add(depType, "net.fabricmc:fabric-language-kotlin:${verProps.flk_version}")
add("include", "net.fabricmc:fabric-language-kotlin:${verProps.flk_version}")
```

**Обе строки обязательны.** `include` нужен для релизного jar (пользователю
ничего качать не надо), а обычная зависимость — для компиляции и для дев-запуска:
с одним только `include` `./gradlew runClient|runServer` падает с
`Mod 'CapeCraft' requires fabric-language-kotlin, which is missing!`
(в дев-режиме на classpath FLK не кладут, `include` там не участвует).

Проверено запуском dedicated-сервера 26.2 с одним лишь `capecraft-1.0.0.jar`
в `mods/` (без отдельного FLK): мод грузится, `KotlinAdapter` из вложенного FLK
резолвит entrypoint-ы, `/capecraft status` отвечает, ошибок ноль.

Дубль mod id не страшен: загрузчик грузит одну версию, верхнеуровневый jar
приоритетнее вложенного. Если у игрока свой FLK — выиграет его.

Двухуровневый JiJ штатный: сам FLK — мод с 13 вложенными jar'ами
(kotlin-stdlib/reflect, kotlinx-coroutines/serialization, atomicfu), загрузчик
разворачивает вложенные моды рекурсивно. Размер итогового jar ~8.3 МБ.

## Sync v2: клиент объявляет, сервер только рассылает

v1 был устроен наоборот и не имеет смысла: клиент слал только `requestId`,
сервер отвечал **своими** отобранными по серверному миру провайдерами.
Направление инвертировано, а `isServerAuthoritative` в `CapeRegistry` делал
чужой плащ зависимым от сервера.

v2 — сервер НЕ источник истины. Он ретранслирует объявления и ничего не решает.

```
C2S announce    игрок → сервер: свой набор провайдеров (с условием when)
C2S upload      игрок → сервер: байты картинки для kind=file, по чанкам
C2S fetch       игрок → сервер: «дай картинку с хэшем h, с оффсета n»
S2C roster      сервер → всем: снимок {игрок → его провайдеры} + ревизия
S2C chunk       сервер → запрашивающему: кусок картинки
```

Файл лежит на диске игрока, у сервера его нет и взять неоткуда — поэтому
заливает **владелец**. Из этого следует приятное свойство: клиент не может
указать на чужой диск в принципе, он предлагает только свой файл. Проверка
`allowFileProviders`/`isSafeFileTemplate` как защита от сервера больше не нужна —
защищаться не от кого. Вместо неё `shareLocalProviders` (по умолчанию **false**):
заливка личного файла на чужой сервер должна быть осознанной.

### Решения, которые неочевидны

**`when` едет по сети.** `Condition` — плоский AST (predicates → Predicate →
Op + Expected), вложенности нет, сериализуется за десяток строк. Без него все
увидели бы чужой плащ, посчитанный в биоме *объявившего* — видимый глюп. Условие
вычисляет каждый клиент сам, против контекста **наблюдаемого** игрока.

**Хэш контента как id картинки.** `file`-провайдер едет как `sha-256` (32 сырых
байта, не hex). Если 50 игроков носят один и тот же файл — одна загрузка, а не 50,
и дедуп на разных провайдерах тоже работает сам.

**Роустер — полный снимок с монотонной ревизией, а не дельта.** Дельты рассинхро-
нятся (пропущенный join/leave → навсегда кривой набор). Снимок самолечится, а
ревизия даёт дешёвую проверку «у меня свежо». Порядок: игроки по времени входа,
при нехватке места — старшие отбрасываются, ставится флаг `truncated`.

**Клиент не доверяет серверу.** Сервер валидирует форму и лимиты (он получает
недоверенный ввод), но ни одно его решение не авторитетно. Клиент перепроверяет
всё сам: `ActiveCape.validate()`, лимиты, и **декодирует картинку** перед тем как
показывать — иначе злой сервер скормит мусор, который уйдёт в GL.

**Версия 2 гейтится байтом в начале.** Пайлоад непрозрачный, поэтому версия
проверяется тривиально. Смешанные версии просто не синхронизируются, без падений.

**`CapeRegistry` становится per-player.** Сейчас `providers` — один глобальный
список на всех (`order()` отдаёт его каждому uuid). В v2 набор принадлежит игроку:
локальный игрок живёт из своего конфига, чужой — из роустера. Флаг
`isServerAuthoritative` и методы `useServerProviders`/`useLocalProviders` уходят.

Проверка живым стендом обязательна минимум на двоих: один объявляет локальный
`file`-cape, второй должен получить и роустер, и байты картинки. Один клиент
этого не проверяет — он единственный, кто знает свой набор.

## Вендоринг (koren/kjen)

koren и kjen — независимые проекты из `/manjaro-home/gg_tv/{koren,kjen}`.
Их исходники скопированы в `src/main/kotlin/dev/ggtv/` ДЛЯ ПОПАДАНИЯ В JAR
(mavenLocal-артефакты не упаковывались в наш jar). При обновлении библиотек —
подтягивать из тех репозиториев и синхронизировать копии + тесты в `src/test/...`.

### Границы в вендоренном коде

`Span` в kjen 1-based, и менять его нельзя: он уезжает в лог мода и в
`CrenError`, то есть это публичный формат. Для редактора нужен 0-based
`TextRange` с `end` — он живёт рядом, в `dev.ggtv.kjen`, и не подменяет `Span`.
Конструктор `Value.VDict.pairs` тоже не трогаем: он вендоренный и менять его
значит расходиться с оригиналом koren.

## IDE-разбор конфига (`capecraft/ide`, `capecraft/schema`)

Пакеты `ide` и `schema` — общий код **без MC API**, чтобы его можно было
тестировать и грузить в редактор. `ConfigSchema`/`WhenSchema` — единственный
источник истины про ключи: анализатор не дублирует списки полей, а спрашивает
схему, поэтому расхождение с модом невозможно по построению.

ОСТОРОЖНО: без MC API только `ide`, `schema` и `lsp`. Остальной общий код
(`CapeConfig`, `CapeAddonLoader`) тянет Fabric — общий source set не
version-free, и это ломало попытку собрать LSP отдельным подпроектом
(см. «LSP для редактора»).

- **Разбор терпимый и не бросает.** Закрытая строка, незакрытый `${...}`,
  комментарий и первая синтаксическая ошибка не должны ронять разбор: в
  редакторе файл с опечаткой в первой строке хуже, чем файл без подсветки.
  Всё после ошибки обязано остаться в дереве — `CrenParserTest` это проверяет.
- **`providers [ ... ]` без разделителя** — не опечатка, а форма из README и
  из парсера мода. Потерять её — значит потерять все провайдеры.
- **Запятая между записями блока — ошибка, а в словаре и массиве — нет.**
  `limits { maxFrames = 1, }` и `limits { a = 1 b = 2 }` игра не грузит
  целиком, а `{ a: 1, b: 2 }` и `[ {…}, {…} ]` читает. Разница историческая:
  фигурные скобки блока разделяет новая строка, скобки словаря — запятая.
  `CrenParser.parseEntries` получает `commasAllowed` и знает, где он.
- **Расхождение с игрой ловится тестом, а не глазами.**
  `CrenKorenAgreementTest` на 26 кусочках конфига проверяет обе стороны: игра
  прочитала — редактор молчит; игра отказалась — редактор сказал. Плюс все
  примеры из README. Починим `koren` — тест упадёт сам.
- **Курсор в разрыве после ключа** (`{ name | }`) — это позиция «что писать
  дальше», там предлагаются типы. Проверяется по тому, что между концом ключа и
  курсором только пробелы: в `{ name = "a", ty| }` дальше ключ, а не тип.
  Диапазон записи обрывается на последнем токене, поэтому курсор ищется и
  отдельно от диапазонов (`gapEntry`), иначе подсказки уезжают в родителя.
- **Неизвестный тип провайдера — предупреждение, не ошибка:** аддоны свои типы
  вешают штатно.
- **`key = |` без значения — это позиция значения.** Запись без значения есть в
  дереве (`form=ASSIGN`, `value=null`), но её диапазон кончается на ключе, и
  `entryAt`/`gapEntry` её не находят. Поэтому в `gapEntry` разрешён ровно один
  разделитель (`=` или `:`) между концом ключа и курсором — и только если
  запятой и перевода строки там нет. Иначе список уезжал в соседние ключи
  блока, то есть предлагал вставить ключ туда, где человек пишет значение.
  Проверено `CrenAnalyzerTest`: `enabled = |` → `true`/`false`, `type = |` →
  `url`/`json`/`file`, а на пустой строке после запятой — снова ключи.

## LSP для редактора (`capecraft/lsp`)

- **Отдельного gradle-подпроекта нет и не будет.** Gradle 9 не даёт
  безопасно резолвить `rootProject.configurations.compileClasspath` из другого
  проекта, а общий source set тянет Loom. Исходники лежат в корневом
  `src/main/kotlin/dev/ggtv/capecraft/lsp/`, задача — `lspJar` в корневом
  `build.gradle`.
- **Jar запускаемый и без Minecraft:** `lspRuntime` = kotlin-stdlib + slf4j
  (api и simple). `Main-Class: dev.ggtv.capecraft.lsp.MainKt`. Проверено, что
  внутри нет `net/minecraft`, `net/fabricmc`, `com/mojang`. Обычный `jar` мода
  наоборот исключает `dev/ggtv/capecraft/lsp/**` — в игре сервера быть не
  должно.
- **Никакого LSP4J/Gson:** свой JSON и своя рамка `Content-Length`
  (`RpcTransport`). `Content-Length` считается в **байтах** UTF-8, а не в
  символах: с кириллицей сервер иначе зависает.
- **Протокол — в stdout, логи — в stderr.** Смешивать нельзя: мусор в stdout
  ломает разбор кадров клиентом. `Main` держит два потока и перед выходом
  сбрасывает оба.
- **Сервер не падает на плохом запросе:** неизвестный метод → `-32601`, нет
  `params`/`range` → пустой ответ, `exit` без `shutdown` → код 1, чистый EOF →
  код 0, после `exit` цикл выходит сразу.
- **Кодировку позиций выбирает клиент, а не сервер.** `initialize` разбирает
  `general.positionEncodings` и берёт первую известную из `utf-16`, `utf-8`,
  `utf-32` (иначе `utf-16`), объявляет её в `positionEncoding`, и дальше все
  `point`/`range` считаются в ней. `CrenDocument.offsetOfClientPosition`
  переводит кодпоинты клиента в кодпоинты Kotlin, `clientColumnOf` — обратно;
  `unitsOf` честно различает эмодзи (2 UTF-16 code units, 1 UTF-8 code point,
  4 байта). Neovim предлагает `{"utf-8","utf-16","utf-32"}` — то есть сервер
  идёт по его внутреннему представлению, и колонки совпадают байт в байт.
- **`textDocument/diagnostic` (pull) отдаёт всегда `{"kind":"full",...}`.**
  Пуши не отключаем: `textDocumentSync = 1` остаётся, а инкрементальные
  изменения обрабатываются защитно — применяются по порядку, позиции
  зажимаются в границы текста, а `range`/`start`/`end` вне документа
  отбрасываются с логом в stderr и оставляют документ как был. Смена
  объявленного режима синхронизации ломала бы клиентов, которые шлют
  только полный текст.
- **Иерархию символов берём из `initialize`, а не из запроса.**
  `hierarchicalDocumentSymbolSupport` лежит в
  `capabilities.textDocument.documentSymbol` — capability приходит один раз
  в `initialize`, в параметрах `textDocument/documentSymbol` её нет. Если её
  нет или она `false` — отдаём плоский `SymbolInformation` со своим `location`
  (не пустым URI) и рекурсивно по всем вложенным записям, а не только по
  верхнему уровню.
- **Диагностика несёт `fixes`, а `codeAction` превращает их в TextEdit.**
  Клиент поле `data` не показывает, поэтому меню правок собирается на стороне
  сервера из диагностики под курсором.
- **Сниппет обязан идти с `insertTextFormat = 2`**, иначе клиент вставит
  `{\n\t$0\n}` буквально и файл станет невалидным.
- **Тесты сервера читают ответы тем же `RpcTransport`**, каким пишут их: свой
  разбор кадров в тесте проскочил бы мимо настоящей ошибки в заголовке.
- Neovim/Lazyvim: `root_dir` в новом `vim.lsp.config` — это
  `function(bufnr, on_dir)`, вызывающий `on_dir(path)`. Старая сигнатура
  `function(bufnr) return path end` из nvim-lspconfig молча ничего не делает,
  и сервер не стартует. Конфиг: `~/.config/nvim/lua/plugins/capecraft-lsp.lua`,
  сервер добавляется в `opts.servers` плагина `nvim-lspconfig` (импортера
  каталога `lua/lsp/` в этой версии LazyVim нет).

### Zed 1.21: подключение KoreN

- **Настройками новый язык не завести.** `settings.json` умеет только
  переопределять свойства уже известного языка. Блок `languages.KoreN` с
  `extensions`/`language_servers` грузится без ошибок, но сервер не
  стартует: имя языка никто не регистрировал. Нужно расширение
  (`tools/zed-koren`, ставится `./tools/zed-koren/install.sh`).
- **Расширение — это WASI-компонент, а не core-модуль.** Сборка под
  `wasm32-wasip1` даёт `failed to compile wasm component: ... attempted to
  parse a wasm module with a component parser`. Нужен
  `--target wasm32-wasip2`.
- **Просто скопировать `extension.wasm` в каталог мало.** Расширение должно
  лежать в `~/.local/share/zed/extensions/installed/<id>/` **и** быть
  описано в `~/.local/share/zed/extensions/index.json` (запись в
  `extensions` + в `languages` с `matcher.path_suffixes`). `work/<id>/`
  используется для dev-расширений, но Zed 1.21 всё равно читает из
  `installed/`. `index.json` Zed перезаписывает сам, поэтому после
  ручной правки он должен быть валидным JSON целиком.
- **Путь к `java` должен быть абсолютным.** `language_server_command`
  возвращает `Command { command: "java" }`, и Zed ищет `java` в рабочем
  каталоге расширения (`extensions/work/koren/java`) → `failed to spawn`.
  Работает `/usr/lib/jvm/java-26-openjdk/bin/java` (то, что даёт
  `readlink -f $(which java)`).
- **Ошибки расширения видны только в логе** Zed
  (`~/.local/share/zed/logs/Zed.log`, искать `[extension_host]` и
  `[language::language_registry]`); в UI их нет. Сам `Zed.log` —
  append-файл за все сессии, так что грепнуть надо по свежему времени, а не
  по `tail`: последние строки могут оказаться сильно старше.
- **`semantic_tokens: "full"`** обязателен: tree-sitter-грамматики у KoreN
  нет, при `combined` Zed не знает, что красить без неё.
- Проверка сквозного протокола: временная замена команды запуска на
  python-прокси, который переписывает кадры LSP в файл (учесть заголовок
  `Content-Length` — `readline()` по json без завершающего перевода строки
  встанет). Так видно, что Zed шлёт `textDocument/semanticTokens/full`,
  `textDocument/diagnostic`, `hover`.

### Пропадающий `blink.cmp`: незавершённая загрузка нативной библиотеки

- **Симптом:** `require("blink.cmp").is_active()` и `is_visible()` всегда
  `false`, меню не появляется ни в одном filetype (в том числе в `.lua`),
  список пунктов пуст, **в `:messages` нет ни ошибки, ни внятного варнинга**.
  Первое подозрение обычно падает на «испорченный конфиг» — он тут ни при
  чём.
- **Причина:** `cmp.setup()` ждёт `fuzzy.download.ensure_downloaded`, а тот
  грузит `libblink_cmp_fuzzy.so` в
  `~/.local/share/nvim/lazy/blink.cmp/target/release/`. Прерванная загрузка
  (закрытый nvim, убитый по timeout) оставляет рядом полный
  `libblink_cmp_fuzzy.so.tmp`, но **не делает финальное переименование** —
  `setup` не доходит до конца, и blink не регистрирует меню. Путь
  вычисляется в `lua/blink/cmp/fuzzy/download/files.lua`, а не в
  `~/.local/share/nvim/blink`: отсутствие последнего каталога ничего не
  значит.
- **Диагностика:** сравнить `sha256sum libblink_cmp_fuzzy.so.tmp` с
  `libblink_cmp_fuzzy.so.sha256`. Совпало — файл цел, достаточно
  `mv libblink_cmp_fuzzy.so.tmp libblink_cmp_fuzzy.so`. Не совпало или
  `.tmp` нет — дать blink допкачать библиотеку, не убивая сессию.
- **Обход без нативного кода:** `fuzzy = { implementation = "lua" }` в
  spec-плагине. Медленнее fuzzy, зато не зависит от загрузки бинарника.
- **Проверять blink только в tmux.** `nvim -c 'luafile ...'` не эмулирует
  ввод: `feedkeys(..., "x")` вводит символ, но восстанавливает режим как
  `:normal` (и `cmp.accept()` становится no-op), а `feedkeys(..., "t")` внутри
  синхронного скрипта не вводит ничего, потому что не крутится main loop.
  Рабочий вариант — `tmux send-keys` в живой сессии.
- **Итоговый конфиг** лежит в `__index`-прокси модуля
  `blink.cmp.config`, а не в возвращаемой таблице функций. `autotrigger` в
  blink 1.10 больше нет: это `completion.trigger.show_on_keyword`.
- **Кавычки вставляет сервер, а не blink:** подсказки значений несут
  `insertText = "\"...\""` (`CrenAnalyzer`), поэтому `type = |` после принятия
  даёт `type = "url"`.

## Портирование 26.2 (Mojang) → Yarn-версии

1.21.x версии между собой различаются только API, поэтому Semantic Sync v2
переносится с 26.2 таблицей подстановок `/tmp/opencode/port/sub.py`
(в репозиторий не кладётся — это одноразовый инструмент). Файлы, которые
нельзя переносить механически:

- `sync/CapeSyncPayloads.kt` — у Mojang `Type<T>` c id-ресурсом, в Yarn
  `CustomPayload.Id<T>` + `PacketCodec<RegistryByteBuf, T>`; пишется руками.
- `mixin/CapeFeatureRendererMixin.kt` — у каждой версии свой render API,
  оставляется свой, правится только вызов `ensureLoading` (передать
  `EntityWorldContext(игрок)`).
- `render/MinecraftWorldContext.kt` — API мира у Yarn другой, переносится
  один раз и копируется дальше как есть.

Что уже учтено в таблице и НЕ требует ручной правки:

| Mojang | Yarn |
| --- | --- |
| `Minecraft.getInstance()` | `MinecraftClient.getInstance()` |
| `.user.name` | `.session?.username` |
| `client.level` | `client.world` |
| `world.isClientSide()` | `world.isClient` |
| `world.getLevelData().getGameTime()` | `world.time` |
| `player.getStringUUID()` | `player.uuidAsString` |
| `player.getName().getString()` | `player.name.string` |
| `CommandSourceStack` | `ServerCommandSource` (`net.minecraft.server.command`) |
| `source.sendSuccess` | `source.sendFeedback` |
| `PayloadTypeRegistry.serverboundPlay()` | `playC2S()` |
| `PayloadTypeRegistry.clientboundPlay()` | `playS2C()` |
| `PoseStack` | `MatrixStack` |
| `StreamCodec` / `RegistryFriendlyByteBuf` | `PacketCodec` / `RegistryByteBuf` |
| `server.playerList.players` | `server.playerManager.playerList` |

**Две ловушки, на которые ушла время, повторять не надо:**

1. Регулярка с `\b` сразу после `()` **никогда не срабатывает**: `)` и
   следующий пробел оба не словесные, границы нет. Вид `X\.getY\(\)\b`
   молча не заменяет ничего, и таблица выглядит рабочей. Хвостовой `\b`
   после `)` убирать совсем.
2. Правило для `Minecraft.getInstance().user.name` должно срабатывать и на
   уже преобразованный `MinecraftClient.getInstance()`, иначе порядок
   правил важнее самих правил: `Minecraft(?:Client)?\.\.getInstance\(\)\.user\.name`.

Проверка порта: `./gradlew compileKotlin -Pmc=<версия> --console=plain` даёт
0 ошибок, но этого мало — компиляция не видит, что миксин не передал
`EntityWorldContext` (условия молча считались бы по своему миру). Поэтому
сверять надо руками, что в каждой версии: `EntityWorldContext` в миксине,
`describe()` в реестре, `publishOwnedImages()` в `onTick()`,
`registry.forget(it)` в дисконнекте, `settings.allowForeignUrls` в `applyRoster`.
