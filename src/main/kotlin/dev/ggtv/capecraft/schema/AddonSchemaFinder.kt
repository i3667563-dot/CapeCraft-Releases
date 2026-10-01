package dev.ggtv.capecraft.schema

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Поиск дескрипторов аддонов рядом с конфигом.
 *
 * ## Откуда берутся дескрипторы
 *
 * Аддон кладёт `capecraft-addon.kn` в корень своего jar'а. Сервер читает их из
 * папки `mods` рядом с открытым конфигом — того же самого jar'а, который мод
 * подхватит при запуске игры. Никакой настройки в редакторе не нужно: путь
 * выводится из uri документа.
 *
 * ## Почему не из classpath
 *
 * Классы аддона живут в classpath мода и в classpath LSP не попадают: это
 * разные процессы и разные jar'ы. Даже если класс загрузился бы, его
 * статическая инициализация в редакторе — это запуск чужого кода ради
 * подсказки. Чтение одного файла из архива ничего не выполняет.
 *
 * ## Кэш
 *
 * Ключ — папка `mods` вместе с её содержимым. Список jar'ов и их размеры
 * складываются в подпись: пока не изменились, повторно открывать архивы
 * незачем, а перезапуск редактора не нужен после установки аддона — хватает
 * переоткрытия файла.
 */
object AddonSchemaFinder {

    /** Путь дескриптора внутри jar'а аддона. */
    const val DESCRIPTOR = "capecraft-addon.kn"

    /** Имя папки с модами рядом с конфигом. */
    const val MODS_DIR = "mods"

    /**
     * Подпись папки `mods`: имя jar'а, его размер и время изменения.
     *
     * Одного размера мало, и это не теория. Пересборка аддона, в которой
     * поправили описание ключа, даёт jar'а того же размера: длина строки почти
     * не меняется, а подсказки уже другие. Проверено подменой — два аддона с
     * дескрипторами одинаковой длины собираются в jar'ы ровно одного размера, и
     * сервер, глядя только на размер, показывал тип уже удалённого аддона.
     *
     * Время изменения ловит подмену целиком. Оно же меняется при переносе
     * папки или восстановлении из копии — тогда перечитывание лишнее, но не
     * вредное.
     *
     * Одно время тоже мало: две сборки в одну секунду неразличимы, поэтому
     * размер остаётся в подписи.
     */
    private fun signature(mods: Path): String {
        val sb = StringBuilder()
        try {
            val stream = Files.newDirectoryStream(mods) { p ->
                val n = p.fileName.toString()
                n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-dev.jar")
            }
            stream.use { entries ->
                for (p in entries.sortedBy { it.fileName.toString() }) {
                    sb.append(p.fileName).append(':')
                    sb.append(Files.size(p)).append(':')
                    sb.append(Files.getLastModifiedTime(p).toMillis()).append(';')
                }
            }
        } catch (e: Exception) {
            // Папки нет или она не читается: пустая подпись означает «нечего
            // читать», а не «сломались подсказки».
            LspLog.warn("папка $mods недоступна: $e")
        }
        return sb.toString()
    }

    /**
     * Кэш: путь к папке mods -> (подпись, дескрипторы).
     *
     * [ConcurrentHashMap], а не [HashMap]: читает поток наблюдения за папкой
     * `mods` (см. `ModsWatcher`), и обычная коллекция на чтении из чужого потока
     * рассинхронизируется. Значения кладём целиком, поэтому читатель видит либо
     * старый список, либо новый, но не половину.
     */
    private val cache = ConcurrentHashMap<String, Pair<String, List<AddonSchema>>>()

    /**
     * Дескрипторы аддонов для конфига по [configFile].
     *
     * Путь ищется вверх от файла: `.../minecraft/config/capecraft.kn` ->
     * `.../minecraft/mods`. Подниматься приходится, потому что редактор
     * открывает файл откуда угодно, а папка `mods` лежит на уровень выше.
     *
     * Пустой список — это «аддонов не видно», а не «ошибка»: конфиг в папке
     * без установленных модов совершенно обычен, и подсказки должны работать
     * для встроенных типов.
     */
    fun forConfig(configFile: Path): List<AddonSchema> {
        val mods = findModsDir(configFile) ?: return emptyList()
        val sig = signature(mods)
        val key = mods.toString()
        val cached = cache[key]
        if (cached != null && cached.first == sig) return cached.second

        val loaded = readAll(mods)
        cache[key] = sig to loaded
        if (loaded.isNotEmpty()) {
            LspLog.info(
                "найдено аддонов: ${loaded.joinToString(", ") { "${it.id} (${it.types.size} тип.)" }}",
            )
        }
        return loaded
    }

    /**
     * Ближайшая папка `mods` выше [from], или `null`.
     *
     * Подъём ограничен [MAX_DEPTH] уровнями: без ограничения путь в
     * `/home/user/проекты/клиент/mods/config/capecraft.kn` нашёл бы `mods`
     * в соседнем проекте — подсказки от чужого набора аддонов хуже, чем
     * никаких.
     */
    fun findModsDir(from: Path): Path? {
        var cur: Path? = from.toAbsolutePath().parent
        var depth = 0
        while (cur != null && depth < MAX_DEPTH) {
            val candidate = cur.resolve(MODS_DIR)
            if (Files.isDirectory(candidate)) return candidate
            cur = cur.parent
            depth++
        }
        return null
    }

    /** Прочитать дескриптор из одного jar'а. */
    fun readFrom(jar: Path): AddonSchema? = try {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(DESCRIPTOR) ?: return null
            zip.getInputStream(entry).use { stream ->
                AddonSchemaParser.parse(
                    stream.readBytes().toString(Charsets.UTF_8),
                    jar.fileName.toString(),
                )
            }
        }
    } catch (e: Exception) {
        LspLog.warn("${jar.fileName}: дескриптор не прочитан: $e")
        null
    }

    /** Сколько уровней вверх искать `mods`. */
    private const val MAX_DEPTH = 4

    private fun readAll(mods: Path): List<AddonSchema> = try {
        val stream = Files.newDirectoryStream(mods) { p ->
            val n = p.fileName.toString()
            n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-dev.jar")
        }
        stream.use { entries ->
            entries.sortedBy { it.fileName.toString() }.mapNotNull { readFrom(it) }
        }
    } catch (e: Exception) {
        LspLog.warn("не удалось прочитать $mods: $e")
        emptyList()
    }

    /**
     * Забыть всё прочитанное.
     *
     * Вызывается наблюдателем за папкой `mods` ([dev.ggtv.capecraft.lsp.ModsWatcher])
     * при изменении её содержимого: подпись и так изменилась бы, но подписаться
     * на событие дешевле, чем читать содержимое семидесяти архивов на каждое
     * нажатие клавиши.
     */
    fun invalidateAll() = cache.clear()

    /** Сброс кэша — для тестов. */
    fun clearCache() = cache.clear()
}