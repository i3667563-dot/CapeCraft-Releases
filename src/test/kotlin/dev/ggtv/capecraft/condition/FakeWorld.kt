package dev.ggtv.capecraft.condition

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot

/**
 * Фейковый живой мир для тестов условий:
 * `Map` значений по ключу «root.field».
 */
class FakeWorld(var fields: Map<String, Value> = emptyMap()) : WorldContext {
    override fun field(root: WorldRoot, field: String, path: String): Value =
        fields["${root.segment}.$field"]
            ?: throw CrenError.NotFound(path)
}

fun str(text: String): Value.VStr = Value.VStr(text)
fun num(d: Double): Value.VFloat = Value.VFloat(d)
fun int(i: Long): Value.VInt = Value.VInt(i)

/** Преобразовать `when { ... }`-подобную строку `.kn` в словарь. */
fun dictOf(vararg pairs: Pair<String, Value>): Value.VDict = Value.VDict(pairs.toList())