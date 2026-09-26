package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.ProviderLoader
import dev.ggtv.koren.KorenConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Конфиг провайдеров на СТОРОНЕ СЕРВЕРА (dedicated server и встроенный сервер).
 *
 * Класс намеренно НЕ переиспользует клиентский `CapeConfig`: на сервере
 * блок `serverSync` и клиентские лимиты памяти не нужны, а файл — тот же
 * самый `config/capecraft.kn`, только в папке сервера.
 *
 * Держит ровно ОДНУ изменяемую ссылку — [catalog]. Раньше здесь были
 * `providers` + `catalog`, и ответ клиенту мог достать старый каталог при
 * новом списке провайдеров (или наоборот): два @Volatile поля не атомарны
 * вместе. Перечитывается по `/capecraft reload`.
 */
class ServerCapeConfig private constructor(
    @Volatile
    var catalog: ServerCapeCatalog,
) {
    /** Сколько провайдеров сейчас в каталоге (для лога и `/capecraft status`). */
    val providerCount: Int get() = catalog.size

    /** Перечитать конфиг с диска и пересобрать каталог. */
    fun reload() {
        catalog = ServerCapeCatalog(readProviders())
    }

    companion object {
        /** Загрузить конфиг сервера; при любой ошибке — пустой каталог и запись в лог. */
        fun load(): ServerCapeConfig = ServerCapeConfig(ServerCapeCatalog(readProviders()))

        private fun readProviders(): List<Provider> {
            val configDir: Path = FabricLoader.getInstance().configDir
            val kn = configDir.resolve("capecraft.kn")
            val crn = configDir.resolve("capecraft.crn")
            val active = if (!Files.exists(kn) && Files.exists(crn)) crn else kn
            if (!Files.exists(active)) {
                CapeCraftLog.LOGGER.info(
                    "CapeCraft: серверный конфиг «${active.fileName}» не найден — " +
                        "активные плащи с сервера отдаваться не будут",
                )
                return emptyList()
            }
            return try {
                val providers = ProviderLoader.load(KorenConfig.load(active))
                CapeCraftLog.LOGGER.info(
                    "CapeCraft: серверный конфиг ${active.fileName} — провайдеров ${providers.size}",
                )
                providers
            } catch (e: Exception) {
                CapeCraftLog.LOGGER.error(
                    "CapeCraft: серверный конфиг ${active.fileName} не прочитан (${e.message}); " +
                        "ответ клиентам будет пустым",
                    e,
                )
                emptyList()
            }
        }
    }
}
