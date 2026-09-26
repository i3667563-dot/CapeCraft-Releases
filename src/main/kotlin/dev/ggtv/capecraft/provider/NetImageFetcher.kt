package dev.ggtv.capecraft.provider

import dev.ggtv.capecraft.sync.NetImageStore

/**
 * Источник байтов для [Resolved.NetImage] — картинки, полученной по Sync v2.
 *
 * Отдельный фетчер, потому что этот источник **никогда не ходит в сеть сам**:
 * адрес у него один — хэш, и байты уже пришли чанками от владельца и прошли
 * проверку в [dev.ggtv.capecraft.sync.ImageAssembler].
 *
 * Пока картинки нет, бросаем [FetchError], а не возвращаем пустой массив:
 * `resolveCapeOrdered` на ошибке идёт к следующему провайдеру, и так плащ
 * подставляется сразу, а не после пустой картинки и ошибки декода.
 */
class NetImageFetcher(private val store: NetImageStore) : CapeFetcher {
    override fun fetch(r: Resolved): ByteArray = when (r) {
        is Resolved.NetImage -> store.get(r.hash)
            ?: throw FetchError("картинка ${r.hash.take(12)} ещё не докачана")

        else -> throw FetchError("сетевой источник не умеет «${r::class.simpleName}»")
    }
}
