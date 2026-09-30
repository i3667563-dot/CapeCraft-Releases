package dev.ggtv.koren

import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value

interface WorldContext {
    fun field(root: WorldRoot, field: String, path: String): Value
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
