package dev.ggtv.capecraft.sync

import dev.ggtv.capecraft.provider.Provider
import dev.ggtv.capecraft.provider.Source
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Политика доверия к тому, что прислал сервер.
 *
 * Сервер — чужая сторона: он может быть злым, сломанным или просто
 * настроенным неаккуратно. Поэтому список из ответа не применяется «как
 * есть», а проходит через проверки:
 *
 *  1. **Аддон-провайдеры собираются только из локального конфига.** Сервер
 *     присылает лишь имя типа; конкретный [dev.ggtv.capecraft.api.provider.CapeSource]
 *     и его параметры берутся из клиентской копии конфига с тем же именем
 *     и типом. Нет такого — провайдер отбрасывается: нельзя выполнить
 *     произвольный код из сети.
 *  2. **`file`-провайдеры по умолчанию запрещены.** Иначе сервер указывает
 *     клиенту читать любой файл с его диска. Разрешаются только при явном
 *     `allowFileProviders = true`, только если шаблон начинается с
 *     `{root}` и не содержит `..` ([isSafeFileTemplate]), и только после
 *     подстановки — уже по реальному пути внутри папки игры ([isInside],
 *     это и есть рантайм-guard реестра).
 *  3. **URL разрешены только `http`/`https`** с непустым хостом
 *     (см. [ActiveCape.isHttpUrl]) — иначе в схеме можно было бы указать
 *     `file://` или `jar:`.
 *
 * Всё остальное ([ActiveCape.validate]) проверяется при разборе пакета.
 */
object SyncPolicy {

    /** Результат конвертации сетевого набора в локальный. */
    data class Converted(
        val providers: List<Provider>,
        /** Человекочитаемые причины отбрасывания — для лога и `/cp status`. */
        val rejected: List<String>,
    )

    /**
     * Превратить ответ сервера в список провайдеров для реестра.
     *
     * @param remote что прислал сервер.
     * @param local локальный конфиг — источник аддон-источников.
     * @param allowFileProviders разрешать ли `type = file` с сервера.
     */
    fun convert(remote: List<ActiveCape>, local: List<Provider>, allowFileProviders: Boolean): Converted {
        val out = ArrayList<Provider>(remote.size)
        val rejected = ArrayList<String>()
        for (cape in remote) {
            val problems = cape.validate()
            if (problems.isNotEmpty()) {
                rejected += "«${cape.name}»: ${problems.first()}"
                continue
            }
            when (cape.kind) {
                ActiveCape.Kind.URL -> out += Provider(cape.name, Source.Url(cape.primary), null, cape.priority)
                ActiveCape.Kind.JSON -> out += Provider(cape.name, Source.Json(cape.primary, cape.extract), null, cape.priority)
                ActiveCape.Kind.FILE -> {
                    if (!allowFileProviders) {
                        rejected += "«${cape.name}»: file-провайдер с сервера запрещён (capeCraft.serverSync.allowFileProviders)"
                    } else if (!isSafeFileTemplate(cape.primary)) {
                        rejected += "«${cape.name}»: небезопасный шаблон пути «${cape.primary}»"
                    } else {
                        out += Provider(cape.name, Source.File(cape.primary), null, cape.priority)
                    }
                }

                ActiveCape.Kind.ADDON -> {
                    // Только из локального конфига: у сервера нет (и не должно
                    // быть) доступа к клиентским параметрам аддона, а собирать
                    // их «примерно» значило бы выполнить аддонный код с
                    // выдуманными аргументами.
                    val localProvider = local.firstOrNull {
                        it.name == cape.name && it.values?.type == cape.primary
                    }
                    if (localProvider?.addonSource == null) {
                        rejected += "«${cape.name}»: аддон-тип «${cape.primary}» на клиенте не зарегистрирован"
                    } else {
                        out += Provider(
                            name = cape.name,
                            source = localProvider.source,
                            condition = null,
                            priority = cape.priority,
                            addonSource = localProvider.addonSource,
                            values = localProvider.values,
                        )
                    }
                }
            }
        }
        return Converted(out, rejected)
    }

    /**
     * Шаблон локального пути приемлем только как `{root}/...` без `..`.
     *
     * `{username}` — ник игрока, поэтому одного «нет `..` в шаблоне» мало:
     * допускаем только `{username}`/`{uuid}` и больше ничего. Этого хватает,
     * потому что окончательную проверку делает [isInside] уже по реально
     * подставленному пути — она строже любого разбора шаблона.
     */
    fun isSafeFileTemplate(template: String): Boolean {
        if (!template.startsWith(ROOT_PLACEHOLDER)) return false
        if (template.contains("..")) return false
        val rest = template.removePrefix(ROOT_PLACEHOLDER).trimStart('/', '\\')
        val placeholders = Regex("\\{[^{}]*}")
        for (m in placeholders.findAll(rest)) {
            when (m.value) {
                "{username}" -> continue
                "{uuid}" -> continue
                else -> return false
            }
        }
        return true
    }

    /**
     * Проверка, что путь внутри [root] — для рантайм-guard'а реестра.
     * Оба пути нормализуются и сравниваются по префиксу.
     */
    fun isInside(root: String, path: String): Boolean = try {
        val rootPath: Path = Paths.get(root).toAbsolutePath().normalize()
        val target = Paths.get(path).toAbsolutePath().normalize()
        target.startsWith(rootPath) && target != rootPath
    } catch (_: Exception) {
        false
    }

    /** Плейсхолдер корня, который [dev.ggtv.capecraft.schema.Placeholders] понимает. */
    const val ROOT_PLACEHOLDER: String = "{root}"
}
