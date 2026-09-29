package dev.ggtv.capecraft.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Сериализатор нужен редактору: LSP говорит через JSON-RPC, то есть наружу
 * уходит только текст. Проверяется в обе стороны — симметрия с парсером
 * ловит и потерянное поле, и неверное экранирование.
 */
class JsonRenderTest {

    private fun rt(text: String): J = Json.parse(text)

    @Test
    fun `примитивы печатаются как в JSON`() {
        assertEquals("null", Json.render(J.JNull))
        assertEquals("true", Json.render(J.JBool(true)))
        assertEquals("false", Json.render(J.JBool(false)))
        assertEquals("42", Json.render(J.JNum(42.0, true, 42L)))
        assertEquals("-7", Json.render(J.JNum(-7.0, true, -7L)))
        assertEquals("1.5", Json.render(J.JNum(1.5, false, 0L)))
    }

    @Test
    fun `целое не печатается как 3 point 0`() {
        // В JSON-RPC позиции и длины — целые. Если целое уедет в 3.0, клиент
        // либо не примет ответ, либо примет не там.
        assertEquals("3", Json.render(rt("3")))
        assertEquals("0", Json.render(rt("0")))
        assertEquals("1000000", Json.render(rt("1000000")))
    }

    @Test
    fun `объекты и массивы без пробелов, порядок полей сохранён`() {
        val v = rt("""{"b":1,"a":[true,null,"x"],"c":{}}""")
        assertEquals("""{"b":1,"a":[true,null,"x"],"c":{}}""", Json.render(v))
    }

    @Test
    fun `управляющие и кавычки экранируются`() {
        val v = J.JStr("кавычка \" обратная \\ перевод\nстроки\tтаб\u0001юнитог\u007F")
        val text = Json.render(v)
        assertTrue(text.contains("\\\""), "двойная кавычка: $text")
        assertTrue(text.contains("\\\\"), "обратная косая: $text")
        assertTrue(text.contains("\\n"), "перевод строки: $text")
        assertTrue(text.contains("\\t"), "таб: $text")
        assertTrue(text.contains("\\u0001"), "юнитог: $text")
        assertTrue(text.contains("\\u007f"), "DEL: $text")
    }

    @Test
    fun `кириллица не экранируется`() {
        // Клиент сам разберётся с UTF-8; экранировать каждую букву незачем и
        // это вдвое раздувает кадр.
        assertEquals("\"плащ\"", Json.render(J.JStr("плащ")))
    }

    @Test
    fun `render и parse симметричны`() {
        // Пробел внутри строки — часть значения, а не форматирование, поэтому
        // ожидание записано руками, а не сборкой мусора регуляркой.
        val source = """{"jsonrpc":"2.0","id":7,"result":{"uri":"file:///a b.kn","items":[],"n":-12.5,"ok":true,"z":null}}"""
        val once = rt(source)
        assertEquals(source, Json.render(once))
        assertEquals(once, rt(Json.render(once)))
    }

    @Test
    fun `пустой объект и массив не путаются с null`() {
        assertEquals("{}", Json.render(rt("{}")))
        assertEquals("[]", Json.render(rt("[]")))
        assertEquals("null", Json.render(rt("null")))
    }
}
