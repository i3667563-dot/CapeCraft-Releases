package dev.ggtv.capecraft.memory

import dev.ggtv.capecraft.image.AnimatedImage
import dev.ggtv.capecraft.image.Frame

/**
 * Умная память: применение лимитов к декодированным плащам (этап 5.5).
 *
 * Отвечает за то, чтобы набор закэшированных плащей не съел всю память
 * клиента. Работает в два уровня:
 *
 * 1. **Per-cape деградация** — при [store] изображение, декодированное
 *    декодерами (все кадры сразу), приводится к лимитам:
 *      - если кадр слишком велик ([Limits.maxPixelsPerFrame]) → area-average
 *        сжатие ([Scale]) до приемлемого размера;
 *      - если суммарные байты плаща превышают [Limits.maxBytesPerCape] →
 *        ещё сильнее сжимаем, а если и это не помогло — прореживаем кадры
 *        ([FrameThinning]).
 * 2. **Глобальный LRU-кэш** — готовые [AnimatedImage] держатся по ключу
 *    (UUID), вытесняются в порядке «давно не использованный», пока суммарный
 *    вес всех плащей <= [Limits.maxBytesTotal].
 *
 * Деградация жертвует разрешением/плавностью, но никогда не роняет
 * рендер. Для обычного PNG-плаща (который почти всегда влезает) — это
 * дешёвый проход без копирований.
 */
class MemoryManager(
    val limits: Limits = Limits(),
) {
    private val cache: LruCache<String, AnimatedImage> = LruCache(
        maxBytes = limits.maxBytesTotal,
        sizeOf = { _, img -> limits.bytesOf(img.width, img.height, img.frameCount) },
    )

    /** Текущий суммарный вес всех закэшированных плащей, байт. */
    val totalBytes: Long get() = cache.size

    /** Число закэшированных плащей. */
    val capesCount: Int get() = cache.count

    /** Ключи всех закэшированных плащей (порядок вставки/lru). */
    val keys: Set<String> get() = cache.keys

    /**
     * Положить плащ игрока [uuid] (закэшировать после деградации).
     * Возвращает итоговое изображение (после сжатия/прореживания).
     */
    fun store(uuid: String, image: AnimatedImage): AnimatedImage {
        val fit = degrade(image)
        cache.put(uuid, fit)
        return fit
    }

    /**
     * Положить УЖЕ деградированное изображение (быстрый путь без повторной
     * деградации). Используется, когда тяжёлая [degrade] выполнена заранее
     * на фоновом потоке, а сюда приходит только результат.
     */
    fun putCached(uuid: String, image: AnimatedImage) {
        cache.put(uuid, image)
    }

    /** Есть ли плащ в кэше (без поднятия LRU-позиции). */
    fun contains(uuid: String): Boolean = cache.contains(uuid)

    /**
     * Достать плащ по ключу, вернув его позицию в LRU наверх (недавно
     * использованный). null — плаща нет в кэше.
     */
    fun get(uuid: String): AnimatedImage? = cache.get(uuid)

    /** Удалить плащ из кэша. */
    fun remove(uuid: String): Boolean {
        val had = cache.contains(uuid)
        cache.remove(uuid)
        return had
    }

    /** Очистить кэш. */
    fun clear() = cache.clear()

    // ---- Деградация одного изображения под лимиты ----

    /**
     * Привести изображение к лимитам одного плаща.
     * Возвращает изображение, чьи суммарные байты <= [Limits.maxBytesPerCape]
     * и размер кадра <= [Limits.maxPixelsPerFrame].
     */
    fun degrade(image: AnimatedImage): AnimatedImage {
        val w = image.width
        val h = image.height

        // 1. Размер кадра превышает лимит — нужен area-average масштаб.
        val frameTooBig = w.toLong() * h > limits.maxPixelsPerFrame

        // 2. Суммарные байты превышают лимит на плащ — масштаб больше/скип кадров.
        val bytesTooBig = limits.bytesOf(w, h, image.frameCount) > limits.maxBytesPerCape

        // 3. Число кадров превышает лимит — нужен скип независимо от размера.
        val tooManyFrames = image.isAnimated && image.frameCount > limits.maxFrames

        if (!frameTooBig && !bytesTooBig && !tooManyFrames) {
            return image // влезает как есть — без копирований
        }

        // Целевая площадь кадра: сжимаем до FRAME_TARGET (разумное рабочее
        // разрешение), но не больше maxPixelsPerFrame; если лимит байт на плащ
        // требует ещё сильнее — распределяем его по кадрам.
        var targetPixels = kotlin.math.min(Limits.FRAME_TARGET_PIXELS, limits.maxPixelsPerFrame)
        if (bytesTooBig) {
            val frames = image.frameCount.coerceAtLeast(1)
            val allowedPerFrame = limits.maxBytesPerCape / Limits.BYTES_PER_PIXEL / frames
            if (allowedPerFrame < targetPixels) targetPixels = allowedPerFrame.coerceAtLeast(1)
        }

        // Масштабируем каждый кадр area-average. Все кадры одного размера холста,
        // поэтому размер результата берём из первой операции.
        var resultW = w
        var resultH = h
        var firstDone = false
        val scaledFrames = ArrayList<Frame>(image.frameCount)
        for (f in image.frames) {
            val (px, nw, nh) = Scale.fitWithin(f.pixels, w, h, targetPixels)
            if (!firstDone) {
                resultW = nw; resultH = nh
                firstDone = true
            }
            scaledFrames += Frame(px, f.durationMs)
        }

        var result = AnimatedImage(resultW, resultH, scaledFrames, image.loopCount)

        // 3. Скип кадров: если число кадров превышает лимит или байты всё ещё
        // не влезают — прореживаем равномерно до maxFrames.
        if (result.frameCount > limits.maxFrames ||
            limits.bytesOf(result.width, result.height, result.frameCount) > limits.maxBytesPerCape
        ) {
            result = FrameThinning.thin(result, limits.maxFrames)
        }

        // 4. Последний рубеж: даже один кадр не влезает по байтам — оставляем
        // один статичный кадр (если это ещё анимация).
        if (result.frameCount > 1 &&
            limits.bytesOf(result.width, result.height, result.frameCount) > limits.maxBytesPerCape
        ) {
            result = FrameThinning.thin(result, 1)
        }
        return result
    }
}
