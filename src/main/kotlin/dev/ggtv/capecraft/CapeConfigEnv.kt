package dev.ggtv.capecraft

import dev.ggtv.capecraft.CapeCraftLog
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Конфигурация мода из окружения: переменные и JVM-аргументы.
 *
 * Нужно там, где лаунчер не даёт положить файл в папку игры, — например
 * systemd-юнит выделенного сервера или поле Env в FreesmLauncher. Раньше
 * единственным источником был `config/capecraft.kn`, и настроить мод без
 * доступа к папке игры было невозможно.
 *
 * Два независимых механизма:
 *
 * 1. **Весь конфиг целиком** — `CAPECRAFT_CONFIG=/path/to/alt.kn`. Файл
 *    читается вместо `config/capecraft.kn`, формат тот же, поддерживает всё,
 *    включая провайдеров. Единственный способ задать провайдеры из окружения.
 * 2. **Точечные переопределения** — для скалярных опций:
 *    `capeCraft.limits.maxFrames` → `CAPECRAFT_LIMITS_MAXFRAMES` или
 *    `-Dcapecraft.limits.maxFrames=20`.
 *
 * Приоритет: переменная окружения важнее `-D`, важнее файла. Имя
 * получается механически — префикс `CAPECRAFT_`, приведение к верхнему
 * регистру, всё кроме букв и цифр заменяется на `_`, — поэтому ключ из
 * конфига не нужно дублировать вручную: достаточно скопировать его и
 * убрать точки.
 *
 * Переопределение с нечитаемым значением (не число, не «true/false») не
 * игнорируется молча: значение пропускается, в лог уходит предупреждение с
 * точным именем переменной. Иначе опечатка в лаунчере выглядит как «мод
 * всё включил» и находится только через `/cp status`.
 */
object CapeConfigEnv {
    const val PREFIX = "CAPECRAFT_"

    /** Ключ для указания альтернативного файла конфига. */
    const val CONFIG_KEY = "config"

    /** Корень блока конфига в `.kn` — он же корень имён переменных. */
    private const val ROOT = "capeCraft"

    // System.getenv не подставляется в юнит-тестах, поэтому источники
    // значений — сменные функции: тестам нужно задать окружение руками.
    @Volatile
    private var envSource: (String) -> String? = { System.getenv(it) }

    @Volatile
    private var propertySource: (String) -> String? = { System.getProperty(it) }

    /**
     * Имя переменной окружения для ключа конфига.
     *
     * Ключ вида `capeCraft.limits.maxFrames` уже содержит имя мода, поэтому
     * префикс добавляется только когда его нет: иначе вышло бы
     * `CAPECRAFT_CAPECRAFT_LIMITS_MAXFRAMES`, и переменная просто не находилась
     * бы. Голый ключ вроде [CONFIG_KEY] префиксуется как есть.
     */
    fun variableFor(key: String): String {
        val tail = key.replace(NON_ALNUM, "_")
        val withoutRoot = if (tail.startsWith(ROOT, ignoreCase = true)) {
            tail.substring(ROOT.length).trimStart('_')
        } else {
            tail
        }
        return PREFIX + withoutRoot.uppercase()
    }

    /**
     * Значение переопределения для [key] или `null`, если окружение молчит.
     * Пустая строка считается заданным значением: так переменной можно
     * очистить строковую опцию, не прибегая к «отключить переменную».
     *
     * Формы имён, которые принимаются, — в порядке приоритета:
     * 1. переменная окружения `CAPECRAFT_LIMITS_MAXFRAMES`;
     * 2. `-DCAPECRAFT_LIMITS_MAXFRAMES=…` — то же имя заглавными: флаг `-D`
     *    обычно копируют из имени переменной, и это должно работать;
     * 3. `-Dcapecraft_limits_maxframes=…` — то же строчными;
     * 4. `-DcapeCraft.limits.maxFrames=…` — ключ конфига дословно;
     * 5. `-Dcapecraft.config=…` — «голый» ключ тоже с корнем, чтобы все
     *    опции настраивались одинаково.
     */
    fun lookup(key: String): String? {
        val variable = variableFor(key)
        val rooted = "$ROOT.$key"
        return envSource(variable)
            ?: propertySource(variable)
            ?: propertySource(variable.lowercase())
            ?: propertySource(key)
            ?: propertySource(rooted)
            ?: propertySource(rooted.lowercase())
    }

    /** Скалярная опция [key] как число; [fallback] — значение из файла. */
    fun longOr(key: String, fallback: Long): Long {
        val raw = lookup(key)?.trim() ?: return fallback
        return raw.toLongOrNull() ?: run {
            warnBadValue(key, raw, "число")
            fallback
        }
    }

    /** Скалярная опция [key] как `true`/`false`; [fallback] — значение из файла. */
    fun booleanOr(key: String, fallback: Boolean): Boolean {
        val raw = lookup(key)?.trim() ?: return fallback
        return when (raw.lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> {
                warnBadValue(key, raw, "true или false")
                fallback
            }
        }
    }

    /**
     * Альтернативный файл конфига из `CAPECRAFT_CONFIG`/`-Dcapecraft.config`.
     * `null` — переменная не задана, читаем обычный `config/capecraft.kn`.
     * Путь читается, но не существует — возвращаем путь и `reason`,
     * чтобы вызывающий сообщил об ошибке, а не сделал вид, что всё в порядке.
     */
    fun configFile(): ConfigFile? {
        val raw = lookup(CONFIG_KEY)?.trim()
        if (raw.isNullOrEmpty()) return null
        val path = try {
            Path.of(raw)
        } catch (e: InvalidPathException) {
            return ConfigFile(raw, "$raw: некорректный путь (${e.reason})")
        }
        if (!Files.isRegularFile(path)) {
            return ConfigFile(raw, "$raw: файл не найден или не читается")
        }
        return ConfigFile(path.toString(), null)
    }

    /** Результат чтения `CAPECRAFT_CONFIG`: [path] и [error], если что-то не так. */
    data class ConfigFile(val path: String, val error: String?)

    /**
     * Какие переопределения из окружения сейчас активны — строки для
     * `/cp status`. Без этого нельзя понять, откуда взялось неожиданное
     * значение: в `.kn` может стоять одно, а переменная лаунчера перебивает
     * его молча, и конфиг выглядит неправильным.
     */
    fun activeOverrides(): List<String> {
        val keys = listOf(
            CONFIG_KEY,
            "$ROOT.limits.maxPixelsPerFrame",
            "$ROOT.limits.maxFrames",
            "$ROOT.limits.maxBytesPerCape",
            "$ROOT.limits.maxBytesTotal",
            "$ROOT.serverSync.enabled",
            "$ROOT.serverSync.intervalTicks",
            "$ROOT.serverSync.timeoutTicks",
            "$ROOT.serverSync.requireServer",
            "$ROOT.serverSync.allowFileProviders",
            "$ROOT.serverSync.backoff",
        )
        return keys.mapNotNull { key ->
            val raw = lookup(key)?.trim() ?: return@mapNotNull null
            if (key == CONFIG_KEY) "${variableFor(key)}=${configFile()?.path ?: raw}" else "${variableFor(key)}=$raw"
        }
    }

    private fun warnBadValue(key: String, value: String, expected: String) {
        CapeCraftLog.LOGGER.warn(
            "CapeCraft: переопределение {}={} не понимаю, жду {}; " +
                "значение из файла сохранено. Проверь переменную в лаунчере.",
            variableFor(key), value, expected,
        )
    }

    private val NON_ALNUM = Regex("[^A-Za-z0-9]")

    /** Подмена источников для тестов. */
    fun overrideSourcesForTest(env: Map<String, String>, properties: Map<String, String>) {
        envSource = { env[it] }
        propertySource = { properties[it] }
    }

    /** Возврат к настоящему окружению JVM. */
    fun resetSources() {
        envSource = { System.getenv(it) }
        propertySource = { System.getProperty(it) }
    }
}
