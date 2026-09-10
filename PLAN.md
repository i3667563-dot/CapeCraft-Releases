# CapeCraft — план работ

Мод плащей нового поколения: Fabric MC 26.2 (необфусцированный), Kotlin, JDK 26.
Статус пункта — `[x]` только когда функция реально работает и проверена
(сборка/тесты/игра), а не «просто запускается».

## Этап 1 — скелет проекта
- [x] Скаффолд: Fabric 26.2 + Kotlin (FLK), JDK 26, loom no-remap (ранее 1.21.10)
- [x] `./gradlew build` проходит, jar собирается
- [x] `fabric.mod.json` + entrypoint, мод виден в игре

## Этап 2 — конфигурация (порт Cren на Kotlin)
- [x] Токенизатор `.crn` (строки, числа, булы, комментарии, позиции)
- [x] Парсер (блоки, мульти-ключи, словари, массивы, ссылки)
- [x] Резолвер (абсолютные/относительные пути, `[n]`, циклы, Ambiguous)
- [x] `Config` API (get_str/get_int/.../keys/get_comment)
- [x] Тесты-порты из Rust-проекта `/home/gg_tv/Rust/cren/tests` (82 теста зелёные)

## Этап 3 — движок изображений
- [x] PNG-декодер: inflate (нативный zlib) + фильтры 0-4, Adam7, цветовые типы
  (truecolor/rgba/indexed/граyscale 1/8/16-бит), tRNS
- [x] APNG: acTL/fcTL/fdAT, кадры, dispose (none/background) + blend ops
- [x] GIF-декодер: LZW (LSB-first, KwKwK, словарь до 4096), GCT/LCT, интерлейс,
  прозрачность, GCE-задержки, dispose (none/background/previous), NETSCAPE2.0 loop
- [x] Общий формат кадра (IntArray ARGB) + AnimatedImage (длительности)
- [x] Тесты: генерация PNG/APNG/GIF программно + декод в тестах (PNG/APNG/GIF/WebP)
- [x] Автодетект формата по сигнатуре (png/apng/gif/webp/без расширения)
- [x] WebP-декодер (VP8L): заголовок/трансформы (predictor, color, subtract-green,
  color-indexing), Huffman, LZ77, цветовой кэш, анимация ANMF (blend/dispose)
- [x] Тесты WebP: RGBA, палитра 256, малая палитра с бандлингом, анимация,
  белый, RGB-градиент, детект по сигнатуре (7 тестов зелёные)
- [x] Тесты GIF: GCT/LCT, прозрачность, dispose, интерлейс, кадры, loop, детект
  (10 тестов зелёные) — починили KwKwK и NETSCAPE2.0 loop в декодере

## Этап 4 — провайдеры и схемы
- [x] Виды результата: прямая ссылка, локальный файл/директория, JSON-схема
- [x] Извлечение из вложенного JSON по инструкции (свой path-язык: `$.a.b[0].url`)
- [x] Плейсхолдеры `{username}`, `{uuid}`, `{name}`, `{root}`
- [x] HTTP-загрузка на `java.net.http`, таймауты, ошибки с контекстом
- [x] Загрузчик провайдеров из `.crn` (список провайдеров, порядок, fallback)
  — Provider.kt (Source/Resolved), Json.kt+JsonPath.kt, Placeholders.kt,
    HttpFetcher.kt+FileFetcher.kt, ProviderLoader.kt+resolveCape (fallback);
    тесты Json(10)+JsonPath(12)+Placeholders(6)+Provider/Loader/Resolve/File(13) зелёные

## Этап 5 — умная память
- [x] Лимиты: макс. пикселей, кадров, байт (на плащ и суммарно)
- [x] LRU-кэш кадров, вытеснение по памяти
- [x] Площадное сжатие (area-average) крупных плащей при нехватке памяти
- [x] Скип кадров у анимаций, если всё ещё не влезает
- [x] `AnimatedImage` — кадры декодируются на лету по времени
  — пакет memory/: Limits, LruCache, Scale (area-average), FrameThinning,
    MemoryManager (многоступенчатая деградация: размер кадра → байты → скип кадров);
    кадр по времени через AnimatedImage.frameAt(t) + LRU-кэш всех плащей.
    Тесты memory (LruCache 7 + Scale 5 + FrameThinning 6 + MemoryManager 7 = 25) зелёные.
    Полное поштучное ленивое декодирование из сжатого потока НЕ делаем — декодеры
    этапа 3 материализуют все кадры сразу; покрыто frameAt + прореживанием + сжатием.

## Этап 6 — интеграция Minecraft
- [ ] Реестр плащей (UUID → плащ), привязка по имени профиля
- [ ] Mixin рендера плаща (перехват CapeFeatureRenderer + свой рендер)
- [ ] Анимация по игровому времени (кадры APNG/GIF)
- [ ] Команда `/cp reload` (динамическая перезагрузка конфигов+плащей)
- [ ] Команды `/cp list`, `/cp status`, `/cp clear` (кэш)

## Этап 7 — качество
- [ ] Полная сборка `./gradlew build` + все тесты зелёные
- [ ] Проверка в игре (runClient): плащ PNG/APNG/GIF, reload
- [ ] AGENTS.md + README.md
- [ ] Бэкап и git-инициализация

## Правило 1 — зависимости
- Внешние зависимости — ни в коем случае (кроме Fabric API).
- Внутренние (JDK/Kotlin-библиотеки, Gradle-плагины) разрешаются и даже поощряются в сложных задачах — цель качественный движок, а не груда самостоятельного хлама.
- Разрешены как стандартная библиотека JDK, так и внешние — при условии, что они будут вшиты в jar'ник (shade/relocate), чтобы пользователь не лез в логи игры из-за отсутствующей библиотеки.

## Решения (зафиксировано)
- Загрузчик: Fabric, **MC 26.2** (необфусцированный — без маппингов; yarn умер на 1.21.11,
  в манифесте 26.2 нет client_mappings). loader 0.19.5, fabric-api 0.160.0+26.2
- Loom 1.17.20 no-remap: плагин `net.fabricmc.fabric-loom` (disableObfuscation),
  без строки `mappings`, `implementation` вместо mod* (mod*-конфигураций нет)
- FLK 1.14.1+kotlin.2.4.20, Kotlin 2.4.20, toolchain JDK 26, `release`/`jvmTarget` = 21
- MC-имена: real Mojang (unobfuscated 26.2), см. таблицу yarn→Mojang в AGENTS.md
- Рендер-пайплайн 26.2: `submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)`
- Команды `/cp` через `ClientCommands` (Fabric command-api-v2 3.1.0), тексты `Component`
- Без ImageIO/AWT для декодирования: свой декодер PNG/APNG/GIF на Kotlin
  поверх java.util.zip.Inflater (полный контроль скорости и памяти)
- HTTP через встроенный java.net.http (без новых зависимостей)
- Ядро (cren/image/schema) — чистый Kotlin без MC, покрыто JUnit

## Роадмап после релиза беты

### 0. Выпуск беты
- [ ] Довести Этап 6-7 до рабочего состояния: рендер/анимация проверены в игре
- [ ] `./gradlew build` + все тесты зелёные, jar готов к распространению
- [ ] README.md с установкой и настройкой провайдеров
- [ ] Релиз беты (тег + GitHub Release)

### 1а. `Kjen` — Kotlin-java Cren (JVM-порт чистого Cren)
> Прямой (дословный) порт Cren на JVM-платформы. То же, что Cren —
> тот же `.crn`-движок, без всякой специфики Minecraft.
> Выпустить официально как отдельную JVM-библиотеку.
- [x] Порт ядра Cren: токенизатор/парсер/резолвер `.crn` (1:1 с Этапом 2)
- [x] JVM-библиотека (`kjen`), публикация на Maven/артефакторию
  — maven-publish, `publishToMavenLocal` в `~/.m2` (`dev.ggtv:kjen:0.1.0`)
- [ ] Официальный релиз, версионирование, документация
- [x] Тесты-порты из Rust-проекта Cren (89 тестов зелёные)

### 1б. Формат `.kn` — KoreN (Kotlin creN, JVM-аналог Cren под специфику Minecraft)
> JVM-аналог Cren, но опирается на Minecraft и его функции, а не на базовые
> сравнения вроде `if .name == "test" { .name = "ok" }`.
> Это рабочий формат конфигов в моде. Выпустить официально как JVM-библиотеку.
- [x] Спецификация KoreN: JVM-аналог Cren с миникайфт-спецификой
  — `koren/SPEC.md` (формат `.kn`: world-корни, функции, динамика, миграция)
- [x] Лексика/парсер/резолвер `.kn` (наследует фичи `.crn` из Этапа 2 + MC-подложка)
  — KorenTokenizer (скобки `(`/`)`), KorenParser (вызовы функций → VFunc),
    KorenResolver (VFunc + world-корни), переиспользует Block/Entry/Value/Path/
    Span/CrenError/Type от kjen (`kjen` объявлен как `api`, kjen `Value` сделан
    не-sealed, чтобы Koren добавил VFunc)
- [x] Специфика Minecraft прямо в языке (см. ниже)
- [x] Миграция конфигов `.crn` → `.kn`, обратная совместимость чтения
  — CapeConfig читает `capecraft.kn`, при отсутствии — fallback на `capecraft.crn`
    (`.crn` ⊂ `.kn`); тесты ProviderLoaderTest переведены на KorenConfig
- [x] Официальный релиз KoreN как JVM-библиотеки + встроено в мод
  — `dev.ggtv:koren:0.1.0` в `~/.m2`, mod depends on `implementation "dev.ggtv:koren:0.1.0"`
- [x] Тесты KoreN (порт 89 kjen-тестов на обратную совместимость `.crn`
  + функции + world-корни + динамика: 119 тестов зелёные)

#### Специфика Minecraft в KoreN (отличает от Coren)
- [x] Блоки-сущности мира: `biome`, `weather`, `time`, `dimension`, `location`
  — ecnum WorldRoot + SPI WorldContext (игра реализует, передаёт в KorenConfig;
    `EmptyWorldContext` = корни недоступны). Конфиг-ключ в корне имеет приоритет над миром
- [x] Доступ к полям: `biome.temperature`, `weather.condition`, `dimension.id`
- [x] Логика на функциях Minecraft вместо базовых сравнений: вместо
  `if .name == "test" { .name = "ok" }` — условия по состоянию мира
- [x] Встроенные функции: `hash(uuid)`, `clamp`, `lerp`, `seq`, `min`, `max`, `abs`
- [x] Динамическая переоценка в игре без полной пересборки конфига
  — каждый `KorenConfig.get()` создаёт свежий KorenResolver: world-корни читаются
    из WorldContext живьём (world поле может меняться между get), функции тоже
    переоцениваются; `KorenConfig.withContext` меняет контекст без пересборки

> Примечание по названиям: Kjen = Kotlin-java creN (JVM-порт чистого Cren,
> без MC); KreN/KoreN = Kotlin creN (JVM-порт Cren под специфику Minecraft).

### 2. Парсер условий выбора провайдера
- [x] Условия `when`/`if` в `.kn` для привязки провайдера к контексту
  — блок `when: { поле: значение }` в словаре провайдера (`dev.ggtv.capecraft.condition`):
    ключ — `root.field` (точки в ключах словаря — новый синтаксис KoreN) или короткое
    имя корня; значение — строка/число с операторами `>`, `>=`, `<`, `<=`, `!`, `a..b`;
    все предикаты условия — AND; недоступное в мире поле = false, исключений нет
- [x] Предикаты: биом (напр. зимний/снежный), погода (ясно/дождь/гроза,
  тьма/пасмурно), время суток, измерение, координаты
  — поля WorldRoot: `biome.id/temperature/precipitation`, `weather.condition`,
    `time.tick/period`, `dimension.type/id`, `location.x/y/z`; семантические алиасы
    коротких имён: `biome: "snowy"` → precipitation=snow, `weather: "thunder"`,
    `time: "night"`, `dimension: "nether"`
- [x] Приоритет условий: при совпадении нескольких — побеждает условие с
  наивысшим приоритетом (не первое в списке)
  — `ProviderSelector.select`: совпавшие по убыванию `priority` (стабильно,
    при равном — порядок списка), затем default в порядке списка
- [x] Пример: `when biome: "snowy" → providerA`, `when weather: "rain" → providerB`,
  оба совпали → берётся условие с приоритетом выше — работает через `priority`
  (установленный в конфиге), реализация и тесты в `ConditionTest`/`ProviderSelectorTest`
- [x] `default`/fallback провайдер, если ни одно условие не подошло
  — провайдеры без `when` всегда активны; `resolveCape(..., world)` тянет
    `WorldContext` живого мира, по умолчанию `EmptyWorldContext`
- [x] Тесты логики выбора провайдера (приоритеты, пересечения, дефолт)
  — +31 тест (всего в моде 215), покрыта и `when`-парсинг в Koren.
    KoreN: новые dict-ключи с точками (парсер + 2 теста), переиздан в mavenLocal

> Функции мира в KoreN и `condition`-предикаты используют общие WorldRoot/WorldContext,
> так что условия провайдера и `.kn` в главном конфиге говорят на одном языке.

### 3. API для разработчиков (аддоны)
> Публичный стабильный контракт, чтобы сторонний мод-аддон расширял CapeCraft.
> Отличие от внутреннего API (`dev.ggtv.capecraft.*`): внутренний — без гарантий
> стабильности, addon-API — задокументированный и версионируемый фасад.
- [x] Официальный пакет `dev.ggtv.capecraft.api.*` (изолирован от внутренностей)
- [x] Регистрация **провайдеров** аддоном: добавить свой `Provider`/`Source`
  (своя логика получения URL, авторизация, свой источник)
- [x] Регистрация **форматов изображений**: аддон вешает декодер для нового
  формата картинки (сейчас декодеры захардкожены в `ImageDecoder.when`)
- [x] Регистрация **плейсхолдеров**: аддон добавляет свой `{placeholder}` в конфиг
- [x] Расширение **конфига** `.crn`: аддон объявляет свои ключи/секции
- [x] **События/хуки**: «плащ загружен», «кадр обновлён», «провайдер не найден»
- [x] Точка расширения рендера (позже — интеграция с парсером условий KoreN)
- [x] Реестр аддон-регистраций (`CapeAddon`/фабрика), инициализация в `entrypoint`
- [x] Документация API.md (раздел «Аддоны») + пример аддона (**лёгкий вариант**: `src/test/.../examples/ExampleCapeAddon.kt` + интеграционный тест, без отдельного Gradle-модуля)
- [ ] Версионирование: стабильность метода/между минорными версиями

### 3.5. Порт на MC 26.2 (ребейз с 1.21.10)
> 26.1+ — первая необфусцированная версия Minecraft: реальные имена Mojang, маппинги не нужны.
- [x] Сборка: loom 1.17.20 no-remap, loader 0.19.5, fabric-api 0.160.0+26.2, FLK 1.14.1+kotlin.2.4.20, JDK 26
- [x] Переименование yarn→Mojang во всех файлах: Minecraft/Identifier/PoseStack/Component/Level и др. (таблица в AGENTS.md)
- [x] Рендер-пайплайн 26.2: `render` → `submit`, `OrderedRenderCommandQueue` → `SubmitNodeCollector`,
  `PlayerEntityRenderState` → `AvatarRenderState`, `submodel` через `collector.submitModel`
- [x] Текстуры 26.2: `DynamicTexture`/`NativeImage.setPixel`, `TextureManager.register/release`, `RenderTypes.entitySolid`
- [x] Мир 26.2: clock-times (`getDefaultClockTime`, `getLevelData().getGameTime`), `getBiomeManager`, `dimension()`
- [x] `./gradlew compileKotlin`/`test`/`build` зелёные: 248 тестов
- [ ] Проверка в игре (runClient) на 26.2: рендер плаща/анимация/reload

### 3.6. Мультиверс: 1.21.x (yarn) + 26.2 (Mojang) из одного репозитория
> Оба сборка завязаны на общий код (cren/koren/kjen/image/schema/provider/condition/api),
> версионно-зависимая обвязка (команды, клиент, реестр, текстуры, mixin, render-контекст) живёт в `versions/<mc>/`.
> Выбор версии: `./gradlew build -Pmc=1.21.1` / `-Pmc=1.21.4` / `-Pmc=1.21.8` / `-Pmc=1.21.10` / `-Pmc=1.21.11` / `-Pmc=26.2` (дефолт — 26.2).
- [x] Вендоринг koren/kjen из отдельного проекта (`/manjaro-home/gg_tv/{koren,kjen}`) в `src/main/kotlin/dev/ggtv/`
  — раньше koren/kjen подключались через mavenLocal, из-за чего их классы не попадали в jar
- [x] Общий код отделён от версионного: `versions/*` (по 10 main + 3 test файлов + fabric.mod.json + gradle.properties)
- [x] Структура build.gradle: conditional loom (`fabric-loom` remap для yarn-версий,
  `net.fabricmc.fabric-loom` no-remap для необфусцированных), sourceSets на `versions/<mc>/`,
  toolchain/`release` из версии (21 для 21.x, 26 для 26.2); foojay-resolver-convention в settings.gradle
  — в loom 1.17.x `net.fabricmc.fabric-loom` — это no-remap маркер, `mappings` в dependencies
  доступны только у `fabric-loom` (legacy/remap), поэтому плагин выбирается условно
- [x] Порт-диапазон 21.x: 1.21.1 (классический render + живая сущность + renderCape;
  нет поля model в CapeFeatureRenderer → mixins без accessor, setColor ABGR без setColorArgb),
  1.21.4 (RenderState + VertexConsumerProvider), 1.21.8 (RenderState + VertexConsumerProvider,
  без outlineColor в state), 1.21.10 (OrderedRenderCommandQueue.submitModel), 1.21.11
  (как 1.21.10, но `RenderLayers.entitySolid` вместо `RenderLayer.getEntitySolid`)
- [x] `./gradlew clean build -Pmc=<любая версия>`: BUILD SUCCESSFUL, 376 тестов каждая, 0 failures
  (проверено для 1.21.1/1.21.4/1.21.8/1.21.10/1.21.11/26.2)
- [x] CI: matrix build по всем версиям (1.21.1/1.21.4/1.21.8/1.21.10/1.21.11/26.2)
- [ ] Проверка в игре (runClient) на каждой версии: рендер плаща/анимация/reload

---

## Философия Cren

Cren — это спецификация формата `.crn`, а не привязка к языку.
Любой может реализовать порт на любую платформу (JVM, C#, Go, Python, Elixir),
если следует спецификации. Официальные порты:
- Cren (Rust) — оригинал
- Kjen (JVM/Kotlin) — официальный JVM-порт

Форки (KoreN и другие) могут быть основаны на ЛЮБОЙ реализации Cren,
главное — доступ к исходникам форкаемой реализации.