package dev.pfeed.domain.reco

/**
 * The bit of a video you keep going back to: scrubbing back to (nearly) the same place a second
 * time marks it as a moment you'd want again. Kept for the session; each place is offered once.
 */
object Rewinds {
    /** Two rewinds landing this close together are to the same bit. */
    const val SAME_BIT_MS = 20_000L

    private val landings = HashMap<String, MutableList<Long>>()
    private val offered = HashMap<String, MutableList<Long>>()

    /**
     * A scrub back in [key] landed at [atMs]. Returns where the bit you keep returning to starts
     * (the earlier of the two landings) the first time it's clear, else null.
     */
    @Synchronized fun note(key: String, atMs: Long): Long? {
        val before = landings.getOrPut(key) { ArrayList() }
        val near = before.firstOrNull { kotlin.math.abs(it - atMs) <= SAME_BIT_MS }
        before += atMs
        if (landings.size > 200) landings.keys.firstOrNull { it != key }?.let { landings.remove(it) }
        near ?: return null
        val start = minOf(near, atMs)
        val done = offered.getOrPut(key) { ArrayList() }
        if (done.any { kotlin.math.abs(it - start) <= SAME_BIT_MS }) return null
        done += start
        return start
    }

    @Synchronized fun clear() { landings.clear(); offered.clear() }
}
