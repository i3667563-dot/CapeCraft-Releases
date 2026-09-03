package dev.ggtv.capecraft

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient

/**
 * Точка входа CapeCraft (клиент).
 *
 * Мод целиком клиентский: плащи рендерятся локально,
 * серверная часть не затрагивается.
 *
 * На init:
 *  1. читаем конфиг (провайдеры + лимиты);
 *  2. собираем реестр плащей;
 *  3. регистрируем команды `/cp`;
 *  4. подписываемся на тик клиента — чтобы при входе в мир локального
 *     игрока сразу подгрузить его плащ (диагностика в логе + кэш готов
 *     до первого кадра рендера).
 */
class CapeCraftClient : ClientModInitializer {
    override fun onInitializeClient() {
        config = CapeConfig()
        registry = CapeRegistry(providers = config.providers, limits = config.limits, root = config.rootFor())
        CapeCommands.register(registry)
        warmUpLocalPlayerCape()
        startAnimationTicker()
        LOGGER.info("CapeCraft загружен (0.1.0): провайдеров ${config.providers.size}")
    }

    /**
     * При появлении локального игрока в мире — предзагрузить его плащ
     * (фоном, без блокировки рендера) и залогировать результат.
     */
    private fun warmUpLocalPlayerCape() {
        var done = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done) return@register
            val player = client.player ?: return@register
            val world = client.world ?: return@register
            if (!world.isClient) return@register
            done = true

            val uuid = player.uuidAsString
            val name = player.name.string
            // Планируем фоновую загрузку; готовый плащ подхватит animate.
            registry.ensureLoading(uuid, name)
        }
    }

    /**
     * Тикер анимации: ~15 раз в секунду обновляет текстуры анимированных плащей
     * до текущего кадра по игровому времени. Это вынесено ИЗ рендера — иначе
     * перезапись пикселей + upload на каждый кадр рендера просаживают FPS.
     *
     * Здесь же тикер подхватывает плащи, догруженные фоновым воркером:
     * создаёт их текстуры (один раз) и дальше крутит кадры анимации. Сам декод
     * и деградация уже сделаны воркером — на этом потоке только лёгкий upload.
     */
    private fun startAnimationTicker() {
        var tick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val world = client.world ?: return@register
            if (!world.isClient) return@register
            tick++
            // ~100 мс = каждый 2-й тик, чтобы не гонять зря вхолостую.
            if (tick % 2 != 0) return@register
            registry.animate(world.time * 50L)
        }
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
            // Остановить воркер старого реестра (фоновые загрузки уже не нужны).
            if (::registry.isInitialized) registry.shutdown()
            registry = newRegistry
        }
    }
}
