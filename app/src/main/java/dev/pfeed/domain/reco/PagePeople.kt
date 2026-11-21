package dev.pfeed.domain.reco

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The performers a video's own page credits (Pornhub's "Pornstars" row, XVideos' models), read
 * when the video is opened: they join the ones its title names (see Mentions.inTitle).
 */
object PagePeople {
    private val map = object : LinkedHashMap<String, List<String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > MAX
    }
    private val _version = MutableStateFlow(0)
    /** Goes up whenever a page adds people, so what shows them can look again. */
    val version: StateFlow<Int> = _version

    @Synchronized fun put(key: String, names: List<String>) {
        if (names.isEmpty() || map[key] == names) return
        map[key] = names.take(6)
        _version.value++
    }

    @Synchronized fun of(key: String): List<String> = map[key].orEmpty()

    private const val MAX = 1000
}
