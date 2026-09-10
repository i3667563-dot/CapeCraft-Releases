package dev.ggtv.capecraft

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier

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

    /** Есть ли уже зарегистрированная текстура плаща [uuid] (совпадает ли размер). */
    fun has(uuid: String, w: Int, h: Int): Boolean {
        val t = MinecraftClient.getInstance().textureManager.getTexture(idFor(uuid))
        return t is NativeImageBackedTexture && t.image?.width == w && t.image?.height == h
    }

    /** Уже есть текстура (без проверки размера) — для выбора id в рендере. */
    fun exists(uuid: String): Boolean =
        MinecraftClient.getInstance().textureManager.getTexture(idFor(uuid)) is NativeImageBackedTexture

    /** Зарегистрировать/обновить текстуру кадра [frame] (w×h ARGB) для [uuid]. */
    fun register(uuid: String, w: Int, h: Int, frame: IntArray): Identifier {
        val id = idFor(uuid)
        val tm = MinecraftClient.getInstance().textureManager
        val existing = tm.getTexture(id)

        // Переиспользуем текстуру, если размер не менялся (все кадры анимации
        // одного холста) — перезаписываем пиксели и перезаливаем, без
        // destroy/recreate каждый кадр (иначе анимация на 60fps — мусор).
        if (existing is NativeImageBackedTexture) {
            val img = existing.image ?: run {
                // Нет изображения — восстановим через destroy ниже.
                null
            }
            if (img != null && img.width == w && img.height == h) {
                copyPixels(img, w, h, frame)
                existing.upload()
                return id
            }
        }

        tm.destroyTexture(id)
        val tex = NativeImageBackedTexture("capecraft-cape", w, h, false)
        tex.setImage(imageOf(w, h, frame))
        tex.upload()
        tm.registerTexture(id, tex)
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

    /** Освободить текстуру игрока (при clear/forget). */
    fun release(uuid: String) {
        MinecraftClient.getInstance().textureManager.destroyTexture(idFor(uuid))
    }

    /** Кэш валидных символов для id текстуры (регекс компилируется один раз). */
    private val SANITIZE = Regex("[^A-Za-z0-9_.-]")

    private fun sanitize(uuid: String) = uuid.replace(SANITIZE, "_")
}
