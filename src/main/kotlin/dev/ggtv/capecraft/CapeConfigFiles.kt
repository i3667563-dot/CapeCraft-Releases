package dev.ggtv.capecraft

import java.nio.file.Files
import java.nio.file.Path

/**
 * Где лежит конфиг мода и нужно ли его создавать.
 *
 * Вынесено отдельно от [CapeConfig], потому что этот же выбор файла
 * повторяется в [dev.ggtv.capecraft.sync.ServerCapeConfig] на каждой из
 * шести версий, а `config/capecraft.kn` — единственное место, куда мод
 * вообще пишет. Логика обязана быть тривиальной: **существующий файл
 * никогда не перезаписывается**.
 *
 * Регрессия, которую это фиксирует: конфиг пересоздавался дефолтом на
 * каждом запуске Minecraft, и пользователь терял свои провайдеры. Причина
 * всегда одна — потерянная проверка `Files.exists` перед записью, поэтому
 * проверка живёт здесь, в одном месте, и покрыта тестами.
 */
object CapeConfigFiles {
    /** Основной конфиг (формат `.kn`, надмножество `.crn`). */
    const val KN_NAME: String = "capecraft.kn"

    /** Старый конфиг (`.crn`) — читается как fallback. */
    const val CRN_NAME: String = "capecraft.crn"

    /**
     * Какой файл читать: `.kn`, если он есть (или если нет ни одного),
     * иначе legacy `.crn`, если он есть.
     */
    fun active(configDir: Path): Path {
        val kn = configDir.resolve(KN_NAME)
        val crn = configDir.resolve(CRN_NAME)
        return if (!Files.exists(kn) && Files.exists(crn)) crn else kn
    }

    /**
     * Создавать ли дефолтный файл.
     *
     * Только если выбранного файла нет. Существующий конфиг — пользовательский,
     * его нельзя затирать ни при загрузке, ни при `/capecraft reload`.
     */
    fun mustCreateDefault(active: Path): Boolean = !Files.exists(active)
}
