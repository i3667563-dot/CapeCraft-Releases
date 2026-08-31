package dev.ggtv.capecraft.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonPathTest {
    private fun parse(s: String) = Json.parse(s)

    @Test
    fun `simple field`() {
        val j = parse("""{"cape_url": "https://x/1.png"}""")
        assertEquals(J.JStr("https://x/1.png"), JsonPath.extract(j, "$.cape_url"))
    }

    @Test
    fun `nested field`() {
        val j = parse("""{"data": {"cape": {"url": "u"}}}""")
        assertEquals(J.JStr("u"), JsonPath.extract(j, "$.data.cape.url"))
    }

    @Test
    fun `array index`() {
        val j = parse("""{"capes": ["a.png", "b.png"]}""")
        assertEquals(J.JStr("b.png"), JsonPath.extract(j, "$.capes[1]"))
    }

    @Test
    fun `mixed object and array`() {
        val j = parse("""{"data": {"items": [{"url": "x"}], "n": 2}}""")
        assertEquals(J.JStr("x"), JsonPath.extract(j, "$.data.items[0].url"))
    }

    @Test
    fun `root-only path returns whole`() {
        val j = parse("""[1,2,3]""")
        assertEquals(j, JsonPath.extract(j, "$"))
    }

    @Test
    fun `extractString returns string`() {
        val j = parse("""{"u": "https://cape.png"}""")
        assertEquals("https://cape.png", JsonPath.extractString(j, "$.u"))
    }

    @Test
    fun `missing field fails`() {
        val j = parse("""{"a": 1}""")
        assertThrows(JsonError::class.java) { JsonPath.extract(j, "$.b") }
    }

    @Test
    fun `walking through non-object fails`() {
        val j = parse("""{"a": "str"}""")
        assertThrows(JsonError::class.java) { JsonPath.extract(j, "$.a.b") }
    }

    @Test
    fun `index out of range fails`() {
        val j = parse("""{"a": [1, 2]}""")
        assertThrows(JsonError::class.java) { JsonPath.extract(j, "$.a[5]") }
    }

    @Test
    fun `not a string fails`() {
        val j = parse("""{"a": 5}""")
        assertThrows(JsonError::class.java) { JsonPath.extractString(j, "$.a") }
    }

    @Test
    fun `path must start with dollar`() {
        assertThrows(JsonError::class.java) { JsonPath.extract(J.JNull, "a.b") }
    }

    @Test
    fun `empty field segment fails`() {
        assertThrows(JsonError::class.java) { JsonPath.extract(J.JNull, "$.a..b") }
    }
}
