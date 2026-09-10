package dev.ggtv.capecraft.api.event

import dev.ggtv.capecraft.image.AnimatedImage

/**
 * Событие жизненного цикла плаща, рассылаемое [CapeEventBus].
 *
 * Все слушатели вызываются с фонового воркер-потока реестра, поэтому внутри
 * слушателя допустима любая работа, но НЕ блокируйте игровой поток (его и нет
 * на этом пути). Не изменяйте конфигурацию/реестр из слушателя.
 */
sealed interface CapeEvent {
    val type: Type

    enum class Type {
        CAPE_LOADED,
        CAPE_LOAD_FAILED,
        FRAME_BUILT,
        PROVIDER_NOT_FOUND,
    }

    /** Плащ успешно загружен и закэширован (UUID + изображение). */
    data class CapeLoaded(
        val uuid: String,
        val username: String,
        val image: AnimatedImage,
    ) : CapeEvent {
        override val type = Type.CAPE_LOADED
    }

    /** Плащ не удалось загрузить (причина в [reason]). */
    data class CapeLoadFailed(
        val uuid: String,
        val username: String,
        val reason: String,
    ) : CapeEvent {
        override val type = Type.CAPE_LOAD_FAILED
    }

    /** Производитель перестроил кадр/текстуру плаща (см. [frameIndex]). */
    data class FrameBuilt(
        val uuid: String,
        val frameIndex: Int,
    ) : CapeEvent {
        override val type = Type.FRAME_BUILT
    }

    /** Ни один провайдер не вернул плащ (полная ошибка fallback-цепи). */
    data class ProviderNotFound(
        val uuid: String,
        val username: String,
        val message: String,
    ) : CapeEvent {
        override val type = Type.PROVIDER_NOT_FOUND
    }
}