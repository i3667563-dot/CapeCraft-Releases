package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value

interface WorldContext {
    fun field(root: WorldRoot, field: String, path: String): Value

    // Два метода ниже — точка расширения для корней `when`, которые объявляет
    // аддон: `fire.burning` и подобные. В [WorldRoot] их быть не может, это
    // закрытый перечень вендоренной библиотеки, а аддон подключается как мод и
    // в неё ничего не добавляет.
    //
    // Читает их реализация контекста, а не реестр: локальный игрок отвечает,
    // а контекст чужого игрока остаётся на заглушке и отвечает [CrenError.NotFound]
    // — ровно как [field] у self-only корня. Иначе условие по состоянию владельца
    // совпало бы у наблюдателя, считающего его против своего игрока.

    /**
     * Значение поля корня, объявленного аддоном.
     *
     * @param root имя корня, как его пишут в `when`, — `fire`
     * @param field поле корня — `burning`
     * @param path полный путь для сообщения об ошибке — `fire.burning`
     * @throws CrenError.NotFound корня нет, поле не читается или контекст не тот
     */
    fun addonField(root: String, field: String, path: String): Value =
        throw CrenError.NotFound(path)

    /**
     * Объект, состояние которого читает аддонный корень.
     *
     * Сущность игрока, а не готовые значения: аддон знает, что ему нужно от
     * игрока, и читает это сам. `null` — читать нечего (серверный контекст или
     * чужой игрок), и читатель обязан это учесть.
     */
    fun subjectEntity(): Any? = null
}

enum class WorldRoot(val segment: String) {
    BIOME("biome"),
    WEATHER("weather"),
    TIME("time"),
    DIMENSION("dimension"),
    LOCATION("location"),
    ARMOR("armor"),
    HEALTH("health"),
    STATE("state");

    companion object {
        fun bySegment(segment: String): WorldRoot? =
            entries.firstOrNull { it.segment == segment }

        fun fromPath(segments: List<String>): WorldRoot? {
            if (segments.size != 2) return null
            return bySegment(segments[0])
        }
    }
}

object EmptyWorldContext : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value =
        throw CrenError.NotFound(path)
}
