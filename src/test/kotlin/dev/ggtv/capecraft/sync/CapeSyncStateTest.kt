package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.sync.SyncProtocol.CHUNK_BYTES
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Машина состояний Sync v2.
 *
 * Отличие от v1, которое тут и проверяется: **опроса нет**. Клиент не ходит к
 * серверу по расписанию и не ждёт `requestId`. Он объявляет набор сам при входе
 * и при смене конфига, а обновления приходят рассылкой. Поэтому здесь нет ни
 * `requestId`, ни `currentIntervalTicks`, и их отсутствие — часть контракта, а
 * не потеря покрытия.
 */
class CapeSyncStateTest {

    private fun state(backoff: Boolean = true) = CapeSyncState(backoff = backoff)

    private fun urlFunction(name: String, priority: Int = 0) = ActiveCape(
        kind = ActiveCape.Kind.URL,
        name = name,
        primary = "https://example.invalid/$name.png",
        extract = "",
        priority = priority,
        condition = null,
        imageHash = null,
    )

    /**
     * Функция с картинкой, которую надо привезти с сервера.
     *
     * Только [ActiveCape.Kind.FILE]: хэш у `url`-функции запрещён, и такой
     * набор `SyncRosterPolicy.accept` выкинет целиком — вместе с ним из
     * роустера исчезнет и всё остальное, что в нём было.
     */
    private fun fileFunction(name: String, hash: ImageHash) = ActiveCape(
        kind = ActiveCape.Kind.FILE,
        name = name,
        primary = "",
        extract = "",
        priority = 0,
        condition = null,
        imageHash = hash,
    )

    // ── объявление ──────────────────────────────────────────────────────────

    @Test
    fun `смена конфига порождает ровно одно объявление`() {
        val s = state()
        val out = s.onConfigChanged(listOf(urlFunction("a"), urlFunction("b")))
        val announce = out.filterIsInstance<SyncOutbound.Announce>()

        assertEquals(1, announce.size, "объявление должно быть одно, а не по одному на провайдер")
        assertEquals(2, announce.single().functions.size, "в объявление должны войти все функции набора")
        assertEquals(1, s.announcesSent)
    }

    @Test
    fun `выключенная синхронизация не объявляет ничего`() {
        val s = CapeSyncState(enabled = false)
        assertTrue(s.onConfigChanged(listOf(urlFunction("a"))).isEmpty())
        assertEquals(0, s.announcesSent)
    }

    // ── роустер ─────────────────────────────────────────────────────────────

    @Test
    fun `первый снимок применяется`() {
        val s = state()
        val applied = s.onRoster(
            Roster(1, listOf(RosterObject("obj-1", listOf(urlFunction("x"))))),
        )
        assertTrue(applied)
        assertEquals(1, s.revision)
        assertEquals(1, s.rostersApplied)
    }

    @Test
    fun `устаревшая ревизия отбрасывается молча`() {
        val s = state()
        s.onRoster(Roster(5, listOf(RosterObject("a", emptyList()))))

        // Ростер с меньшей ревизией приходит, когда пакеты переплелись — из-за
        // TCP такое возможно даже при отправке по порядку (пересоединение).
        assertFalse(s.onRoster(Roster(3, listOf(RosterObject("a", listOf(urlFunction("stale")))))))
        assertEquals(1, s.rostersStale)
        assertEquals(5, s.revision, "ревизия не должна откатываться")
    }

    @Test
    fun `та же ревизия повторно не применяется`() {
        val s = state()
        s.onRoster(Roster(2, emptyList()))
        assertFalse(s.onRoster(Roster(2, listOf(RosterObject("a", emptyList())))))
    }

    @Test
    fun `снимок без объектов применять можно, и он не считается пустым`() {
        val s = state()
        assertTrue(s.onRoster(Roster(7, emptyList())))
        assertEquals(7, s.revision)
    }

    // ── картинки ────────────────────────────────────────────────────────────

    @Test
    fun `недостающая картинка запрашивается чанком`() {
        val hash = ImageHash.compute(byteArrayOf(7, 7, 7))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))

        val fetch = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>()
        assertEquals(1, fetch.size, "нужна картинка — должен быть ровно один fetch")
        assertEquals(0, fetch.single().offset, "первый запрос всегда с нуля")
        assertEquals(1, s.fetchesSent)
    }

    @Test
    fun `готовая картинка повторно не запрашивается`() {
        // Хэш обязан считаться от тех же байтов, что придут в чанке: иначе
        // ассемблер отбросит сборку по «хеш не совпал» и картинка не станет
        // готовой — но по другой, неверной причине.
        val bytes = ByteArray(4) { 7 }
        val hash = ImageHash.compute(bytes)
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))

        s.onChunk(Chunk(hash = hash, totalSize = 4, offset = 0, bytes = bytes))
        assertTrue(s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().isEmpty())
        assertTrue(s.missingHashes().isEmpty())
    }

    @Test
    fun `канал не готов — запросы не летят, но и счётчик не растёт`() {
        val hash = ImageHash.compute(byteArrayOf(7))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))

        val out = s.onTick(channelReady = false)
        assertTrue(out.isEmpty(), "при закрытом канале отправлять нечего")
        assertEquals(0, s.fetchesSent, "неотправленный запрос не должен считаться отправленным")
    }

    // ── загрузка своих картинок ─────────────────────────────────────────────

    @Test
    fun `заливаются только те мои картинки, на которые ссылаются чужие`() {
        val wanted = ImageHash.compute(byteArrayOf(1, 1))
        val mine = ImageHash.compute(byteArrayOf(2, 2))
        val unused = ImageHash.compute(byteArrayOf(3, 3))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("other", listOf(fileFunction("f", wanted))))))

        val pending = s.pendingUploads(s.referencedHashes(exceptId = "me"), owned = setOf(mine, unused))
        assertTrue(pending.isEmpty(), "чужие ссылки на МОИ картинки не должны появляться из моего набора")

        // А теперь чужой набор ссылается ровно на одну из моих.
        s.onRoster(
            Roster(
                2,
                listOf(
                    RosterObject("other", listOf(fileFunction("f", mine))),
                    RosterObject("me", listOf(fileFunction("g", unused))),
                ),
            ),
        )
        val pending2 = s.pendingUploads(s.referencedHashes(exceptId = "me"), setOf(mine, unused))
        assertEquals(listOf(mine), pending2, "заливаться должна только реально затребованная картинка")
    }

    @Test
    fun `многочанковая загрузка продолжается тик за тиком`() {
        val mine = ImageHash.compute(byteArrayOf(2, 2))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("other", listOf(fileFunction("f", mine))))))
        val referenced = s.referencedHashes()

        // Владелец отдаёт куски по одному и отмечает отправленный объём.
        // Если следующий кусок перестал предлагаться, многочанковая загрузка
        // (а локальный файл вправе весить больше чанка) намертво залипла бы.
        // Смещение — это то, из чего клиент режет следующий кусок. Если оно не
        // двигается, «предлагается» один и тот же первый кусок вечно.
        s.markUploadProgress(mine, sentTotal = 0)
        assertEquals(1, s.pendingUploads(referenced, setOf(mine)).size, "первый кусок должен предлагаться")
        assertEquals(0, s.uploadedBytesOf(mine), "до отправки смещение нулевое")

        val afterFirst = s.markUploadProgress(mine, sentTotal = CHUNK_BYTES)
        assertEquals(CHUNK_BYTES, afterFirst, "после первого куска смещение должно уехать на размер чанка")
        assertEquals(1, s.pendingUploads(referenced, setOf(mine)).size, "второй кусок должен предлагаться")
        assertEquals(CHUNK_BYTES, s.uploadedBytesOf(mine), "смещение обязано совпадать с отправленным объёмом")

        s.markUploadProgress(mine, sentTotal = CHUNK_BYTES * 3)
        s.markUploadComplete(mine)
        assertTrue(s.pendingUploads(referenced, setOf(mine)).isEmpty(), "после полной отправки кусков нет")
    }

    @Test
    fun `загруженная картинка больше не предлагается`() {
        val mine = ImageHash.compute(byteArrayOf(2, 2))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("other", listOf(fileFunction("f", mine))))))
        val referenced = s.referencedHashes()

        assertEquals(listOf(mine), s.pendingUploads(referenced, setOf(mine)))
        s.markUploadProgress(mine, sentTotal = 128)
        s.markUploadComplete(mine)
        assertTrue(s.pendingUploads(referenced, setOf(mine)).isEmpty())
    }

    @Test
    fun `прогресс загрузки не откатывается назад`() {
        val h = ImageHash.compute(byteArrayOf(5))
        val s = state()
        assertEquals(0, s.uploadedBytesOf(h))
        assertEquals(64, s.markUploadProgress(h, 64))
        // Повторный кусок с меньшим смещением — не должно уменьшать отданное.
        assertEquals(64, s.markUploadProgress(h, 32))
    }

    // ── зависшая докачка ────────────────────────────────────────────────────

    @Test
    fun `пока куски идут, повторно не просим`() {
        val hash = ImageHash.compute(ByteArray(8))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))
        // Первый кусок из двух пришёл — сборка идёт.
        s.onChunk(Chunk(hash = hash, totalSize = 16, offset = 0, bytes = ByteArray(8)))
        repeat(SyncProtocol.MIN_INTERVAL_TICKS) { s.onTickAdvance() }

        // Ответ на прошлый запрос получен, значит запрос на следующий кусок
        // не только допустим, но и необходим: иначе картинка из нескольких
        // чанков никогда не доходит до конца.
        val next = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().firstOrNull()
        assertNotNull(next, "после пришедшего куска докачка обязана продолжиться")
        assertEquals(8, next.offset, "следующий кусок берётся с места обрыва")

        // А вот повторный запрос, пока ответ не пришёл, отправлять нельзя:
        // сервер завалит одинаковыми запросами, а толку от них ноль.
        repeat(SyncProtocol.MIN_INTERVAL_TICKS) { s.onTickAdvance() }
        assertTrue(
            s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().isEmpty(),
            "нельзя перебивать запрошенный кусок новым запросом — сервер завалит одинаковыми запросами",
        )
    }

    @Test
    fun `зависшая на середине картинка сбрасывается и запрашивается заново с нуля`() {
        val hash = ImageHash.compute(ByteArray(8))
        val s = state()
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))
        // Первый кусок из двух пришёл, второй — нет: сервер замолчал посреди картинки.
        s.onChunk(Chunk(hash = hash, totalSize = 16, offset = 0, bytes = ByteArray(8)))

        // Молчание терпимо, но не бесконечно: картинка, которую так и не
        // доставили, обязана снова стать запрашиваемой, иначе игрок останется
        // без плаща до самого выхода с сервера. Ждём заведомо больше
        // stall-таймаута — он обязан перекрывать худший интервал backoff, иначе
        // нормальная медленная передача будет выглядеть зависшей.
        repeat(SyncProtocol.stallTicks(SyncProtocol.MAX_BACKOFF_INTERVAL_TICKS) + 50) { s.onTickAdvance() }
        val fetch = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().firstOrNull()
        assertNotNull(fetch, "зависшая картинка должна быть переспрошена, а не ждать вечно")
        assertEquals(0, fetch.offset, "докачка начинается с нуля, а не с места обрыва")
    }

    @Test
    fun `молчащий сервер всё равно приводит к повтору запроса`() {
        val hash = ImageHash.compute(ByteArray(8))
        // Потолок намеренно крошечный: иначе проверка шла бы тысячи тиков.
        val s = CapeSyncState(maxRetryTicks = 40)
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))

        val first = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().firstOrNull()
        assertNotNull(first, "нужен первый запрос")
        // Ответа нет вообще: ни отказа, ни куска.
        repeat(60) { s.onTickAdvance() }
        val again = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().firstOrNull()
        assertNotNull(again, "молчание сервера обязано приводить к повтору, иначе игрок останется без плаща")
        assertEquals(first.offset, again.offset, "повтор спрашивает тот же кусок, а не начинает заново")
    }

    @Test
    fun `интервал между попытками не превышает потолок`() {
        val hash = ImageHash.compute(ByteArray(8))
        val cap = 100
        val s = CapeSyncState(maxRetryTicks = cap)
        s.onRoster(Roster(1, listOf(RosterObject("a", listOf(fileFunction("f", hash))))))

        val at = mutableListOf<Int>()
        for (tick in 0 until 2000) {
            s.onTickAdvance()
            if (s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>().isNotEmpty()) at += tick
        }
        assertTrue(at.size >= 3, "нужно несколько попыток, получили ${at.size}")
        for (i in 1 until at.size) {
            val gap = at[i] - at[i - 1]
            assertTrue(
                gap <= cap,
                "между попытками прошло $gap тиков при потолке $cap: разброс не упирается в потолок",
            )
        }
    }

    // ── разрыв ──────────────────────────────────────────────────────────────

      @Test
      fun `разрыв обнуляет ревизию и состояние загрузок`() {
          val s = state()
          s.onRoster(Roster(9, listOf(RosterObject("a", listOf(fileFunction("f", ImageHash.compute(byteArrayOf(1))))))))
          s.markUploadProgress(ImageHash.compute(byteArrayOf(1)), 64)

          s.onDisconnect()
          assertEquals(0, s.revision, "после разрыва старый роустер не должен считаться текущим")
          assertEquals(0, s.uploadedBytesOf(ImageHash.compute(byteArrayOf(1))))
      }

      // ── перенастройка без потери снимка ─────────────────────────────────────

      @Test
      fun `reload не обнуляет ревизию снимка`() {
          val s = state()
          s.onRoster(Roster(4, listOf(RosterObject("a", listOf(urlFunction("x"))))))

          s.reconfigure(enabled = true, backoff = true)

          assertEquals(4, s.revision, "после reload прежний снимок всё ещё текущий")
          assertEquals(1, s.roster.objects.size, "после reload роустер должен остаться на месте")
      }

      @Test
      fun `reload не обрывает докачку своей картинки`() {
          val s = state()
          val hash = ImageHash.compute(ByteArray(300_000) { (it % 251).toByte() })
          // Свой `file`-плащ: байты лежат на сервере, клиент их обязан вытянуть
          // обратно по своему же хэшу из роустера.
          s.onRoster(Roster(1, listOf(RosterObject("me", listOf(fileFunction("local", hash))))))

          s.reconfigure(enabled = true, backoff = true)
          val fetch = s.onTick(channelReady = true).filterIsInstance<SyncOutbound.FetchImage>()

          assertEquals(
              1,
              fetch.size,
              "после reload клиент обязан продолжать запрашивать свою картинку, " +
                  "иначе плащ пропадает до чужого reload",
          )
          assertEquals(hash, fetch.single().hash)
      }

      @Test
      fun `reload применяет новые настройки и сбрасывает таймер докачки`() {
          val s = state(backoff = true)

          s.reconfigure(enabled = false, backoff = false)

          assertFalse(s.enabled, "новый enabled должен применяться на месте")
          assertFalse(s.backoff, "новый backoff должен применяться на месте")
          assertTrue(
              s.onTick(channelReady = true).isEmpty(),
              "при выключенной синхронизации тик не должен ничего слать",
          )
      }

      @Test
      fun `разрыв после reload всё равно обнуляет ревизию`() {
          val s = state()
          s.onRoster(Roster(6, listOf(RosterObject("a", listOf(urlFunction("x"))))))
          s.reconfigure(enabled = true, backoff = true)

          s.onDisconnect()

          assertEquals(0, s.revision, "смена сервера обязана забыть снимок, в отличие от reload")
      }
  }

