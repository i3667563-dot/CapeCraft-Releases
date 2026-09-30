package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.koren.WorldContext

/**
 * Логика выбора провайдера под текущее состояние мира и переменные игрока.
 *
 * Порядок кандидатов = порядок fallback в [dev.ggtv.capecraft.provider.resolveCape]:
 *
 * 1. Сначала провайдеры, у которых есть хоть одно условие (`when` и/или `if`),
 *    и **оба** условия выполняются (AND): [Condition.matches] по [WorldContext],
 *    [VarCondition.matches] по [Placeholders.Context].
 * 2. Потом провайдеры без условий (default/fallback).
 *
 * Внутри каждой группы — по убыванию [Provider.priority], при равном
 * приоритете стабильно в порядке списка. Приоритет работает на **все**
 * провайдеры, а не только на условные: иначе `priority` у провайдера без
 * условий был бы заведомо бесполезным ключом в конфиге — редактор подсвечивал
 * бы цифру, которая ничего не меняет. Порядок групп при этом сохранён:
 * условные остаются ahead безусловных, иначе безусловный перебил бы
 * подошедшее условие своим приоритетом.
 *
 * Если ни одно условие не совпало, остаются только default-провайдеры;
 * если и их нет — пустой список (ошибку формирует resolveCape).
 *
 * [vars] — контекст переменных наблюдаемого игрока (`username`, `uuid`, `root`,
 * аддон-плейсхолдеры). Для локального отбора (свой конфиг) используется
 * контекст локального игрока.
 */
object ProviderSelector {

    /**
     * Выбрать и упорядочить провайдеров для данного мира и переменных.
     *
     * Тонкое обёртка над [evaluate]: отбор ровно тот же, потому что это тот
     * же код. Отдельная реализация «для отчёта» рано или поздно разошлась бы
     * с боевой — и `/cp list` начал бы врать именно там, где человек уже
     * запутался.
     */
    fun select(
        providers: List<Provider>,
        world: WorldContext,
        vars: Placeholders.Context,
    ): List<Provider> = evaluate(providers, world, vars).ordered

    /**
     * То же, плюс разбор по каждому провайдеру: что проверялось, что показало
     * и подошло ли условие.
     *
     * Одна лишняя работа на провайдера (`explain` идёт в мир по полям), но
     * вызывается только из `/cp list` — в горячий путь рендера не входит.
     */
    fun evaluate(
        providers: List<Provider>,
        world: WorldContext,
        vars: Placeholders.Context,
    ): SelectionReport {
        // Сортируем отчёты, а не провайдеры: так сохраняется стабильность
        // `sortedWith` по исходному порядку списка при равном приоритете.
        val byPriority = compareByDescending<ProviderReport> { it.provider.priority }
        val reports = providers.map { p ->
            val whenChecks = p.condition?.explain(world).orEmpty()
            val ifChecks = p.ifCondition?.explain(vars).orEmpty()
            ProviderReport(
                provider = p,
                whenChecks = whenChecks,
                ifChecks = ifChecks,
                isFallback = !p.hasConditions,
                // Считаем «подошёл» по тем же отчётам, а не вторым вызовом
                // `matches`. Два чтения означали два разных значения: если мир
                // (или переменная окружения) успел измениться между ними, отчёт
                // показал бы одну картину, а выбор сделал бы по другой. Для
                // диагностики это худший вид расхождения — враньёмая причина.
                matched = p.hasConditions &&
                    whenChecks.all { it.ok } &&
                    ifChecks.all { it.ok },
                selected = false,
            )
        }
        // Порядок групп обязателен: условные ahead безусловных, иначе
        // безусловный перебил бы подошедшее условие своим приоритетом.
        val ordered = ArrayList<Provider>(reports.size)
        reports.filter { it.matched }.sortedWith(byPriority).forEach { ordered.add(it.provider) }
        reports.filter { it.isFallback }.sortedWith(byPriority).forEach { ordered.add(it.provider) }
        // `Provider` — обычный класс, а не data: `equals` тождественный, так
        // что `Set` здесь это «те самые объекты», без риска сравнить поля.
        val picked = ordered.toSet()
        return SelectionReport(
            ordered = ordered,
            reports = reports.map { it.copy(selected = it.provider in picked) },
        )
    }
}

/** Разбор одного провайдера: чем он проверялся и что из этого вышло. */
data class ProviderReport(
    val provider: Provider,
    /** Проверки блока `when` — по одной на предикат. */
    val whenChecks: List<PredicateReport>,
    /** Проверки блока `if` — по одной на предикат. */
    val ifChecks: List<VarPredicateReport>,
    /** Провайдер без условий: включается всегда, но проигрывает подошедшим. */
    val isFallback: Boolean,
    /** Выполнены ли оба условия (или единственное из них). */
    val matched: Boolean,
    /** Попал ли в итоговый порядок — то есть плащ реально может прийти от него. */
    val selected: Boolean,
) {
    /** Есть ли хоть одно условие: без них провайдер работает всегда. */
    val hasConditions: Boolean get() = !isFallback
}

/** Разбор отбора целиком: порядок для загрузки и отчёт по каждому провайдеру. */
data class SelectionReport(
    /** Порядок загрузки: ровно то, что вернул бы [ProviderSelector.select]. */
    val ordered: List<Provider>,
    val reports: List<ProviderReport>,
) {
    /** Отчёт по провайдеру, который откроет следующую загрузку. */
    val winner: ProviderReport? = reports.firstOrNull { it.provider === ordered.firstOrNull() }
}
