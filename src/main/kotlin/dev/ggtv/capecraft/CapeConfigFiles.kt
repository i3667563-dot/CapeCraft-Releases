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

    /**
     * Какой файл читать с учётом `CAPECRAFT_CONFIG` — итог выбора целиком.
     *
     * Отдельный метод, потому что `CAPECRAFT_CONFIG` чаще всего задан, а файла по
     * нему нет: опечатка в пути, том, ещё не смонтированный том, ключ от
     * лаунчера переехал на другую машину. Раньше такой случай **выключал мод
     * целиком** — [dev.ggtv.capecraft.CapeConfig.reload] писал одну строку в лог
     * и уходил, оставляя ноль провайдеров и дефолтные лимиты. Плащей не видно,
     * в чате ничего не сказано, а разгадать приходится по логу.
     *
     * Теперь такой файл не заменяет конфиг, а только сообщает о себе: читаем
     * обычный `config/capecraft.kn`, а [Resolution.warning] объясняет в логе и в
     * `/cp status`, почему. Мод с плащами лучше мода без плащей, даже если
     * плащи не те.
     *
     * @param fromEnv результат [dev.ggtv.capecraft.CapeConfigEnv.configFile]
     * @param configDir папка `config` игры
     */
    fun resolve(fromEnv: CapeConfigEnv.ConfigFile?, configDir: Path): Resolution {
        if (fromEnv == null) {
            val file = active(configDir)
            return Resolution(file, mustCreateDefault(file), null, false)
        }
        if (fromEnv.error == null) {
            // Пользователь указал свой источник: лезть в его папку config и
            // создавать там дефолт незачем — он этим файлом не пользуется.
            return Resolution(Path.of(fromEnv.path), false, null, true)
        }
        // Файл из окружения недоступен — откатываемся на обычный конфиг, но
        // говорим об этом дважды: в лог и в статус.
        val file = active(configDir)
        return Resolution(
            file,
            mustCreateDefault(file),
            "${fromEnv.error}; читаю обычный конфиг $file",
            false,
        )
    }

    /**
     * Итог [resolve]: что читать, создавать ли дефолт и на что пожаловаться.
     *
     * @property file файл, который будет прочитан
     * @property createDefault нужно ли создать дефолт, раз файла нет
     * @property warning причина отката с файла из окружения, либо `null`
     * @property fromEnv файл задан `CAPECRAFT_CONFIG`, а не найден в папке config
     */
    data class Resolution(
        val file: Path,
        val createDefault: Boolean,
        val warning: String?,
        val fromEnv: Boolean,
    )
}
