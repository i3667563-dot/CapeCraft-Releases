package dev.ggtv.capecraft

import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.sync.ServerSyncSettings
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

    /** Старый файл формата `.crn` — читается как fallback, если `.kn` нет. */
    val legacyPath: Path = FabricLoader.getInstance().configDir.resolve(CapeConfigFiles.CRN_NAME)

    private val rootDir: Path
        get() = FabricLoader.getInstance().gameDir

    init {
        reload()
    }

    /** Перечитать конфиг с диска (для `/cp reload`). */
    fun reload() {
        try {
            val active = CapeConfigFiles.active(path.parent)
            if (CapeConfigFiles.mustCreateDefault(active)) writeDefault()
            val cfg = KorenConfig.load(active)
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

    /** Корень для плейсхолдера `{root}` — папка игры (для локальных файлов). */
    fun rootFor(): String = rootDir.toString()

    private fun parseLimits(cfg: KorenConfig): Limits {
        // Если ключа limits нет — оставляем дефолт.
        return try {
            val base = Limits()
            Limits(
                maxPixelsPerFrame = cfg.getIntOr("capeCraft.limits.maxPixelsPerFrame", base.maxPixelsPerFrame),
                maxFrames = cfg.getIntOr("capeCraft.limits.maxFrames", base.maxFrames.toLong()).toInt(),
                maxBytesPerCape = cfg.getIntOr("capeCraft.limits.maxBytesPerCape", base.maxBytesPerCape),
                maxBytesTotal = cfg.getIntOr("capeCraft.limits.maxBytesTotal", base.maxBytesTotal),
            )
        } catch (e: Exception) {
            lastError = "лимиты: ${e.message}"
            Limits()
        }
    }

    private fun writeDefault() {
        val text = """
            # CapeCraft — конфиг плащей (.kn, надмножество .crn).
            # Провайдеры проверяются по порядку: первый успешный отдаёт плащ (fallback).
            capeCraft {
                providers [
                    # URL-провайдер: прямая ссылка на картинку.
                    # {username} — имя игрока, {uuid} — UUID без дефисов.
                    { name = "example", type = "url", url = "https://example.com/capes/{username}.png" },
                    # JSON-провайдер: тянем URL плаща из JSON по инструкции.
                    # { name = "api", type = "json", url = "https://api.example.com/cape?u={username}", extract = "$.data.cape_url" },
                    # Локальный файл в папке игры: {root} = папка игры.
                    { name = "local", type = "file", path = "{root}/capes/{uuid}.png" }
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
                    # Опрашивать ли сервер об активных плащах.
                    enabled = true
                    # Как часто спрашивать (тиков, 20 = сек): 40 = раз в 2 сек.
                    intervalTicks = 40
                    # Сколько ждать ответа (тиков): 100 = 5 сек.
                    timeoutTicks = 100
                    # true = без ответа сервера локальный набор НЕ используется.
                    requireServer = false
                    # Разрешить серверу присылать `type = file` (чтение с диска
                    # клиента). По умолчанию запрещено из соображений безопасности.
                    allowFileProviders = false
                }
            }
        """.trimIndent()
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun KorenConfig.getIntOr(path: String, def: Long): Long = try {
        getInt(path)
    } catch (e: Exception) {
        def
    }
}
