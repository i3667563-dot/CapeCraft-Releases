package dev.ggtv.koren

import dev.ggtv.koren.KorenTokenKind.*
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Обратная совместимость токенизации: `.crn` — строгое подмножество `.kn`.
 * Порт TokenizerTest из Kjen + новые скобочные токены.
 */
class KorenTokenizerTest {

    private fun kinds(input: String): List<KorenTokenKind> =
        KorenTokenizer.tokenize(input).map { it.kind }

    private fun kindsWithoutNewlines(input: String): List<KorenTokenKind> =
        kinds(input).filterNot { it == Newline }

    @Test
    fun `comment is saved`() {
        val tokens = KorenTokenizer.tokenize("# привет, я коммент\n")
        assertEquals(Comment("привет, я коммент"), tokens[0].kind)
    }

    @Test
    fun `comment after value`() {
        assertEquals(
            listOf(Word("port"), Assign, Int(8080), Comment("слушать тут"), Newline),
            kinds("port = 8080 # слушать тут\n"),
        )
    }

    @Test
    fun `hash inside quotes is not comment`() {
        assertEquals(
            listOf(Word("title"), Assign, Str("#не_коммент")),
            kindsWithoutNewlines("title = \"#не_коммент\"\n"),
        )
    }

    @Test
    fun `block and array tokens`() {
        val tokens = kinds("server {\n    databases [\n        { type = \"postgres\" },\n    ]\n}\n")
        assertEquals(
            listOf(
                Word("server"), LBrace, Newline,
                Word("databases"), LBracket, Newline,
                LBrace, Word("type"), Assign, Str("postgres"), RBrace, Comma, Newline,
                RBracket, Newline,
                RBrace, Newline,
            ),
            tokens,
        )
    }

    @Test
    fun `ref path tokens`() {
        assertEquals(
            listOf(
                Word("token"), Assign,
                Word("server"), Dot, Word("token"),
                LBracket, Int(1), RBracket,
            ),
            kindsWithoutNewlines("token = server.token[1]\n"),
        )
    }

    @Test
    fun `numbers`() {
        assertEquals(
            listOf(
                Word("a"), Assign, Int(42),
                Word("b"), Assign, Int(-7),
                Word("c"), Assign, Float(1.5),
            ),
            kindsWithoutNewlines("a = 42\nb = -7\nc = 1.5\n"),
        )
    }

    @Test
    fun `bools`() {
        assertEquals(
            listOf(
                Word("a"), Assign, Bool(true),
                Word("b"), Assign, Bool(false),
            ),
            kindsWithoutNewlines("a = true\nb = false\n"),
        )
    }

    @Test
    fun `word with dash is word not number`() {
        assertEquals(
            listOf(Word("my-key"), Assign, Int(-5)),
            kindsWithoutNewlines("my-key = -5\n"),
        )
    }

    @Test
    fun `word starting with digit is word`() {
        assertEquals(
            listOf(
                Word("2fa"), Assign, Bool(true),
                Word("1password"), Assign, Str("x"),
            ),
            kindsWithoutNewlines("2fa = true\n1password = \"x\"\n"),
        )
    }

    @Test
    fun `escapes in string`() {
        assertEquals(
            listOf(Word("s"), Assign, Str("say \"hi\"")),
            kindsWithoutNewlines("s = \"say \\\"hi\\\"\"\n"),
        )
    }

    @Test
    fun `dict inline tokens`() {
        assertEquals(
            listOf(
                Word("token"), Assign,
                LBrace, Word("name"), Colon, Str("bot"), Comma,
                Word("value"), Colon, Str("..."), RBrace,
            ),
            kindsWithoutNewlines("token = {name: \"bot\", value: \"...\"}\n"),
        )
    }

    @Test
    fun `unicode word keys`() {
        val tokens = kinds("ключ = 1\n")
        assertEquals(listOf(Word("ключ"), Assign, Int(1), Newline), tokens)
    }

    @Test
    fun `function call tokens`() {
        assertEquals(
            listOf(
                Word("x"), Assign,
                Word("clamp"), LParen, Word("a"), Comma, Int(0), Comma, Int(100), RParen,
                Newline,
            ),
            kinds("x = clamp(a, 0, 100)\n"),
        )
    }

    @Test
    fun `nested function call tokens`() {
        assertEquals(
            listOf(
                Word("x"), Assign,
                Word("lerp"), LParen, Int(0), Comma, Int(1),
                Comma, Word("clamp"), LParen, Word("t"), Comma, Int(0), Comma, Int(1), RParen,
                RParen, Newline,
            ),
            kinds("x = lerp(0, 1, clamp(t, 0, 1))\n"),
        )
    }

    @Test
    fun `error unclosed string`() {
        val e = assertFailsWith<CrenError.Parse> { KorenTokenizer.tokenize("s = \"не закрыто\n") }
        assertTrue(e.messageText.contains("незакрытая строка"))
        assertEquals(Span(1, 5), e.span)
    }

    @Test
    fun `error unknown escape`() {
        val e = assertFailsWith<CrenError.Parse> { KorenTokenizer.tokenize("s = \"\\q\"\n") }
        assertTrue(e.messageText.contains("неизвестный escape"))
    }

    @Test
    fun `error unexpected char`() {
        val e = assertFailsWith<CrenError.Parse> { KorenTokenizer.tokenize("a = @\n") }
        assertTrue(e.messageText.contains("неожиданный символ"))
        assertEquals(Span(1, 5), e.span)
    }

    @Test
    fun `error span tracks lines`() {
        val e = assertFailsWith<CrenError.Parse> { KorenTokenizer.tokenize("a = 1\nb = \"\\q\"\n") }
        assertEquals(2, e.span.line)
    }

    @Test
    fun `empty input`() {
        assertTrue(KorenTokenizer.tokenize("").isEmpty())
    }

    @Test
    fun `only comments`() {
        assertEquals(
            listOf(Comment("один"), Newline, Newline, Comment("два"), Newline),
            kinds("# один\n\n# два\n"),
        )
    }
}