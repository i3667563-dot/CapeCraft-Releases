package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.config.CapeAddonConfigRegistry
import dev.ggtv.capecraft.api.event.CapeEventBus
import dev.ggtv.capecraft.api.image.CapeDecoderRegistry
import dev.ggtv.capecraft.api.placeholder.CapePlaceholderRegistry
import dev.ggtv.capecraft.api.provider.CapeSourceTypeRegistry
import dev.ggtv.capecraft.api.render.CapeRenderModifierRegistry

/**
 * Фасад addon-API CapeCraft.
 *
 * Это публичный, стабильный и версионируемый контракт для сторонних модов-аддонов.
 * В отличие от внутреннего `dev.ggtv.capecraft.*`, здесь НЕ вскрываются детали
 * реализации (фетчеры, кэш, декод кадров) — только реестры и шина событий.
 *
 * Аддон получает экземпляр в [CapeAddon.register] и регистрирует:
 *  - [sourceTypes] — новые типы провайдеров (свой `type = "..."` и логика получения байтов);
 *  - [decoders] — поддержку новых форматов изображений (по сигнатуре байтов);
 *  - [placeholders] — свои плейсхолдеры (`{my-placeholder}`) для шаблонов URL/путей;
 *  - [config] — схему конфига аддона (ключи со значениями по умолчанию);
 *  - [renderModifiers] — модификаторы рендера (эффекты, тонирование, условия);
 *  - [events] — слушатели жизненного цикла плаща.
 *
 * Объект без государства: реестры наполняются на старте и живут до конца игры.
 * Все регистрации заменяют ключ при повторной регистрации (идемпотентно).
 */
class CapeApi @JvmOverloads constructor(
    val sourceTypes: CapeSourceTypeRegistry = CapeSourceTypeRegistry(),
    val decoders: CapeDecoderRegistry = CapeDecoderRegistry(),
    val placeholders: CapePlaceholderRegistry = CapePlaceholderRegistry(),
    val config: CapeAddonConfigRegistry = CapeAddonConfigRegistry(),
    val renderModifiers: CapeRenderModifierRegistry = CapeRenderModifierRegistry(),
    val events: CapeEventBus = CapeEventBus(),
)

/** Номер версии addon-API (для проверки совместимости аддона и модом). */
const val CAPE_RUNTIME_API_VERSION = 1