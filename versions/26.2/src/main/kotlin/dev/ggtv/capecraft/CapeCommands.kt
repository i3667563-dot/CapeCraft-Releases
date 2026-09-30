package dev.ggtv.capecraft

import dev.ggtv.capecraft.CapeConfigEnv
import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.render.MinecraftWorldContext
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
        // В v2 запроса нет: клиент сам объявляет набор. После перезагрузки
        // конфига объявляем сразу, иначе остальные до следующего изменения
        // видят прежний набор.
        CapeSyncClient.announceNow()
        val msg = if (cfg.lastError != null) " с ошибкой: ${cfg.lastError}" else ""
        // Отдельной строкой, а не в скобках: откат с недоступного файла из
        // окружения — ровно та причина, по которой человек идёт читать лог.
        val warn = cfg.sourceWarning?.let { "\nВНИМАНИЕ: $it" } ?: ""
        ctx.source.sendFeedback(Component.literal("CapeCraft перезагружен (${cfg.activeSource}). Провайдеров: ${cfg.providers.size}, " +
            "кэш плащей очищен, загрузка в фоне, набор — локальный конфиг" +
            ", набор объявлен" +
            "$msg$warn"))
        return 1
    }

    private fun sync(ctx: CommandContext<FabricClientCommandSource>): Int {
        // В v2 «запросить набор» = «объявить свой заново».
        CapeSyncClient.announceNow()
        val state = CapeSyncClient.stateForStatus()
        ctx.source.sendFeedback(
            Component.literal(
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

    /**
     * Разбор отбора: что мод выбрал и почему.
     *
     * Раньше тут был только список загруженных плащей и байты, из которых
     * нельзя было понять **ничего** про выбор. Ровно поэтому баг в условиях
     * приходилось выискивать глазами по конфигу: мод молчал, а человек
     * догадывался. Теперь видно и фактическое значение каждого поля, и
     * причину, по которой провайдер отсеялся.
     *
     * Отчёт считается тем же [dev.ggtv.capecraft.condition.ProviderSelector],
     * что и рендер, поэтому «здесь подошло» и «там подошло» не могут
     * разойтись: расхождение было бы хуже отсутствия вывода.
     */
    private fun list(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        val report = registry.explainLocal(registry.providers, MinecraftWorldContext)
        val who = Minecraft.getInstance().user.name
        say(ctx, "Провайдеров: ${report.reports.size}. Отбор для $who.")

        val chain = report.ordered.map { it.name }
        if (chain.isEmpty()) {
            say(ctx, "Ничего не подошло, и запасных провайдеров нет — плаща не будет.")
        } else {
            say(ctx, "Цепочка загрузки: ${chain.joinToString(" → ")}")
            say(ctx, "На экране: ${chain.first()}")
        }

        // Расхождение возможно только если состояние мира сменилось между
        // последним пересчётом и этой командой. Само по себе полезно увидеть:
        // значит картинка на экране ещё от прошлого отбора.
        val applied = registry.localPlayerId?.let { registry.appliedOrder[it] }
        if (applied != null && applied != chain) {
            say(ctx, "Применён был: ${applied.joinToString(" → ")}")
        }

        for (r in report.reports) {
            val mark = when {
                r.provider === report.ordered.firstOrNull() -> "→"
                r.selected -> "+"
                else -> "×"
            }
            val tail = when {
                r.isFallback -> "без условий, всегда в цепочке"
                r.matched -> "условия выполнены"
                else -> "не подходит"
            }
            val prio = if (r.provider.priority == 0) "" else "  приоритет ${r.provider.priority}"
            say(ctx, "$mark ${r.provider.name}$prio — $tail")
            for (c in r.whenChecks) {
                say(ctx, "      ${tick(c.ok)} ${c.path}: ${c.actual} ${c.expected}")
            }
            for (c in r.ifChecks) {
                say(ctx, "      ${tick(c.ok)} if ${c.name}: ${c.actual} ${c.expected}")
            }
        }

        val keys = registry.cachedKeys
        val errs = registry.errorsSnapshot
        if (keys.isEmpty() && errs.isNotEmpty()) {
            say(ctx, "Плащей в памяти нет. Ошибки загрузки:")
            for (e in errs.values) say(ctx, "  $e")
        } else {
            say(ctx, "Плащей в памяти: ${keys.size}, ${registry.totalBytes} байт")
        }
        return 1
    }

    /** Строка в чат. Отдельный хелпер — строк в выводе много. */
    private fun say(ctx: CommandContext<FabricClientCommandSource>, text: String) {
        ctx.source.sendFeedback(Component.literal(text))
    }

    /** Галочка проверки: совпало фактическое значение с ожиданием или нет. */
    private fun tick(ok: Boolean): String = if (ok) "✓" else "✗"

    private fun status(ctx: CommandContext<FabricClientCommandSource>, registry: CapeRegistry): Int {
        val cfg = CapeCraftClient.config
        val name = Minecraft.getInstance().user.name
        ctx.source.sendFeedback(Component.literal("CapeCraft ${modVersion()}"))
        ctx.source.sendFeedback(Component.literal("Игрок: $name"))
        // Откуда взят конфиг — иначе при CAPECRAFT_CONFIG в статусе не видно
        // ни пути, ни того, что внешний файл не прочитался и взят обычный.
        ctx.source.sendFeedback(Component.literal("Конфиг: ${cfg.activeSource}"))
        cfg.sourceWarning?.let {
            ctx.source.sendFeedback(Component.literal("  ВНИМАНИЕ: $it"))
        }
        cfg.lastError?.let {
            ctx.source.sendFeedback(Component.literal("  ОШИБКА: $it"))
        }
        ctx.source.sendFeedback(Component.literal("Провайдеров: ${registry.providers.size} (${sourceOf(registry)})"))
        ctx.source.sendFeedback(Component.literal("Плащей в кэше: ${registry.size}"))
        ctx.source.sendFeedback(Component.literal("Память плащей: ${registry.totalBytes} байт"))
        for (override in CapeConfigEnv.activeOverrides()) {
            ctx.source.sendFeedback(Component.literal("Переопределение из окружения: $override"))
        }
        ctx.source.sendFeedback(Component.literal("Объектов с набором: ${registry.knownObjectIds().size}"))
        // Сколько объектов носит мой набор принудительно: без мода, с
        // выключенной синхронизацией или на сервере не-CapeCraft. Пока их
        // нет, правило либо ещё не сработало (срок ожидания), либо все
        // объекты объявились.
        ctx.source.sendFeedback(Component.literal("Мой набор принудительно: ${registry.forcedLocalCount()}"))
        ctx.source.sendFeedback(Component.literal("Картинок по сети в кэше: ${registry.networkImages.count()} " +
            "(${registry.networkImages.totalBytes()} байт), своих: ${registry.networkImages.ownedCount()}"))
        val state = CapeSyncClient.stateForStatus()
        ctx.source.sendFeedback(
            Component.literal(
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
        ctx.source.sendFeedback(Component.literal("Кэш плащей очищен. Мод продолжает работать, плащи загрузятся заново."))
        return 1
    }
}
