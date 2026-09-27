package dev.ggtv.capecraft.lsp

import dev.ggtv.capecraft.schema.J
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcTransportTest {

    private fun frame(body: String, eol: String = "\r\n"): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return ("Content-Length: ${bytes.size}$eol" + eol + body).toByteArray(Charsets.UTF_8)
    }

    private fun transport(input: String, eol: String = "\r\n") = RpcTransport(
        ByteArrayInputStream(frame(input, eol)),
        ByteArrayOutputStream(),
    )

    @Test
    fun `читает одиночный кадр`() {
        val t = transport("""{"jsonrpc":"2.0","id":1,"method":"shutdown"}""")
        val m = t.read()!!
        assertEquals("shutdown", (m["method"] as J.JStr).s)
    }

    @Test
    fun `длина считается в байтах а не в символах`() {
        // Кириллица: 2 байта на букву. Если бы длина считалась в символах,
        // клиент (и тест) прочитал бы обрезанный JSON, а остаток кадра был бы
        // принят за следующий заголовок. Именно на этом сервер «работает с
        // латиницей и зависает на русском».
        val body = """{"jsonrpc":"2.0","id":1,"method":"текст","params":{"подсказка":"плащ"}}"""
        val t = transport(body)
        val m = t.read()!!
        assertEquals("текст", (m["method"] as J.JStr).s)
        val params = m["params"] as J.JObj
        assertEquals("плащ", (params["подсказка"] as J.JStr).s)
    }

    @Test
    fun `читает кадры подряд в одном потоке`() {
        val input = frame("""{"id":1,"method":"a"}""") + frame("""{"id":2,"method":"b"}""")
        val t = RpcTransport(ByteArrayInputStream(input), ByteArrayOutputStream())
        assertEquals("a", ((t.read()!!["method"]) as J.JStr).s)
        assertEquals("b", ((t.read()!!["method"]) as J.JStr).s)
        assertNull(t.read(), "после последнего кадра должен быть конец потока")
    }

    @Test
    fun `принимает перевод строки без CR`() {
        val t = transport("""{"id":1,"method":"a"}""", eol = "\n")
        assertEquals("a", ((t.read()!!["method"]) as J.JStr).s)
    }

    @Test
    fun `пропускает чужие заголовки`() {
        // Клиент вправе прислать Content-Type, а в будущем пришлёт что-то ещё.
        // Лишний заголовок не должен ломать разбор.
        val body = """{"id":1,"method":"a"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val input = (
            "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" + body
            ).toByteArray(Charsets.UTF_8)
        val t = RpcTransport(ByteArrayInputStream(input), ByteArrayOutputStream())
        assertEquals("a", ((t.read()!!["method"]) as J.JStr).s)
    }

    @Test
    fun `обрезанный кадр падает с внятной ошибкой а не молча`() {
        val bytes = """{"id":1,"method":"a"}""".toByteArray(Charsets.UTF_8)
        // Обещаем 100 байт, присылаем 5: остаток — тишина.
        val input = "Content-Length: 100\r\n\r\n{\"id\"".toByteArray(Charsets.UTF_8)
        assertTrue(bytes.isNotEmpty())
        val t = RpcTransport(ByteArrayInputStream(input), ByteArrayOutputStream())
        val e = assertFailsWith<java.io.IOException> { t.read() }
        assertTrue(e.message!!.contains("обрезан"), "сообщение должно называть проблему: ${e.message}")
    }

    @Test
    fun `мусор в Content-Length падает с внятной ошибкой`() {
        val input = "Content-Length: много\r\n\r\n".toByteArray(Charsets.UTF_8)
        val t = RpcTransport(ByteArrayInputStream(input), ByteArrayOutputStream())
        val e = assertFailsWith<java.io.IOException> { t.read() }
        assertTrue(e.message!!.contains("Content-Length"), "сообщение должно называть заголовок: ${e.message}")
    }

    @Test
    fun `кадр не объект отвергается`() {
        val t = transport("""[1,2,3]""")
        val e = assertFailsWith<java.io.IOException> { t.read() }
        assertTrue(e.message!!.contains("объект"), "сообщение должно называть проблему: ${e.message}")
    }

    @Test
    fun `запись оборачивает в заголовок с длиной в байтах`() {
        val out = ByteArrayOutputStream()
        RpcTransport(ByteArrayInputStream(ByteArray(0)), out)
            .write(J.JObj(listOf("method" to J.JStr("плащ"))))
        val text = out.toString(Charsets.UTF_8.name())
        val headerEnd = text.indexOf("\r\n\r\n")
        assertTrue(headerEnd > 0, "нет заголовка: $text")
        val declared = text.substringAfter("Content-Length: ").substringBefore("\r\n").trim().toInt()
        val body = text.substring(headerEnd + 4)
        assertEquals(
            declared,
            body.toByteArray(Charsets.UTF_8).size,
            "заголовок обязан совпадать с длиной тела в байтах",
        )
        assertEquals("""{"method":"плащ"}""", body)
    }

    private operator fun J.JObj.get(key: String): J? = fields.firstOrNull { it.first == key }?.second
}
