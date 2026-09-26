package dev.ggtv.capecraft

import dev.ggtv.capecraft.api.CapeAddonLoader
import dev.ggtv.capecraft.api.CapeApiHolder
import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.capecraft.sync.CapeSyncClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Точка входа CapeCraft (клиент).
 *
 * Клиент отвечает за рендер плащей и за скачивание картинок; набор
 * провайдеров при этом может прийти с сервера (см. [CapeSyncClient]) —
 * серверная половина живёт в [CapeCraftServer].
 *
 * На init:
 *  1. загружаем аддоны (entrypoint `capecraft:addons`) — они регистрируют
 *     свои типы провайдеров, декодеры и плейсхолдеры;
 *  2. читаем конфиг (провайдеры + лимиты + serverSync) — ProviderLoader
 *     видит аддон-типы;
 *  3. собираем реестр плащей;
 *  4. регистрируем команды `/cp`;
 *  5. подписываемся на тик клиента — чтобы при входе в мир локального
 *     игрока сразу подгрузить его плащ (диагностика в логе + кэш готов
 *     до первого кадра рендера);
 *  6. подписываемся на сетевую синхронизацию с сервером.
 *
 * MC 26.2: необфусцированные имена Mojang.
 */
class CapeCraftClient : ClientModInitializer {
    override fun onInitializeClient() {
        CapeAddonLoader.load()
        config = CapeConfig()
        registry = CapeRegistry(providers = config.providers, limits = config.limits, root = config.rootFor())
        CapeCommands.register(registry)
        CapeSyncClient.initialize()
        warmUpLocalPlayerCape()
        startAnimationTicker()
        LOGGER.info(
            "CapeCraft загружен (API $CAPE_RUNTIME_API_VERSION): " +
                "провайдеров ${config.providers.size}, " +
                "аддон-типов ${CapeApiHolder.api.sourceTypes.ids().size}, " +
                "аддон-декодеров ${CapeApiHolder.api.decoders.ids().size}",
        )
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
            val world = client.level ?: return@register
            if (!world.isClientSide()) return@register
            done = true

            val uuid = player.getStringUUID()
            val name = player.getName().getString()
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
            val world = client.level ?: return@register
            if (!world.isClientSide()) return@register
            tick++
            // ~100 мс = каждый 2-й тик, чтобы не гонять зря вхолостую.
            if (tick % 2 != 0) return@register
            registry.animate(world.getLevelData().getGameTime() * 50L)
            // Реалтайм-пересчёт условий провайдеров (день/ночь/погода/биом):
            // каждый ~20-й тик (~1 сек) проверяем, не сменился ли выбранный
            // провайдер, и при смене — бесшовно подгружаем новый плащ.
            if (tick % 20 == 0) {
                registry.refreshConditions(dev.ggtv.capecraft.render.MinecraftWorldContext)
                // Тот же тик — шаг синхронизации с сервером (интервал внутри).
                CapeSyncClient.onClientTick(inWorld = true)
            }
        }
    }

    companion object {
        const val MOD_ID: String = CapeCraftLog.MOD_ID
        const val ADDON_ENTRYPOINT: String = CapeAddonLoader.ENTRYPOINT

        /** Логгер мода (общий с серверной частью — см. [CapeCraftLog]). */
        val LOGGER = CapeCraftLog.LOGGER

        /** Конфиг мода (провайдеры + лимиты + serverSync). */
        lateinit var config: CapeConfig
            private set

        /** Реестр плащей (UUID → AnimatedImage). */
        lateinit var registry: CapeRegistry
            private set

        /** Статистика: число аддон-типов провайдеров (для логов). */
        val addonSourceTypes: Int get() = CapeApiHolder.api.sourceTypes.ids().size

        /** Статистика: число аддон-декодеров (для логов). */
        val addonDecoders: Int get() = CapeApiHolder.api.decoders.ids().size

        /** Пересоздать реестр после перезагрузки конфига (для `/cp reload`). */
        @Synchronized
        fun replaceRegistry(newRegistry: CapeRegistry) {
            // Остановить воркер старого реестра (фоновые загрузки уже не нужны).
            if (::registry.isInitialized) registry.shutdown()
            registry = newRegistry
        }
    }
}
