package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem
import kotlinx.serialization.Serializable

/**
 * What a site itself shows beside a video ("related", "up next"): built from what its own viewers
 * watch together, which no tag can match. Read off the video's page when it's opened for playback.
 * When you enjoy a video, its related ones are queued for For You's next candidates, so a good find
 * leads to more like it, the way the sites' own players do.
 */
object Related {
    private val map = object : LinkedHashMap<String, List<MediaItem>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > 400
    }
    private val queue = ArrayDeque<MediaItem>()

    @Synchronized fun put(key: String, items: List<MediaItem>) { map[key] = items.take(MAX_PER_VIDEO) }
    @Synchronized fun of(key: String): List<MediaItem> = map[key].orEmpty()

    /** Videos you enjoyed lately that have related ones: (title, key), newest first. */
    private val recent = ArrayDeque<Pair<String, String>>()

    @Synchronized fun recentlyEnjoyed(): List<Pair<String, List<MediaItem>>> =
        recent.mapNotNull { (title, key) -> map[key]?.takeIf { it.size >= 4 }?.let { title to it } }

    /** You enjoyed [key]: its related videos go to the front of For You's queue. */
    @Synchronized fun enjoyed(key: String, title: String? = null) {
        val r = map[key] ?: return
        if (title != null) {
            recent.removeAll { it.second == key }
            recent.addFirst(title to key)
            while (recent.size > 5) recent.removeLast()
        }
        r.forEach { queue.addLast(it) }
        // Shown beside several videos you enjoyed: the sites' viewers keep pairing it with your kind of thing.
        if (enjoyedKeys.add(key)) {
            r.forEach { support[it.key] = (support[it.key] ?: 0) + 1 }
            enjoyedOrder.addLast(key to title)
            while (enjoyedOrder.size > KEEP * 2) enjoyedOrder.removeFirst()
        }
        while (queue.size > MAX_QUEUED) queue.removeFirst()
    }

    private val enjoyedKeys = HashSet<String>()
    /** The videos you enjoyed, oldest first (title if known): what's kept between launches. */
    private val enjoyedOrder = ArrayDeque<Pair<String, String?>>()

    @Serializable data class Entry(val key: String, val title: String? = null, val related: List<MediaItem> = emptyList())
    @Serializable data class Snapshot(val enjoyed: List<Entry> = emptyList())

    /** The latest videos you enjoyed and what their sites showed beside them, to keep. */
    @Synchronized fun export(): Snapshot =
        Snapshot(enjoyedOrder.toList().takeLast(KEEP).mapNotNull { (k, t) -> map[k]?.let { Entry(k, t, it.take(KEEP_PER_VIDEO)) } })

    /** Back from a previous launch: as if those videos had just been enjoyed (without queueing them again). */
    @Synchronized fun restore(s: Snapshot) {
        s.enjoyed.forEach { e ->
            if (e.related.isEmpty() || e.key in enjoyedKeys) return@forEach
            map[e.key] = e.related
            enjoyedKeys += e.key
            enjoyedOrder.addLast(e.key to e.title)
            e.related.forEach { support[it.key] = (support[it.key] ?: 0) + 1 }
            if (e.title != null) { recent.removeAll { it.second == e.key }; recent.addFirst(e.title to e.key) }
        }
        while (recent.size > 5) recent.removeLast()
    }

    /** Forget all of it (history cleared). */
    @Synchronized fun clear() {
        map.clear(); queue.clear(); recent.clear(); enjoyedKeys.clear(); enjoyedOrder.clear(); support.clear()
    }
    private val support = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 800
    }

    /**
     * How many videos you enjoyed lately list [key] beside them (0 if none): co-watching, as each
     * site's own viewers do it. For You counts it in a post's favour.
     */
    @Synchronized fun support(key: String): Int = support[key] ?: 0

    /** Videos beside at least [min] of the ones you enjoyed lately, most-paired first. */
    @Synchronized fun cowatched(min: Int = 2, limit: Int = 12): List<MediaItem> =
        enjoyedKeys.flatMap { map[it].orEmpty() }.distinctBy { it.key }
            .filter { (support[it.key] ?: 0) >= min && it.key !in enjoyedKeys }
            .sortedByDescending { support[it.key] ?: 0 }
            .take(limit)

    /** Everything queued since last asked. */
    @Synchronized fun drain(): List<MediaItem> = queue.toList().also { queue.clear() }

    private const val MAX_PER_VIDEO = 12
    private const val MAX_QUEUED = 60
    private const val KEEP = 20
    private const val KEEP_PER_VIDEO = 8
}
