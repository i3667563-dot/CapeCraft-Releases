package dev.ggtv.capecraft

import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.sync.CapeSyncClient
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
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
        // Перечитать конфиг и перезагрузить провайдеры/лимиты/плащи в том же
        // реестре (бесшовно: старые текстуры висят, пока новые грузятся в фоне).
        val cfg = CapeCraftClient.config
        cfg.reload()
        CapeSyncClient.applySettings(cfg.serverSync)
        // Если сейчас активен набор с сервера — перечитывание локального
        // конфига его не отменяет: сервер пришлёт свой набор следующим ответом.
        if (!registry.isServerAuthoritative) {
            registry.reload(cfg.providers, cfg.limits, cfg.rootFor())
        }
        val msg = if (cfg.lastError != null) " с ошибкой: ${cfg.lastError}" else ""
        ctx.source.sendFeedback(
            Text.literal(
                "Конфиг перезагружен (${cfg.path}). Провайдеров: ${cfg.providers.size}, " +
                    "перезагрузка плащей в фоне$msg",
            ),
        )
        return 1
    }

    private fun sync(ctx: CommandContext<FabricClientCommandSource>): Int {
        val id = CapeSyncClient.requestNow()
        ctx.source.sendFeedback(
            Text.literal(
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
        val me = MinecraftClient.getInstance()
        val name = me.session?.username ?: "?"
        ctx.source.sendFeedback(Text.literal("CapeCraft ${modVersion()}"))
        ctx.source.sendFeedback(Text.literal("Игрок: $name"))
        ctx.source.sendFeedback(Text.literal("Провайдеров: ${registry.providers.size} (${sourceOf(registry)})"))
        ctx.source.sendFeedback(Text.literal("Плащей в кэше: ${registry.size}"))
        ctx.source.sendFeedback(Text.literal("Память плащей: ${registry.totalBytes} байт"))
        for (line in CapeSyncClient.statusLines()) {
            ctx.source.sendFeedback(Text.literal(line))
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
        ctx.source.sendFeedback(Text.literal("Кэш плащей очищен."))
        return 1
    }
}