package dev.ggtv.capecraft.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `object with fields`() {
        val j = Json.parse("""{"a": 1, "b": "x", "c": true, "d": null}""")
        val obj = j as J.JObj
        assertEquals(4, obj.fields.size)
        assertEquals("a", obj.fields[0].first)
        assertEquals(J.JNum(1.0, true, 1), obj.fields[0].second)
        assertEquals(J.JStr("x"), obj.fields[1].second)
        assertEquals(J.JBool(true), obj.fields[2].second)
        assertEquals(J.JNull, obj.fields[3].second)
    }

    @Test
    fun `nested object and array`() {
        val j = Json.parse("""{"data": {"cape_url": "https://x/1.png"}, "list": [1, 2.5, "s"]}""")
        val data = (j as J.JObj).fields[0].second as J.JObj
        assertEquals(J.JStr("https://x/1.png"), data.fields[0].second)
        val list = (j as J.JObj).fields[1].second as J.JArr
        assertEquals(J.JNum(2.5, false, 0), list.items[1])
    }

    @Test
    fun `float number is marked float`() {
        val j = Json.parse("3.14") as J.JNum
        assertEquals(false, j.isInt)
        assertEquals(j.d, 3.14, 1e-9)
    }

    @Test
    fun `escaped strings`() {
        val j = Json.parse(""""a\"b\\c\n\u0041"""") as J.JStr
        assertEquals("a\"b\\c\nA", j.s)
    }

    @Test
    fun `empty object and array`() {
        assertEquals(J.JObj(emptyList()), Json.parse("{}"))
        assertEquals(J.JArr(emptyList()), Json.parse("[]"))
    }

    @Test
    fun `whitespace tolerated`() {
        val j = Json.parse("""  {  "a" : 1  }  """) as J.JObj
        assertEquals(J.JNum(1.0, true, 1), j.fields[0].second)
    }

    @Test
    fun `trailing garbage fails`() {
        assertThrows(JsonError::class.java) { Json.parse("{} xyz") }
    }

    @Test
    fun `unclosed string fails`() {
        assertThrows(JsonError::class.java) { Json.parse(""""abc""") }
    }

    @Test
    fun `bad number fails`() {
        assertThrows(JsonError::class.java) { Json.parse("--5") }
    }

    @Test
    fun `top-level array`() {
        val j = Json.parse("[1, 2, 3]") as J.JArr
        assertEquals(3, j.items.size)
    }
}
