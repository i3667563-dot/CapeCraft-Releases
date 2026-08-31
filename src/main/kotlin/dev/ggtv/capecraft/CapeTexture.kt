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

    /** Зарегистрировать/обновить текстуру кадра [frame] (w×h ARGB) для [uuid]. */
    fun register(uuid: String, w: Int, h: Int, frame: IntArray): Identifier {
        val id = idFor(uuid)
        val image = NativeImage(w, h, false)
        for (i in frame.indices) {
            image.setColorArgb(i % w, i / w, frame[i])
        }
        val tm = MinecraftClient.getInstance().textureManager
        val existing = tm.getTexture(id)
        tm.destroyTexture(id)
        val tex = NativeImageBackedTexture("capecraft-cape", w, h, false)
        tex.setImage(image)
        tex.upload()
        tm.registerTexture(id, tex)
        existing?.close()
        return id
    }

    /** Освободить текстуру игрока (при clear/forget). */
    fun release(uuid: String) {
        MinecraftClient.getInstance().textureManager.destroyTexture(idFor(uuid))
    }

    private fun sanitize(uuid: String) = uuid.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
