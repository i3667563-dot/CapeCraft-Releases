package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.condition.ProviderSelector
import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.koren.WorldContext

/**
 * Серверный каталог плащей: из всех провайдеров конфига выбирает активные
 * для конкретного игрока и превращает их в сетевые [ActiveCape].
 *
 * Никакого I/O и никаких Minecraft API — только разбор конфига и условий.
 * Серверный слой (Fabric payload, мир игрока) добавляется поверх в
 * версионно-зависимом `CapeSyncServer`.
 *
 * @param providers все провайдеры из серверного `config/capecraft.kn`.
 * @param maxProviders сколько максимум отдать (лимит протокола).
 */
class ServerCapeCatalog(
    private val providers: List<Provider>,
    private val maxProviders: Int = SyncProtocol.MAX_PROVIDERS,
) {
    /**
     * Активные провайдеры для [world] в уже готовом порядке выбора
     * (условия → приоритет → default-цепочка).
     *
     * Некорректные описания ([ActiveCape.validate]) выбрасываются: битый
     * провайдер в конфиге не должен ломать синхронизацию целиком.
     */
    fun responseFor(world: WorldContext): SyncResponseBody {
        val selected = ProviderSelector.select(providers, world)
        val described = selected.map { p -> p to p.toActiveCape()?.takeIf { it.validate().isEmpty() } }
        val kept = described.take(maxProviders)
        return SyncResponseBody(
            providers = kept.mapNotNull { it.second },
            droppedInvalid = described.count { it.second == null },
            truncated = (described.size - kept.size).coerceAtLeast(0),
        )
    }

    /** Сколько провайдеров вообще на сервере (для лога и `/cp status`). */
    val size: Int get() = providers.size

    /** Пустой каталог — сервер без конфига (ответ «плащей нет»). */
    companion object {
        val EMPTY: ServerCapeCatalog = ServerCapeCatalog(emptyList())
    }
}

/** Содержимое ответа сервера до обёртки в сетевой payload. */
data class SyncResponseBody(
    val providers: List<ActiveCape>,
    /** Отброшено как невалидные (плохой URL, пустой extract и т.п.). */
    val droppedInvalid: Int,
    /** Отброшено сверх [SyncProtocol.MAX_PROVIDERS]. */
    val truncated: Int,
)
