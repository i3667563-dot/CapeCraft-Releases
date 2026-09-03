package dev.ggtv.capecraft

import dev.ggtv.capecraft.cren.CrenConfig
import dev.ggtv.capecraft.memory.Limits
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.capecraft.provider.Provider
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Загрузка конфига мода из `config/capecraft.crn`.
 *
 * Формат (см. PLAN.md, этап 4):
 * ```
 * capeCraft {
 *     providers [
 *         { name = "trusted", type = "url",  url = "https://.../{username}.png" }
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
 * Внимание: в словарях (фигурные скобки) пары разделяются запятыми — CREN
 * не принимает записи через пробел, как обычный `key = value`. То же самое
 * для элементов массива (квадратные скобки): между ними нужны запятые.
 *
 * Если файла нет — создаёт дефолтный и использует его. Ошибки парсинга
 * не роняют мод: [providers]/[limits] остаются дефолтными, а описание
 * кладётся в [lastError] для `/cp status`.
 */
class CapeConfig {
    @Volatile
    var providers: List<Provider> = emptyList()
        private set

    @Volatile
    var limits: Limits = Limits()
        private set

    @Volatile
    var lastError: String? = null
        private set

    val path: Path = FabricLoader.getInstance().configDir.resolve("capecraft.crn")

    private val rootDir: Path
        get() = FabricLoader.getInstance().gameDir

    init {
        reload()
    }

    /** Перечитать конфиг с диска (для `/cp reload`). */
    fun reload() {
        try {
            if (!Files.exists(path)) writeDefault()
            val cfg = CrenConfig.load(path)
            providers = ProviderLoader.load(cfg)
            limits = parseLimits(cfg)
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            CapeCraftClient.LOGGER.error("CapeCraft: не удалось прочитать конфиг: ${e.message}", e)
        }
    }

    /** Корень для плейсхолдера `{root}` — папка игры (для локальных файлов). */
    fun rootFor(): String = rootDir.toString()

    private fun parseLimits(cfg: CrenConfig): Limits {
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
            # CapeCraft — конфиг плащей.
            # Провайдеры проверяются по порядку: первый успешный отдаёт плащ (fallback).
            capeCraft {
                providers [
                    # URL-провайдер: прямая ссылка на картинку.
                    # {username} — имя игрока, {uuid} — UUID без дефисов.
                    { name = "example", type = "url", url = "https://example.com/capes/{username}.png" }
                    # JSON-провайдер: тянем URL плаща из JSON по инструкции.
                    # { name = "api", type = "json", url = "https://api.example.com/cape?u={username}", extract = "$.data.cape_url" }
                    # Локальный файл в папке игры: {root} = папка игры.
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
            }
        """.trimIndent()
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun CrenConfig.getIntOr(path: String, def: Long): Long = try {
        getInt(path)
    } catch (e: Exception) {
        def
    }
}
