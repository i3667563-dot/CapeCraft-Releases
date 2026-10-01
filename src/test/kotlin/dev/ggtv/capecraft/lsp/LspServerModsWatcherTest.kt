package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.schema.AddonSchemaFinder
import dev.ggtv.capecraft.schema.ConfigSchema
import dev.ggtv.capecraft.schema.J
import dev.ggtv.capecraft.schema.Json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сервер, который ещё работает в момент, когда тест трогает диск.
 *
 * Обычный [LspServerTest] отдаёт все кадры разом и читает ответы после
 * остановки сервера. Так не проверить главное свойство наблюдения за `mods`:
 * сервер **сам**, без единого запроса клиента, переиздаёт диагностики, когда
 * аддон убрали из папки или положили в неё. Отсюда живые трубы, отдельный
 * поток на чтение ответов и ожидание сообщения, которого никто не просил.
 */
class LspServerModsWatcherTest {

    private lateinit var root: Path
    private lateinit var config: Path
    private lateinit var inbox: ArrayBlockingQueue<J.JObj>
    private lateinit var log: StringBuilder
    private var clientToServer: OutputStream? = null
    private var serverThread: Thread? = null

    private val uri: String get() = "file://$config"

    private val configText = """
        capeCraft {
            providers = [
                { type = "seed"
                  self = true }
            ]
        }
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        AddonSchemaFinder.clearCache()
        ConfigSchema.clearAddonSchemas()
        ModsWatcher.resetForTests()
        root = Files.createTempDirectory("capecraft-lsp-mods")
        config = root.resolve("config/capecraft.kn")
        config.parent.createDirectories()
        config.writeText(configText, Charsets.UTF_8)
        inbox = ArrayBlockingQueue(64)
        log = StringBuilder()
    }

    @AfterTest
    fun tearDown() {
        clientToServer?.close()
        serverThread?.join(3000)
        AddonSchemaFinder.clearCache()
        ConfigSchema.clearAddonSchemas()
        root.toFile().deleteRecursively()
    }

    /** Настоящий jar с дескриптором аддона. */
    private fun addonJar(name: String, id: String, type: String): Path {
        val path = root.resolve("mods/$name")
        path.parent.createDirectories()
        val descriptor = """
            addon {
                id = "$id"
                version = "1.0.0"
                apiVersion = 1

                types = [ { id = "$type", doc = "$type", keys = [ { name = "gray", type = "bool" } ] } ]
            }
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((entry, body) in mapOf("capecraft-addon.kn" to descriptor, "fabric.mod.json" to "{}")) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }

    private fun startServer() {
        val serverIn = PipedInputStream(64 * 1024)
        val toServer = PipedOutputStream(serverIn)
        clientToServer = toServer

        val fromServer = PipedOutputStream()
        val serverOut = PipedInputStream(fromServer, 64 * 1024)

        serverThread = Thread {
            LspServer(serverIn, fromServer) { log.appendLine(it) }.run()
        }.apply {
            isDaemon = true
            start()
        }

        val reader = RpcTransport(serverOut, ByteArrayOutputStream())
        Thread {
            while (true) {
                val msg = try {
                    reader.read() ?: break
                } catch (e: Exception) {
                    break
                }
                inbox.offer(msg)
            }
        }.apply {
            isDaemon = true
            name = "lsp-test-client"
            start()
        }

        send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}""")
        send("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")
        val text = Json.render(J.JStr(configText))
        send("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"$uri","languageId":"kn","version":1,"text":$text}}}""")
    }

    private fun send(raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        clientToServer?.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        clientToServer?.write(bytes)
        clientToServer?.flush()
    }

    private fun J.JObj.at(key: String): J? = fields.firstOrNull { it.first == key }?.second

    private fun J.JObj.str(key: String): String? = (at(key) as? J.JStr)?.s

    /** Диагностики по нашему uri, как их видит клиент. */
    private fun diagnostics(timeoutMs: Long = 10_000): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val msg = inbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val params = msg.at("params") as? J.JObj ?: continue
            if (msg.str("method") != "textDocument/publishDiagnostics" || params.str("uri") != uri) continue
            val items = (params.at("diagnostics") as? J.JArr)?.items ?: return emptyList()
            return items.mapNotNull { ((it as? J.JObj)?.at("message") as? J.JStr)?.s }
        }
        error("диагностики не пришли за $timeoutMs мс; в логе сервера:\n$log")
    }

    private fun publishedMethods(): List<String> = inbox.mapNotNull { it.str("method") }

    @Test
    fun `убранный аддон исчезает из диагностик без переоткрытия файла`() {
        // Сценарий из жизни: аддон положили в mods, поправили конфиг, потом
        // отнесли jar в другую папку и вернулись к файлу, не переоткрывая его.
        // До наблюдения сервер молчал и держал подсказки удалённого типа.
        addonJar("seed.jar", id = "seed", type = "seed")
        startServer()
        assertEquals(emptyList(), diagnostics(), "с аддоном тип seed законен, ругаться не на что")
        inbox.clear()

        Files.createDirectories(root.resolve("mods-archive"))
        Files.move(root.resolve("mods/seed.jar"), root.resolve("mods-archive/seed.jar"))

        assertTrue(
            diagnostics().any { it.contains("seed") && it.contains("не подходит") },
            "аддон убран из mods — сервер обязан сам сообщить, что тип больше не подходит.\nВ логе:\n$log",
        )
    }

    @Test
    fun `появившийся аддон убирает ошибку без переоткрытия файла`() {
        startServer()
        assertTrue(
            diagnostics().any { it.contains("seed") && it.contains("не подходит") },
            "без аддона тип seed неизвестен",
        )
        inbox.clear()

        addonJar("late.jar", id = "late", type = "seed")

        assertEquals(
            emptyList(),
            diagnostics(),
            "аддон поставили — ошибка должна уйти сама, без запроса от клиента.\nВ логе:\n$log",
        )
    }

    @Test
    fun `установка аддона не превращается в шторм публикаций`() {
        // Копирование jar'а — это создание плюс несколько записей, и без
        // склейки событий клиент получил бы десяток одинаковых публикаций.
        // Одна публикация законна: аддон действительно появился. Две — нет.
        addonJar("a.jar", id = "a", type = "seed")
        startServer()
        assertEquals(emptyList(), diagnostics())
        inbox.clear()

        addonJar("b.jar", id = "b", type = "seed")
        Thread.sleep(800)

        assertEquals(
            1,
            publishedMethods().count { it == "textDocument/publishDiagnostics" },
            "установка одного jar'а — одна публикация, а не на каждое событие.\nВ логе:\n$log",
        )
    }
}