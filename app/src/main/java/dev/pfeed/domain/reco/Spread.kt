package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem

/**
 * Hard spacing rules, so the feed never shows a run of posts from one place.
 *
 * "One place" is what you see on screen as the source: one subreddit, one image-board site, one
 * Mastodon server, one site like Coomer or RedGIFs ([origin]). Within any [WINDOW] consecutive
 * items (counting what was already shown before this batch):
 *  - the same origin never appears twice in a row;
 *  - one origin fills at most [MAX_PER_ORIGIN] slots;
 *  - one creator fills at most [MAX_PER_CREATOR];
 *  - one post (a gallery of images) contributes at most one item.
 *
 * Rules are relaxed in order only when nothing in the pool satisfies them, so the feed never stalls
 * when there's little to choose from.
 */
class Spread(recent: List<MediaItem> = emptyList()) {
    private class Keys(val origin: String, val post: String?, val creator: String?)

    private val tail = ArrayDeque<Keys>(WINDOW + 1).apply { recent.takeLast(WINDOW).forEach { addLast(keys(it)) } }

    /** Strictest level [item] passes: 3 = all rules, 2 = not back-to-back + one per post, 1 = not back-to-back, 0 = none. */
    fun level(item: MediaItem): Int = level(keys(item))

    private fun level(k: Keys): Int {
        if (tail.lastOrNull()?.origin == k.origin) return 0
        if (k.post != null && tail.any { it.post == k.post }) return 1
        if (tail.count { it.origin == k.origin } >= MAX_PER_ORIGIN) return 2
        if (k.creator != null && tail.count { it.creator == k.creator } >= MAX_PER_CREATOR) return 2
        return 3
    }

    fun add(item: MediaItem) {
        tail.addLast(keys(item))
        if (tail.size > WINDOW) tail.removeFirst()
    }

    /**
     * Index of the item [preferred] most among those meeting the strictest level any candidate
     * meets. [preferred] returns a score (higher is better).
     *
     * Returns -1 if the best any candidate can do is below [minLevel]. Callers pass [minLevel] =
     * [ALL_RULES] to refuse a pick that would break a spacing rule (e.g. a forced run from one
     * origin because the pool has nothing else), so the batch can stop short and resume once more
     * varied candidates arrive, instead of dumping a run on screen.
     */
    fun <T> pick(candidates: List<T>, item: (T) -> MediaItem, minLevel: Int = 0, preferred: (T) -> Double): Int {
        val levels = IntArray(candidates.size) { level(item(candidates[it])) }
        val best = levels.maxOrNull() ?: return -1
        if (best < minLevel) return -1
        var index = -1
        var score = Double.NEGATIVE_INFINITY
        for (i in candidates.indices) {
            if (levels[i] != best) continue
            val s = preferred(candidates[i])
            if (s > score) { score = s; index = i }
        }
        return index
    }

    /** A random candidate among those meeting the strictest level any candidate meets, or -1 if that's below [minLevel]. */
    fun <T> pickRandom(candidates: List<T>, item: (T) -> MediaItem, random: kotlin.random.Random, minLevel: Int = 0): Int {
        val levels = IntArray(candidates.size) { level(item(candidates[it])) }
        val best = levels.maxOrNull() ?: return -1
        if (best < minLevel) return -1
        val eligible = candidates.indices.filter { levels[it] == best }
        return eligible[random.nextInt(eligible.size)]
    }

    companion object {
        /** About one screen-session: the rules hold over any 12 consecutive items. */
        const val WINDOW = 12
        const val MAX_PER_ORIGIN = 3
        const val MAX_PER_CREATOR = 2

        /** [level] value meaning every spacing rule is satisfied (no run, no over-used origin/creator/post). */
        const val ALL_RULES = 3

        /** Where an item comes from, at the level you'd notice: subreddit, board site, server, site. */
        fun origin(item: MediaItem): String = when (item.sourceId) {
            "reddit" -> "r/" + (item.tags.firstOrNull()?.lowercase() ?: "")
            "booru", "philomena", "mastodon", "kemono", "tube" ->
                item.sourceId + ":" + item.id.substringBefore('/', "").removePrefix("api.").lowercase()
            else -> item.sourceId
        }

        private fun keys(item: MediaItem) = Keys(origin(item), postKey(item), creatorKey(item))

        /** Items that came from one multi-image post share this. */
        fun postKey(item: MediaItem): String? = item.pageUrl.takeIf { it.isNotBlank() }

        private fun creatorKey(item: MediaItem): String? =
            item.creator?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { item.sourceId + ":" + it }

        /**
         * Orders [items] (already in preference order) so the rules above hold as far as possible,
         * taking at most [limit]. Used by the date-ordered feeds (Latest, Following, tags).
         *
         * When [allowForcedRun] is false (the default in the feed while any source might still add
         * variety), the batch stops as soon as the only remaining choice would break a spacing rule,
         * rather than emitting a run from one origin; the feed fills the rest on the next load, once
         * more varied candidates have arrived. Set it true only when there's genuinely nothing else
         * coming, so the feed still fills from whatever single origin is left instead of stalling.
         */
        fun order(items: List<MediaItem>, recent: List<MediaItem>, limit: Int, allowForcedRun: Boolean = true): List<MediaItem> {
            val spread = Spread(recent)
            val pool = items.toMutableList()
            val out = ArrayList<MediaItem>(minOf(limit, items.size))
            val minLevel = if (allowForcedRun) 0 else ALL_RULES
            while (pool.isNotEmpty() && out.size < limit) {
                // Earlier in the list = preferred (newer / better match).
                val i = spread.pick(pool.withIndex().toList(), { it.value }, minLevel) { -it.index.toDouble() }
                if (i < 0) break
                val picked = pool.removeAt(i)
                out += picked
                spread.add(picked)
            }
            return out
        }
    }
}
