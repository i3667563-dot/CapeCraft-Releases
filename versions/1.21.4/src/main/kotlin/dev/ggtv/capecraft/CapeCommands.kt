package dev.ggtv.capecraft

import dev.ggtv.capecraft.CapeConfigEnv
import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.sync.CapeSyncClient
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * Команды `/cp` для управления плащами на лету.
 *
 *  - `/cp reload` — перечитать конфиг провайдеров и сбросить кэш плащей;
 *  - `/cp list`   — показать закэшированные плащи (UUID) и объём памяти;
 *  - `/cp status` — общее состояние: число плащей, память, лимиты, sync;
 *  - `/cp sync`   — спросить у сервера активный набор плащей прямо сейчас;
 *  - `/cp clear`  — очистить кэш плащей.
 *
 * Регистрируются как клиентские команды (Fabric API) — работают в одиночке
 * и на сервере без прав.
 */
object CapeCommands {

    fun register(registry: CapeRegistry) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("cp")
                    .then(ClientCommandManager.literal("reload").executes { reload(it, registry) })
                    .then(ClientCommandManager.literal("list").executes { list(it, registry) })
                    .then(ClientCommandManager.literal("status").executes { status(it, registry) })
                    .then(ClientCommandManager.literal("sync").executes { sync(it) })
                    .then(ClientCommandManager.literal("clear").executes { clear(it, registry) }),
            )
        }
    }

    private fun reload(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        // Полная перезагрузка: конфиг с диска, дефолт при отсутствии, новый
        // набор провайдеров, полный сброс кэша и GPU-текстур, авторитетность
        // сервера снята. Бесшовного обновления здесь намеренно нет — это
        // команда «перезагрузить мод», а не «пнуть провайдеров».
        val cfg = CapeCraftClient.config
        cfg.reload()
        CapeSyncClient.applySettings(cfg.serverSync)
        registry.reloadAll(cfg.providers, cfg.limits, cfg.rootFor())
        // В v2 запроса нет: клиент сам объявляет набор. После перезагрузки
        // конфига объявляем сразу, иначе остальные до следующего изменения
        // видят прежний набор.
        CapeSyncClient.announceNow()
        val msg = if (cfg.lastError != null) " с ошибкой: ${cfg.lastError}" else ""
        ctx.source.sendFeedback(Text.literal("CapeCraft перезагружен (${cfg.path}). Провайдеров: ${cfg.providers.size}, " +
            "кэш плащей очищен, загрузка в фоне, набор — локальный конфиг" +
            ", набор объявлен" +
            "$msg"))
        return 1
    }

    private fun sync(ctx: CommandContext<FabricClientCommandSource>): Int {
        // В v2 «запросить набор» = «объявить свой заново».
        CapeSyncClient.announceNow()
        val state = CapeSyncClient.stateForStatus()
        ctx.source.sendFeedback(
            Text.literal(
                if (!state.enabled) {
                    "CapeCraft: синхронизация выключена в конфиге."
                } else {
                    "CapeCraft: набор объявлен, ревизия роустера ${state.revision}, " +
                        "применено снимков ${state.rostersApplied}. " +
                        "Сервер отвечает только роустером и чанками картинок."
                },
            ),
        )
        return 1
    }

    private fun list(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        val keys = registry.cachedKeys
        val errs = registry.errorsSnapshot
        if (keys.isEmpty()) {
            if (errs.isEmpty()) {
                ctx.source.sendFeedback(Text.literal("Плащей в памяти нет."))
            } else {
                ctx.source.sendFeedback(Text.literal("Плащей в памяти нет. Ошибки загрузки:"))
                for (e in errs.values) {
                    ctx.source.sendFeedback(Text.literal("  $e"))
                }
            }
            return 1
        }
        ctx.source.sendFeedback(Text.literal("Плащи в памяти (${keys.size}):"))
        for (k in keys) {
            val err = registry.error(k)
            ctx.source.sendFeedback(Text.literal("  $k" + if (err != null) " — $err" else ""))
        }
        ctx.source.sendFeedback(Text.literal("Память: ${registry.totalBytes} байт"))
        return 1
    }

    private fun status(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        val name = MinecraftClient.getInstance().session?.username
        ctx.source.sendFeedback(Text.literal("CapeCraft ${modVersion()}"))
        ctx.source.sendFeedback(Text.literal("Игрок: $name"))
        ctx.source.sendFeedback(Text.literal("Провайдеров: ${registry.providers.size} (${sourceOf(registry)})"))
        ctx.source.sendFeedback(Text.literal("Плащей в кэше: ${registry.size}"))
        ctx.source.sendFeedback(Text.literal("Память плащей: ${registry.totalBytes} байт"))
        for (override in CapeConfigEnv.activeOverrides()) {
            ctx.source.sendFeedback(Text.literal("Переопределение из окружения: $override"))
        }
        ctx.source.sendFeedback(Text.literal("Объектов с набором: ${registry.knownObjectIds().size}"))
        ctx.source.sendFeedback(Text.literal("Картинок по сети в кэше: ${registry.networkImages.count()} " +
            "(${registry.networkImages.totalBytes()} байт), своих: ${registry.networkImages.ownedCount()}"))
        val state = CapeSyncClient.stateForStatus()
        ctx.source.sendFeedback(
            Text.literal(
                "Sync v2: ревизия ${state.revision}, объявлений отправлено ${state.announcesSent}, " +
                    "ростеров применено ${state.rostersApplied} (устаревших ${state.rostersStale}), " +
                    "запрошено картинок ${state.fetchesSent}, чанков принято ${state.chunksReceived}, " +
                    "не хватает картинок ${state.missingHashes().size}" +
                    if (CapeSyncClient.lastTruncatedCount() > 0) {
                        ", ПОТЕРЯНО ОБЪЕКТОВ ПРИ ОБРЕЗКЕ: ${CapeSyncClient.lastTruncatedCount()}"
                    } else {
                        ""
                    } +
                    (state.lastError?.let { ", ошибка: $it" } ?: ""),
            ),
        )
        return 1
    }

    /**
     * Откуда набор.
     *
     * Формулировка про сервер убрана намеренно: в v2 сервер не присылает мой
     * набор, он только сообщает чужие. Свой набор всегда локальный, а чужие
     * лежат по объектам и в этом счётчике не участвуют.
     */
    private fun sourceOf(registry: CapeRegistry): String = "локальный конфиг"

    /** Версия мода из fabric.mod.json (не захардкожена). */
    private fun modVersion(): String =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(CapeCraftClient.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("?")

    private fun clear(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        registry.clear()
        ctx.source.sendFeedback(Text.literal("Кэш плащей очищен. Мод продолжает работать, плащи загрузятся заново."))
        return 1
    }
}
