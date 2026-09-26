package dev.ggtv.capecraft

/**
 * Логгер мода, доступный и на клиенте, и на выделенном сервере.
 *
 * Раньше логгер жил в компаньоне клиентского [CapeCraftClient], из-за чего
 * общий код (например [CapeConfig]) нельзя было вызывать на сервере: класс
 * `CapeCraftClient` тянет за собой клиентские классы Fabric/MC. Теперь
 * логгер общий, а [CapeCraftClient.LOGGER] остаётся алиасом для совместимости.
 */
object CapeCraftLog {
    const val MOD_ID: String = "capecraft"

    val LOGGER: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(MOD_ID)
}
