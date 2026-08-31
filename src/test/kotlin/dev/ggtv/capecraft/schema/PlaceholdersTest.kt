package dev.ggtv.capecraft.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PlaceholdersTest {
    private val ctx = Placeholders.Context(
        username = "Steve",
        uuid = "069a79f444e94726a5befca90e38aaf5",
        name = "trusted",
        root = "/home/user/.minecraft/capecraft",
    )

    @Test
    fun `all placeholders replaced`() {
        val t = "https://api.example.com/{username}/{uuid}?name={name}&root={root}"
        assertEquals(
            "https://api.example.com/Steve/069a79f444e94726a5befca90e38aaf5?name=trusted&root=/home/user/.minecraft/capecraft",
            Placeholders.render(t, ctx),
        )
    }

    @Test
    fun `no placeholders passthrough`() {
        assertEquals("hello", Placeholders.render("hello", ctx))
    }

    @Test
    fun `placeholder in the middle`() {
        assertEquals("aSteveb", Placeholders.render("a{username}b", ctx))
    }

    @Test
    fun `unknown placeholder fails`() {
        assertThrows(JsonError::class.java) { Placeholders.render("{bogus}", ctx) }
    }

    @Test
    fun `unclosed placeholder fails`() {
        assertThrows(JsonError::class.java) { Placeholders.render("{username", ctx) }
    }

    @Test
    fun `empty context yields empty values`() {
        assertEquals("-", Placeholders.render("{username}-{uuid}", Placeholders.Context()))
    }
}
