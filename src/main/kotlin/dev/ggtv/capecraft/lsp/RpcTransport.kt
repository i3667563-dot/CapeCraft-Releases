package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.schema.J
import dev.ggtv.capecraft.schema.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Кадрирование JSON-RPC 2.0 поверх потоков — то, что LSP называет «base
 * protocol».
 *
 * ## Почему свой, а не библиотека
 *
 * Кадр — это заголовок `Content-Length: N` и ровно N байт после пустой строки.
 * Всё. Библиотека ради этого стоила бы десятка мегабайт в мод-зависимостях,
 * которые в мод не нужны: сервер запускает редактор, а не Minecraft.
 *
 * ## Главная опасность — не UTF-8, а длина
 *
 * Длина в заголовке считается в **байтах**, а не в символах. Считать в
 * символах — классическая ошибка, из-за которой сервер «работает» на файлах
 * без кириллицы и молча зависает на файле с ней: клиент читает N символов и
 * получает обрезанный JSON, а сервер читает остаток кадра как следующий
 * заголовок. Здесь длина меряется по байтам, и это закреплено тестом с
 * кириллицей в теле.
 */
class RpcTransport(
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    /**
     * Прочитать один кадр. `null` — поток кончился (клиент закрыл stdin, это
     * штатное завершение, а не ошибка).
     */
    fun read(): J.JObj? {
        val length = readContentLength() ?: return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) {
                // Кадр обещан, но не пришёл: это разрыв потока, а не «конец».
                // Молча вернуть null нельзя — клиент решит, что сервер умер
                // тихо, и не покажет ни одной ошибки.
                throw java.io.IOException("кадр обрезан: ждали $length байт, пришло $read")
            }
            read += n
        }
        val text = String(body, StandardCharsets.UTF_8)
        return when (val parsed = Json.parse(text)) {
            is J.JObj -> parsed
            // JSON-RPC требует объект. Массив или строка — это либо пакетная
            // форма, которую мы не объявляли, либо мусор; и то и другое лучше
            // назвать прямо, чем проглотить.
            else -> throw java.io.IOException("кадр не JSON-объект: ${text.take(80)}")
        }
    }

    /**
     * Отправить кадр.
     *
     * `synchronized` не для красоты: кроме потока сервера пишет поток
     * наблюдения за папкой `mods`, и без блокировки два кадра делили бы
     * `OutputStream` — заголовок одного попал бы в тело другого, и клиент
     * потерял бы не сообщение, а всю сессию.
     */
    @Synchronized
    fun write(message: J.JObj) {
        val text = Json.render(message)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    /**
     * Заголовки до пустой строки. Возвращает длину тела.
     *
     * Перевод строки в конце заголовка бывает и `\r\n`, и `\n` — клиенты
     * различаются, поэтому оба принимаются, а неожиданный заголовок
     * игнорируется, а не считается за длину.
     */
    private fun readContentLength(): Int? {
        var length: Int? = null
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) return length
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (!name.equals("Content-Length", ignoreCase = true)) continue
            val value = line.substring(colon + 1).trim().toIntOrNull()
                ?: throw java.io.IOException("мусор в Content-Length: «$line»")
            length = value
        }
    }

    private fun readLine(): String? {
        val buf = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.isEmpty()) null else buf.toString()
            if (c == '\n'.code) {
                val s = buf.toString()
                return if (s.endsWith("\r")) s.dropLast(1) else s
            }
            buf.append(c.toChar())
        }
    }

    override fun close() {
        // Потоки не закрываем: ими владеет вызывающий (в тестах — общий буфер),
        // и закрытие тут обрушило бы последующую диагностику.
    }
}
