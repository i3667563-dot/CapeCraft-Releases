package dev.ggtv.capecraft.provider

/**
 * Имена ключей и типов провайдеров — единственный источник правды для `.kn`.
 *
 * ## Почему это отдельный файл, а не вложенные объекты загрузчика
 *
 * [ProviderLoader] ходит по сети и в файловую систему: он создаёт провайдеры,
 * а те читают кадр. Тащить такой класс ради сверки строки `"url"` нельзя по
 * двум причинам. Первая — валидация схемы и подсказки редактора начинают
 * зависеть от кода, который работает с сетью: любой его чих роняет
 * подсказки. Вторая — сборка: этот файл не тянет Minecraft, поэтому с него
 * собирается LSP-сервер и любой другой инструмент, а с [ProviderLoader] —
 * уже нет.
 *
 * [ProviderLoader.Keys] и [ProviderLoader.Types] были удалены вместе с
 * копиями констант: оставить их значило бы завести вторую таблицу строк,
 * которая разойдётся с первой при первом же переименовании. Kotlin не умеет
 * alias на вложенный объект, поэтому ссылка меняется на [ProviderNames.Keys]
 * в трёх местах: сам загрузчик, схема и её тест.
 */
object ProviderNames {

    /** Ключи словаря провайдера, как в `{ name = ..., type = ... }`. */
    object Keys {
        const val NAME = "name"
        const val TYPE = "type"
        const val URL = "url"
        const val PATH = "path"
        const val EXTRACT = "extract"
        const val WHEN = "when"
        const val PRIORITY = "priority"
    }

    /** Типы провайдеров, как в `type = ...`. */
    object Types {
        const val URL = "url"
        const val FILE = "file"
        const val JSON = "json"
    }
}
