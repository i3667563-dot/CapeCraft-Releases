package dev.ggtv.capecraft

import dev.ggtv.capecraft.CapeConfigEnv
import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.sync.CapeSyncClient
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

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
                ClientCommands.literal("cp")
                    .then(ClientCommands.literal("reload").executes { reload(it, registry) })
                    .then(ClientCommands.literal("list").executes { list(it, registry) })
                    .then(ClientCommands.literal("status").executes { status(it, registry) })
                    .then(ClientCommands.literal("sync").executes { sync(it) })
                    .then(ClientCommands.literal("clear").executes { clear(it, registry) }),
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
        // Синхронизация с сервером с адаптивным интервалом: следующий запрос
        // может быть через минуту, поэтому после полной перезагрузки просим
        // набор сразу — иначе мод до минуты работает на локальном конфиге.
        val syncId = CapeSyncClient.requestNow()
        val msg = if (cfg.lastError != null) " с ошибкой: ${cfg.lastError}" else ""
        ctx.source.sendFeedback(Component.literal("CapeCraft перезагружен (${cfg.path}). Провайдеров: ${cfg.providers.size}, " +
            "кэш плащей очищен, загрузка в фоне, набор — локальный конфиг" +
            (if (syncId != null) ", запрос синхронизации $syncId" else ", синхронизация не отправлена") +
            "$msg"))
        return 1
    }

    private fun sync(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = CapeSyncClient.requestNow()
        ctx.source.sendFeedback(
            Component.literal(
                if (id == null) {
                    "CapeCraft: запрос не отправлен — синхронизация выключена или сервер без мода."
                } else {
                    "CapeCraft: запрос отправлен (requestId $id), ждём ответ."
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
                ctx.source.sendFeedback(Component.literal("Плащей в памяти нет."))
            } else {
                ctx.source.sendFeedback(Component.literal("Плащей в памяти нет. Ошибки загрузки:"))
                for (e in errs.values) {
                    ctx.source.sendFeedback(Component.literal("  $e"))
                }
            }
            return 1
        }
        ctx.source.sendFeedback(Component.literal("Плащи в памяти (${keys.size}):"))
        for (k in keys) {
            val err = registry.error(k)
            ctx.source.sendFeedback(Component.literal("  $k" + if (err != null) " — $err" else ""))
        }
        ctx.source.sendFeedback(Component.literal("Память: ${registry.totalBytes} байт"))
        return 1
    }

    private fun status(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        val name = Minecraft.getInstance().user.name
        ctx.source.sendFeedback(Component.literal("CapeCraft ${modVersion()}"))
        ctx.source.sendFeedback(Component.literal("Игрок: $name"))
        ctx.source.sendFeedback(Component.literal("Провайдеров: ${registry.providers.size} (${sourceOf(registry)})"))
        ctx.source.sendFeedback(Component.literal("Плащей в кэше: ${registry.size}"))
        ctx.source.sendFeedback(Component.literal("Память плащей: ${registry.totalBytes} байт"))
        for (override in CapeConfigEnv.activeOverrides()) {
            ctx.source.sendFeedback(Component.literal("Переопределение из окружения: $override"))
        }
        for (line in CapeSyncClient.statusLines()) {
            ctx.source.sendFeedback(Component.literal(line))
        }
        return 1
    }

    private fun sourceOf(registry: CapeRegistry): String =
        if (registry.isServerAuthoritative) "набор с сервера" else "локальный конфиг"

    /** Версия мода из fabric.mod.json (не захардкожена). */
    private fun modVersion(): String =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(CapeCraftClient.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("?")

    private fun clear(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        registry.clear()
        ctx.source.sendFeedback(Component.literal("Кэш плащей очищен. Мод продолжает работать, плащи загрузятся заново."))
        return 1
    }
}
