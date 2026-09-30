package dev.ggtv.capecraft

import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.sync.ServerSyncSettings
import dev.ggtv.kjen.CrenError
import dev.ggtv.koren.KorenConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Загрузка конфига мода из `config/capecraft.kn`.
 *
 * Формат — `.kn` (KoreN), надмножество `.crn` (см. koren/SPEC.md):
 * ```
 * capeCraft {
 *     providers [
 *         { name = "trusted", type = "url",  url = "https://.../{username}.png" },
 *         { name = "local",   type = "file", path = "{root}/capes/{uuid}.png" }
 *     ]
 *     limits {
 *         maxPixelsPerFrame = 4000000
 *         maxFrames         = 100
 *         maxBytesPerCape   = 67108864
 *         maxBytesTotal     = 134217728
 *     }
 * }
 * ```
 *
 * Обратная совместимость: если `capecraft.kn` нет, но есть старый
 * `capecraft.crn` — он читается тем же парсером (`.crn` ⊂ `.kn`).
 *
 * Внимание: в словарях (фигурные скобки) пары разделяются запятыми — форма
 * не принимает записи через пробел, как обычный `key = value`. То же самое
 * для элементов массива (квадратные скобки): между ними нужны запятые.
 *
 * Если файла нет — создаёт дефолтный и использует его. Ошибки парсинга
 * не роняют мод: [providers]/[limits] остаются дефолтными, а описание
 * кладётся в [lastError] для `/cp status`.
 *
 * Класс работает и на клиенте, и на выделенном сервере (тот же файл
 * `config/capecraft.kn` в папке сервера — источник провайдеров для
 * синхронизации, см. `capeCraft.sync.ServerCapeCatalog`).
 */
/**
 * Шаблон `config/capecraft.kn`, который создаётся при первом запуске.
 *
 * Вынесен в константу ради тестируемости: [CapeConfig] берёт путь из
 * `FabricLoader`, в тестах её не создать, а шаблон должен быть проверяемым.
 * Пока он жил внутри метода, в нём месяцами лежали ключи v1
 * (`intervalTicks`, `requireServer`, `allowFileProviders`), которые
 * [dev.ggtv.capecraft.sync.ServerSyncSettings] уже не читает, и два активных
 * провайдера-заглушки, из-за чего чистая установка считала их своими.
 */
internal val DEFAULT_KN_TEXT: String = """
            # CapeCraft — конфиг плащей (.kn, надмножество .crn).
            # Провайдеры проверяются по порядку: первый успешный отдаёт плащ (fallback).
            capeCraft {
                providers [
                    # URL-провайдер: прямая ссылка на картинку.
                    # {username} — имя игрока, {uuid} — UUID без дефисов.
                    # Все три примера ниже закомментированы намеренно: на чистой
                    # установке активных провайдеров нет, и мод не пытается никуда
                    # ходить. Раскомментируй или допиши свой — это единственное,
                    # что нужно для работы.
                    #
                    # URL-провайдер: прямая ссылка на картинку.
                    # {username} — имя игрока, {uuid} — UUID без дефисов.
                    # { name = "example", type = "url", url = "https://example.com/capes/{username}.png" },
                    # JSON-провайдер: тянем URL плаща из JSON по инструкции.
                    # { name = "api", type = "json", url = "https://api.example.com/cape?u={username}", extract = "$.data.cape_url" },
                    # Локальный файл в папке игры: {root} = папка игры.
                    # Чтобы его увидели другие, включи shareLocalProviders ниже.
                    # { name = "local", type = "file", path = "{root}/capes/{uuid}.png" }
                ]
                limits {
                    # Пикселей в одном кадре (ширина*высота), дальше — сжатие.
                    maxPixelsPerFrame = 4000000
                    # Максимум кадров в анимации, дальше — скип кадров.
                    maxFrames = 100
                    # Байт под пиксели одного плаща (все кадры).
                    maxBytesPerCape = 67108864
                    # Суммарно байт под все плащи в кэше.
                    maxBytesTotal = 134217728
                }
                serverSync {
                    # Объявлять ли свой набор и принимать чужие (протокол v2).
                    # В v1 здесь был опрос по таймеру; в v2 клиент объявляет
                    # один раз при входе и при /cp reload, а обновления приходят
                    # рассылкой, поэтому intervalTicks/timeoutTicks больше не нужны.
                    enabled = true
                    # Отдавать ли другим свой локальный `file`-плащ: байты уедут
                    # на сервер и оттуда ко всем, кто на меня смотрит. Выключено
                    # по умолчанию. У `http`/`json` отдельного согласия не нужно.
                    shareLocalProviders = false
                    # Принимать ли чужие объявления с чужим `http`-адресом.
                    # Выключено по умолчанию: иначе мой рендер становится маячком
                    # для адресов, придуманных другим игроком.
                    allowForeignUrls = false
                    # Замедлять запросы картинок, пока нет ответа.
                    backoff = true
                }
            }
""".trimIndent()

class CapeConfig {
    @Volatile
    var providers: List<Provider> = emptyList()
        private set

    @Volatile
    var limits: Limits = Limits()
        private set

    /** Настройки синхронизации с сервером (блок `capeCraft.serverSync`). */
    @Volatile
    var serverSync: ServerSyncSettings = ServerSyncSettings()
        private set

    @Volatile
    var lastError: String? = null
        private set

    val path: Path = FabricLoader.getInstance().configDir.resolve(CapeConfigFiles.KN_NAME)

    /**
     * Файл конфига, который реально прочитан.
     *
     * Не путать с [path]: тот — всегда `config/capecraft.kn`, а прочитать можно
     * файл из `CAPECRAFT_CONFIG`. Команды раньше печатали [path] и тем самым
     * врали о том, откуда взят конфиг, — при внешнем файле у человека в
     * `/cp reload` был виден путь, который мод не открывал.
     */
    @Volatile
    var activeSource: String = path.toString()
        private set

    /**
     * Почему пришлось откатиться с файла из окружения на обычный, либо `null`.
     *
     * Держится отдельно от [lastError]: конфиг-то прочитан, и плащи работают.
     * `lastError` для этого не годится — он означает «плащей не будет» и
     * сбрасывается в конце успешной загрузки.
     */
    @Volatile
    var sourceWarning: String? = null
        private set

    /** Старый файл формата `.crn` — читается как fallback, если `.kn` нет. */
    val legacyPath: Path = FabricLoader.getInstance().configDir.resolve(CapeConfigFiles.CRN_NAME)

    private val rootDir: Path
        get() = FabricLoader.getInstance().gameDir

    init {
        reload()
    }

    /** Пречитать конфиг с диска (для `/cp reload`). */
    fun reload() {
        try {
            val resolution = CapeConfigFiles.resolve(resolveConfigFile(), path.parent)
            activeSource = resolution.file.toString()
            sourceWarning = resolution.warning
            // Предупреждение, а не ошибка: конфиг прочитан, плащи работают.
            // ERROR здесь пугал бы пользователя, у которого всё наоборот — есть
            // чем пользоваться, но не тот источник, который он просил.
            resolution.warning?.let { CapeCraftLog.LOGGER.warn("CapeCraft: {}", it) }
            if (resolution.createDefault) writeDefault()
            // Незаданная переменная окружения — не поломка конфига: парсер
            // подставляет пустую строку, а плащ без адреса просто не грузится.
            // Но молчать об этом нельзя: `url = "$HOST/api"` и `url = ""` в
            // логе выглядят одинаково, а адрес у провайдера может быть один
            // на весь конфиг. Один раз на имя — иначе пять тысяч строк на
            // каждый кадр.
            val unset = HashSet<String>()
            val cfg = KorenConfig.load(resolution.file) { unset += it }
            unset.forEach {
                CapeCraftLog.LOGGER.warn(
                    "CapeCraft: переменная окружения ${'$'}{} не задана, подставлена пустая строка",
                    it,
                )
            }
            providers = ProviderLoader.load(cfg)
            limits = parseLimits(cfg)
            serverSync = ServerSyncSettings.parse(cfg)
            dev.ggtv.capecraft.api.CapeApiHolder.api.config.loadFrom(cfg)
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            CapeCraftLog.LOGGER.error("CapeCraft: не удалось прочитать конфиг: ${e.message}", e)
        }
    }

    /**
     * Файл конфига из `CAPECRAFT_CONFIG`, либо `null` — читаем обычный
     * `config/capecraft.kn`. Сам выбор, включая откат с недоступного внешнего
     * файла, делает [CapeConfigFiles.resolve].
     */
    private fun resolveConfigFile(): CapeConfigEnv.ConfigFile? = CapeConfigEnv.configFile()

    /** Корень для плейсхолдера `{root}` — папка игры (для локальных файлов). */
    fun rootFor(): String = rootDir.toString()

    private fun parseLimits(cfg: KorenConfig): Limits {
        // Если ключа limits нет — оставляем дефолт.
        return try {
            val base = Limits()
            Limits(
                maxPixelsPerFrame = limit(cfg, "maxPixelsPerFrame", base.maxPixelsPerFrame),
                // maxFrames — единственный лимит, который нельзя выдать за
                // 4 миллиарда кадров: здесь режем до Int, иначе опечатка в
                // переменной окружения превратится в NegativeArraySize.
                maxFrames = limit(cfg, "maxFrames", base.maxFrames.toLong())
                    .coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                maxBytesPerCape = limit(cfg, "maxBytesPerCape", base.maxBytesPerCape),
                maxBytesTotal = limit(cfg, "maxBytesTotal", base.maxBytesTotal),
            )
        } catch (e: Exception) {
            lastError = "лимиты: ${e.message}"
            Limits()
        }
    }

    /**
     * Лимит из файла, если он задан, иначе переопределение из окружения, иначе
     * дефолт. Порядок именно такой: переменная лаунчера должна побеждать
     * значение из `.kn`, но не должна затирать его, если её не задавали.
     */
    private fun limit(cfg: KorenConfig, key: String, def: Long): Long =
        CapeConfigEnv.longOr("capeCraft.limits.$key", cfg.getIntOr("capeCraft.limits.$key", def))

    private fun writeDefault() {
        val text = DEFAULT_KN_TEXT
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    /**
     * Лимит из `.kn`, иначе дефолт.
     *
     * Отсутствие ключа — это нормально, берём дефолт молча. Несовпадение типа —
     * нет: пользователь написал значение, а мод затихо заменил его своим. Раньше
     * здесь стоял `catch (e: Exception)`, и подстановка окружения
     * (`maxBytesTotal = "${CAPE_CACHE_BYTES}"`) давала `str` вместо `int`, после
     * чего мод использовал дефолт и не писал в лог ничего. Подстановка всегда
     * возвращает строку, поэтому для чисел и булевых нужен `CapeConfigEnv`
     * (`CAPECRAFT_LIMITS_*` или `-DcapeCraft.limits.*`), и об этом сказано прямо
     * в сообщении.
     */
    private fun KorenConfig.getIntOr(path: String, def: Long): Long = try {
        getInt(path)
    } catch (_: CrenError.NotFound) {
        def
    } catch (e: CrenError.TypeMismatch) {
        CapeCraftLog.LOGGER.warn(
            "{}: в конфиге ожидалось {}, а там строка — беру дефолт {}. " +
                "Подстановка окружения всегда даёт строку, для числа нужно {} " +
                "или -D{}=...",
            path,
            e.expected,
            def,
            CapeConfigEnv.variableFor(path),
            path,
        )
        def
    }
}
