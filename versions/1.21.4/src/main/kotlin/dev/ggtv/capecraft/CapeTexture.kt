package dev.ggtv.capecraft

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Регистрация кадров плаща как динамических текстур для рендера.
 *
 * Превращает [IntArray] ARGB (из декодера этапа 3) в [NativeImage] и
 * регистрирует через [NativeImageBackedTexture]. Текстура переиспользуется
 * по UUID — id текстуры стабилен для игрока, `registerTexture` с тем же id
 * заменяет содержимое (для анимации).
 */
object CapeTexture {

    /** Стабильный id текстуры плаща игрока. */
    fun idFor(uuid: String): Identifier =
        Identifier.of("capecraft", "cape/" + sanitize(uuid))

    /**
     * Текстуры, зарегистрированные нами: id -> текстура.
     *
     * Своя карта нужна потому, что `TextureManager.getTexture()` — не «спросить,
     * есть ли текстура», а операция с побочным эффектом: на отсутствующем id
     * он создаёт `SimpleTexture`, **регистрирует** его и пытается загрузить
     * ресурс. Поэтому `getTexture(id)` в проверке «есть ли у нас плащ» сам
     * порождал `Missing resource capecraft:cape/... referenced from itself`, а
     * следующий `release(id)` закрывал уже не нашу, сломанную текстуру —
     * `Failed to close texture`.
     *
     * Свой реестр убирает обе проблемы: чужое под нашим id мы не трогаем, а
     * наличие своей текстуры знаем наверняка, не спрашивая менеджер.
     * Потокобезопасен: `release` зовут и с сетевого, и с воркерского потока.
     */
    private val owned = ConcurrentHashMap<Identifier, NativeImageBackedTexture>()

    /** Есть ли уже зарегистрированная текстура плаща [uuid] (совпадает ли размер). */
    fun has(uuid: String, w: Int, h: Int): Boolean {
        val img = owned[idFor(uuid)]?.image ?: return false
        return img.width == w && img.height == h
    }

    /** Уже есть текстура (без проверки размера) — для выбора id в рендере. */
    fun exists(uuid: String): Boolean = owned.containsKey(idFor(uuid))

    /** Зарегистрировать/обновить текстуру кадра [frame] (w×h ARGB) для [uuid]. */
    fun register(uuid: String, w: Int, h: Int, frame: IntArray): Identifier {
        val id = idFor(uuid)
        val tm = MinecraftClient.getInstance().textureManager
        val existing = owned[id]

        // Переиспользуем текстуру, если размер не менялся (все кадры анимации
        // одного холста) — перезаписываем пиксели и перезаливаем, без
        // destroy/recreate каждый кадр (иначе анимация на 60fps — мусор).
        if (existing != null) {
            val img = existing.image
            if (img != null && img.width == w && img.height == h) {
                copyPixels(img, w, h, frame)
                existing.upload()
                return id
            }
        }

        // id наш, но под ним может лежать текстура прошлой генерации или
        // сломанная — снимаем. Здесь мы гарантированно на рендер-потоке (зовёт
        // `animate` из тика), так что освобождение GL-ресурсов законно.
        owned.remove(id)
        tm.destroyTexture(id)
        val tex = NativeImageBackedTexture(w, h, false)
        tex.setImage(imageOf(w, h, frame))
        tex.upload()
        tm.registerTexture(id, tex)
        owned[id] = tex
        if (!debugLogged) {
            debugLogged = true
            val c = frame[0]
            CapeCraftClient.LOGGER.info(
                "CapeTexture: создана ${w}x$h, первый пиксель ARGB(" +
                    "${(c ushr 24) and 0xFF},${(c ushr 16) and 0xFF}," +
                    "${(c ushr 8) and 0xFF},${c and 0xFF})"
            )
        }
        return id
    }

    /** Переписать пиксели кадра в [image] без `%`/`/` на каждый пиксель. */
    private fun copyPixels(image: NativeImage, w: Int, h: Int, frame: IntArray) {
        var x = 0
        var y = 0
        for (i in frame.indices) {
            image.setColorArgb(x, y, frame[i])
            x++
            if (x == w) {
                x = 0
                y++
                if (y >= h) break
            }
        }
    }

    private var debugLogged = false

    private fun imageOf(w: Int, h: Int, frame: IntArray): NativeImage {
        val image = NativeImage(w, h, false)
        copyPixels(image, w, h, frame)
        return image
    }

    /**
     * Освободить текстуру игрока (при clear/forget).
     *
     * `close()` у текстуры освобождает GL-ресурсы, поэтому это допустимо
     * только с рендер-потока. Нас зовут и с сетевого (сброс сессии при
     * переподключении), и с воркерского потока — оттуда такой вызов падал в
     * `Failed to close texture` и оставлял менеджер в неопределённом
     * состоянии. С рендер-потока выполняем сразу: отложенный через `execute`
     * `release` успел бы снести текстуру, которую следующий же `register` уже
     * пересоздал.
     */
    fun release(uuid: String) {
        val id = idFor(uuid)
        // Не наша текстура (или её уже нет) — чужое под нашим id не трогаем.
        if (owned.remove(id) == null) return
        val mc = MinecraftClient.getInstance()
        val drop = Runnable { mc.textureManager.destroyTexture(id) }
        if (mc.isOnThread()) drop.run() else mc.execute(drop)
    }

    /** Кэш валидных символов для id текстуры (регекс компилируется один раз). */
    private val SANITIZE = Regex("[^A-Za-z0-9_.-]")

    private fun sanitize(uuid: String) = uuid.replace(SANITIZE, "_")
}
