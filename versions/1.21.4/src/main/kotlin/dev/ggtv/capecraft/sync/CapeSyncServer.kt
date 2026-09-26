package dev.ggtv.capecraft.sync

import com.mojang.brigadier.context.CommandContext
import dev.ggtv.capecraft.CapeCraftLog
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Серверная половина CapeCraft Sync v2.
 *
 * ## Что сервер делает, а что не делает
 *
 * Делает ровно три вещи:
 * 1. Из отправителя соединения берёт id объекта и запоминает объявленный набор
 *    функций ([RosterStore]).
 * 2. Рассылает всем снимок роустера целиком.
 * 3. Хранит байты картинок, загруженных владельцами, и отдаёт их чанками тем,
 *    кто их запросил.
 *
 * **Не делает**: не выбирает провайдеры, не считает `when`, не решает, кто что
 * наденет. Условия считает каждый клиент сам, против своего наблюдаемого
 * объекта. Поэтому серверу не нужен ни мир игрока, ни [ServerWorldContext] —
 * в v1 они были нужны именно потому, что он решал за клиента.
 *
 * ## Откуда берётся id объекта
 *
 * Из профиля отправителя ([UUID]), а не из пакета. В пакете id нет вообще, и
 * это не оптимизация, а требование модели: клиент, который умеет назвать
 * себя, может назваться чужим. Раз он не передаёт id, подделать его нечем —
 * а свой он и так знает локально, из сессии.
 *
 * ## Доверие
 *
 * Сервер не проверяет, правдиво ли объявление. Это осознанно: сервер —
 * транспорт и смотритель ревизии, а не арбитр правды. Единственное, что он
 * режет, — размеры и явный мусор, иначе один клиент раздует снимок до
 * мегабайта и забьёт канал всем остальным.
 *
 * MC 26.2: необфусцированные имена Mojang.
 */
object CapeSyncServer {

    /** Снимок объявлений. Ревизия растёт только при реальном изменении. */
    private val roster = RosterStore()

    /** Байты картинок, загруженные владельцами. Путь на диск сюда не попадает. */
    private val images = ImageStore()

    /**
     * Троттлинг объявлений (п.7 протокола).
     *
     * Объявление — единственное сообщение, которое клиент шлёт по своей воле и
     * повторяет сколько угодно раз. Без ограничения `/cp reload` в цикле или
     * мод на старте превращался бы в рассылку всем, а каждый announce —
     * в пересборку и отправку снимка всем. Здесь на игрока — не чаще раза в
     * [ANNOUNCE_MIN_INTERVAL_MS], а сверх того — не чаще, чем на самом деле
     * менялся набор.
     *
     * Идёт по времени, а не по числу сообщений, потому что «пять announce
     * за секунду» и «пять announce за час» — разная нагрузка, а счётчик
     * сообщений их не различил бы.
     */
    private const val ANNOUNCE_MIN_INTERVAL_MS = 2_000L

    /** id → когда последний раз принимали объявление. */
    private val lastAnnounceAt = ConcurrentHashMap<String, Long>()

    /**
     * Объявления, отброшенные троттлингом.
     *
     * Троттлинг ограничивает ЧАСТОТУ, а не сами объявления: игрок, дважды
     * нажавший `/cp reload`, не должен остаться со старым плащем из-за
     * того, что кнопка нажата чаще, чем раз в две секунды. Отброшенное
     * объявление не теряется, а ждёт своей очереди и применяется на
     * ближайшем тике сервера, как только интервал прошёл.
     */
    private val pendingAnnounces = ConcurrentHashMap<String, Announce>()

    @Volatile
    private var announcesAccepted: Long = 0
        private set

    @Volatile
    private var announcesThrottled: Long = 0
        private set

    @Volatile
    private var uploadsAccepted: Long = 0
        private set

    @Volatile
    private var chunksServed: Long = 0
        private set

    @Volatile
    private var broadcastCount: Long = 0
        private set

    /** Зарегистрировать каналы, приёмник и команды. Из `main`-entrypoint. */
    fun initialize() {
        PayloadTypeRegistry.playC2S().register(
            CapeSyncPayloads.ClientMessage.TYPE,
            CapeSyncPayloads.ClientMessage.CODEC,
        )
        PayloadTypeRegistry.playS2C().register(
            CapeSyncPayloads.ServerMessage.TYPE,
            CapeSyncPayloads.ServerMessage.CODEC,
        )

        ServerPlayNetworking.registerGlobalReceiver(CapeSyncPayloads.ClientMessage.TYPE) { payload, context ->
            // Приём идёт на сетевом потоке. Раскладку, роустер и картинки
            // трогаем только на серверном, иначе гонка с рассылкой.
            val server = context.server()
            server.execute { handle(payload.bytes, context.player(), server) }
        }

        ServerTickEvents.END_SERVER_TICK.register { server -> flushPendingAnnounces(server) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            // id берём у самого хендлера: к моменту DISCONNECT игрока уже
            // нет в playerList, и искать его перебором по списку — значит
            // снять не того и оставить объявление в роустере навсегда.
            val id = objectIdOf(handler.player)
            if (roster.remove(id)) {
                images.dropUploads(id)
                broadcast(server, null)
            }
            lastAnnounceAt.remove(id)
            pendingAnnounces.remove(id)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("capecraft")
                    .requires(CapeServerPermission.GAMEMASTER)
                    .then(CommandManager.literal("status").executes { status(it) })
                    .then(CommandManager.literal("syncstatus").executes { syncStatus(it) }),
            )
        }
        CapeCraftLog.LOGGER.info(
            "CapeCraft: серверная синхронизация включена, протокол v${SyncProtocol.VERSION}, " +
                "сервер только рассылает объявления и хранит картинки",
        )
    }

    /**
     * id объекта — из профиля отправителя.
     *
     * Клиент этот id знает и сам, из своей сессии, поэтому сверка работается без
     * объявления: сервер не пересылает «твой id», он выдаёт свой, и обе
     * стороны независимо приходят к одному значению.
     */
    private fun objectIdOf(player: ServerPlayerEntity): String = player.gameProfile.id.toString()

    private fun handle(bytes: ByteArray, player: ServerPlayerEntity, server: MinecraftServer) {
        val id = objectIdOf(player)
        val message = try {
            SyncCodec.decodeC2S(bytes)
        } catch (e: Exception) {
            // Битый пакет — молча игнорируем, иначе один кривой клиент будет
            // засорять лог на каждом своём сообщении.
            CapeCraftLog.LOGGER.debug("CapeCraft: отброшен битый пакет sync от $id: ${e.message}")
            return
        }
        when (message) {
            is Announce -> onAnnounce(id, message, player, server)
            is Upload -> onUpload(id, message)
            is Fetch -> onFetch(id, message, player)
        }
    }

    /**
     * Раздать объявления, отброшенные троттлингом.
     *
     * Вызывается каждый тик сервера. Объявление, пережившее троттлинг, ждёт
     * здесь своей очереди: как только интервал прошло, набор применяется и
     * снимок уходит всем. Игрок, ушедший с сервера, из очереди вычищен на
     * выходе, поэтому чужих объявлений здесь не бывает.
     */
    private fun flushPendingAnnounces(server: MinecraftServer) {
        if (pendingAnnounces.isEmpty()) return
        val now = System.nanoTime() / 1_000_000L
        for ((id, message) in pendingAnnounces) {
            if (now - (lastAnnounceAt[id] ?: 0L) < ANNOUNCE_MIN_INTERVAL_MS) continue
            // Игрок мог уйти между троттлингом и раздачей: объявлять за него
            // нечего, а роустер иначе сохранит мёртвую запись.
            if (server.playerManager.playerList.none { objectIdOf(it) == id }) {
                pendingAnnounces.remove(id)
                continue
            }
            pendingAnnounces.remove(id)
            lastAnnounceAt[id] = now
            if (roster.announce(id, message.functions)) {
                announcesAccepted++
                broadcast(server, null)
            }
        }
    }

    private fun onAnnounce(id: String, message: Announce, player: ServerPlayerEntity, server: MinecraftServer) {
        val now = System.nanoTime() / 1_000_000L
        val last = lastAnnounceAt[id] ?: 0L
        if (now - last < ANNOUNCE_MIN_INTERVAL_MS) {
            announcesThrottled++
            // Не теряем: объявление уйдёт, как только интервал пройдёт.
            pendingAnnounces[id] = message
            return
        }
        pendingAnnounces.remove(id)
        lastAnnounceAt[id] = now

        // Ревзия растёт только если набор реально отличается: иначе повторное
        // объявление того же гоняло бы снимок по всем клиентам впустую.
        if (!roster.announce(id, message.functions)) return
        announcesAccepted++
        broadcast(server, player.gameProfile.name)
    }

    private fun onUpload(id: String, message: Upload) {
        when (val result = images.acceptChunk(id, message)) {
            is ImageStore.UploadResult.Complete -> {
                uploadsAccepted++
                CapeCraftLog.LOGGER.info(
                    "CapeCraft: картинка ${ImageHash.toHex(result.hash).take(12)} загружена " +
                        "(${result.bytes} байт) и готова к отдаче",
                )
            }
            // Обрыв загрузки — обычное дело, логировать на уровне ошибки
            // незачем: клиент просто попробует ещё раз.
            else -> Unit
        }
    }

    private fun onFetch(id: String, message: Fetch, player: ServerPlayerEntity) {
        val chunk = images.chunk(message.hash, message.offset, message.length)
            ?: return // картинки нет — клиент сам решит, что без плаща
        // totalSize — размер ВСЕЙ картинки. Считать его как «размер куска плюс
        // смещение» нельзя: для первого куска это ровно размер первого куска, и
        // клиент собирал буфер в 64 КиБ, сверял с ним хэш всей картинки и
        // отбрасывал её. Докачка многочанковой картинки после этого шла не с
        // того места.
        val total = images.sizeOf(message.hash) ?: return
        val out = Chunk(
            hash = message.hash,
            totalSize = total,
            offset = message.offset,
            bytes = chunk,
        )
        ServerPlayNetworking.send(player, CapeSyncPayloads.ServerMessage(SyncCodec.encodeChunk(out)))
        chunksServed++
    }

    /**
     * Разослать снимок роустера всем, кому есть что принимать.
     *
     * Снимок целиком, а не дельта: дельты требуют, чтобы клиент помнил
     * предыдущее состояние, и одна потерянная дельта тихо оставляла бы у него
     * плащ, которого уже нет. Снимок стоит тем, что иногда лишний раз
     * повторяет то, что клиент и так знает.
     */
    private fun broadcast(server: MinecraftServer, who: String? = null) {
        val snapshot = roster.snapshot()
        if (snapshot.objects.isEmpty() && snapshot.revision == 0L) return
        val bytes = SyncCodec.encodeRoster(snapshot)
        broadcastCount++
        if (snapshot.truncated > 0) {
            CapeCraftLog.LOGGER.warn(
                "CapeCraft: снимок обрезан — ${snapshot.objects.size} объектов, " +
                    "потеряно ${snapshot.truncated} (лимит ${SyncProtocol.MAX_ROSTER_BYTES} байт). " +
                    "Часть игроков не получит свои плащи, пока не разойдутся.",
            )
        }
        var delivered = 0
        // playerList — приватное поле с публичным геттером, в Kotlin оно
        // доступно как свойство; players там тоже через геттер.
        for (target in server.playerManager.playerList) {
            ServerPlayNetworking.send(target, CapeSyncPayloads.ServerMessage(bytes))
            delivered++
        }
        if (who != null && delivered > 1) {
            CapeCraftLog.LOGGER.debug("CapeCraft: $who объявил набор, снимок v${snapshot.revision} ушёл $delivered клиентам")
        }
    }

    private fun status(ctx: CommandContext<ServerCommandSource>): Int {
        ctx.source.sendFeedback(
            { Text.literal("CapeCraft: объектов в роустере ${roster.size()}, ревизия ${roster.currentRevision()}, картинок ${images.count()}") },
            true,
        )
        return 1
    }

    private fun syncStatus(ctx: CommandContext<ServerCommandSource>): Int {
        ctx.source.sendFeedback(
            {
                Text.literal(
                    "CapeCraft Sync v${SyncProtocol.VERSION}: " +
                        "объявлений принято $announcesAccepted, отброшено троттлингом $announcesThrottled, " +
                        "рассылок $broadcastCount, картинок загружено $uploadsAccepted, " +
                        "чанков отдано $chunksServed, кэш ${images.totalBytes()} байт, " +
                        "незаконченных загрузок ${images.pendingCount()}",
                )
            },
            true,
        )
        return 1
    }
}
