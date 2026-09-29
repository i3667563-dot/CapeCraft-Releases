package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.sync.SyncProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Границы [HttpFetcher].
 *
 * Сервер поднимается сырым сокетом, а не `com.sun.net.httpserver`: нужны
 * вручную подделанный `Content-Length` и тело, которое этому заголовку
 * противоречит. Проверяется ровно то, что не проверяется руками: потолок
 * ответа, отказ от не-HTTP схем и типизированность ошибок.
 */
class HttpFetcherTest {

    // ---- потолок размера ----

    @Test
    fun `тело больше лимита отвергается, а не читается в память`() {
        val body = ByteArray(4096) { 'x'.code.toByte() }
        withServer({ sock -> sock.respondOk(body) }) { port ->
            // лимит меньше тела: потолок должен сработать
            val f = HttpFetcher(maxCapeBytes = 1024)
            val err = assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Url("http://127.0.0.1:$port/cape.png"))
            }
            assertTrue(err.message!!.contains("больше лимита"), "было: ${err.message}")
        }
    }

    @Test
    fun `тело ровно в лимит проходит`() {
        val body = ByteArray(1024) { 'y'.code.toByte() }
        withServer({ sock -> sock.respondOk(body) }) { port ->
            val f = HttpFetcher(maxCapeBytes = 1024)
            assertArrayEquals(body, f.fetch(Resolved.Url("http://127.0.0.1:$port/cape.png")))
        }
    }

    @Test
    fun `пустое тело не считается превышением`() {
        withServer({ sock -> sock.respondOk(ByteArray(0)) }) { port ->
            val f = HttpFetcher(maxCapeBytes = 8)
            assertEquals(0, f.fetch(Resolved.Url("http://127.0.0.1:$port/empty.png")).size)
        }
    }

    @Test
    fun `поток без Content-Length обрывается на лимите`() {
        // Настоящий неограниченный случай: chunked-кодирование, длины нет
        // ни в заголовке, ни заранее. Сервер сам решает, когда остановиться,
        // поэтому потолок держит клиент, а не сервер.
        withServer({ sock -> sock.respondChunked(2 * 1024 * 1024) }) { port ->
            val f = HttpFetcher(maxCapeBytes = 1024)
            val err = assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Url("http://127.0.0.1:$port/stream.png"))
            }
            assertTrue(err.message!!.contains("больше лимита"), "было: ${err.message}")
        }
    }

    @Test
    fun `chunked-тело ровно в лимит проходит`() {
        val body = ByteArray(1024) { 'q'.code.toByte() }
        withServer({ sock -> sock.respondChunked(body.size, body) }) { port ->
            val f = HttpFetcher(maxCapeBytes = 1024)
            assertArrayEquals(body, f.fetch(Resolved.Url("http://127.0.0.1:$port/stream.png")))
        }
    }

    @Test
    fun `потолок капки совпадает с протокольным`() {
        // Число нельзя расходиться с Sync: капка, не влезшая в Sync, не должна
        // занимать память и при загрузке.
        assertEquals(SyncProtocol.MAX_IMAGE_BYTES, HttpFetcher.MAX_CAPE_BYTES)
        assertTrue(HttpFetcher.MAX_JSON_BYTES < HttpFetcher.MAX_CAPE_BYTES)
    }

    // ---- схема URL ----

    @Test
    fun `схема file даёт FetchError, а не сырое исключение`() {
        val f = HttpFetcher()
        val err = assertThrows(FetchError::class.java) {
            f.fetch(Resolved.Url("file:///etc/passwd"))
        }
        assertTrue(err.message!!.contains("схема"), "было: ${err.message}")
    }

    @Test
    fun `схема jar и data тоже отвергаются`() {
        val f = HttpFetcher()
        for (url in listOf("jar:file:///x.jar!/y.png", "data:image/png;base64,iVBOR")) {
            val err = assertThrows(FetchError::class.java) { f.fetch(Resolved.Url(url)) }
            assertTrue(err.message!!.contains("схема"), "было: ${err.message}")
        }
    }

    @Test
    fun `схема без указателя отвергается`() {
        val f = HttpFetcher()
        assertThrows(FetchError::class.java) { f.fetch(Resolved.Url("127.0.0.1:8080/cape.png")) }
    }

    @Test
    fun `некорректный URL даёт FetchError`() {
        val f = HttpFetcher()
        assertThrows(FetchError::class.java) { f.fetch(Resolved.Url("http://не домен/x")) }
    }

    // ---- статусы и JSON ----

    @Test
    fun `не-2xx даёт FetchError со статусом`() {
        withServer({ sock -> sock.respondRaw("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n", ByteArray(0)) }) { port ->
            val f = HttpFetcher()
            val err = assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Url("http://127.0.0.1:$port/missing.png"))
            }
            assertTrue(err.message!!.contains("404"), "было: ${err.message}")
        }
    }

    @Test
    fun `JSON-источник тянет капку по инструкции`() {
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val requests = AtomicInteger()
        withServer({ sock ->
            val n = requests.incrementAndGet()
            if (n == 1) {
                val back = "http://127.0.0.1:${sock.localPort}/real.png"
                sock.respondOk("""{"data":{"cape":{"url":"$back"}}}""".toByteArray())
            } else {
                sock.respondOk(png)
            }
        }) { port ->
            val f = HttpFetcher()
            val bytes = f.fetch(Resolved.Json("http://127.0.0.1:$port/api.json", "$.data.cape.url"))
            assertArrayEquals(png, bytes)
            assertEquals(2, requests.get())
        }
    }

    @Test
    fun `JSON больше своего потолка отвергается`() {
        val big = """{"u":"${"a".repeat(5000)}"}"""
        withServer({ sock -> sock.respondOk(big.toByteArray()) }) { port ->
            val f = HttpFetcher(maxJsonBytes = 128)
            val err = assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Json("http://127.0.0.1:$port/api.json", "$.u"))
            }
            assertTrue(err.message!!.contains("больше лимита"), "было: ${err.message}")
        }
    }

    @Test
    fun `JSON с чужим содержимым даёт FetchError`() {
        withServer({ sock -> sock.respondOk("не json вовсе".toByteArray()) }) { port ->
            val f = HttpFetcher()
            val err = assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Json("http://127.0.0.1:$port/api.json", "$.u"))
            }
            assertTrue(err.message!!.contains("разобрать JSON"), "было: ${err.message}")
        }
    }

    @Test
    fun `JSON без нужного поля даёт FetchError`() {
        withServer({ sock -> sock.respondOk("""{"other":1}""".toByteArray()) }) { port ->
            val f = HttpFetcher()
            assertThrows(FetchError::class.java) {
                f.fetch(Resolved.Json("http://127.0.0.1:$port/api.json", "$.u"))
            }
        }
    }

    // ---- инфраструктура ----

    private fun withServer(handler: (Socket) -> Unit, block: (Int) -> Unit) {
        val server = ServerSocket(0, 32, java.net.InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                try {
                    client.use(handler)
                } catch (_: Exception) {
                    // оборванный клиент — не интересно
                }
            }
        }
        try {
            block(server.localPort)
        } finally {
            runCatching { server.close() }
            worker.join(2000)
        }
    }

    private fun Socket.respondOk(body: ByteArray) =
        respondRaw("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n", body)

    private fun Socket.respondRaw(head: String, body: ByteArray) {
        readRequestHead()
        outputStream.write(head.toByteArray())
        outputStream.write(body)
        outputStream.flush()
    }

    private fun Socket.respondChunked(totalBytes: Int, chunk: ByteArray = ByteArray(8 * 1024) { 'c'.code.toByte() }) {
        readRequestHead()
        outputStream.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        var sent = 0
        try {
            while (sent < totalBytes) {
                val size = minOf(chunk.size, totalBytes - sent)
                outputStream.write("${Integer.toHexString(size)}\r\n".toByteArray())
                outputStream.write(chunk, 0, size)
                outputStream.write("\r\n".toByteArray())
                sent += size
            }
            outputStream.write("0\r\n\r\n".toByteArray())
            outputStream.flush()
        } catch (_: IOException) {
            // клиент оборвал соединение, увидев лимит, — это и есть успех теста
        }
    }

    /**
     * Читает только заголовок запроса — до пустой строки. `readBytes()` до EOF
     * здесь завис бы навсегда: клиент держит соединение открытым.
     */
    private fun Socket.readRequestHead() {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) return
        }
    }
}
