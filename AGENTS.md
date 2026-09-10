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

Все сборки: BUILD SUCCESSFUL, 376 тестов, 0 failures.

## Структура

```
src/main/kotlin/dev/ggtv/                 # ОБЩИЙ версион-независимый код
  koren/  kjen/                           # Вендоренные библиотеки (НЕ менять бесконтрольно)
  capecraft/{cren,image,schema,provider,memory,condition,api}/  # общий движок + addon API
src/main/resources/                       # общий mixins.json и ресурсы
versions/<mc>/src/main/kotlin/dev/ggtv/capecraft/   # версонно-зависимый код
  CapeCommands|CapeCraftClient|CapeRegistry|CapeTexture|CapeConfig? нет — конфиг общий
  mixin/, render/MinecraftWorldContext.kt, api/render/
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

(таблица неполная — доразбирать при правках рендер-кода в каждой версии)

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
./gradlew clean build -Pmc=1.21.1    # и 1.21.4, 1.21.8, 1.21.10, 1.21.11
./gradlew clean build -Pmc=26.2      # полная сборка 26.2 + тесты
```

## mixins.json

Версионный (в `versions/<mc>/src/main/resources/`), НЕ общий в `src/main/resources/`:
- во всех версиях только `CapeFeatureRendererMixin` (accessor был удалён во всех портах:
  ваниль НЕ отменяется, подменяется лишь текстура плаща в render-state — физика ванильная)

## Вендоринг (koren/kjen)

koren и kjen — независимые проекты из `/manjaro-home/gg_tv/{koren,kjen}`.
Их исходники скопированы в `src/main/kotlin/dev/ggtv/` ДЛЯ ПОПАДАНИЯ В JAR
(mavenLocal-артефакты не упаковывались в наш jar). При обновлении библиотек —
подтягивать из тех репозиториев и синхронизировать копии + тесты в `src/test/...`.