package dev.ggtv.capecraft.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Тесты перевода клиентских позиций в смещения и обратно.
 *
 * Отдельно от остального LSP потому, что ошибка здесь бессимптомна: считать
 * колонки в UTF-16, когда клиент считает в UTF-8, — это не исключение, а
 * подсветка на один символ левее. Ловить такое можно только тестом с
 * символом за пределами BMP, где единицы расходятся.
 */
class CrenDocumentTest {

    /**
     * Эмодзи за пределами BMP: 2 code unit, 1 кодпоинт, 4 байта.
     *
     * Символ собирается из code point, а не вписан в файл: иначе тест
     * зависит от того, как редактор сохранил исходник, и при смене кодировки
     * падает на ровном месте.
     */
    private val emoji = String(Character.toChars(0x1F600))

    /** Ключ `maxFrames` начинается с 45 UTF-16, 44 кодпоинта и 47 байт. */
    private val withEmoji = "capeCraft { limits { maxBytesPerCape = \"$emoji\", maxFrames = 100 } }"

    @Test
    fun `смещение по позиции клиента в трёх кодировках`() {
        val doc = CrenDocument(withEmoji)
        val atKey = withEmoji.indexOf("maxFrames")
        assertEquals(atKey, doc.offsetOfClientPosition(0, 45, PositionEncoding.UTF16))
        assertEquals(atKey, doc.offsetOfClientPosition(0, 44, PositionEncoding.UTF32))
        assertEquals(atKey, doc.offsetOfClientPosition(0, 47, PositionEncoding.UTF8))
    }

    @Test
    fun `колонка смещения в трёх кодировках`() {
        val doc = CrenDocument(withEmoji)
        val atMaxFrames = withEmoji.indexOf("maxFrames")
        assertEquals(45, doc.clientColumnOf(atMaxFrames, PositionEncoding.UTF16))
        assertEquals(44, doc.clientColumnOf(atMaxFrames, PositionEncoding.UTF32))
        assertEquals(47, doc.clientColumnOf(atMaxFrames, PositionEncoding.UTF8))
    }

    @Test
    fun `перевод туда и обратно сходится на каждой кодировке`() {
        val doc = CrenDocument(withEmoji)
        val atMaxFrames = withEmoji.indexOf("maxFrames")
        for (encoding in PositionEncoding.entries) {
            val column = doc.clientColumnOf(atMaxFrames, encoding)
            assertEquals(
                atMaxFrames,
                doc.offsetOfClientPosition(0, column, encoding),
                "кодировка ${encoding.protocolName}: смещение не вернулось",
            )
        }
    }

    @Test
    fun `по умолчанию utf-16`() {
        // Спецификация требует UTF-16, пока клиент не предложил другое, и
        // сервер не должен угадывать иначе.
        val doc = CrenDocument(withEmoji)
        assertEquals(
            doc.offsetOfClientPosition(0, 45, PositionEncoding.UTF16),
            doc.offsetOfClientPosition(0, 45),
        )
        assertEquals(45, doc.clientColumnOf(withEmoji.indexOf("maxFrames")))
    }

    @Test
    fun `позиция внутри эмодзи округляется к границе символа`() {
        // Клиент не должен присылать такое, но если пришлёт — мы обязаны
        // вернуть смещение на границе, а не внутрь сурогатной пары: внутри
        // пары смещение указывает на «половину» символа, и всё после него
        // разъедется.
        val doc = CrenDocument(withEmoji)
        val inside = doc.offsetOfClientPosition(0, 42, PositionEncoding.UTF8)
        assertEquals(42, inside, "байт 42 — это середина эмодзи, ждём конец символа")
        assertEquals("\", maxFrames = 100 } }", doc.text.substring(inside!!))
    }

    @Test
    fun `колонка за концом строки зажимается`() {
        val doc = CrenDocument("a = 1\nb = 2")
        assertEquals(5, doc.offsetOfClientPosition(0, 999, PositionEncoding.UTF16))
        assertEquals(5, doc.offsetOfClientPosition(0, 999, PositionEncoding.UTF32))
        assertEquals(5, doc.offsetOfClientPosition(0, 999, PositionEncoding.UTF8))
    }

    @Test
    fun `строка за пределами файла не переводится`() {
        val doc = CrenDocument("a = 1")
        assertNull(doc.offsetOfClientPosition(5, 0, PositionEncoding.UTF16))
        assertNull(doc.offsetOfClientPosition(-1, 0, PositionEncoding.UTF16))
    }

    @Test
    fun `замена диапазона по позициям клиента`() {
        // Заменяем `providers [` на блок server — как это делает редактор,
        // когда человек переписывает файл. Колонки клиентские, строки — с
        // нуля, как в протоколе.
        val doc = CrenDocument("capeCraft {\n    providers [\n    ]\n}")
        val patched = doc.replaceClientRange(
            1, 4, 1, 15, "server { enabled = true }", PositionEncoding.UTF16,
        )
        assertEquals(
            "capeCraft {\n    server { enabled = true }\n    ]\n}",
            patched,
        )
    }

    @Test
    fun `замена диапазона с эмодзи`() {
        val doc = CrenDocument("a = \"$emoji\"")
        // Эмодзи занимает 2 code unit, 1 кодпоинт и 4 байта — диапазон надо
        // задавать в единицах той кодировки, на которой считает клиент.
        assertEquals("a = \"x\"", doc.replaceClientRange(0, 5, 0, 7, "x", PositionEncoding.UTF16))
        assertEquals("a = \"x\"", doc.replaceClientRange(0, 5, 0, 6, "x", PositionEncoding.UTF32))
        assertEquals("a = \"x\"", doc.replaceClientRange(0, 5, 0, 9, "x", PositionEncoding.UTF8))
    }

    @Test
    fun `неверный диапазон даёт null а не порчу`() {
        val doc = CrenDocument("a = 1")
        assertNull(doc.replaceClientRange(9, 0, 9, 1, "x", PositionEncoding.UTF16))
        assertNull(doc.replaceClientRange(0, 4, 0, 1, "x", PositionEncoding.UTF16))
    }
}
