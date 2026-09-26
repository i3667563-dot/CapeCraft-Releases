package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.CapeCraftClient
import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.capecraft.provider.resolveCapeResult
import dev.ggtv.capecraft.schema.Placeholders
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import java.util.concurrent.Executors

/**
 * Клиентская половина CapeCraft Sync v2.
 *
 * ## Роль клиента
 *
 * Клиент **объявляет** свой набор функций ([Announce]) и **применяет** чужие
 * наборы из [Roster]. Он не спрашивает «что мне носить» — сервер такого вопроса
 * не задаёт. Ответы два: «у этого объекта такой набор» и «дай кусок картинки с
 * этого хэша».
 *
 * ## Откуда id
 *
 * Клиент не знает id с сервера и не отправляет его: он берёт свой [java.util.UUID]
 * из сессии, а сервер берёт id объекта из профиля отправителя. Оба приходят к
 * одному значению без объявления, и подделать чужой id нечем — в пакете его нет.
 *
 * ## Порядок обработки входящего
 *
 * - **роустер** — сразу на клиентский поток: он меняет наборы функций, и
 *   задержка видна глазом;
 * - **чанк** — на воркер: сборка пишет в буфер и считает хэш, на главном потоке
 *   это был бы пропуск кадров на каждые 64 КиБ.
 *
 * Это и есть ответ, зачем разделять типы сообщений: play-канал Minecraft — один
 * TCP-поток, и отдельный payload от большого чанка не обошёл бы его в очереди.
 * Помогает приоритет обработки, а не отдельный канал.
 */
object CapeSyncClient {

    private var state: CapeSyncState = ServerSyncSettings().toState()

    private var settings: ServerSyncSettings = ServerSyncSettings()

    /** Тиков до следующего куска аплоада — свой троттлинг, а не общий backoff. */
    private var ticksToNextUpload: Int = 0

    @Volatile
    private var lastTruncated: Int = 0
        private set

    /** Сборка чанков — вне главного потока. */
    private val chunkWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CapeCraft-sync-chunks").apply { isDaemon = true }
    }

    /** Подписка на сеть. Вызывается из клиентского entrypoint. */
    fun initialize() {
        applySettings(CapeCraftClient.config.serverSync)

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            state.onJoin()
            // Реестру нужен мой id до первого объявления: иначе на кадре с
            // собственным плащом он ещё не знает, что этот набор «мой».
            selfId()?.let { CapeCraftClient.registry.setLocalPlayer(it) }
            publishOwnedImages()
            announceNow()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            state.onDisconnect()
            lastTruncated = 0
            // Ростер этого сервера больше не имеет силы. Чужие наборы выкидываем
            // вместе с байтами и текстурами: иначе они живут до конца сессии и
            // накапливаются на каждом заходе, а на следующем сервере рисуется
            // уже не то. Свой набор тоже сбрасывается — на входе он и так
            // перечитывается из конфига.
            CapeCraftClient.registry.knownObjectIds().toList()
                .forEach { CapeCraftClient.registry.forget(it) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CapeSyncPayloads.ServerMessage.TYPE) { payload, _ ->
            onServerMessage(payload.bytes)
        }
    }

    /** Мой id — из сессии; сервер берёт id объекта отсюда же. */
    private fun selfId(): String? = Minecraft.getInstance().player?.gameProfile?.id?.toString()

    private fun onServerMessage(bytes: ByteArray) {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xFF) != SyncProtocol.VERSION) return
        val message = try {
            SyncCodec.decodeS2C(bytes)
        } catch (e: Exception) {
            CapeCraftLog.LOGGER.warn("CapeCraft: отброшен битый пакет sync от сервера: ${e.message}")
            return
        }
        when (message) {
            // Ростер применяем на клиентском потоке: он меняет реестр наборов,
            // который читает рендер. Сеть сюда не ходит — байты уже разобраны.
            is Roster -> Minecraft.getInstance().execute { applyRoster(message) }
            is Chunk -> chunkWorker.execute { onChunk(message) }
        }
    }

    private fun onChunk(chunk: Chunk) {
        when (val result = state.onChunk(chunk)) {
            is ImageAssembler.Result.Complete -> {
                CapeCraftLog.LOGGER.info(
                    "CapeCraft: картинка ${ImageHash.toHex(result.hash).take(12)} докачана " +
                        "(${result.bytes.size} байт)",
                )
                // Кладём в общий кэш: байты нужны всем наборам, ссылающимся на
                // этот хэш, а не только одному. И сбрасываем применённый порядок —
                // иначе плащ так и останется на fallback, потому что набор не
                // менялся, а доступность картинки выросла.
                Minecraft.getInstance().execute {
                    CapeCraftClient.registry.networkImages.put(
                        ImageHash.toHex(result.hash),
                        result.bytes,
                    )
                    CapeCraftClient.registry.invalidateAppliedOrder()
                }
            }

            is ImageAssembler.Result.Rejected ->
                CapeCraftLog.LOGGER.warn("CapeCraft: чанк отброшен: ${result.reason}")

            is ImageAssembler.Result.Progress -> Unit
        }
    }

    /**
     * Применить снимок роустера.
     *
     * Единственное, что клиент берёт у сервера, — наборы функций, и
     * применяются они по [dev.ggtv.capecraft.CapeRegistry.useObjectFunctions]
     * только к тем объектам, чьи наборы отличаются от прежних.
     */
    private fun applyRoster(roster: Roster) {
        val applied = state.onRoster(roster)
        if (!applied) return // устаревшая ревизия
        val registry = CapeCraftClient.registry
        val seen = HashSet<String>(roster.objects.size)
        for (obj in roster.objects) {
            seen.add(obj.id)
            registry.useObjectFunctions(
                obj.id,
                SyncRosterPolicy.toLocalProviders(obj.functions, settings.allowForeignUrls),
            )
        }
        // Объекта в ПОЛНОМ снимке нет — объявления у него тоже нет. Снимаем
        // набор, но не трогаем учёт игроков: вышедший всё ещё может быть в
        // кэше мира.
        //
        // В ОБРЕЗАННОМ снимке отсутствие ничего не значит: сервер отбросил
        // самых давно не видевшиеся объявления, а не выкинул игроков. Сносить
        // по нему наборы было бы наказанием за переполнение — плащи
        // замигали бы у длинного хвоста онлайна.
        if (roster.truncated == 0) {
            for (id in registry.knownObjectIds()) {
                if (id !in seen) registry.forgetObject(id)
            }
        }
        if (roster.truncated > 0) {
            lastTruncated = roster.truncated
            CapeCraftLog.LOGGER.warn(
                "CapeCraft: снимок обрезан, потеряно объявлений ${roster.truncated}. " +
                    "Их наборы оставлены как были, чтобы плащи не мигали. " +
                    "Лечится меньшим числом функций у игроков или ростом лимита роустера.",
            )
        }
        publishOwnedImages()
    }

    /**
     * Залить свои картинки, на которые кто-то сослался.
     *
     * Не «все мои», а именно те, что встретились в чужих объявлениях: никто на
     * меня не смотрит — тащить байты незачем, и личный локальный файл просто
     * не уедет. Заливается кусками по [SyncProtocol.CHUNK_BYTES], с места
     * обрыва, а не с нуля.
     */
    private fun publishOwnedImages() {
        if (!settings.enabled || !settings.shareLocalProviders) return
        val registry = CapeCraftClient.registry
        val ownedHashes = registry.networkImages.ownedHashes().mapNotNull { ImageHash.fromHex(it) }.toSet()
        val myId = selfId()
        for (hash in state.pendingUploads(state.publishableHashes(myId), ownedHashes)) {
            val bytes = registry.networkImages.owned(ImageHash.toHex(hash)) ?: continue
            val sent = state.uploadedBytesOf(hash)
            if (sent >= bytes.size) {
                state.markUploadComplete(hash)
                continue
            }
            val end = (sent + SyncProtocol.CHUNK_BYTES).coerceAtMost(bytes.size)
            if (!canSend()) return
            ClientPlayNetworking.send(
                CapeSyncPayloads.ClientMessage(
                    SyncCodec.encodeUpload(
                        Upload(hash = hash, totalSize = bytes.size, offset = sent, bytes = bytes.copyOfRange(sent, end)),
                    ),
                ),
            )
            state.markUploadProgress(hash, end)
            if (end >= bytes.size) state.markUploadComplete(hash)
        }
    }

    /** Объявить свой набор. */
    fun announceNow() {
        if (!settings.enabled) return
        publishOwnedImages()
        for (message in state.onConfigChanged(currentFunctions())) {
            if (message !is SyncOutbound.Announce) continue
            if (!canSend()) return
            ClientPlayNetworking.send(CapeSyncPayloads.ClientMessage(SyncCodec.encodeAnnounce(Announce(message.functions))))
        }
    }

    /**
     * Текущий набор в виде сообщения.
     *
     * `file`-плащ едет **без пути**, только с хэшем, и только если
     * [ServerSyncSettings.shareLocalProviders] включён: локальный файл по
     * умолчанию не раздаётся.
     */
    private fun currentFunctions(): List<ActiveCape> {
        val shareLocal = settings.shareLocalProviders
        val registry = CapeCraftClient.registry
        val out = ArrayList<ActiveCape>(registry.providers.size)
        for (provider in registry.providers) {
            if (provider.source is Source.File) {
                if (!shareLocal) continue // локальный файл наружу не идёт
                val hash = hashOfLocalFile(provider) ?: continue
                registry.networkImages.putOwned(ImageHash.toHex(hash), bytesOfLocalFile(provider) ?: continue)
            }
            out.add(provider.toActiveCape(if (provider.source is Source.File) hashOfLocalFile(provider) else null) ?: continue)
        }
        return out
    }

    /**
     * Хэш локального файла.
     *
     * Считается от байт, которые реестр уже закешировал: к моменту объявления
     * плащ обычно уже загружен, и повторно ходить в файловую систему незачем.
     * Не посчитался — функция не поедет вовсе, что честнее, чем объявить
     * хэш, которого не существует.
     */
    private fun bytesOfLocalFile(provider: Provider): ByteArray? = try {
        val path = provider.resolve(Placeholders.Context("", "", ""), registryRoot()).let {
            (it as? dev.ggtv.capecraft.provider.Resolved.File)?.path
        } ?: return null
        java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path))
    } catch (_: Exception) {
        null
    }

    private fun registryRoot(): String = ""

    private fun hashOfLocalFile(provider: Provider): ImageHash? =
        bytesOfLocalFile(provider)?.let { ImageHash.compute(it) }

    /**
     * Тик: докладываем недостающие картинки и продолжаем свои загрузки.
     *
     * [publishOwnedImages] обязан быть здесь, а не только на событиях: он
     * отдаёт по одному куску, поэтому картинка больше одного чанка (а локальный
     * файл вправе быть до 8 МиБ) без тикового повтора зависла бы на первом же
     * куске и никогда не была бы дослана.
     */
    fun onTick() {
        // Часы состояния двигаются здесь, а не вызывающим кодом: отдельный вход
        // для этого забывали звать, и клиент вечно ждал следующей попытки —
        // `ticks` оставался 0, условие `ticks < nextRetryTick` не проходило
        // больше ни разу, и за сессию уходил ровно один fetch. Докачка картинки
        // крупнее одного чанка (64 КиБ) на этом заканчивалась навсегда.
        state.onTickAdvance()
        if (ticksToNextUpload <= 0) {
            ticksToNextUpload = SyncProtocol.MIN_INTERVAL_TICKS
            publishOwnedImages()
        } else {
            ticksToNextUpload--
        }
        if (!settings.enabled) return
        for (message in state.onTick(canSend())) {
            if (message !is SyncOutbound.FetchImage) continue
            if (!canSend()) return
            ClientPlayNetworking.send(
                CapeSyncPayloads.ClientMessage(
                    SyncCodec.encodeFetch(Fetch(message.hash, message.offset, message.length)),
                ),
            )
        }
    }

    private fun canSend(): Boolean = ClientPlayNetworking.canSend(CapeSyncPayloads.ClientMessage.TYPE)

    /** Применить настройки и заново объявить набор. */
    fun applySettings(new: ServerSyncSettings) {
        settings = new
        // Не new.toState(): новый экземпляр обнулил бы ревизию снимка и
        // `wanted`, а сервер шлёт снимок только при изменении чьего-то набора —
        // после reload клиент остался бы без ростера до чужого reload.
        state.reconfigure(new.enabled, new.backoff)
    }

    /** Сколько объектов потерял последний обрезанный снимок — для `/cp status`. */
    fun lastTruncatedCount(): Int = lastTruncated

    fun stateForStatus(): CapeSyncState = state
}
