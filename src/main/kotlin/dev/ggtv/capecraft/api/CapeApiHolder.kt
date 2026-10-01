package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.GifDecoder
import dev.ggtv.capecraft.image.ImageFormat
import dev.ggtv.capecraft.image.PngDecoder
import dev.ggtv.capecraft.image.WebpDecoder
import dev.ggtv.capecraft.schema.Placeholders

/**
 * Глобальный держатель активного [CapeApi] и мост между ним и внутренностями.
 *
 * Фабричные реестры (декодеры/плейсхолдеры) доступны и вне MC-контекста,
 * поэтому конструируются без ссылок на версионный `CapeCraftClient` и
 * тестируются чисто. [CapeApiHolder] собирает один общий [CapeApi],
 * регистрирует встроенные декодеры/плейсхолдеры и отдаётся аддонам.
 *
 * Импорт на `CapeCraftClient` здесь был бы связкой общего кода с версионным
 * и ломал бы сборку LSP-сервера, который компилирует эту же папку: версии
 * под ним нет, и KDoc-ссылки на класс не должны требовать её наличия.
 */
object CapeApiHolder {

    /** Единственный активный [CapeApi] (все реестры наполнены до старта). */
    @Volatile
    var api: CapeApi = createDefault()
        @Synchronized set

    /** Собрать [CapeApi] со встроенными декодерами и плейсхолдерами. */
    @Synchronized
    fun createDefault(): CapeApi {
        val a = CapeApi()
        // Форматы изображений — сигнатура по байтам (как видно из ImageFormat.detect).
        a.decoders.register(
            dev.ggtv.capecraft.api.CapeDecoderSpec("png", { sig(ImageFormat.PNG, it) }) {
                data, src -> PngDecoder.decode(data, src)
            },
        )
        a.decoders.register(
            dev.ggtv.capecraft.api.CapeDecoderSpec("gif", { sig(ImageFormat.GIF, it) }) {
                data, src -> GifDecoder.decode(data, src)
            },
        )
        a.decoders.register(
            dev.ggtv.capecraft.api.CapeDecoderSpec("webp", { sig(ImageFormat.WEBP, it) }) {
                data, src -> WebpDecoder.decode(data, src)
            },
        )
        // Встроенные плейсхолдеры — как в Placeholders.value.
        a.placeholders.register(
            dev.ggtv.capecraft.api.CapePlaceholderSpec("username") { it.username },
        )
        a.placeholders.register(
            dev.ggtv.capecraft.api.CapePlaceholderSpec("uuid") { it.uuid },
        )
        a.placeholders.register(
            dev.ggtv.capecraft.api.CapePlaceholderSpec("name") { it.name },
        )
        a.placeholders.register(
            dev.ggtv.capecraft.api.CapePlaceholderSpec("root") { it.root },
        )
        return a
    }

    /** Сигнатура формата (защищённый от коротких данных индексации). */
    private fun sig(f: ImageFormat, data: ByteArray): Boolean =
        ImageFormat.detect(data) == f || data.size >= 1

    /** Плейсхолдер-мост: пробует аддон-резолвер для неизвестного ключа. */
    fun placeholder(key: String, ctx: Placeholders.Context): String? =
        api.placeholders[key]?.resolver?.resolve(
            dev.ggtv.capecraft.api.placeholder.PlaceholderContext(
                username = ctx.username, uuid = ctx.uuid, name = ctx.name, root = ctx.root,
            ),
        )

    /**
     * Кто сейчас запрашивается — см. [CapeFetchSubject].
     *
     * ThreadLocal, а не поле в [CapeValues]: значение собирается при чтении
     * конфига один раз, а игрок меняется на каждый вызов, и на каждый вызов же
     * приходится свой поток (загрузка идёт в пуле). Поле в данных конфига
     * заморозило бы первый UUID на все объекты, а общее поле — отдало бы
     * перепутанное значение при параллельной загрузке.
     *
     * Публикация — [withSubject], который восстанавливает прежнее значение в
     * `finally`: воркер переиспользуется, и забытый сброс означал бы, что
     * следующий вызов увидит чужого игрока. Читать можно только внутри
     * `withSubject`, иначе значение — `null`.
     */
    private val subject = ThreadLocal<CapeFetchSubject?>()

    /** Кто запрашивает плащ прямо сейчас, либо `null` вне вызова аддона. */
    fun subject(): CapeFetchSubject? = subject.get()

    /** Выставить [who] на время [block] и вернуть её результат. */
    fun <T> withSubject(who: CapeFetchSubject?, block: () -> T): T {
        val prev = subject.get()
        subject.set(who)
        try {
            return block()
        } finally {
            if (prev == null) subject.remove() else subject.set(prev)
        }
    }

    /** Декодер-мост: возвращает AnimatedImage из байтов через аддон-либо-встроенный декодер. */
    fun decode(data: ByteArray, source: String?): AnimatedImage {
        val spec = api.decoders.decoderFor(data)
            ?: throw dev.ggtv.capecraft.image.ImageDecodeException("неизвестный формат изображения", source)
        return spec.decoder.decode(data, source)
    }
}