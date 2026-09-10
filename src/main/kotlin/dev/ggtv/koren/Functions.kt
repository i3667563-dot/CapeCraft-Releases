package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Span
import dev.ggtv.kjen.Value

/**
 * Встроенные функции `.kn`. Вызываются из [KorenResolver] с уже
 * разрешёнными аргументами (ссылки и вложенные функции вычислены).
 */
object Functions {

    private const val UNKNOWN = "неизвестная функция"

    /** Выполнить функцию по имени. Аргументы должны быть уже разрешены. */
    fun call(name: String, args: List<Value>, span: Span): Value {
        // Проверка arity до диспетчеризации — общий для всех набор.
        return when (name) {
            "hash" -> hash(args, span)
            "clamp" -> clamp(args, span)
            "lerp" -> lerp(args, span)
            "seq" -> seq(args, span)
            "min" -> minMax(args, span, min = true)
            "max" -> minMax(args, span, min = false)
            "abs" -> abs(args, span)
            else -> throw CrenError.Parse(
                "$UNKNOWN «$name» (доступны: hash, clamp, lerp, seq, min, max, abs)", span,
            )
        }
    }

    /** Имена известных функций — для подсказок в ошибках парсинга. */
    internal fun knownNames(): String = "hash, clamp, lerp, seq, min, max, abs"

    private fun hash(args: List<Value>, span: Span): Value {
        requireArity("hash", exact = 1, args, span)
        val text = when (val v = args[0]) {
            is Value.VStr -> v.s
            is Value.VInt -> v.i.toString()
            is Value.VFloat -> v.f.toString()
            is Value.VBool -> v.b.toString()
            else -> throw typeError("hash", "str/int/float/bool", v, span)
        }
        // FNV-1a 64-bit — быстрый детерминированный дайджест от строки.
        var h = 0xcbf29ce484222325UL
        for (b in text.encodeToByteArray()) {
            h = (h xor b.toULong()) * 0x100000001b3UL
        }
        return Value.VInt(h.toLong())
    }

    private fun clamp(args: List<Value>, span: Span): Value {
        requireArity("clamp", exact = 3, args, span)
        val v = asNum("clamp", args[0], span)
        val lo = asNum("clamp", args[1], span)
        val hi = asNum("clamp", args[2], span)
        if (lo > hi) throw CrenError.Parse("clamp: минимальная граница больше максимальной", span)
        val clamped = v.coerceIn(lo, hi)
        return if (isWhole(v) && isWhole(lo) && isWhole(hi)) Value.VInt(clamped.toLong()) else Value.VFloat(clamped)
    }

    private fun lerp(args: List<Value>, span: Span): Value {
        requireArity("lerp", exact = 3, args, span)
        val a = asNum("lerp", args[0], span)
        val b = asNum("lerp", args[1], span)
        val t = asNum("lerp", args[2], span)
        if (t < 0.0 || t > 1.0) throw CrenError.Parse("lerp: t должен быть в [0, 1]", span)
        return Value.VFloat(a + (b - a) * t)
    }

    private fun seq(args: List<Value>, span: Span): Value {
        requireArity("seq", exact = 2, args, span)
        val a = asInt("seq", args[0], span)
        val b = asInt("seq", args[1], span)
        val items = (a..b).map { Value.VInt(it) as Value }
        return Value.VArray(items)
    }

    private fun minMax(args: List<Value>, span: Span, min: Boolean): Value {
        if (args.size < 2) throw CrenError.Parse(if (min) "min" else "max" + ": нужно минимум 2 аргумента", span)
        val values = args.map { asNum("min/max", it, span) }
        val best = if (min) values.min() else values.max()
        val allWhole = values.all { isWhole(it) }
        return if (allWhole) Value.VInt(best.toLong()) else Value.VFloat(best)
    }

    private fun abs(args: List<Value>, span: Span): Value {
        requireArity("abs", exact = 1, args, span)
        val v = asNum("abs", args[0], span)
        val r = kotlin.math.abs(v)
        return if (isWhole(v)) Value.VInt(r.toLong()) else Value.VFloat(r)
    }

    // ── Хелперы ─────────────────────────────────────────────────────

    private fun requireArity(name: String, exact: Int, args: List<Value>, span: Span) {
        if (args.size != exact) {
            throw CrenError.Parse("$name: ожидалось $exact аргумента(ов), найдено ${args.size}", span)
        }
    }

    private fun asNum(name: String, v: Value, span: Span): Double = when (v) {
        is Value.VInt -> v.i.toDouble()
        is Value.VFloat -> v.f
        else -> throw typeError(name, "число", v, span)
    }

    private fun asInt(name: String, v: Value, span: Span): Long = when (v) {
        is Value.VInt -> v.i
        else -> throw typeError(name, "целое число", v, span)
    }

    private fun isWhole(v: Double): Boolean = v == v.toLong().toDouble()

    private fun typeError(name: String, expected: String, v: Value, span: Span): CrenError.TypeMismatch =
        CrenError.TypeMismatch(expected, v.kind, span)
}