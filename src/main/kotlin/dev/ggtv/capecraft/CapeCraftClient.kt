package dev.ggtv.capecraft

import net.fabricmc.api.ClientModInitializer

/**
 * Точка входа CapeCraft (клиент).
 *
 * Мод целиком клиентский: плащи рендерятся локально,
 * серверная часть не затрагивается.
 *
 * На init:
 *  1. читаем конфиг (провайдеры + лимиты);
 *  2. собираем реестр плащей;
 *  3. регистрируем команды `/cp`.
 */
class CapeCraftClient : ClientModInitializer {
    override fun onInitializeClient() {
        config = CapeConfig()
        registry = CapeRegistry(providers = config.providers, root = config.rootFor())
        CapeCommands.register(registry)
        LOGGER.info("CapeCraft загружен (0.1.0): провайдеров ${config.providers.size}")
    }

    companion object {
        const val MOD_ID = "capecraft"
        val LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID)

        /** Конфиг мода (провайдеры + лимиты). */
        lateinit var config: CapeConfig
            private set

        /** Реестр плащей (UUID → AnimatedImage). */
        lateinit var registry: CapeRegistry
            private set

        /** Пересоздать реестр после перезагрузки конфига (для `/cp reload`). */
        @Synchronized
        fun replaceRegistry(newRegistry: CapeRegistry) {
            registry = newRegistry
        }
    }
}
