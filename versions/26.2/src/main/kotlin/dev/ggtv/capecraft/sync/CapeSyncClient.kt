package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.capecraft.CapeRegistry
import dev.ggtv.capecraft.provider.Guard
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Resolved
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

/**
 * Клиентская половина CapeCraft Sync.
 *
 * ## Как это работает
 * - При входе на сервер и раз в `serverSync.intervalTicks` тиков клиент
 *   спрашивает «дай мой активный набор плащей»;
 * - сервер отвечает ТОЛЬКО активными провайдерами (см. [CapeSyncServer]);
 * - ответ прогоняется через [SyncPolicy] и, если набор изменился,
 *   перезагружает реестр на серверных провайдерах (бесшовно: старые
 *   текстуры висят, пока грузятся новые);
 * - **таймаут/сервер без мода — локальный конфиг** (кроме `requireServer =
 *   true`, где отсутствие ответа значит «плащей нет»);
 * - **валидный пустой ответ — это «плащей нет»**, а не «сервер молчит»:
 *   локальный набор при этом отключается.
 *
 * Серверные провайдеры работают «как прислали»: клиент НЕ пересчитывает их
 * `when`-условия (сервер уже отобрал активные, а мир клиента может отличаться
 * от серверного). Локальные — пересчитываются на живом мире.
 *
 * Всё состояние живёт в [CapeSyncState] (чистая машина, тестируется без
 * игры); здесь только Minecraft-обвязка: каналы, тик, переключение реестра.
 *
 * MC 26.2: необфусцированные имена Mojang.
 */
object CapeSyncClient {

    @Volatile
    private var state: CapeSyncState = ServerSyncSettings().toState()

    /** Причины отбрасывания провайдеров из последнего ответа (для `/cp status`). */
    @Volatile
    var lastRejectReasons: List<String> = emptyList()
        private set

    /** Подписка на сеть. Вызывается из клиентского entrypoint. */
    fun initialize() {
        applySettings(CapeCraftClient.config.serverSync)

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            val id = state.onJoin()
            CapeCraftLog.LOGGER.info("CapeCraft: вход на сервер, sync requestId=$id")
            if (id != null) sendRequest(id)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            state.onDisconnect()
            useLocalProviders()
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (state.requireServer) useServerProviders(emptyList())
        }

        ClientPlayNetworking.registerGlobalReceiver(CapeSyncPayloads.Response.TYPE) { payload, _ ->
            // Приём идёт на сетевом потоке — реестр трогаем только на клиентском.
            Minecraft.getInstance().execute { onResponse(payload) }
        }

        CapeCraftLog.LOGGER.info(
            "CapeCraft: серверная синхронизация (${ServerSyncSettings.describe(CapeCraftClient.config.serverSync)})",
        )
    }

    /** Тик клиента: запрос не чаще заданного интервала. */
    fun onClientTick(inWorld: Boolean) {
        if (Minecraft.getInstance().connection == null) return
        if (!canSendRequest()) {
            // Сервер без мода: работаем на локальном наборе (или пусто, если
            // requireServer). Ничего не логируем на каждом тике.
            state.onServerUnsupported()
            if (state.requireServer) useServerProviders(emptyList())
            return
        }
        val timeouts = state.timedOut
        val id = state.onTick(inWorld, channelReady = true)
        if (state.timedOut > timeouts) onServerUnreachable()
        if (id != null) sendRequest(id)
    }

    /** `/cp sync` — спросить немедленно, не дожидаясь интервала. */
    fun requestNow(): Int? {
        if (Minecraft.getInstance().connection == null) return null
        val id = state.requestNow() ?: return null
        if (!canSendRequest()) {
            state.onServerUnsupported()
            return null
        }
        sendRequest(id)
        return id
    }

    /** Перечитать настройки синхронизации (после `/cp reload`). */
    fun applySettings(settings: ServerSyncSettings) {
        state = settings.toState()
        if (!settings.enabled) useLocalProviders()
    }

    /** Строки статуса для `/cp status`. */
    fun statusLines(): List<String> = buildList {
        add("Sync: ${ServerSyncSettings.describe(CapeCraftClient.config.serverSync)}")
        add(
            "Sync: ответов принято ${state.responsesAccepted}, таймаутов ${state.timedOut}, набор — " +
                if (state.usingServerProviders) "СЕРВЕР" else "локальный конфиг",
        )
        add("Sync: последний requestId ${state.lastRequestId}, ошибка ${state.lastError ?: "нет"}")
        if (lastRejectReasons.isNotEmpty()) {
            add("Sync: отброшено ${lastRejectReasons.size}: " + lastRejectReasons.joinToString("; "))
        }
    }

    /**
     * Готов ли канал запроса. `canSend` бросает, если соединение уже не в
     * play-состоянии (гонка с дисконнектом), поэтому глотаем это как «нет».
     */
    private fun canSendRequest(): Boolean = try {
        ClientPlayNetworking.canSend(CapeSyncPayloads.Request.TYPE)
    } catch (_: IllegalStateException) {
        false
    }

    private fun sendRequest(id: Int?) {
        if (id == null) return
        if (!canSendRequest()) {
            state.onServerUnsupported()
            if (state.requireServer) useServerProviders(emptyList())
            return
        }
        ClientPlayNetworking.send(CapeSyncPayloads.Request(id))
    }

    private fun onResponse(payload: CapeSyncPayloads.Response) {
        val changed = state.onResponse(payload.toSyncState())
        state.lastError?.let { CapeCraftLog.LOGGER.warn("CapeCraft: sync — $it") }
        if (!changed) return

        val converted = SyncPolicy.convert(
            remote = payload.providers,
            local = CapeCraftClient.config.providers,
            allowFileProviders = CapeCraftClient.config.serverSync.allowFileProviders,
        )
        lastRejectReasons = converted.rejected
        if (converted.rejected.isNotEmpty()) {
            CapeCraftLog.LOGGER.warn(
                "CapeCraft: сервер прислал ${payload.providers.size} провайдеров, " +
                    "отброшено ${converted.rejected.size}: " + converted.rejected.joinToString("; "),
            )
        }
        useServerProviders(converted.providers)
        CapeCraftLog.LOGGER.info(
            "CapeCraft: набор с сервера применён (${converted.providers.size}): " +
                converted.providers.joinToString(", ") { it.name },
        )
    }

    /** Сервер не ответил: по правилам fallback'а возвращаемся к локальному набору. */
    private fun onServerUnreachable() {
        if (state.requireServer) useServerProviders(emptyList()) else useLocalProviders()
    }

    /** Переключить реестр на серверный (authoritative) набор. */
    private fun useServerProviders(providers: List<Provider>) {
        val root = CapeCraftClient.config.rootFor()
        CapeCraftClient.registry.useServerProviders(providers, root, fileGuard(root))
    }

    /** Вернуться к локальному конфигу (сервер не ответил / рассинхрон). */
    private fun useLocalProviders() {
        if (!CapeCraftClient.registry.isServerAuthoritative) return
        val cfg = CapeCraftClient.config
        CapeCraftClient.registry.useLocalProviders(cfg.providers, cfg.rootFor())
        CapeCraftLog.LOGGER.info("CapeCraft: серверный набор сброшен, работает локальный конфиг")
    }

    /** Файловый провайдер с сервера обязан лежать внутри папки игры. */
    private fun fileGuard(root: String): Guard = Guard { r: Resolved ->
        if (r is Resolved.File && !SyncPolicy.isInside(root, r.path)) {
            throw IllegalArgumentException("путь вне папки игры: «${r.path}»")
        }
    }
}
