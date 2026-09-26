package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.CapeCraftLog
import net.fabricmc.loader.api.FabricLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Загрузка аддонов через Fabric entrypoint `capecraft:addons`.
 *
 * Вызывается и клиентским, и серверным entrypoint'ом ДО чтения конфига:
 * аддоны регистрируют типы провайдеров, декодеры и плейсхолдеры, которые
 * нужны при разборе `.kn`. Упавший аддон не роняет мод — ошибка попадает
 * в лог, остальные аддоны грузятся как обычно.
 *
 * Идемпотентна: на клиенте с модом оба entrypoint'а живут в одной JVM, и без
 * защиты `register` аддона выполнялся бы дважды (двойная регистрация декодеров
 * и плейсхолдеров). Второй и последующие вызовы — no-op.
 */
object CapeAddonLoader {

    const val ENTRYPOINT: String = "capecraft:addons"

    private val loaded = AtomicBoolean(false)

    /** Загрузить все аддоны; вернуть число успешно зарегистрированных. */
    fun load(): Int {
        if (!loaded.compareAndSet(false, true)) return 0
        val containers = FabricLoader.getInstance()
            .getEntrypointContainers(ENTRYPOINT, CapeAddon::class.java)
        val api = CapeApiHolder.api
        var ok = 0
        for (c in containers) {
            try {
                c.entrypoint.register(api)
                CapeCraftLog.LOGGER.info(
                    "CapeCraft: аддон «${c.provider.metadata.id}» зарегистрирован",
                )
                ok++
            } catch (e: Exception) {
                CapeCraftLog.LOGGER.error(
                    "CapeCraft: аддон «${c.provider.metadata.id}» упал при регистрации: ${e.message}",
                    e,
                )
            }
        }
        return ok
    }
}
