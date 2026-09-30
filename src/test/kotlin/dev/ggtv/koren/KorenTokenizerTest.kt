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

    private fun envKinds(
        input: String,
        env: Map<String, String>,
        onUnset: (String) -> Unit = {},
    ): List<KorenTokenKind> =
        KorenTokenizer.tokenizeWithEnv(input, env, onUnset)
            .map { it.kind }.filterNot { it == Newline }

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

    @Test
    fun `non finite float is rejected`() {
        val e = assertFailsWith<CrenError.Parse> {
            KorenTokenizer.tokenize("a = ${"9".repeat(309)}.0\n")
        }
        assertTrue(e.messageText.contains("вне диапазона f64"))
    }

    @Test
    fun `fractional number suffix is rejected`() {
        val e = assertFailsWith<CrenError.Parse> { KorenTokenizer.tokenize("a = 2.3foo\n") }
        assertTrue(e.messageText.contains("после дробного числа"))
    }

    @Test
    fun `raw environment value supports supplementary unicode`() {
        val tokens = KorenTokenizer.tokenizeWithEnv(
            "emoji = ${'$'}KOREN_EMOJI\nport = 8080\n",
            mapOf("KOREN_EMOJI" to "😀"),
        )
        assertEquals(Str("😀"), tokens[2].kind)
        assertEquals(Word("port"), tokens[4].kind)
        assertEquals(Int(8080), tokens[6].kind)
    }

    // ─── `$` в ключе против `$` в значении ─────────────────────────────────
    //
    // Подстановка окружения жадная и случается на разборе: незаданная
    // переменная — ошибка загрузки. Для значения так правильно, а вот ключ
    // `if = { $TIER: "gold" }` подстановка бы убила: имя исчезло бы, и условие
    // стало бы сравнением плейсхолдера по имени, совпавшему со значением
    // переменной.
    //
    // Поэтому ключ отдаётся как [EnvRef] и разбирается позже — тем, кто знает,
    // что это условие, когда переменная уже может появиться.

    @Test
    fun `env key is not substituted even when it is set`() {
        val k = envKinds("if = { ${'$'}TIER: \"gold\" }\n", mapOf("TIER" to "prod"))
        assertEquals(listOf(Word("if"), Assign, LBrace, EnvRef("${'$'}TIER"), Colon, Str("gold"), RBrace), k)
    }

    @Test
    fun `env key survives when variable is missing`() {
        val k = envKinds("if = { ${'$'}TIER: \"gold\" }\n", emptyMap())
        assertEquals(EnvRef("${'$'}TIER"), k[3])
    }

    @Test
    fun `env key with hyphen and underscore is one name`() {
        val hyphen = envKinds("if = { ${'$'}TIER-ONE: \"gold\" }\n", emptyMap())
        assertEquals(EnvRef("${'$'}TIER-ONE"), hyphen[3])
        val underscore = envKinds("if = { ${'$'}TIER_ONE: \"gold\" }\n", emptyMap())
        assertEquals(EnvRef("${'$'}TIER_ONE"), underscore[3])
    }

    @Test
    fun `env key in braces is not substituted`() {
        val k = envKinds("if = { ${'$'}{TIER}: \"gold\" }\n", mapOf("TIER" to "prod"))
        assertEquals(EnvRef("${'$'}{TIER}"), k[3])
    }

    @Test
    fun `env key accepts equals as separator`() {
        val k = envKinds("if = { ${'$'}TIER = \"gold\" }\n", emptyMap())
        assertEquals(listOf(Word("if"), Assign, LBrace, EnvRef("${'$'}TIER"), Assign, Str("gold"), RBrace), k)
    }

    @Test
    fun `env key accepts spaces around separator`() {
        val k = envKinds("if = { ${'$'}TIER : \"gold\" }\n", emptyMap())
        assertEquals(listOf(Word("if"), Assign, LBrace, EnvRef("${'$'}TIER"), Colon, Str("gold"), RBrace), k)
    }

    @Test
    fun `env key may be followed by another entry`() {
        val k = envKinds("if = { ${'$'}TIER: \"gold\", username: \"Eonixx\" }\n", emptyMap())
        assertEquals(EnvRef("${'$'}TIER"), k[3])
        assertEquals(Word("username"), k[7])
    }

    @Test
    fun `env key on its own line is still a key`() {
        val k = envKinds(
            "if = {\n    ${'$'}TIER: \"gold\",\n    username: \"Eonixx\",\n}\n",
            emptyMap(),
        )
        assertEquals(EnvRef("${'$'}TIER"), k[3])
        assertEquals(Word("username"), k[7])
    }

    @Test
    fun `env in value is still substituted`() {
        val k = envKinds("url = ${'$'}HOST\n", mapOf("HOST" to "caps.example.com"))
        assertEquals(Str("caps.example.com"), k[2])
    }

    @Test
    fun `missing env in value is an empty string and not an error`() {
        val unset = mutableListOf<String>()
        val k = envKinds(
            "url = ${'$'}NOT_SET_ANYWHERE\n",
            emptyMap(),
            onUnset = { unset += it },
        )
        assertEquals(Str(""), k[2])
        assertEquals(listOf("NOT_SET_ANYWHERE"), unset)
    }

    @Test
    fun `env in value with colon is one value`() {
        // Регрессия: `:` не разрывает `$`-кусок, иначе документированный
        // `url = $HOST:8080/capes/x.png` рассыпался бы на три токена.
        val k = envKinds("url = ${'$'}HOST:8080/capes/x.png\n", mapOf("HOST" to "example.com"))
        assertEquals(Str("example.com:8080/capes/x.png"), k[2])
    }

    @Test
    fun `env at end of line is a value not a key`() {
        // `$NAME` в конце строки — почти всегда значение: новая строка после
        // `$` намеренно не разрывает «ключ», иначе любая следующая строка
        // могла бы превратить значение в ключ.
        val k = envKinds("a = ${'$'}HOST\nb = 1\n", mapOf("HOST" to "h"))
        assertEquals(Str("h"), k[2])
        assertEquals(Word("b"), k[3])
    }

    @Test
    fun `env ref with trailing text is a value`() {
        val k = envKinds("url = ${'$'}HOST/p.png\n", mapOf("HOST" to "example.com"))
        assertEquals(Str("example.com/p.png"), k[2])
    }
}
