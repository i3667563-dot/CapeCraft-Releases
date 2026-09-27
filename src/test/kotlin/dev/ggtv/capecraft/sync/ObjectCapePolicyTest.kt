package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.Condition
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import dev.ggtv.kjen.Value
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Правило выбора набора для чужого объекта.
 *
 * Проверяется ровно то, что ломается тихо: объект без синхронизации обязан
 * получить мой локальный конфиг (свой провайдер по `{username}`), а объект,
 * который объявился, — свой набор, и никакой грейс-период не должен этому
 * мешать. Таймер тут — единственное место, где легко словить «вечный плаш
 * без синхронизации» или «свой конфиг поверх чужого».
 */
class ObjectCapePolicyTest {

    private val grace = ObjectCapePolicy.ANNOUNCE_GRACE_MS

    private fun urlProvider(name: String, priority: Int = 0, condition: Condition? = null) = Provider(
        name = name,
        source = Source.Url("https://example.invalid/$name.png"),
        condition = condition,
        priority = priority,
    )

    private fun fileProvider(name: String) = Provider(
        name = name,
        source = Source.File("{root}/capes/{uuid}.png"),
    )

    /** Мир с заданной температурой биома — подставляется вместо игрового. */
    private class FixedWorld(private val temperature: String) : WorldContext {
        override fun field(root: WorldRoot, field: String, path: String): Value =
            if (root == WorldRoot.BIOME && field == "temperature") {
                Value.VFloat(temperature.toDouble())
            } else {
                Value.VStr("unknown")
            }
    }

    private val jungle = FixedWorld("0.95")

    private fun warmerThan(value: Double) = Condition.parse(
        Value.VDict(
            listOf(
                "biome.temperature" to Value.VStr("> $value"),
            ),
        ),
    )

    // ── объект без синхронизации получает мой набор ─────────────────────────

    @Test
    fun `объект без объявления после срока получает локальный конфиг`() {
        val r = ObjectCapePolicy.resolve(
            isSelf = false,
            declaredProviders = null,
            localProviders = listOf(urlProvider("мой-url")),
            firstSeenMs = 1_000L,
            nowMs = 1_000L + grace,
            context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, r.decision)
        assertEquals(listOf("мой-url"), r.providers.map { it.name })
    }

    @Test
    fun `до истечения срока набор не выбирается вовсе`() {
        val r = ObjectCapePolicy.resolve(
            isSelf = false,
            declaredProviders = null,
            localProviders = listOf(urlProvider("мой-url")),
            firstSeenMs = 1_000L,
            nowMs = 1_000L + grace - 1,
            context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.WAIT, r.decision)
        assertTrue(r.providers.isEmpty(), "до объявления набор пуст, а не мой: ${r.providers.map { it.name }}")
    }

    @Test
    fun `локальный file-провайдер тоже навязывается`() {
        // Приватность тут ни при чём: file идёт по своему диску от моего
        // кабинета, а не наружу. Наружу уезжает только то, что сам владелец
        // объявил, — см. shareLocalProviders.
        val r = ObjectCapePolicy.resolve(
            isSelf = false,
            declaredProviders = null,
            localProviders = listOf(fileProvider("мой-файл")),
            firstSeenMs = 0L,
            nowMs = grace * 10,
            context = EmptyWorldContext,
        )

        assertEquals(listOf("мой-файл"), r.providers.map { it.name })
    }

    @Test
    fun `принудительный набор считает условия против мира того, кого видно`() {
        // Тот же отобранный список, что и для своего, но условия берутся из
        // контекста объекта: это делает ProviderSelector, а не этот класс.
        val local = listOf(
            urlProvider("всегда"),
            urlProvider("в-джунглях", condition = warmerThan(0.5)),
        )
        val inJungle = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = local,
            firstSeenMs = 0L, nowMs = grace * 10, context = jungle,
        )
        val inTundra = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = local,
            firstSeenMs = 0L, nowMs = grace * 10, context = FixedWorld("0.1"),
        )

        assertEquals(listOf("в-джунглях", "всегда"), inJungle.providers.map { it.name })
        assertEquals(listOf("всегда"), inTundra.providers.map { it.name })
    }

    // ── объявление важнее таймера ────────────────────────────────────────────

    @Test
    fun `объявленный объект получает свой набор, а не мой`() {
        val r = ObjectCapePolicy.resolve(
            isSelf = false,
            declaredProviders = listOf(urlProvider("его-набор")),
            localProviders = listOf(urlProvider("мой-набор")),
            firstSeenMs = 0L,
            nowMs = grace * 100,
            context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.DECLARED, r.decision)
        assertEquals(listOf("его-набор"), r.providers.map { it.name })
    }

    @Test
    fun `позднее объявление перебивает уже истёкший срок`() {
        // Порядок событий: увидели, срок вышел, навязали свой — и пришло
        // объявление. Чужой набор обязан заменить мой, а не наоборот.
        val first = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 0L, nowMs = grace + 1, context = EmptyWorldContext,
        )
        val after = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = listOf(urlProvider("его")),
            localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 0L, nowMs = grace + 1, context = EmptyWorldContext,
        )

        assertEquals(listOf("мой"), first.providers.map { it.name })
        assertEquals(ObjectCapePolicy.Decision.DECLARED, after.decision)
        assertEquals(listOf("его"), after.providers.map { it.name })
    }

    @Test
    fun `ушедший из цепочки объект снова получает локальный конфиг`() {
        // Ростер без объекта — `forgetObject`, набор снят. Синхронизации у
        // него больше нет, и ждать заново бессмысленно: срок отсчитывается заново
        // от нового первого появления, а решение принимает вызывающий код.
        val afterForget = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 50_000L, nowMs = 50_000L, context = EmptyWorldContext,
        )
        assertEquals(ObjectCapePolicy.Decision.WAIT, afterForget.decision)

        val afterGrace = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 50_000L, nowMs = 50_000L + grace, context = EmptyWorldContext,
        )
        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, afterGrace.decision)
        assertEquals(listOf("мой"), afterGrace.providers.map { it.name })
    }

    // ── себя насиловать нельзя, и локальный file не теряется ───────────────

    @Test
    fun `свой набор берётся сразу, без ожидания`() {
        val r = ObjectCapePolicy.resolve(
            isSelf = true, declaredProviders = null, localProviders = listOf(fileProvider("мой-файл")),
            firstSeenMs = 0L, nowMs = 0L, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.SELF, r.decision)
        assertEquals(listOf("мой-файл"), r.providers.map { it.name })
    }

    @Test
    fun `свой набор не берётся из роустера`() {
        // Сервер рассылает снимок всем, включая объявившего, а `file`-плащ по
        // умолчанию наружу не уезжает — в своём же снимке его нет. Если бы
        // «свой» читал роустер, локальный плащ пропал бы на входе на сервер.
        val r = ObjectCapePolicy.resolve(
            isSelf = true,
            declaredProviders = listOf(urlProvider("его-url")),
            localProviders = listOf(fileProvider("мой-файл")),
            firstSeenMs = 0L, nowMs = 0L, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.SELF, r.decision)
        assertEquals(listOf("мой-файл"), r.providers.map { it.name })
    }

    // ── границы таймера ─────────────────────────────────────────────────────

    @Test
    fun `срок ноль навязывает набор сразу`() {
        val r = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = null, localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 7L, nowMs = 7L, context = EmptyWorldContext, graceMs = 0L,
        )
        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, r.decision)
    }

    @Test
    fun `ровно на границе срока набор уже навязан`() {
        val onEdge = ObjectCapePolicy.decide(false, false, firstSeenMs = 100L, nowMs = 100L + grace)
        val justBefore = ObjectCapePolicy.decide(false, false, firstSeenMs = 100L, nowMs = 100L + grace - 1)

        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, onEdge)
        assertEquals(ObjectCapePolicy.Decision.WAIT, justBefore)
    }

    @Test
    fun `часы назад не считаются молчанием`() {
        // firstSeen в будущем — это не «объявления нет пять секунд», а мусор
        // часов. Навязывать набор из-за этого нельзя.
        assertEquals(
            ObjectCapePolicy.Decision.WAIT,
            ObjectCapePolicy.decide(false, false, firstSeenMs = 10_000L, nowMs = 0L),
        )
    }

    @Test
    fun `срок ожидания положителен и не миллисекунды`() {
        // Слишком короткий срок означал бы «навязать свой набор до того, как
        // придёт роустер», то есть ровно то гонка, ради которой он и ждёт.
        assertEquals(5_000L, ObjectCapePolicy.ANNOUNCE_GRACE_MS)
    }

    @Test
    fun `пустое объявление навязывает мой набор`() {
        // Клиент без провайдеров объявляет пустоту, и раньше это считалось
        // объявлением: игрок без конфига навсегда оставался без плаща, хотя его
        // плащ находится тем же запросом по {username}. Найдено на живых двух
        // клиентах — GGSHNIKK с web-провайдером и Eonixx без провайдеров.
        val r = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = emptyList(), localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 0L, nowMs = grace * 10, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, r.decision)
        assertEquals(listOf("мой"), r.providers.map { it.name })
    }

    @Test
    fun `пустое объявление не ждёт пяти секунд`() {
        // Объявление уже пришло и сказало «ничего»: ждать тут нечего, иначе
        // после каждого входа игрок без конфига был бы без плаща лишние 5 секунд.
        val now = 1_000L
        val r = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = emptyList(), localProviders = listOf(urlProvider("мой")),
            firstSeenMs = now, nowMs = now, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.FORCED_LOCAL, r.decision)
        assertEquals(listOf("мой"), r.providers.map { it.name })
    }

    @Test
    fun `пустое объявление не трогает свой набор`() {
        // Свой объект идёт первым в decide: пустое объявление себя не касается.
        val r = ObjectCapePolicy.resolve(
            isSelf = true, declaredProviders = emptyList(), localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 0L, nowMs = 0L, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.SELF, r.decision)
    }

    @Test
    fun `непустое объявление по-прежнему главнее моего набора`() {
        // Обратная сторона правки: чужой объявленный набор навязывать нельзя,
        // иначе игрок с настроенным плащем получал бы мой.
        val r = ObjectCapePolicy.resolve(
            isSelf = false, declaredProviders = listOf(urlProvider("его")),
            localProviders = listOf(urlProvider("мой")),
            firstSeenMs = 0L, nowMs = 0L, context = EmptyWorldContext,
        )

        assertEquals(ObjectCapePolicy.Decision.DECLARED, r.decision)
        assertEquals(listOf("его"), r.providers.map { it.name })
    }
}
