package dev.ggtv.capecraft.sync

import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.capecraft.render.ServerWorldContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

/**
 * Серверная половина CapeCraft Sync.
 *
 * Вызывается из `main`-entrypoint мода, то есть работает и на выделенном
 * сервере, и на встроенном сервере клиента. Ванильный клиент (без мода) сюда
 * не попадёт — он просто не отправит запрос.
 *
 * ## Что делает сервер
 * 1. На старте регистрирует типы payload'ов и приёмник запросов.
 * 2. Читает `config/capecraft.kn` и строит [ServerCapeCatalog].
 * 3. На каждый запрос: берёт [ServerWorldContext] ИГРОКА, применяет
 *    [dev.ggtv.capecraft.condition.ProviderSelector] (условия `when` решаются
 *    на сервере, где мир настоящий) и отвечает **только активными**
 *    провайдерами, уже в готовом порядке выбора.
 *
 * Сервер НЕ отдаёт байты картинок — только описания провайдеров; скачивает
 * клиент. Это дёшево (десятки байт на ответ) и не превращает сервер в
 * прокси для картинок.
 *
 * MC 1.21.x: имена yarn.
 */
object CapeSyncServer {

    @Volatile
    private var config: ServerCapeConfig? = null

    /** Сколько ответов отдано (диагностика `/capecraft status`). */
    @Volatile
    var responsesServed: Long = 0
        private set

    /** Зарегистрировать каналы, приёмник и команды. Из `main`-entrypoint. */
    fun initialize() {
        PayloadTypeRegistry.playC2S().register(CapeSyncPayloads.Request.TYPE, CapeSyncPayloads.Request.CODEC)
        PayloadTypeRegistry.playS2C().register(CapeSyncPayloads.Response.TYPE, CapeSyncPayloads.Response.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(CapeSyncPayloads.Request.TYPE) { payload, context ->
            // Хендлер приходит на сетевом потоке — мир игрока читаем только
            // на серверном.
            val server = context.server()
            server.execute { respond(payload, context) }
        }

        config = ServerCapeConfig.load()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("capecraft")
                    .requires(CapeServerPermission.GAMEMASTER)
                    .then(CommandManager.literal("reload").executes { reload(it) })
                    .then(CommandManager.literal("status").executes { status(it) }),
            )
        }
        CapeCraftLog.LOGGER.info("CapeCraft: серверная синхронизация плащей включена (протокол v${SyncProtocol.VERSION})")
    }

    /** Перечитать серверный конфиг (без перезапуска). */
    fun reloadCatalog(): Int {
        val cfg = config ?: ServerCapeConfig.load().also { config = it }
        cfg.reload()
        return cfg.providerCount
    }

    private fun respond(payload: CapeSyncPayloads.Request, context: ServerPlayNetworking.Context) {
        val player = context.player()
        val cfg = config ?: return
        val body = try {
            cfg.catalog.responseFor(ServerWorldContext(player))
        } catch (e: Exception) {
            CapeCraftLog.LOGGER.error("CapeCraft: не удалось собрать ответ для ${player.gameProfile.name}: ${e.message}", e)
            return
        }
        val response = CapeSyncPayloads.Response(
            requestId = payload.requestId,
            providers = body.providers,
            truncated = body.truncated,
        )
        if (body.providers.size == SyncProtocol.MAX_PROVIDERS || body.truncated > 0 || body.droppedInvalid > 0) {
            CapeCraftLog.LOGGER.warn(
                "CapeCraft: ${player.gameProfile.name}: отдано ${body.providers.size} провайдеров " +
                    "(обрезано ${body.truncated}, отброшено битых ${body.droppedInvalid})",
            )
        }
        ServerPlayNetworking.send(player, response)
        responsesServed++
    }

    private fun reload(ctx: CommandContext<ServerCommandSource>): Int {
        val count = reloadCatalog()
        ctx.source.sendFeedback(
            { Text.literal("CapeCraft: серверный конфиг перезагружен, провайдеров $count") },
            true,
        )
        return 1
    }

    private fun status(ctx: CommandContext<ServerCommandSource>): Int {
        val cfg = config
        ctx.source.sendFeedback(
            {
                Text.literal(
                    "CapeCraft Sync v${SyncProtocol.VERSION}: провайдеров на сервере ${cfg?.providerCount ?: 0}, " +
                        "ответов отдано $responsesServed",
                )
            },
            true,
        )
        return 1
    }
}
