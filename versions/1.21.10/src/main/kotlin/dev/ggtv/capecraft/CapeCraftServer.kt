package dev.ggtv.capecraft

import dev.ggtv.capecraft.api.CapeAddonLoader
import dev.ggtv.capecraft.sync.CapeSyncServer
import net.fabricmc.api.ModInitializer

/**
 * Точка входа CapeCraft на СТОРОНЕ СЕРВЕРА (common `main`-entrypoint).
 *
 * Выполняется и на выделенном сервере, и на встроенном сервере клиента
 * (клиент с модом = клиент + сервер в одной JVM). Клиентская половина
 * инициализируется отдельно в [CapeCraftClient] и отвечает за рендер и
 * скачивание картинок.
 *
 * Порядок важен: сначала аддоны (они регистрируют типы провайдеров, нужные
 * при разборе `.kn`), потом приём сетевых запросов, потом конфиг.
 *
 * MC 1.21.x: имена yarn.
 */
class CapeCraftServer : ModInitializer {
    override fun onInitialize() {
        CapeAddonLoader.load()
        CapeSyncServer.initialize()
        CapeCraftLog.LOGGER.info("CapeCraft: серверная часть инициализирована")
    }
}
