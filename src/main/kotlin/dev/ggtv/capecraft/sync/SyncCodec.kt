package dev.ggtv.capecraft.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// ─────────────────────────── модели сообщений ───────────────────────────

/** C2S: «вот мой набор функций». Своего id клиент не присылает — сервер берёт отправителя. */
data class Announce(val functions: List<ActiveCape>)

/**
 * Один объект в роустере: непрозрачный [id] и его набор функций.
 *
 * ## Почему здесь нет никакого игрока
 *
 * Ни имени, ни «владельца», ни uuid как такового. Клиенту не нужно знать, кто
 * это и что это за игрок: ему нужно знать «у этого объекта такой набор функций
 * с такими условиями и приоритетами», а по ним уже вычислить, что и как
 * рисовать. Всё, что сверх этого, — лишняя связность: игрок может сменить
 * имя, перейти на другого, а объект с тем же набором функций останется тем же
 * объектом.
 *
 * [id] — просто непрозрачная строка. Смысл ей придаёт **отправитель** (тот,
 * кто в какой момент реально рисуется), а не протокол. Присваивает и
 * проверяет сервер: подделать чужой id клиент не может уже потому, что не
 * присылает его вовсе.
 */
data class RosterObject(
    val id: String,
    val functions: List<ActiveCape>,
)

/** S2C: полный снимок «у каких объектов какие наборы функций». */
data class Roster(
    val revision: Long,
    val objects: List<RosterObject>,
    /** Сколько объектов не влезло в [SyncProtocol.MAX_ROSTER_BYTES]. */
    val truncated: Int = 0,
)

/**
 * C2S: кусок картинки `file`-провайдера.
 *
 * Локальный файл лежит на диске владельца, у сервера его нет — поэтому
 * заливает владелец, по [SyncProtocol.CHUNK_BYTES] за раз.
 */
class Upload(
    val hash: ImageHash,
    val totalSize: Int,
    val offset: Int,
    val bytes: ByteArray,
) {
    val isLast: Boolean get() = offset + bytes.size >= totalSize
    val chunksDone: Int get() = (offset + bytes.size + SyncProtocol.CHUNK_BYTES - 1) / SyncProtocol.CHUNK_BYTES

    override fun equals(other: Any?): Boolean =
        other is Upload && hash == other.hash && totalSize == other.totalSize &&
            offset == other.offset && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (hash.hashCode() * 31 + totalSize) * 31 + offset * 31 + bytes.contentHashCode()

    override fun toString(): String =
        "Upload(${ImageHash.shortHex(hash)} $offset..${offset + bytes.size}/$totalSize)"
}

/** C2S: «дай кусок картинки с этим хэшем». */
data class Fetch(val hash: ImageHash, val offset: Int, val length: Int)

/** S2C: кусок картинки в ответ на [Fetch]. */
class Chunk(
    val hash: ImageHash,
    val totalSize: Int,
    val offset: Int,
    val bytes: ByteArray,
) {
    val isLast: Boolean get() = offset + bytes.size >= totalSize

    override fun equals(other: Any?): Boolean =
        other is Chunk && hash == other.hash && totalSize == other.totalSize &&
            offset == other.offset && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (hash.hashCode() * 31 + totalSize) * 31 + offset * 31 + bytes.contentHashCode()

    override fun toString(): String =
        "Chunk(${ImageHash.shortHex(hash)} $offset..${offset + bytes.size}/$totalSize)"
}

// ─────────────────────────────── кодек ───────────────────────────────

/**
 * Бинарный кодек CapeCraft Sync v3. Без Minecraft API — чистые байты,
 * поэтому тестируется без запуска игры.
 *
 * ## Модель
 *
 * Клиент объявляет свой **набор функций** (провайдеров с условиями и
 * приоритетами), сервер рассылает остальным «объект с таким набором».
 * Клиенту не нужно знать, кто этот объект и что это за игрок: он получает
 * набор, сам вычисляет по условиям и приоритетам, что и как рисовать, и
 * берёт это из [RosterObject]. Всё, что сверх набора, — лишняя связность:
 * игрок может сменить имя, а объект с тем же набором остаётся собой.
 *
 * Своего id клиент **не присылает**: сервер берёт отправителя, поэтому
 * подделать чужой id невозможно по построению.
 *
 * Сервер не решает, кто что носит: он проверяет форму и лимиты (вход
 * недоверенный) и пересылает. То, что пришло по сети, каждый получатель
 * перепроверяет сам.
 *
 * ```
 * C2S announce   клиент → сервер   свой набор функций (с условием when)
 * C2S upload     клиент → сервер   кусок картинки file-функции
 * C2S fetch      клиент → сервер   «дай кусок картинки, с оффсета N»
 * S2C roster     сервер → всем     снимок {объект → набор функций} + ревизия
 * S2C chunk      сервер → клиенту  кусок картинки
 * ```
 *
 * ## Формат
 *
 * Общий заголовок (оба направления):
 * ```
 * u8  версия протокола (3)
 * u8  тип сообщения
 * ```
 * Сообщение C2S:
 * ```
 * announce:
 *   u16 число функций (0..MAX_PROVIDERS)
 *   функция × N
 * fetch:
 *   32 bytes хэш, i32 оффсет, i32 длина
 * upload:
 *   32 bytes хэш, i32 полный размер, i32 оффсет, i32 длина куска, кусок
 * ```
 * Сообщение S2C:
 * ```
 * roster:
 *   i64  ревизия
 *   u16  число объектов (0..MAX_ROSTER_PLAYERS)
 *   u8   флаг «снимок обрезан по лимиту»
 *   объект × N:
 *     str id, u16 число функций, функция × M
 * chunk:
 *   32 bytes хэш, i32 полный размер, i32 оффсет, i32 длина куска, кусок
 * ```
 * Функция:
 * ```
 * u8   вид (0=url, 1=file, 2=json, 3=addon)
 * i32  приоритет
 * str  имя
 * str  основной   (у file — пусто: путь наружу не отдаётся)
 * str  extract
 * u8   есть условие when; [условие] — u16 предикатов, предикат × K
 * u8   есть условие if;   [условие-if] — u16 предикатов, предикат-if × L
 * u8   есть хэш;   [32 bytes]
 * ```
 * Предикат `when`:
 * ```
 * str поле, u8 корень, u8 операция, u8 тип ожидаемого
 *   (0=str: str значение; 1=num: f64; 2=range: f64, f64)
 * ```
 * Предикат `if` — то же, но вместо «поле + корень» одна строка с именем
 * переменной (точка в имени значащая, `$FOO` едет как есть):
 * ```
 * str имя переменной, u8 операция, u8 тип ожидаемого
 *   (0=str: str значение; 1=num: f64; 2=range: f64, f64)
 * ```
 * У `if` нет байта корня намеренно: набор имён переменных открытый (аддоны
 * приезжают и уезжают вместе с установкой), и перечислять его на проводе
 * означало бы, что клиент без чужого аддона не сможет отличить «переменной
 * нет» от «условие нарушено».
 *
 * Целые — big-endian (`DataOutputStream`), длины строк — в байтах UTF-8.
 *
 * ## Правила разбора
 *
 * Разбор СТРОГИЙ: версия, тип, лимиты и обязательность полей проверяются
 * **до** аллокации, любое нарушение — [SyncProtocolException], а не «попробуем
 * потерпим». Ни одна длина не читается в буфер раньше, чем проверена, иначе
 * злой сервер одной строкой `u16 65535` заставит клиент выделить память.
 * Мусор в хвосте — тоже ошибка: иначе формат можно было бы достраивать
 * мусором и не заметить рассинхрона.
 */
object SyncCodec {

    // ── C2S ──

    fun encodeAnnounce(msg: Announce): ByteArray {
        requireFunctions(msg.functions.size, "в объявлении")
        val out = ByteArrayOutputStream(256)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeByte(SyncProtocol.MSG_ANNOUNCE)
            d.writeShort(msg.functions.size)
            for (p in msg.functions) d.writeProvider(p)
        }
        return out.toByteArray().also { requireControlSize(it, "объявление") }
    }

    fun decodeAnnounce(bytes: ByteArray): Announce {
        requireControlSize(bytes, "объявление")
        return guarded("объявление") {
            val d = stream(bytes)
            d.header(SyncProtocol.MSG_ANNOUNCE)
            val count = d.readUnsignedShort()
            requireFunctions(count, "в объявлении")
            val out = ArrayList<ActiveCape>(minOf(count, 16))
            repeat(count) { out += d.readProvider() }
            d.requireEnd()
            Announce(out)
        }
    }

    fun encodeFetch(msg: Fetch): ByteArray {
        if (msg.length < 0 || msg.length > SyncProtocol.CHUNK_BYTES) {
            throw SyncProtocolException("запрошено ${msg.length} байт, максимум ${SyncProtocol.CHUNK_BYTES}")
        }
        if (msg.offset < 0) {
            throw SyncProtocolException("отрицательный оффсет ${msg.offset}")
        }
        val out = ByteArrayOutputStream(48)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeByte(SyncProtocol.MSG_FETCH)
            d.write(msg.hash.toBytes())
            d.writeInt(msg.offset)
            d.writeInt(msg.length)
        }
        return out.toByteArray()
    }

    fun decodeFetch(bytes: ByteArray): Fetch {
        requireControlSize(bytes, "запрос картинки")
        return guarded("запрос картинки") {
            val d = stream(bytes)
            d.header(SyncProtocol.MSG_FETCH)
            val hash = d.readHash()
            val offset = d.readInt()
            val length = d.readInt()
            if (offset < 0) throw SyncProtocolException("отрицательный оффсет $offset")
            if (length < 0 || length > SyncProtocol.CHUNK_BYTES) {
                throw SyncProtocolException("запрошено $length байт, максимум ${SyncProtocol.CHUNK_BYTES}")
            }
            d.requireEnd()
            Fetch(hash, offset, length)
        }
    }

    fun encodeUpload(msg: Upload): ByteArray {
        val bytes = msg.bytes
        if (msg.totalSize < 0 || msg.totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
            throw SyncProtocolException("размер картинки ${msg.totalSize}, максимум ${SyncProtocol.MAX_IMAGE_BYTES}")
        }
        if (msg.offset < 0 || msg.offset + bytes.size > msg.totalSize) {
            throw SyncProtocolException("кусок ${msg.offset}..${msg.offset + bytes.size} вылезает за ${msg.totalSize}")
        }
        if (bytes.size > SyncProtocol.CHUNK_BYTES) {
            throw SyncProtocolException("кусок ${bytes.size} байт, максимум ${SyncProtocol.CHUNK_BYTES}")
        }
        val out = ByteArrayOutputStream(bytes.size + 64)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeByte(SyncProtocol.MSG_UPLOAD)
            d.write(msg.hash.toBytes())
            d.writeInt(msg.totalSize)
            d.writeInt(msg.offset)
            d.writeInt(bytes.size)
            d.write(bytes)
        }
        return out.toByteArray()
    }

    fun decodeUpload(bytes: ByteArray): Upload {
        // Верхняя граница: заголовок + полный чанк.
        requireSize(bytes, SyncProtocol.CHUNK_BYTES + 64, "загрузка картинки")
        return guarded("загрузка картинки") {
            val d = stream(bytes)
            d.header(SyncProtocol.MSG_UPLOAD)
            val hash = d.readHash()
            val totalSize = d.readInt()
            val offset = d.readInt()
            val length = d.readInt()
            if (totalSize < 0 || totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
                throw SyncProtocolException("размер картинки $totalSize, максимум ${SyncProtocol.MAX_IMAGE_BYTES}")
            }
            if (offset < 0 || offset + length > totalSize) {
                throw SyncProtocolException("кусок $offset..${offset + length} вылезает за $totalSize")
            }
            if (length > SyncProtocol.CHUNK_BYTES) {
                throw SyncProtocolException("кусок $length байт, максимум ${SyncProtocol.CHUNK_BYTES}")
            }
            val data = ByteArray(length)
            d.readFully(data)
            d.requireEnd()
            Upload(hash, totalSize, offset, data)
        }
    }

    // ── S2C ──

    fun encodeRoster(msg: Roster): ByteArray {
        if (msg.objects.size > SyncProtocol.MAX_ROSTER_PLAYERS) {
            throw SyncProtocolException(
                "в роустере ${msg.objects.size} объектов, максимум ${SyncProtocol.MAX_ROSTER_PLAYERS}",
            )
        }
        val out = ByteArrayOutputStream(1024)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeByte(SyncProtocol.MSG_ROSTER)
            d.writeLong(msg.revision)
            d.writeShort(msg.objects.size)
            d.writeByte(if (msg.truncated > 0) 1 else 0)
            for (o in msg.objects) {
                d.writeString(o.id, SyncProtocol.MAX_OBJECT_ID_BYTES, "id объекта")
                requireFunctions(o.functions.size, "у объекта ${o.id}")
                d.writeShort(o.functions.size)
                for (p in o.functions) d.writeProvider(p)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size > SyncProtocol.MAX_ROSTER_BYTES) {
            throw SyncProtocolException(
                "роустер ${bytes.size} байт, максимум ${SyncProtocol.MAX_ROSTER_BYTES}",
            )
        }
        return bytes
    }

    fun decodeRoster(bytes: ByteArray): Roster {
        requireSize(bytes, SyncProtocol.MAX_ROSTER_BYTES, "роустер")
        return guarded("роустер") {
            val d = stream(bytes)
            d.header(SyncProtocol.MSG_ROSTER)
            val revision = d.readLong()
            val count = d.readUnsignedShort()
            if (count > SyncProtocol.MAX_ROSTER_PLAYERS) {
                throw SyncProtocolException("в роустере $count объектов, максимум ${SyncProtocol.MAX_ROSTER_PLAYERS}")
            }
            val truncated = if (d.readUnsignedByte() != 0) 1 else 0
            val out = ArrayList<RosterObject>(minOf(count, 32))
            repeat(count) {
                val id = d.readString(SyncProtocol.MAX_OBJECT_ID_BYTES, "id объекта")
                val n = d.readUnsignedShort()
                requireFunctions(n, "у объекта $id")
                val functions = ArrayList<ActiveCape>(minOf(n, 16))
                repeat(n) { functions += d.readProvider() }
                out += RosterObject(id, functions)
            }
            d.requireEnd()
            Roster(revision, out, truncated)
        }
    }

    fun encodeChunk(msg: Chunk): ByteArray {
        val bytes = msg.bytes
        if (msg.totalSize < 0 || msg.totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
            throw SyncProtocolException("размер картинки ${msg.totalSize}, максимум ${SyncProtocol.MAX_IMAGE_BYTES}")
        }
        if (msg.offset < 0 || msg.offset + bytes.size > msg.totalSize) {
            throw SyncProtocolException("кусок ${msg.offset}..${msg.offset + bytes.size} вылезает за ${msg.totalSize}")
        }
        if (bytes.size > SyncProtocol.CHUNK_BYTES) {
            throw SyncProtocolException("кусок ${bytes.size} байт, максимум ${SyncProtocol.CHUNK_BYTES}")
        }
        val out = ByteArrayOutputStream(bytes.size + 64)
        DataOutputStream(out).use { d ->
            d.writeByte(SyncProtocol.VERSION)
            d.writeByte(SyncProtocol.MSG_CHUNK)
            d.write(msg.hash.toBytes())
            d.writeInt(msg.totalSize)
            d.writeInt(msg.offset)
            d.writeInt(bytes.size)
            d.write(bytes)
        }
        return out.toByteArray()
    }

    fun decodeChunk(bytes: ByteArray): Chunk {
        requireSize(bytes, SyncProtocol.CHUNK_BYTES + 64, "кусок картинки")
        return guarded("кусок картинки") {
            val d = stream(bytes)
            d.header(SyncProtocol.MSG_CHUNK)
            val hash = d.readHash()
            val totalSize = d.readInt()
            val offset = d.readInt()
            val length = d.readInt()
            if (totalSize < 0 || totalSize > SyncProtocol.MAX_IMAGE_BYTES) {
                throw SyncProtocolException("размер картинки $totalSize, максимум ${SyncProtocol.MAX_IMAGE_BYTES}")
            }
            if (offset < 0 || offset + length > totalSize) {
                throw SyncProtocolException("кусок $offset..${offset + length} вылезает за $totalSize")
            }
            if (length > SyncProtocol.CHUNK_BYTES) {
                throw SyncProtocolException("кусок $length байт, максимум ${SyncProtocol.CHUNK_BYTES}")
            }
            val data = ByteArray(length)
            d.readFully(data)
            d.requireEnd()
            Chunk(hash, totalSize, offset, data)
        }
    }

    // ── диспетчер по направлению ──

    /**
     * Разобрать любое C2S-сообщение по типу из заголовка.
     *
     * Нужен, чтобы версионная обвязка (6 почти одинаковых копий) не знала ни
     * про типы, ни про формат: она отдаёт байты и получает готовую модель.
     * Сообщение чужого направления — ошибка, а не «попробуем разобрать».
     */
    fun decodeC2S(bytes: ByteArray): Any {
        val type = peekType(bytes, "C2S")
        return when (type) {
            SyncProtocol.MSG_ANNOUNCE -> decodeAnnounce(bytes)
            SyncProtocol.MSG_FETCH -> decodeFetch(bytes)
            SyncProtocol.MSG_UPLOAD -> decodeUpload(bytes)
            else -> throw SyncProtocolException("тип $type не отправляется клиентом")
        }
    }

    /** Разобрать любое S2C-сообщение по типу из заголовка. */
    fun decodeS2C(bytes: ByteArray): Any {
        val type = peekType(bytes, "S2C")
        return when (type) {
            SyncProtocol.MSG_ROSTER -> decodeRoster(bytes)
            SyncProtocol.MSG_CHUNK -> decodeChunk(bytes)
            else -> throw SyncProtocolException("тип $type не отправляется сервером")
        }
    }

    // ── провайдер и условие ──

    private fun DataOutputStream.writeProvider(p: ActiveCape) {
        writeByte(p.kind.tag)
        writeInt(p.priority)
        writeString(p.name, SyncProtocol.MAX_NAME_BYTES, "имя провайдера")
        // У file-провайдера путь на проводе запрещён: его заменяет imageHash.
        // Здесь он не просто проверяется, а молча стирается — потому что
        // writeProvider это единственные ворота, через которые уходит ЛЮБОЕ
        // объявление, включая собранное addon'ом или будущим кодом, который
        // забудет прочистку. Стирание дешевле отказа: одно битое объявление
        // иначе уронило бы весь announce вместе с остальными.
        val onWire = if (p.kind == ActiveCape.Kind.FILE) "" else p.primary
        writeString(onWire, SyncProtocol.MAX_STRING_BYTES, "параметр провайдера")
        writeString(p.extract, SyncProtocol.MAX_STRING_BYTES, "extract провайдера")
        val cond = p.condition
        writeByte(if (cond == null) 0 else 1)
        if (cond != null) writeCondition(cond)
        // v3: условие `if`. Пишется строго между `when` и хэшем — порядок
        // полей в проводе и есть формат, а «optional в конце» здесь означало
        // бы, что старый клиент примет v3-пакет и молча сдвинет разбор.
        val condIf = p.ifCondition
        writeByte(if (condIf == null) 0 else 1)
        if (condIf != null) writeIfCondition(condIf)
        writeByte(if (p.imageHash == null) 0 else 1)
        if (p.imageHash != null) write(p.imageHash.toBytes())
    }

    private fun DataInputStream.readProvider(): ActiveCape {
        val kind = ActiveCape.Kind.byTag(readUnsignedByte())
            ?: throw SyncProtocolException("неизвестный вид провайдера")
        val priority = readInt()
        val name = readString(SyncProtocol.MAX_NAME_BYTES, "имя провайдера")
        val primary = readString(SyncProtocol.MAX_STRING_BYTES, "параметр провайдера")
        val extract = readString(SyncProtocol.MAX_STRING_BYTES, "extract провайдера")
        val condition = if (readUnsignedByte() != 0) readCondition() else null
        val ifCondition = if (readUnsignedByte() != 0) readIfCondition() else null
        val hash = if (readUnsignedByte() != 0) readHash() else null
        // Именованными: у ActiveCape два соседних nullable-поля, и позиционная
        // передача молча отдала бы ifCondition-у хэш картинки.
        return ActiveCape(
            name = name,
            kind = kind,
            primary = primary,
            extract = extract,
            priority = priority,
            condition = condition,
            ifCondition = ifCondition,
            imageHash = hash,
        )
    }

    private fun DataOutputStream.writeCondition(c: WireCondition) {
        // На разборе лимит есть (readCondition), на записи обязан быть тоже:
        // иначе свой же клиент может собрать announce, который он сам же не
        // сумеет прочитать.
        if (c.predicates.size > SyncProtocol.MAX_PREDICATES) {
            throw SyncProtocolException(
                "${c.predicates.size} предикатов в условии, максимум ${SyncProtocol.MAX_PREDICATES}",
            )
        }
        writeShort(c.predicates.size)
        for (p in c.predicates) {
            writeString(p.field, SyncProtocol.MAX_FIELD_BYTES, "поле условия")
            writeByte(p.root.tag)
            writeByte(p.op.tag)
            when (val e = p.expected) {
                is WireExpected.Str -> {
                    writeByte(TAG_STR)
                    writeString(e.s, SyncProtocol.MAX_EXPECTED_STR_BYTES, "значение условия")
                }

                is WireExpected.Num -> {
                    writeByte(TAG_NUM)
                    writeDouble(e.d)
                }

                is WireExpected.Range -> {
                    writeByte(TAG_RANGE)
                    writeDouble(e.from)
                    writeDouble(e.to)
                }
            }
        }
    }

    private fun DataInputStream.readCondition(): WireCondition {
        val n = readUnsignedShort()
        if (n > SyncProtocol.MAX_PREDICATES) {
            throw SyncProtocolException("$n предикатов, максимум ${SyncProtocol.MAX_PREDICATES}")
        }
        val out = ArrayList<WirePredicate>(minOf(n, 8))
        repeat(n) {
            val field = readString(SyncProtocol.MAX_FIELD_BYTES, "поле условия")
            val root = WireRoot.byTag(readUnsignedByte())
                ?: throw SyncProtocolException("неизвестный корень условия")
            val op = WireOp.byTag(readUnsignedByte())
                ?: throw SyncProtocolException("неизвестная операция условия")
            val expected = when (val tag = readUnsignedByte()) {
                TAG_STR -> WireExpected.Str(readString(SyncProtocol.MAX_EXPECTED_STR_BYTES, "значение условия"))
                TAG_NUM -> WireExpected.Num(readDouble())
                TAG_RANGE -> {
                    val from = readDouble()
                    val to = readDouble()
                    if (from > to) throw SyncProtocolException("диапазон $from..$to перевёрнут")
                    WireExpected.Range(from, to)
                }

                else -> throw SyncProtocolException("неизвестный тип ожидаемого значения $tag")
            }
            out += WirePredicate(root, field, op, expected)
        }
        return WireCondition(out)
    }

    private fun DataOutputStream.writeIfCondition(c: WireIfCondition) {
        // Лимит на записи обязателен, как и в writeCondition: свой же клиент не
        // должен уметь собрать announce, который сам же не сумеет прочитать.
        if (c.predicates.size > SyncProtocol.MAX_PREDICATES) {
            throw SyncProtocolException(
                "${c.predicates.size} предикатов в if, максимум ${SyncProtocol.MAX_PREDICATES}",
            )
        }
        writeShort(c.predicates.size)
        for (p in c.predicates) {
            // Имя переменной целиком, точкой в том числе: `$FOO` и
            // аддонное `myAddon.level` — одна строка, а не путь по корням.
            writeString(p.name, SyncProtocol.MAX_FIELD_BYTES, "имя переменной в if")
            writeByte(p.op.tag)
            when (val e = p.expected) {
                is WireExpected.Str -> {
                    writeByte(TAG_STR)
                    writeString(e.s, SyncProtocol.MAX_EXPECTED_STR_BYTES, "значение условия if")
                }

                is WireExpected.Num -> {
                    writeByte(TAG_NUM)
                    writeDouble(e.d)
                }

                is WireExpected.Range -> {
                    writeByte(TAG_RANGE)
                    writeDouble(e.from)
                    writeDouble(e.to)
                }
            }
        }
    }

    private fun DataInputStream.readIfCondition(): WireIfCondition {
        val n = readUnsignedShort()
        if (n > SyncProtocol.MAX_PREDICATES) {
            throw SyncProtocolException("$n предикатов в if, максимум ${SyncProtocol.MAX_PREDICATES}")
        }
        val out = ArrayList<WireVarPredicate>(minOf(n, 8))
        repeat(n) {
            val name = readString(SyncProtocol.MAX_FIELD_BYTES, "имя переменной в if")
            val op = WireOp.byTag(readUnsignedByte())
                ?: throw SyncProtocolException("неизвестная операция в if")
            val expected = when (val tag = readUnsignedByte()) {
                TAG_STR -> WireExpected.Str(readString(SyncProtocol.MAX_EXPECTED_STR_BYTES, "значение условия if"))
                TAG_NUM -> WireExpected.Num(readDouble())
                TAG_RANGE -> {
                    val from = readDouble()
                    val to = readDouble()
                    if (from > to) throw SyncProtocolException("диапазон $from..$to в if перевёрнут")
                    WireExpected.Range(from, to)
                }

                else -> throw SyncProtocolException("неизвестный тип ожидаемого значения в if: $tag")
            }
            out += WireVarPredicate(name, op, expected)
        }
        return WireIfCondition(out)
    }

    // ── мелочи разбора ──

    private const val TAG_STR = 0
    private const val TAG_NUM = 1
    private const val TAG_RANGE = 2

    private fun stream(bytes: ByteArray): DataInputStream {
        if (bytes.size < 2) {
            throw SyncProtocolException("payload ${bytes.size} байт: нужен хотя бы заголовок")
        }
        return DataInputStream(ByteArrayInputStream(bytes))
    }

    /** Заголовок: версия + тип. Тип должен быть тем, что ждёт вызывающий. */
    private fun DataInputStream.header(expectedType: Int) {
        val version = readUnsignedByte()
        if (version != SyncProtocol.VERSION) {
            throw SyncProtocolException(
                "версия протокола $version не поддерживается (ожидалась ${SyncProtocol.VERSION})",
            )
        }
        val type = readUnsignedByte()
        if (type != expectedType) {
            throw SyncProtocolException("ожидался тип $expectedType, а пришёл $type")
        }
    }

    private fun peekType(bytes: ByteArray, dir: String): Int {
        if (bytes.size < 2) {
            throw SyncProtocolException("payload ${bytes.size} байт: нужен хотя бы заголовок")
        }
        val version = bytes[0].toInt() and 0xFF
        if (version != SyncProtocol.VERSION) {
            throw SyncProtocolException(
                "версия протокола $version не поддерживается (ожидалась ${SyncProtocol.VERSION})",
            )
        }
        val type = bytes[1].toInt() and 0xFF
        if (type !in KNOWN_TYPES) {
            throw SyncProtocolException("неизвестный тип $type в $dir")
        }
        return type
    }

    private val KNOWN_TYPES = intArrayOf(
        SyncProtocol.MSG_ANNOUNCE,
        SyncProtocol.MSG_UPLOAD,
        SyncProtocol.MSG_FETCH,
        SyncProtocol.MSG_ROSTER,
        SyncProtocol.MSG_CHUNK,
    )

    private fun requireFunctions(count: Int, where: String) {
        if (count > SyncProtocol.MAX_PROVIDERS) {
            throw SyncProtocolException("$where $count провайдеров, максимум ${SyncProtocol.MAX_PROVIDERS}")
        }
    }

    private fun DataInputStream.requireEnd() {
        val left = available()
        if (left != 0) throw SyncProtocolException("в сообщении $left лишних байт")
    }

    private fun requireControlSize(bytes: ByteArray, what: String) {
        if (bytes.size > SyncProtocol.MAX_PAYLOAD_BYTES) {
            throw SyncProtocolException("$what ${bytes.size} байт, максимум ${SyncProtocol.MAX_PAYLOAD_BYTES}")
        }
    }

    private fun requireSize(bytes: ByteArray, max: Int, what: String) {
        if (bytes.size > max) {
            throw SyncProtocolException("$what ${bytes.size} байт, максимум $max")
        }
    }

    private fun DataInputStream.readHash(): ImageHash {
        val buf = ByteArray(SyncProtocol.IMAGE_HASH_BYTES)
        readFully(buf)
        return ImageHash.fromWire(buf)
    }

    /** Любая ошибка разбора — [SyncProtocolException], без сырых исключений. */
    private inline fun <T> guarded(what: String, block: () -> T): T = try {
        block()
    } catch (e: SyncProtocolException) {
        throw e
    } catch (e: Exception) {
        throw SyncProtocolException("не удалось разобрать $what: ${e.message.orEmpty()}")
    }

    /** Записать строку с префиксом-длиной; длина — в байтах UTF-8. */
    private fun DataOutputStream.writeString(value: String, max: Int, what: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        if (data.size > max) {
            throw SyncProtocolException("$what ${data.size} байт, максимум $max")
        }
        writeShort(data.size)
        write(data)
    }

    /** Прочитать строку, проверяя лимит ДО аллокации. */
    private fun DataInputStream.readString(max: Int, what: String): String {
        val len = readUnsignedShort()
        if (len > max) {
            throw SyncProtocolException("$what $len байт, максимум $max")
        }
        if (len == 0) return ""
        if (len > available()) {
            throw SyncProtocolException("$what объявлен на $len байт, а в пакете осталось ${available()}")
        }
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}
