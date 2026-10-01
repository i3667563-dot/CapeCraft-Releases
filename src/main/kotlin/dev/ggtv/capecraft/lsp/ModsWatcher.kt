package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.schema.AddonSchemaFinder
import dev.ggtv.capecraft.schema.LspLog
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

/**
 * Наблюдение за папкой `mods`, чтобы подсказки про аддонов не устаревали.
 *
 * ## Зачем
 *
 * Без наблюдения аддон, который положили в `mods` или оттуда убрали, был виден
 * серверу только при следующем `didOpen`. На практике это выглядит так: человек
 * убрал аддон в другую папку, вернулся к конфигу, а редактор по-прежнему
 * предлагал тип удалённого аддона — и ругался на код, который только что был
 * законным. Приходилось переоткрывать файл руками.
 *
 * ## Как
 *
 * [WatchService] на каталоге `mods` и на его родителе. Родитель нужен для двух
 * событий, которых не видит сам каталог: папка `mods` удалилась целиком или
 * появилась только что. События склеиваются: копирование jar'а порождает
 * десяток `ENTRY_MODIFY`, а читать семьдесят архивов на каждое из них — работа
 * впустую.
 *
 * ## Поток
 *
 * Один поток на весь сервер, демон, потому что он не должен держать JVM. Он не
 * разбирает документы и не считает диагностики сам — только роняет кэш и
 * зовёт [onChanged]. Дальше всё делает поток сервера, который и так работает с
 * документами.
 *
 * Наследник [AutoCloseable] не нужен: объект живёт вместе с процессом, а
 * `shutdown` сервера всё равно заканчивается вместе с ним.
 */
object ModsWatcher {

    /** Сколько ждать, пока осядут события одного изменения. */
    private const val DEBOUNCE_MS = 150L

    private val service: WatchService by lazy { FileSystems.getDefault().newWatchService() }

    /** Уже подписанные каталоги: повторная подписка на тот же путь не нужна. */
    private val watched = ConcurrentHashMap.newKeySet<Path>()

    /** Что звать, когда папка `mods` изменилась. Ставится при старте сервера. */
    @Volatile
    private var onChanged: (() -> Unit)? = null

    @Volatile
    private var started = false

    /**
     * Подписаться на изменения и задать, что делать при них.
     *
     * Повторный вызов только меняет обработчик: второй поток не нужен, а
     * подписки уже на месте.
     */
    fun start(onChanged: () -> Unit) {
        this.onChanged = onChanged
        if (started) return
        started = true
        Thread(::loop, "capecraft-lsp-mods").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Следить за `mods` для конфига [configFile].
     *
     * Само наличие папки не проверяется: если её нет, подписываться не на что, а
     * [AddonSchemaFinder] всё равно вернёт пустой список. Когда папка появится,
     * событие придёт с родителя.
     */
    fun watchFor(configFile: Path) {
        val mods = AddonSchemaFinder.findModsDir(configFile) ?: return
        subscribe(mods)
        mods.parent?.let { subscribe(it) }
    }

    /** Подписаться на каталог, если ещё не подписаны. */
    private fun subscribe(dir: Path) {
        val path = dir.toAbsolutePath().normalize()
        if (!watched.add(path)) return
        try {
            if (!Files.isDirectory(path)) {
                watched.remove(path)
                return
            }
            // Подписка идёт на сам каталог, а не на сервис: `WatchService`
            // — это только очередь событий, регистрировать каталоги должна
            // реализация `Watchable`, которой является `Path`.
            path.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
        } catch (e: Exception) {
            watched.remove(path)
            LspLog.warn("не слежу за $path: $e")
        }
    }

    /**
     * Основной цикл: разбудили — подождать, пока события осядут, — обрушить
     * кэш и позвать обработчик.
     */
    private fun loop() {
        while (true) {
            val key = try {
                service.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: ClosedWatchServiceException) {
                return
            }
            try {
                settle(key)
                AddonSchemaFinder.invalidateAll()
                onChanged?.invoke()
            } catch (e: Exception) {
                // Наблюдение — удобство, а не основа работы. Упало наблюдение,
                // сервер продолжает работать: аддоны перечитаются при
                // следующем запросе по схеме.
                LspLog.warn("наблюдение за mods прервано: $e")
            }
        }
    }

    /**
     * Слить события, накопившиеся пока сервер возился с архивами.
     *
     * События, случившиеся в папке, которой уже нет, заодно уводят подписку:
     * без этого ключ остался бы в [watched] навсегда и при пересоздании папки
     * событий мы бы не получили.
     */
    private fun settle(first: WatchKey) {
        Thread.sleep(DEBOUNCE_MS)
        val keys = ArrayList<WatchKey>()
        keys += first
        while (true) {
            val more = service.poll() ?: break
            keys += more
        }
        for (k in keys) {
            if (k.pollEvents().isNotEmpty()) forgetMissing(k)
            if (!k.reset()) forgetMissing(k)
        }
    }

    /** Снять подписку с каталогов, которых больше нет. */
    private fun forgetMissing(key: WatchKey) {
        val dir = key.watchable() as? Path ?: return
        if (Files.isDirectory(dir)) return
        if (watched.remove(dir)) {
            try {
                key.cancel()
            } catch (e: Exception) {
                LspLog.warn("не снять наблюдение за $dir: $e")
            }
        }
    }

    /** Только для тестов: забыть все подписки. */
    fun resetForTests() {
        watched.clear()
    }
}