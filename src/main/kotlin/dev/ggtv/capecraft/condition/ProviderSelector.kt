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

    /** Выбрать и упорядочить провайдеров для данного мира и переменных. */
    fun select(
        providers: List<Provider>,
        world: WorldContext,
        vars: Placeholders.Context,
    ): List<Provider> {
        val byPriority = compareByDescending<Provider> { it.priority }
        val matched = providers
            .filter { provider ->
                // `hasConditions` обязателен: без него провайдер без условий
                // прошёл бы проверку «оба условия выполнены» (оба — null) и
                // попал бы в `matched`, а потом ещё раз в `defaults`.
                provider.hasConditions &&
                    (provider.condition?.matches(world) ?: true) &&
                    (provider.ifCondition?.matches(vars) ?: true)
            }
            .sortedWith(byPriority)
        val defaults = providers.filter { !it.hasConditions }.sortedWith(byPriority)
        return matched + defaults
    }
}