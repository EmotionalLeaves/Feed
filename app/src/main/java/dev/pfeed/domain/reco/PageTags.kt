package dev.pfeed.domain.reco

/**
 * Tags found on a video's own page when it was opened (sites whose listings carry none), so what
 * For You learns from watching it knows what it was. Kept for the session, a few thousand at most.
 */
object PageTags {
    private val map = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > MAX
    }

    @Synchronized fun put(key: String, tags: List<String>) { map[key] = tags.take(30) }
    @Synchronized fun of(key: String): List<String> = map[key].orEmpty()

    private const val MAX = 3000
}
