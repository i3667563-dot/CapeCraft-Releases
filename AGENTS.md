# AGENTS.md — CapeCraft

## Сборка (мультиверс)

Мод собирается под несколько версии Minecraft из одного репозитория.
Версия выбирается флагом **обязательным**:

- `./gradlew build -Pmc=1.21.1`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.4`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.8`    — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.10`   — yarn (Fabric mod)
- `./gradlew build -Pmc=1.21.11`   — yarn (Fabric mod)
- `./gradlew build -Pmc=26.2`      — необфусцированная MC (дефолт при отсутствии `-Pmc`)

Все сборки: BUILD SUCCESSFUL, 570 тестов, 0 failures (проверено 26.09.2026).
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
```

Все шесть версий: BUILD SUCCESSFUL, 519 тестов, 0 failures.

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

## Вендоринг (koren/kjen)

koren и kjen — независимые проекты из `/manjaro-home/gg_tv/{koren,kjen}`.
Их исходники скопированы в `src/main/kotlin/dev/ggtv/` ДЛЯ ПОПАДАНИЯ В JAR
(mavenLocal-артефакты не упаковывались в наш jar). При обновлении библиотек —
подтягивать из тех репозиториев и синхронизировать копии + тесты в `src/test/...`.