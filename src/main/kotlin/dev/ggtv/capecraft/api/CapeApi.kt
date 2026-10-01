package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.condition.CapeWhenRegistry
import dev.ggtv.capecraft.api.condition.CapeWhenRoots
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
 *  - [whenConditions] — свои корни условий `when { fire.burning: "true" }`;
 *  - [renderModifiers] — модификаторы рендера (эффекты, тонирование, условия);
 *  - [events] — слушатели жизненного цикла плаща.
 *
 * Объект без состояния: реестры наполняются на старте и живут до конца игры.
 * Все регистрации заменяют ключ при повторной регистрации (идемпотентно).
 */
class CapeApi @JvmOverloads constructor(
    val sourceTypes: CapeSourceTypeRegistry = CapeSourceTypeRegistry(),
    val decoders: CapeDecoderRegistry = CapeDecoderRegistry(),
    val placeholders: CapePlaceholderRegistry = CapePlaceholderRegistry(),
    val config: CapeAddonConfigRegistry = CapeAddonConfigRegistry(),
    val renderModifiers: CapeRenderModifierRegistry = CapeRenderModifierRegistry(),
    val events: CapeEventBus = CapeEventBus(),
    /**
     * Корни `when` аддона: `when { fire.burning: "true" }`.
     *
     * По умолчанию — общий [CapeWhenRoots.registry], а не новый реестр: разбор
     * `when` идёт через него же ([dev.ggtv.capecraft.condition.Condition]), и
     * два разных реестра разошлись бы так, что аддон зарегистрировал корень, а
     * мод о нём не знает. Подменять нужно только в тестах.
     */
    val whenConditions: CapeWhenRegistry = CapeWhenRoots.registry,
)

/**
 * Номер версии addon-API (для проверки совместимости аддона и модом).
 *
 * 2 — добавлен реестр аддонных корней `when` ([CapeApi.whenConditions]) и
 * `WorldContext.addonField`. Аддон, объявивший только версию 1, честно получит
 * `НЕСОВМЕСТИМО` в [dev.ggtv.capecraft.api.provider.CapeSourceTypeRegistry.checkCompatibility]:
 * у него нет ни того, ни другого.
 */
const val CAPE_RUNTIME_API_VERSION = 2