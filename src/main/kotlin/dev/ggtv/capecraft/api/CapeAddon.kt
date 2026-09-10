package dev.ggtv.capecraft.api

/**
 * Точка входа аддона CapeCraft.
 *
 * Аддон реализует этот интерфейс и объявляет его в своём `fabric.mod.json`
 * в entrypoint `capecraft:addons` (см. API.md). [register] вызывается модом
 * при старте клиента ДО чтения конфига — чтобы аддоны успели зарегистрировать
 * типы провайдеров, декодеры и плейсхолдеры, которые нужны при обработке `.kn`.
 */
fun interface CapeAddon {
    fun register(api: CapeApi)
}