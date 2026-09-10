package dev.ggtv.capecraft

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Регистрация кадров плаща как динамических текстур для рендера.
 *
 * Превращает [IntArray] ARGB (из декодера этапа 3) в [NativeImage] и
 * регистрирует через [DynamicTexture]. Текстура переиспользуется по UUID —
 * id текстуры стабилен для игрока, `register` с тем же id заменяет содержимое
 * (для анимации).
 *
 * MC 26.2 (необфусцированные имена Mojang): `RegistryEntry`/`Identifier`
 * в `net.minecraft.resources`, `DynamicTexture`/`TextureManager` в
 * `net.minecraft.client.renderer.texture`, `NativeImage` в
 * `com.mojang.blaze3d.platform`.
 */
object CapeTexture {

    /** Стабильный id текстуры плаща игрока. */
    fun idFor(uuid: String): Identifier =
        Identifier.fromNamespaceAndPath("capecraft", "cape/" + sanitize(uuid))

    /** Есть ли уже зарегистрированная текстура плаща [uuid] (совпадает ли размер). */
    fun has(uuid: String, w: Int, h: Int): Boolean {
        val t = Minecraft.getInstance().textureManager.getTexture(idFor(uuid))
        return t is DynamicTexture && t.getPixels()?.width == w && t.getPixels()?.height == h
    }

    /** Уже есть текстура (без проверки размера) — для выбора id в рендере. */
    fun exists(uuid: String): Boolean =
        Minecraft.getInstance().textureManager.getTexture(idFor(uuid)) is DynamicTexture

    /** Зарегистрировать/обновить текстуру кадра [frame] (w×h ARGB) для [uuid]. */
    fun register(uuid: String, w: Int, h: Int, frame: IntArray): Identifier {
        val id = idFor(uuid)
        val tm = Minecraft.getInstance().textureManager
        val existing = tm.getTexture(id)

        // Переиспользуем текстуру, если размер не менялся (все кадры анимации
        // одного холста) — перезаписываем пиксели и перезаливаем, без
        // release/recreate каждый кадр (иначе анимация на 60fps — мусор).
        if (existing is DynamicTexture) {
            val img = existing.getPixels()
            if (img != null && img.width == w && img.height == h) {
                copyPixels(img, w, h, frame)
                existing.upload()
                return id
            }
        }

        tm.release(id)
        val tex = DynamicTexture("capecraft-cape", w, h, false)
        val img = requireNotNull(tex.getPixels()) { "DynamicTexture не содержит NativeImage" }
        copyPixels(img, w, h, frame)
        tex.upload()
        tm.register(id, tex)
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
            image.setPixel(x, y, frame[i])
            x++
            if (x == w) {
                x = 0
                y++
                if (y >= h) break
            }
        }
    }

    private var debugLogged = false

    /** Освободить текстуру игрока (при clear/forget). */
    fun release(uuid: String) {
        Minecraft.getInstance().textureManager.release(idFor(uuid))
    }

    /** Кэш валидных символов для id текстуры (регекс компилируется один раз). */
    private val SANITIZE = Regex("[^A-Za-z0-9_.-]")

    private fun sanitize(uuid: String) = uuid.replace(SANITIZE, "_")
}