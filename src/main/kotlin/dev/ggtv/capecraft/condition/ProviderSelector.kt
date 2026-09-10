package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.koren.WorldContext

/**
 * Логика выбора провайдера под текущее состояние мира (роадмап п. 2).
 *
 * Порядок кандидатов = порядок fallback в [dev.ggtv.capecraft.provider.resolveCape]:
 *
 * 1. Сначала провайдеры, чьё [Condition] выполняется на [WorldContext],
 *    отсортированные по убыванию [Provider.priority] (при равном приоритете
 *    стабильно — в порядке списка).
 * 2. Потом провайдеры без условия (default/fallback) — в порядке списка.
 *
 * Если ни одно условие не совпало, остаются только default-провайдеры;
 * если и их нет — пустой список (ошибку формирует resolveCape).
 */
object ProviderSelector {

    /** Выбрать и упорядочить провайдеров для данного мира. */
    fun select(providers: List<Provider>, world: WorldContext): List<Provider> {
        val matched = providers
            .filter { it.condition != null && it.condition.matches(world) }
            .sortedWith(compareByDescending<Provider> { it.priority })
        val defaults = providers.filter { it.condition == null }
        return matched + defaults
    }
}