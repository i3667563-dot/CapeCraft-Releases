package dev.ggtv.capecraft.cren

/**
 * Путь ссылки: `server.token[1]`, `server1.host`.
 */
data class Path(
    val segments: List<String>,
    /** Номер мульти-ключа (1-based) для каждого сегмента — из формы `server.token[1]`. */
    val indices: List<Int?>,
    /** true — путь от корня, false — относительно текущего блока. */
    val absolute: Boolean,
) {
    override fun toString(): String = buildString {
        for ((i, seg) in segments.withIndex()) {
            if (i > 0) append('.')
            append(seg)
            if (i < this@Path.indices.size) this@Path.indices[i]?.let { append("[$it]") }
        }
    }

    companion object {
        /**
         * Разобрать строку пути. Работает сразу, до парсера.
         *
         * `"server.token[1]"` → segments: [server, token], indices: [null, 1].
         * `"server1.host"` → суффикс-номер разбирается при резолве.
         * Ведущая точка — относительный путь: `".shared.host"` → absolute: false.
         *
         * @throws CrenError.Parse при неверном пути.
         */
        fun parse(s: String): Path {
            val segments = mutableListOf<String>()
            val indices = mutableListOf<Int?>()
            var absolute = true

            fun fail(message: String): Nothing = throw CrenError.Parse(message, Span.ZERO)

            var rest = s
            if (rest.startsWith('.')) {
                if (rest.startsWith("..")) {
                    fail("«..» не поддержан в пути «$rest»: только «.имя» от текущего блока")
                }
                absolute = false
                rest = rest.substring(1)
            }

            val parts = rest.split('.').map { it.trim() }
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty()) fail("пустой сегмент пути: «$s»")
                val isLast = i == parts.lastIndex

                if (part.endsWith(']')) {
                    if (!isLast) {
                        fail("номер [n] поддерживается только в конце пути: «$s» (в середине пишите номер после имени: «a1.b»)")
                    }
                    val idxStart = part.lastIndexOf('[')
                    if (idxStart <= 0 || part.indexOf('[') != idxStart) {
                        fail("неверный сегмент пути: «$s»")
                    }
                    val name = part.substring(0, idxStart)
                    val idx = part.substring(idxStart + 1, part.length - 1).toIntOrNull()
                        ?: fail("неверный номер в пути: «$s»")
                    if (idx == 0) fail("номер в пути начинается с 1: «$s»")
                    segments += name
                    indices += idx
                } else {
                    if (part.contains('[') || part.contains(']')) fail("неверный сегмент пути: «$s»")
                    segments += part
                    indices += null
                }
            }

            if (segments.isEmpty()) fail("пустой путь: «$s»")

            return Path(segments, indices, absolute)
        }
    }
}